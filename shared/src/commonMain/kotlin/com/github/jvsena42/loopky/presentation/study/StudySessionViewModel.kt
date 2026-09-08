package com.github.jvsena42.loopky.presentation.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.domain.model.AnswerMatcher
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.DEFAULT_NEW_CARDS_PER_DAY
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.SpeakMatcher
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.TypedAnswerOutcome
import com.github.jvsena42.loopky.platform.SpeechError
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the spaced-repetition study loop.
 *
 * [deckId] `null` studies every due card across owned decks; a non-null value studies one deck.
 * Grading delegates to [SrsRepository.review], which owns the scheduler — the VM only sequences
 * the queue and tracks reveal/progress.
 *
 * [isPreview] is a sample of a deck nobody has kept, for a visitor with no account or a reader
 * looking at a stranger's deck. It shares this whole class deliberately; what it does not share is
 * the scheduler, so it needs no session. Every [SrsRepository] call below is behind that flag, and
 * the grade buttons become a plain "Next" — offering a difficulty for a review that will not be
 * stored is a button that lies about what it did.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class StudySessionViewModel(
    private val deckId: String?,
    private val srsRepository: SrsRepository,
    private val deckRepository: DeckRepository,
    private val cardRepository: CardRepository,
    private val settingsRepository: SettingsRepository,
    private val identityRepository: IdentityRepository,
    private val isPreview: Boolean = false,
    /**
     * Whose homeserver the previewed deck lives on. Only a preview needs it: a real session studies
     * decks already in the cache, while a preview is routinely the first thing to touch this deck.
     */
    private val previewAuthorPubky: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<StudySessionUiState>(StudySessionUiState.Loading)
    val state: StateFlow<StudySessionUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StudySessionEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<StudySessionEffect> = _effects.asSharedFlow()

    /**
     * `tryEmit`, not a launched `emit`: a haptic belongs to the moment of the tap, so one that has
     * to queue for buffer space is worse dropped than fired late against the next card.
     */
    private fun haptic(pattern: StudyHaptic) {
        _effects.tryEmit(StudySessionEffect.Haptic(pattern))
    }

    private var queue: List<StudyPresentation> = emptyList()
    private var index = 0
    private var revealed = false

    /**
     * Cards graded, not presentations shown. A deck studied both ways queues each card twice, and
     * "You reviewed 24 cards" for twelve cards would be a plain miscount.
     */
    private val gradedCardIds = mutableSetOf<String>()
    private var deckTitle = ""
    private var speakPhase: SpeakPhase = SpeakPhase.Idle
    private var typePhase: TypePhase = TypePhase.Off
    private var typedAnswer: String = ""

    /**
     * Whether the answer is actually legible right now — not the same question as [revealed]. On a
     * typing card the flip is never blocked, but the back stays masked until it is checked or given
     * up on, so anything acting on "the side facing the user" must ask this or read out the answer
     * the mask is hiding.
     */
    private val answerVisible: Boolean get() = revealed && typePhase !is TypePhase.Answering

    private val current: StudyPresentation? get() = queue.getOrNull(index)

    /**
     * The two faces as this presentation shows them. Every question about "the front" or "the back"
     * must come through here: on a reversed presentation they are the other way round, and reading
     * `card.front`/`card.back` is how a card gets graded against the side it just showed you.
     */
    private val promptSide: CardSide?
        get() = current?.let { if (it.reversed) it.card.back else it.card.front }

    private val answerSide: CardSide?
        get() = current?.let { if (it.reversed) it.card.front else it.card.back }

    /**
     * The forward half of a card whose reverse is still ahead. [base] is the state the pair started
     * from — where the reverse schedules from if it goes worse, since applying a second grade on
     * top of the written forward one would compound two reviews out of one.
     */
    private data class PairInFlight(val base: SrsState?, val forwardGrade: SrsGrade)

    /** cardId → its forward half, dropped as soon as the reverse is graded. */
    private val pairs = mutableMapOf<String, PairInFlight>()

    /** What the in-flight pronunciation attempt grades against. See [targetFor]. */
    private var speakTarget: SpeakTarget? = null

    /**
     * Why buffered reviews are not reaching the homeserver. Held on the VM rather than only in the
     * state because [emitCurrent] rebuilds the state on every card, and a warning the user has not
     * acted on must not vanish when they grade the next one.
     */
    private var syncError: ErrorReason? = null

    /**
     * Resolved once for a preview, unused otherwise. It decides what the end of a preview *offers*
     * — an account for a guest, follow-or-clone for a signed-in reader — not whether it runs.
     */
    private var isSignedIn = false

    /** id → title, warmed lazily from [DeckRepository.listOwned] so multi-deck sessions can label each card. */
    private var deckTitles: Map<String, String> = emptyMap()
    private var gradeJob: Job? = null

    /**
     * The goal celebration is on screen. On the VM as well as in the state for the same reason as
     * [syncError]. Whether it may be shown *at all* lives in [DailyStudyProgress], so it survives
     * leaving the screen and resets with the day.
     */
    private var goalReached = false

    init {
        load()
        // The failing flush is usually the one started as this screen goes away, so the repository
        // replays its last failure — a collector attaching here still learns about it.
        viewModelScope.launch {
            srsRepository.flushFailures.collect { reason ->
                Log.e(TAG, "flush failed — $reason")
                setSyncError(reason)
            }
        }
    }

    fun onRefresh() = load()

    private fun load() {
        viewModelScope.launch {
            _state.update { StudySessionUiState.Loading }
            // Skipped for a preview: resolveDeckTitle falls back to listOwned(), which needs a
            // session, and previewQueue reads the title off the manifest it fetches anyway.
            deckTitle = if (isPreview) "" else deckId?.let { resolveDeckTitle(it) }.orEmpty()
            if (isPreview) {
                isSignedIn = runSuspendCatching { identityRepository.currentSession() }.getOrNull() != null
            }
            runSuspendCatching {
                val cards = when {
                    isPreview -> previewQueue()
                    deckId == null -> srsRepository.dueToday()
                    else -> srsRepository.dueForDeck(deckId)
                }
                sequence(cards)
            }
                .onSuccess { presentations ->
                    queue = presentations
                    index = 0
                    revealed = false
                    gradedCardIds.clear()
                    pairs.clear()
                    resetTyping()
                    emitCurrent()
                }
                .onFailure { err ->
                    Log.e(TAG, "load: FAILED — ${err.message}", err)
                    _state.update { StudySessionUiState.Error(err.toErrorReason()) }
                }
        }
    }

    /**
     * Lay [cards] out as the presentations this session will show — ordinarily one apiece, but a
     * deck opted into both directions gets each card again, reversed, a few presentations behind
     * itself. "Shortly after, in the same session" rather than a stored date, because review state
     * is keyed by card id and a per-direction due time would need a direction-aware key (§8.7).
     *
     * A preview never pairs: nothing is graded, and doubling a ten-card sample only delays the offer.
     */
    private suspend fun sequence(cards: List<Card>): List<StudyPresentation> {
        if (isPreview || cards.isEmpty()) return cards.map { StudyPresentation(it) }
        // Resolved per deck, not from `deckId`: a session started from Home spans every studiable
        // deck, and each author opted in — or did not — for their own.
        val reversing = cards.map { it.deckId }.distinct()
            .filter { deckRepository.getLocal(it)?.reverseEnabled == true }
            .toSet()
        if (reversing.isEmpty()) return cards.map { StudyPresentation(it) }
        // The goal decides how far back a reverse sits, not how many cards are served: the queue
        // below is still every card. See reverseGapFor.
        settingsRepository.ensureLoaded()
        val goal = settingsRepository.studySettings.value.settings.newCardsPerDayGoal
        return expandWithReverses(cards, reverseGapFor(goal)) { it.deckId in reversing }
    }

    /**
     * The first [PREVIEW_CARDS] cards of one deck, in the author's own order. A fetch rather than a
     * cache read, and remote-capable: a preview is usually the first thing to touch this deck on
     * this device. Capped because the point is to reach the end and be asked.
     */
    private suspend fun previewQueue(): List<Card> {
        val id = requireNotNull(deckId) { "A preview is always of a single deck" }
        val deck = deckRepository.getLocal(id)
            ?: previewAuthorPubky?.let { deckRepository.fetchRemote(it, id).getOrThrow() }
            ?: error("Deck $id is not available to preview")
        deckTitle = deck.title
        return cardRepository.fetchByDeck(deck).getOrThrow().take(PREVIEW_CARDS)
    }

    fun onReveal() {
        if (!revealed) {
            revealed = true
            haptic(StudyHaptic.Tick)
            emitCurrent()
        }
    }

    /** The counterpart to [onGrade] — same advance, no scheduling, since nothing is recorded. */
    fun onNextCard() {
        if (!isPreview || gradeJob?.isActive == true) return
        tapHaptic()
        current?.let { gradedCardIds += it.card.id }
        advanceIndex()
        emitCurrent()
    }

    fun onGrade(grade: SrsGrade) {
        if (isPreview || gradeJob?.isActive == true) return
        val presentation = current ?: return
        tapHaptic()
        gradeJob = viewModelScope.launch {
            gradeResultFor(presentation, grade)
                .onFailure { err ->
                    Log.e(TAG, "grade: FAILED — ${err.message}", err)
                    // The automatic every-FLUSH_EVERY flush comes back through here, so a full
                    // quota becomes visible mid-session rather than only when the screen closes.
                    // The grade is buffered either way and the card still advances (#91).
                    syncError = err.toErrorReason()
                }
            gradedCardIds += presentation.card.id
            // Ordered as it always was: the celebration asks whether there is a card *behind* it to
            // keep studying, so it has to run once the index has already moved on.
            advanceIndex()
            celebrateGoalIfOwed()
            emitCurrent()
        }
    }

    /**
     * Write the grade for one presentation — which for a paired card is not one write per tap.
     *
     * Both directions share one review state, so the pair is scheduled from whichever went worse.
     * The forward half is written as it happens, so a session abandoned before the reverse keeps
     * it; a worse reverse re-schedules from where the pair started, and an equal or better one
     * writes nothing.
     */
    private suspend fun gradeResultFor(
        presentation: StudyPresentation,
        grade: SrsGrade,
    ): Result<Unit> {
        val card = presentation.card
        if (!presentation.reversed) {
            // Captured before the write, so the reverse half can schedule from where this started.
            val base = runSuspendCatching { srsRepository.stateFor(card.deckId, card.id) }
                .getOrNull()
            val result = srsRepository.review(card, grade)
            if (hasReverseAhead(card.id)) pairs[card.id] = PairInFlight(base, grade)
            return result.map { }
        }
        // Defensive only — a reverse is always emitted behind its own card. Grading it as an
        // ordinary review is the honest fallback: the card was answered, and something must
        // schedule it.
        val pair = pairs.remove(card.id) ?: return srsRepository.review(card, grade).map { }
        if (grade >= pair.forwardGrade) return Result.success(Unit)
        return srsRepository.reviewFrom(card, pair.base, grade).map { }
    }

    private fun hasReverseAhead(cardId: String): Boolean =
        queue.drop(index + 1).any { it.reversed && it.card.id == cardId }

    /**
     * Acknowledge a tap that moves the queue on — unless it is the last card, whose acknowledgement
     * is the completion's own Success a moment later. Two buzzes that close together read as one
     * smeared one rather than as two events.
     */
    private fun tapHaptic() {
        if (index < queue.lastIndex) haptic(StudyHaptic.Tick)
    }

    private fun advanceIndex() {
        index++
        revealed = false
        speakPhase = SpeakPhase.Idle
        resetTyping()
    }

    /**
     * The queue is untouched — the card behind the celebration is already the next one, and "Keep
     * studying" simply dismisses it. Marked as shown the moment it goes up, not when it is
     * dismissed: a process killed while it is on screen has still shown it.
     */
    private suspend fun celebrateGoalIfOwed() {
        val goal = settingsRepository.studySettings.value.settings.newCardsPerDayGoal
        if (!srsRepository.dailyProgress.value.owesGoalCelebration(goal)) return
        // The celebration renders over a card, so there has to be one. Grading the last card
        // straight past the goal goes to "All done!", which says the same thing — and marking it
        // shown here would spend the day's one celebration on a screen that never appeared.
        if (queue.getOrNull(index) == null) return
        goalReached = true
        haptic(StudyHaptic.Success)
        goalCelebration = GoalCelebration(
            newCardsToday = srsRepository.dailyProgress.value.newCards,
            goal = goal,
        )
        runSuspendCatching { srsRepository.markGoalCelebrated() }
            .onFailure { Log.e(TAG, "markGoalCelebrated: FAILED — ${it.message}", it) }
    }

    private var goalCelebration: GoalCelebration? = null

    fun onContinueAfterGoal() {
        goalReached = false
        _state.update { current ->
            (current as? StudySessionUiState.Reviewing)?.copy(goalCelebration = null) ?: current
        }
    }

    // ── Type the answer (#115) ───────────────────────────────────────────────

    fun onAnswerChange(text: String) {
        if (typePhase !is TypePhase.Answering) return
        typedAnswer = text
        _state.update { current ->
            (current as? StudySessionUiState.Reviewing)?.copy(typedAnswer = text) ?: current
        }
    }

    /**
     * Compare what was typed against the card's back.
     *
     * Only a **correct** answer opens the card. Anything else says so and leaves you answering with
     * what you wrote still in the field: handing the answer over on the first slip turns one typo
     * into a lost card, and a near miss is a hint to fix an accent, not a verdict. [onGiveUp] is
     * the way out.
     *
     * No grade is chosen here either way — the matcher's strictness decides what to *say*, never
     * what to schedule.
     */
    fun onCheckAnswer() {
        // A check landing after the queue advanced would otherwise grade the next card's text.
        if (gradeJob?.isActive == true) return
        if (typePhase !is TypePhase.Answering) return
        val expected = answerSide?.text
            ?.takeIf { AnswerMatcher.isTypable(it) } ?: return
        val typed = typedAnswer.trim()
        if (typed.isEmpty()) return
        val outcome = AnswerMatcher.judge(typed, expected)
        if (outcome == TypedAnswerOutcome.Correct) {
            typePhase = TypePhase.Correct(typed)
            revealed = true
            haptic(StudyHaptic.Success)
        } else {
            haptic(StudyHaptic.Warning)
            // `revealed` is deliberately untouched: a card already flipped stays flipped, still
            // showing the input — a miss changes what is said, not what is shown.
            typePhase = TypePhase.Answering(lastMiss = TypeMiss(typed, outcome))
        }
        emitCurrent()
    }

    /**
     * Show the answer and nothing else. Always available while answering, with no confirm and no
     * penalty. It deliberately does not pre-select or highlight a difficulty — a "you clearly meant
     * Again" nudge would turn an escape hatch into a punishment.
     */
    fun onGiveUp() {
        if (gradeJob?.isActive == true) return
        if (typePhase !is TypePhase.Answering) return
        typePhase = TypePhase.GaveUp
        revealed = true
        haptic(StudyHaptic.Tick)
        emitCurrent()
    }

    private fun resetTyping() {
        typePhase = TypePhase.Off
        typedAnswer = ""
    }

    /** Pronunciation practice for the side facing the user (gated on the deck's speak opt-in). */
    fun onSpeakTest() {
        val s = _state.value
        if (s !is StudySessionUiState.Reviewing || !s.speakEnabled) return
        startRecognition(targetFor(s) ?: return)
    }

    fun onSpeechResult(text: String) {
        // Graded against what was captured when listening began, not whichever side faces now: the
        // answer arrives asynchronously, and a card flipped meanwhile would mark a correct
        // utterance wrong against the opposite side's text.
        val target = speakTarget ?: return
        // The target's own language, not the deck's front: it decides which language's number words
        // "10" may be spoken as, and the two sides of a card rarely share one.
        val result = SpeakMatcher.match(text, target.expected, target.languageTag)
        haptic(if (result.correct) StudyHaptic.Success else StudyHaptic.Warning)
        setSpeakPhase(
            if (result.correct) {
                SpeakPhase.Correct(result.heard)
            } else {
                SpeakPhase.Wrong(heard = result.heard, expected = result.expected)
            },
        )
    }

    /**
     * A recognition that produced no answer is *reported*, never dismissed. Closing the sheet on
     * an error is indistinguishable from the app having dropped the tap, and the common Android
     * errors — nothing matched, the engine busy on a fast retry, no model for the deck's declared
     * language — are exactly the ones the reader can act on.
     *
     * Late errors after a dismissal are dropped, like a late result: [speakTarget] is null once
     * the sheet is gone, so nothing can reopen it.
     */
    fun onSpeechError(reason: SpeechError) {
        val target = speakTarget ?: return
        haptic(StudyHaptic.Failure)
        setSpeakPhase(SpeakPhase.Failed(reason = reason, expected = target.expected))
    }

    /** Another go at the same target — never re-derived, so a retry cannot change what it grades. */
    fun onSpeakRetry() {
        val s = _state.value
        if (s !is StudySessionUiState.Reviewing || !s.speakEnabled) return
        startRecognition(speakTarget ?: targetFor(s) ?: return)
    }

    /**
     * What a pronunciation attempt grades against: the side facing the user, and *that side's*
     * language. Null when the side has no text, or the deck declared no pair — given no language
     * the recognizer would transcribe with the reader's own locale's model.
     */
    private fun targetFor(s: StudySessionUiState.Reviewing): SpeakTarget? {
        // promptSide/answerSide, not card.front/card.back: on a reversed presentation the face in
        // front of the reader is the card's back, and its language is the deck's back language.
        val side = (if (answerVisible) answerSide else promptSide) ?: return null
        val expected = side.text?.takeIf { it.isNotBlank() } ?: return null
        val languageTag = (if (answerVisible) s.backLang else s.frontLang) ?: return null
        return SpeakTarget(expected, languageTag)
    }

    private fun startRecognition(target: SpeakTarget) {
        speakTarget = target
        // The one cue that the microphone is open: the sheet is the same sheet, and there is no
        // sound to confirm it.
        haptic(StudyHaptic.Tick)
        setSpeakPhase(SpeakPhase.Listening(target.expected))
        viewModelScope.launch {
            _effects.emit(
                StudySessionEffect.StartSpeechRecognition(target.expected, target.languageTag),
            )
        }
    }

    fun onSpeakDismiss() {
        setSpeakPhase(SpeakPhase.Idle)
    }

    private fun setSpeakPhase(phase: SpeakPhase) {
        if (phase is SpeakPhase.Idle) speakTarget = null
        speakPhase = phase
        val s = _state.value
        if (s is StudySessionUiState.Reviewing) {
            _state.update { s.copy(speakPhase = phase) }
        }
    }

    /**
     * Read the side facing the user, in *that side's* language — the two sides of a vocabulary card
     * are routinely different ones. Gated here and not only in the UI, like the announce gate, so a
     * deck that declared no languages cannot be read aloud in the reader's accent. What is read is
     * the phrase, not the card's editorial asides (see [AnswerMatcher.stripParentheticals]).
     */
    fun onSpeak() {
        val s = _state.value
        if (s !is StudySessionUiState.Reviewing || !s.listenEnabled) return
        // answerVisible, not revealed: on a flipped-but-masked typing card the back is on screen but
        // hidden, and reading it aloud would hand over the answer the mask is withholding.
        // Parenthesized asides are dropped for the same reason the matchers drop them.
        val text = (if (answerVisible) answerSide?.text else promptSide?.text)
            ?.let(AnswerMatcher::stripParentheticals)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val languageTag = (if (answerVisible) s.backLang else s.frontLang) ?: return
        viewModelScope.launch { _effects.emit(StudySessionEffect.Speak(text, languageTag)) }
    }

    fun onClose() {
        // flushAsync, not a launch here: viewModelScope is cancelled in onCleared(), so a flush
        // started as this screen goes away would be killed before it saved the reviews it exists
        // for. A preview has graded nothing and has no session to flush under.
        if (!isPreview) srsRepository.flushAsync()
        viewModelScope.launch { _effects.emit(StudySessionEffect.Close) }
    }

    /** Buffered reviews must reach the homeserver even if the process is about to be backgrounded. */
    override fun onCleared() {
        if (!isPreview) srsRepository.flushAsync()
        super.onCleared()
    }

    private fun setSyncError(reason: ErrorReason) {
        syncError = reason
        _state.update { current ->
            when (current) {
                is StudySessionUiState.Reviewing -> current.copy(syncError = reason)
                is StudySessionUiState.Complete -> current.copy(syncError = reason)
                else -> current
            }
        }
    }

    /** Clears the warning from the screen, not from the buffer. */
    fun onDismissSyncError() {
        syncError = null
        _state.update { current ->
            when (current) {
                is StudySessionUiState.Reviewing -> current.copy(syncError = null)
                is StudySessionUiState.Complete -> current.copy(syncError = null)
                else -> current
            }
        }
    }

    private fun emitCurrent() {
        if (queue.isEmpty()) {
            _state.update { StudySessionUiState.Empty(deckTitle) }
            return
        }
        val presentation = current
        if (presentation == null) {
            emitComplete()
            return
        }
        val card = presentation.card
        val reversed = presentation.reversed
        val prompt = if (reversed) card.back else card.front
        val answer = if (reversed) card.front else card.back
        viewModelScope.launch {
            // The repository owns this: the intervals are a user setting, so labels computed here
            // could drift from what grading actually writes.
            val labels = intervalLabelsFor(presentation)
            val title = deckTitle.ifBlank { resolveDeckTitle(card.deckId) }.ifBlank { card.deckId }
            val deck = deckRepository.getLocal(card.deckId)
            typePhase = typePhaseFor(prompt, answer, deck)
            _state.update { StudySessionUiState.Reviewing(
                deckTitle = title,
                isPreview = isPreview,
                goalCelebration = goalCelebration.takeIf { goalReached },
                position = index + 1,
                total = queue.size,
                reversed = reversed,
                frontText = prompt.text.orEmpty(),
                backText = answer.text.orEmpty(),
                backLabel = prompt.text,
                revealed = revealed,
                intervals = labels,
                listenEnabled = deck?.listenEnabled == true && deck.speechReady,
                speakEnabled = deck?.speakEnabled == true && deck.speechReady,
                speakPhase = speakPhase,
                typePhase = typePhase,
                typedAnswer = typedAnswer,
                // Swapped with the sides: the language belongs to the face, not the slot. A reversed
                // Spanish card read with the front's English voice is the failure the declared pair
                // exists to prevent.
                frontLang = if (reversed) deck?.backLang else deck?.frontLang,
                backLang = if (reversed) deck?.frontLang else deck?.backLang,
                deckId = card.deckId,
                authorPubky = deck?.authorPubky.orEmpty(),
                frontImageRef = prompt.imageRef,
                backImageRef = answer.imageRef,
                syncError = syncError,
            ) }
        }
    }

    /**
     * The queue is exhausted. A preview stops here — nothing was graded, so there is nothing to
     * flush and no next-due date to name; what it says instead is how to keep the progress it did
     * not record.
     */
    private fun emitComplete() {
        haptic(StudyHaptic.Success)
        if (isPreview) {
            _state.update {
                StudySessionUiState.Complete(
                    gradedCardIds.size,
                    isPreview = true,
                    isSignedIn = isSignedIn,
                )
            }
            return
        }
        srsRepository.flushAsync()
        _state.update { StudySessionUiState.Complete(gradedCardIds.size, syncError = syncError) }
        // After the state, not before: the congrats screen must not wait on a lookup, and nextDueAt
        // is cache-only but still suspending.
        viewModelScope.launch {
            val nextDue = runSuspendCatching { srsRepository.nextDueAt() }
                .onFailure { Log.e(TAG, "nextDueAt: FAILED — ${it.message}", it) }
                .getOrNull()
            val progress = srsRepository.dailyProgress.value
            _state.update { current ->
                (current as? StudySessionUiState.Complete)?.copy(
                    nextDueAtMillis = nextDue,
                    newCardsToday = progress.newCards,
                    newCardsGoal = settingsRepository.studySettings.value.settings.newCardsPerDayGoal,
                ) ?: current
            }
        }
    }

    /**
     * What the four grade buttons should say they will do.
     *
     * On the reverse half of a pair that is not simply what the grade would schedule: the pair lands
     * on the worse direction, so every grade above the forward one would in fact write the forward
     * one's interval. Capping keeps the buttons honest, at the cost of two sometimes reading alike.
     */
    private suspend fun intervalLabelsFor(
        presentation: StudyPresentation,
    ): Map<SrsGrade, String> {
        if (isPreview) return emptyMap()
        val pair = pairs[presentation.card.id]?.takeIf { presentation.reversed }
            ?: return srsRepository.previewIntervals(presentation.card)
        return srsRepository.previewIntervalsFrom(presentation.card, pair.base, pair.forwardGrade)
    }

    /**
     * Where the presented card sits in the typing flow, given what the deck opted into.
     *
     * A card with nothing typable on the back, or no prompt to type from, falls back to ordinary
     * tap-to-reveal — an image-only answer, which Anki imports produce, must never put up an input
     * with nothing to match. Asked about the sides as *presented*, so a reversed card is judged on
     * the face the reader types towards.
     *
     * The back test is [AnswerMatcher.isTypable], deliberately not `isNotBlank()`: a back of `"—"`
     * or a lone emoji is not blank but normalizes to nothing, so nothing could ever match it — and
     * since a wrong Check no longer reveals, such a card would be a dead end.
     */
    private fun typePhaseFor(prompt: CardSide, answer: CardSide, deck: Deck?): TypePhase {
        val eligible = deck?.typeEnabled == true &&
            AnswerMatcher.isTypable(answer.text.orEmpty()) &&
            (!prompt.text.isNullOrBlank() || prompt.imageRef != null)
        return when {
            !eligible -> TypePhase.Off
            typePhase is TypePhase.Off -> TypePhase.Answering()
            else -> typePhase
        }
    }

    /**
     * Tries the in-memory cache, then falls back to [DeckRepository.listOwned] once, so a session
     * opened without the deck pre-loaded still shows a name.
     */
    private suspend fun resolveDeckTitle(id: String): String {
        deckRepository.getLocal(id)?.title?.takeIf { it.isNotBlank() }?.let { return it }
        if (deckTitles.isEmpty()) {
            deckTitles = runSuspendCatching { deckRepository.listOwned() }
                .onFailure { Log.e(TAG, "resolveDeckTitle: listOwned FAILED — ${it.message}", it) }
                .getOrDefault(emptyList())
                .associate { it.id to it.title }
        }
        return deckTitles[id].orEmpty()
    }

    companion object {
        private const val TAG = "Loopky/StudyVM"

        /**
         * How many cards a preview serves. A sample, not a session: the offer to keep going lives
         * at the end of it, so it has to be reachable in a couple of minutes.
         */
        internal const val PREVIEW_CARDS = 10
    }
}

sealed interface StudySessionUiState {
    data object Loading : StudySessionUiState

    /** No cards due — "all caught up". */
    data class Empty(val deckTitle: String) : StudySessionUiState

    data class Reviewing(
        val deckTitle: String,
        /**
         * A sample of a deck nobody has kept: nothing is graded or stored. The SRS buttons become a
         * plain "Next", and the screen says so.
         */
        val isPreview: Boolean = false,
        /**
         * Today's new-card goal has just been met. A congratulation, not a stop sign: the queue
         * behind it is unchanged, so "Keep studying" costs only a dismissal. Shown once a day.
         */
        val goalCelebration: GoalCelebration? = null,
        val position: Int,
        val total: Int,
        /**
         * This presentation is the card the other way round: its back is the prompt. Not a different
         * card and not a different review state — the pair is graded once. The screen says so,
         * because a deck whose sides look alike gives no other signal the direction changed.
         */
        val reversed: Boolean = false,
        val frontText: String,
        val backText: String,
        /**
         * The prompt, shown small above the answer on the card back — as the author wrote it. Not
         * uppercased: a deck whose sides differ only in case would have the label spell out its own
         * answer.
         */
        val backLabel: String?,
        val revealed: Boolean,
        val intervals: Map<SrsGrade, String>,
        /**
         * Both fold in the deck's `speechReady`: with no declared language pair the OS engines fall
         * back to the reader's locale, so the features are not offered at all.
         */
        val listenEnabled: Boolean = false,
        val speakEnabled: Boolean = false,
        val speakPhase: SpeakPhase = SpeakPhase.Idle,
        /**
         * Where this card is in the type-the-answer flow, or [TypePhase.Off] when the deck has not
         * opted in — or when this card cannot be typed. See [answerHidden].
         */
        val typePhase: TypePhase = TypePhase.Off,
        /** Held here so it survives the state rebuild every reveal causes. */
        val typedAnswer: String = "",
        /** BCP-47 tags, non-null whenever [listenEnabled]/[speakEnabled] are. */
        val frontLang: String? = null,
        val backLang: String? = null,
        val deckId: String = "",
        /** Media on a followed deck lives on the author's homeserver, not yours. */
        val authorPubky: String = "",
        /** Shown as a circular avatar on the card back. */
        val frontImageRef: MediaRef.Image? = null,
        /**
         * The answer itself, when the answer is a picture. Stored and editable since cards gained
         * media but never surfaced here, so a card whose answer is a diagram revealed a blank face
         * — which is exactly where an Anki import puts pictures (#96).
         */
        val backImageRef: MediaRef.Image? = null,
        /**
         * Set when graded reviews are not reaching the homeserver. Not an [Error]: the session
         * carries on and the reviews are buffered and journalled, so blanking the card mid-way
         * would cost more than the warning is worth.
         */
        val syncError: ErrorReason? = null,
    ) : StudySessionUiState {
        /**
         * The card may be flipped, but the answer on it is masked. Typing withholds the word, not
         * the gesture.
         */
        val answerHidden: Boolean get() = typePhase is TypePhase.Answering

        /**
         * [revealed] alone is not enough once the answer can be revealed-but-masked: grading a card
         * you have not been shown the answer to is not a judgement about anything.
         */
        val gradesAvailable: Boolean get() = revealed && !answerHidden && !isPreview

        /** "Next" stands where the grades would: the card is legible and there is nothing to grade. */
        val previewAdvanceAvailable: Boolean get() = isPreview && revealed && !answerHidden
    }

    data class Complete(
        val reviewed: Int,
        /** The session was a preview, so [reviewed] is cards *tried* and nothing was recorded. */
        val isPreview: Boolean = false,
        /** Only consulted for a preview: it decides whether the offer is an account or a follow. */
        val isSignedIn: Boolean = false,
        val syncError: ErrorReason? = null,
        /**
         * When the next card comes up. Without it an empty queue read as a dead end rather than as
         * earned (#101 §5). Null when nothing is scheduled at all.
         */
        val nextDueAtMillis: Long? = null,
        val newCardsToday: Int = 0,
        val newCardsGoal: Int = DEFAULT_NEW_CARDS_PER_DAY,
    ) : StudySessionUiState

    data class Error(val reason: ErrorReason) : StudySessionUiState
}

/** What the goal celebration says. Its own type so the screen needs no arithmetic. */
data class GoalCelebration(val newCardsToday: Int, val goal: Int)

/** The text and language one pronunciation attempt is measured against. */
private data class SpeakTarget(val expected: String, val languageTag: String)

/** Pronunciation-practice sheet state for the current card back. */
sealed interface SpeakPhase {
    data object Idle : SpeakPhase

    /**
     * [expected] is the captured target, carried on the phase so the sheet prompts with the very
     * text [onSpeechResult] grades against — reading it off the state's front/back would let the
     * prompt and the grading disagree about which side the attempt is for.
     */
    data class Listening(val expected: String) : SpeakPhase

    data class Correct(val heard: String) : SpeakPhase
    data class Wrong(val heard: String, val expected: String) : SpeakPhase

    /**
     * Recognition ended without an attempt to grade. Carries [expected] so a retry keeps prompting
     * with the same target the failed listen was for.
     */
    data class Failed(val reason: SpeechError, val expected: String) : SpeakPhase {
        /** Neither a refused permission nor a missing language model changes by trying again. */
        val retryable: Boolean
            get() = reason !in
                setOf(SpeechError.Permission, SpeechError.Unavailable, SpeechError.LanguageUnavailable)
    }
}

/**
 * Where the current card sits in the type-the-answer flow (#115). The flip is orthogonal: a card
 * can be turned face-up in any phase. What the phase decides is whether that face is legible.
 */
sealed interface TypePhase {
    /** The deck has not opted in, or this card cannot be typed. Tap-to-reveal, exactly as before. */
    data object Off : TypePhase

    /**
     * Input on screen, answer hidden. The only phase offering Give up, and the phase a rejected
     * attempt stays in — which is why [lastMiss] lives here rather than on a phase of its own.
     */
    data class Answering(val lastMiss: TypeMiss? = null) : TypePhase

    /**
     * Typed correctly, so the back is on show. The only Check outcome that opens the card — and it
     * still picks no SRS grade, so the matcher's strictness cannot reach a card's `dueAt`.
     */
    data class Correct(val typed: String) : TypePhase

    /**
     * Carries nothing on purpose: giving up reveals the back and says not one word about how the
     * card should be graded.
     */
    data object GaveUp : TypePhase
}

/**
 * An attempt that was turned down, kept so the screen can say how near it came while the card stays
 * shut. [outcome] is never [TypedAnswerOutcome.Correct] — that one opens the card instead.
 */
data class TypeMiss(val typed: String, val outcome: TypedAnswerOutcome)

sealed interface StudySessionEffect {
    /** [languageTag] is BCP-47; without it the engine reads the card in the reader's own locale. */
    data class Speak(val text: String, val languageTag: String) : StudySessionEffect
    data class StartSpeechRecognition(
        val expected: String,
        val languageTag: String,
    ) : StudySessionEffect
    data object Close : StudySessionEffect

    /**
     * Buzz the phone. An effect rather than something the screens do on tap, because whether a tap
     * *did* anything is known here and nowhere else — a grade arriving while the previous one is
     * still writing, a Check on an untypable card and a second reveal are all ignored, and a buzz
     * for one of those tells the reader something happened when nothing did.
     */
    data class Haptic(val pattern: StudyHaptic) : StudySessionEffect
}

/**
 * The study loop's haptic vocabulary. Four patterns, kept few because telling them apart is the
 * whole point — and for the same reason neither platform may collapse two of them.
 *
 * PascalCase like every enum that crosses to Swift here: Kotlin exports entries lowercased with the
 * separators dropped, so a SCREAMING_SNAKE entry crosses under a name nothing can predict.
 */
enum class StudyHaptic {
    /** The card moved: flipped, graded, given up on, or the microphone opened. */
    Tick,

    /** Right — a correct check, a correct utterance, the goal met, the session finished. */
    Success,

    /** Wrong, but the reader's turn continues: a missed check, a mispronounced word. */
    Warning,

    /**
     * The app could not do the thing at all — a listen that produced no answer. Distinct from
     * [Warning] on both platforms: `.error` against `.warning` on iOS, a fading three-pulse
     * stutter against one blunt pulse on Android.
     */
    Failure,
}
