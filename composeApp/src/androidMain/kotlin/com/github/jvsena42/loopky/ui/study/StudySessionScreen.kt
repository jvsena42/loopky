package com.github.jvsena42.loopky.ui.study

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.platform.SpeakOutcome
import com.github.jvsena42.loopky.platform.Speaker
import com.github.jvsena42.loopky.platform.SpeechError
import com.github.jvsena42.loopky.platform.SpeechEvent
import com.github.jvsena42.loopky.platform.SpeechRecognizer
import com.github.jvsena42.loopky.presentation.study.GoalCelebration
import com.github.jvsena42.loopky.presentation.study.SpeakPhase
import com.github.jvsena42.loopky.presentation.study.StudySessionEffect
import com.github.jvsena42.loopky.presentation.study.StudySessionUiState
import com.github.jvsena42.loopky.presentation.study.StudySessionViewModel
import com.github.jvsena42.loopky.presentation.study.TypePhase
import com.github.jvsena42.loopky.ui.components.Confetti
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.PermissionBlockedDialog
import com.github.jvsena42.loopky.ui.components.PermissionRationaleDialog
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.components.errorTitle
import com.github.jvsena42.loopky.ui.components.rememberReduceMotion
import com.github.jvsena42.loopky.ui.layout.WindowWidthClass
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun StudySessionRoute(
    deckId: String?,
    /**
     * Sample [deckId] rather than study it: a fixed handful of cards, graded and stored nowhere.
     * How a signed-out visitor — or anyone looking at a deck they have not kept — gets to try it.
     */
    isPreview: Boolean = false,
    /** Whose homeserver holds the previewed deck. Only a preview needs it; see the ViewModel. */
    previewAuthorPubky: String? = null,
    onClose: () -> Unit = {},
    /** Leave for the sign-in flow, from the offer at the end of a guest's preview. */
    onSignIn: () -> Unit = {},
) {
    val viewModel = koinViewModel<StudySessionViewModel> {
        parametersOf(deckId, isPreview, previewAuthorPubky)
    }
    val speaker = koinInject<Speaker>()
    val speechRecognizer = koinInject<SpeechRecognizer>()
    val haptics: HapticFeedback = LocalHapticFeedback.current

    val currentClose by rememberUpdatedState(onClose)
    val context = LocalContext.current
    val hapticPlayer = remember(context, haptics) { StudyHapticPlayer(context, haptics) }
    val scope = rememberCoroutineScope()
    val recognitionJob = remember { mutableStateOf<Job?>(null) }

    val requestSpeak = rememberMicPermissionRequest(
        onGranted = viewModel::onSpeakTest,
        // A refusal is a dismissal, not a failed listen: nothing was started, and the rationale
        // dialog has already said why the microphone is needed.
        onDenied = viewModel::onSpeakDismiss,
    )

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is StudySessionEffect.Speak -> {
                    // A missing voice leaves the engine on whatever it loaded last, so silence
                    // beats reading a Spanish card in an English accent — say why.
                    if (speaker.speak(effect.text, effect.languageTag) != SpeakOutcome.Spoken) {
                        Toast.makeText(context, R.string.listen_voice_unavailable, Toast.LENGTH_LONG)
                            .show()
                    }
                }
                is StudySessionEffect.StartSpeechRecognition -> {
                    val previous = recognitionJob.value
                    recognitionJob.value = scope.launch {
                        // Joined, not just cancelled: the previous recogniser is destroyed in the
                        // flow's awaitClose, and creating a second one before that lands is
                        // answered with ERROR_RECOGNIZER_BUSY — which is what a fast Try again is.
                        previous?.cancelAndJoin()
                        if (!speechRecognizer.isAvailable()) {
                            viewModel.onSpeechError(SpeechError.Unavailable)
                            return@launch
                        }
                        speechRecognizer.listen(effect.languageTag).collect { event ->
                            when (event) {
                                is SpeechEvent.Result -> viewModel.onSpeechResult(event.text)
                                is SpeechEvent.Error -> viewModel.onSpeechError(event.reason)
                                else -> Unit
                            }
                        }
                    }
                }
                is StudySessionEffect.Haptic -> hapticPlayer.play(effect.pattern)

                StudySessionEffect.Close -> currentClose()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    // Stop the recognizer as soon as the speak sheet leaves the listening phase.
    val listening = (state as? StudySessionUiState.Reviewing)?.speakPhase is SpeakPhase.Listening
    LaunchedEffect(listening) {
        if (!listening) recognitionJob.value?.cancel()
    }

    StudySessionScreen(
        state = state,
        onReveal = viewModel::onReveal,
        onGrade = viewModel::onGrade,
        onNextCard = viewModel::onNextCard,
        onSignIn = onSignIn,
        onSpeak = viewModel::onSpeak,
        onSpeakTest = requestSpeak,
        onSpeakContinue = viewModel::onSpeakDismiss,
        onSpeakRetry = viewModel::onSpeakRetry,
        onSpeakCancel = viewModel::onSpeakDismiss,
        onAnswerChange = viewModel::onAnswerChange,
        onCheckAnswer = viewModel::onCheckAnswer,
        onGiveUp = viewModel::onGiveUp,
        onClose = viewModel::onClose,
        onDone = onClose,
        onDismissSyncError = viewModel::onDismissSyncError,
        onContinueAfterGoal = viewModel::onContinueAfterGoal,
    )
}

@Composable
fun StudySessionScreen(
    state: StudySessionUiState,
    onReveal: () -> Unit,
    onGrade: (SrsGrade) -> Unit,
    onSpeak: () -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
    /** Advance a preview. Stands where the four grade buttons would — there is nothing to grade. */
    onNextCard: () -> Unit = {},
    /** The offer at the end of a guest's preview. */
    onSignIn: () -> Unit = {},
    onSpeakTest: () -> Unit = {},
    onSpeakContinue: () -> Unit = {},
    onSpeakRetry: () -> Unit = {},
    onSpeakCancel: () -> Unit = {},
    onAnswerChange: (String) -> Unit = {},
    onCheckAnswer: () -> Unit = {},
    onGiveUp: () -> Unit = {},
    onDismissSyncError: () -> Unit = {},
    onContinueAfterGoal: () -> Unit = {},
) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfaceSecondary)
            .windowInsetsPadding(WindowInsets.systemBars)
            // Deliberately *not* `imePadding()`. The card is `weight(1f)` of this column, so
            // padding the whole screen for the keyboard resized the card every time the keyboard
            // came or went — a ~145 dp jump on a phone, landing exactly when a typed answer opened
            // the card and the grades appeared. The keyboard is held off the field itself instead,
            // inside the card (`CardFace`), which leaves the frame where it was.
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        when (state) {
            StudySessionUiState.Loading -> LoopkyLoadingScreen(
                message = stringResource(R.string.study_loading),
            )

            is StudySessionUiState.Error -> CenteredMessage(
                title = errorTitle(state.reason),
                subtitle = errorMessage(state.reason),
                actionLabel = stringResource(R.string.study_close),
                onAction = onClose,
            )

            is StudySessionUiState.Empty -> CenteredMessage(
                title = stringResource(R.string.study_empty_title),
                subtitle = state.deckTitle.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.study_empty_subtitle_deck, it) }
                    ?: stringResource(R.string.study_empty_subtitle),
                actionLabel = stringResource(R.string.study_done),
                onAction = onClose,
            )

            is StudySessionUiState.Complete -> CompleteMessage(
                state = state,
                onDone = onDone,
                onSignIn = onSignIn,
            )

            is StudySessionUiState.Reviewing -> ReviewingContent(
                state = state,
                onReveal = onReveal,
                onGrade = onGrade,
                onNextCard = onNextCard,
                onSpeak = onSpeak,
                onSpeakTest = onSpeakTest,
                onAnswerChange = onAnswerChange,
                onCheckAnswer = onCheckAnswer,
                onGiveUp = onGiveUp,
                onClose = onClose,
            )
        }

        // Overlaid rather than replacing the card: the reviews are buffered and journalled, so the
        // session is still worth finishing — the user just needs to know the writing has stopped.
        state.syncError?.let { reason ->
            SyncErrorBanner(
                reason = reason,
                onDismiss = onDismissSyncError,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // A full-screen moment rather than a banner, and the only modal thing in the session.
        // It is shown once a day, so the interruption is cheap; and "Keep studying" is right there
        // because meeting a goal must never be the thing that ends a session.
        (state as? StudySessionUiState.Reviewing)?.goalCelebration?.let { celebration ->
            GoalCelebrationScreen(
                celebration = celebration,
                onKeepStudying = onContinueAfterGoal,
                onDone = onClose,
            )
        }
    }

    if (state is StudySessionUiState.Reviewing) {
        SpeakSheets(
            phase = state.speakPhase,
            onCancel = onSpeakCancel,
            onContinue = onSpeakContinue,
            onRetry = onSpeakRetry,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun ReviewingContent(
    state: StudySessionUiState.Reviewing,
    onReveal: () -> Unit,
    onGrade: (SrsGrade) -> Unit,
    onNextCard: () -> Unit,
    onSpeak: () -> Unit,
    onSpeakTest: () -> Unit,
    onAnswerChange: (String) -> Unit,
    onCheckAnswer: () -> Unit,
    onGiveUp: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    // Landscape stands the grades in a column beside the card instead of a row beneath it. The
    // card is the tall thing on this screen and the window is the wide one, so the four buttons
    // are the only content that can spend width without being stretched by it — and moving them
    // out gives the card back the ~110dp they were taking off its height.
    val widthClass = windowWidthClass()
    // A preview has no grade column to stand anywhere: what replaces the four buttons is a single
    // "Next", which is the wrong shape for a 120dp column beside the card and belongs in the row
    // under it at every width. So the wide branch is about the *grades*, not about the window.
    val wide = widthClass.isExpanded && !state.isPreview
    // See studyCardMaxHeight: the ceiling is keyed on the width class, not fixed.
    val cardMaxHeight = studyCardMaxHeight(widthClass)
    Column(
        // Studying is a single-focus task, so it gets a narrow ceiling rather than the reading
        // one: a flashcard blown up to a landscape tablet is 1200dp of white around one word, and
        // four grade buttons stretched to match are a 300dp-wide "Again". Capped and centred, the
        // card keeps the proportions it has on a phone and the eye keeps its place across a flip.
        // The wide ceiling is the same card plus the grade column beside it.
        modifier = Modifier
            .fillMaxSize()
            .contentPane(if (wide) STUDY_WIDE_PANE_WIDTH else STUDY_PANE_WIDTH),
    ) {
        // Header: close · deck name + counter · spacer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onClose,
                modifier = Modifier
                    .testTag("study_close")
                    .size(40.dp),
                shape = RoundedCornerShape(50),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colors.surfaceCard,
                    contentColor = colors.foregroundPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.study_close),
                    modifier = Modifier.size(20.dp),
                )
            }
            StudyHeaderTitle(
                deckTitle = state.deckTitle,
                position = state.position,
                total = state.total,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            StudyHeaderTrailing(reversed = state.reversed)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { state.position.toFloat() / state.total.coerceAtLeast(1) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            color = colors.accentPrimary,
            trackColor = colors.borderSubtle,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )

        // On screen for the whole preview, not only at the end of it. "None of this is being
        // saved" is the one thing a preview must never let someone discover afterwards, and the
        // header above says which deck rather than what kind of session this is.
        PreviewBadge(visible = state.isPreview, modifier = Modifier.align(Alignment.CenterHorizontally))

        Spacer(modifier = Modifier.height(20.dp))

        // Card (tap front to reveal) with a 3D flip that only ever plays forward. Advancing to
        // the next card is a fade/slide, NOT a reverse flip: each card owns its own rotation
        // state, so a new card composes at 0f (front) and the outgoing card keeps rendering its
        // own snapshot while it fades. Without this the un-flip showed the next card's answer
        // for the first half of the turn.
        val reduceMotion = rememberReduceMotion()
        // Shown, not reserved. Holding the column open on the front would leave the card sitting
        // half a gutter off-centre for the whole time the question is up, which is most of a
        // session — so the card is centred on its own, and the grades expand in beside it, sliding
        // it left to make the room. The card's own width never changes, so the flip is still
        // stable in the way that matters: the text does not re-wrap, it only moves.
        val showGradeColumn = wide && state.gradesAvailable
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    // `fill = false` so the card keeps its own width and lets the row centre it,
                    // rather than swallowing whatever the grade column is not using.
                    .weight(1f, fill = false)
                    .widthIn(max = STUDY_CARD_WIDTH)
                    .fillMaxHeight(),
            ) {
                // The weighted Box keeps the flip frame stable across the reveal; the card inside is
                // capped so a one-word prompt doesn't stretch into a near-full-screen rectangle.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = CardSnapshot(
                            position = state.position,
                            frontText = state.frontText,
                            // The flip is never blocked while an answer is being typed — what typing
                            // withholds is the word, not the gesture. So the card turns as it always has,
                            // and the back arrives with the input where its answer goes; the answer's own
                            // text and picture are simply not passed until it is earned.
                            backText = if (state.answerHidden) "" else state.backText,
                            backLabel = state.backLabel,
                            frontImageRef = state.frontImageRef,
                            backImageRef = state.backImageRef.takeUnless { state.answerHidden },
                            revealed = state.revealed,
                            typePhase = state.typePhase,
                            typedAnswer = state.typedAnswer,
                        ),
                        // Keyed on the card, so revealing swaps content in place (the flip) and only a
                        // card change runs the enter/exit transition.
                        contentKey = { it.position },
                        transitionSpec = {
                            if (reduceMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                fadeIn(tween(durationMillis = 200)) +
                                    slideInVertically(tween(durationMillis = 200)) { it / 12 } togetherWith
                                    fadeOut(tween(durationMillis = 100))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = cardMaxHeight)
                            .fillMaxHeight(),
                        label = "cardAdvance",
                    ) { card ->
                        FlippableCard(
                            card = card,
                            reduceMotion = reduceMotion,
                            listenEnabled = state.listenEnabled,
                            speakEnabled = state.speakEnabled,
                            deckId = state.deckId,
                            authorPubky = state.authorPubky,
                            onReveal = onReveal,
                            onSpeak = onSpeak,
                            onSpeakTest = onSpeakTest,
                            answerInput = (card.typePhase as? TypePhase.Answering)?.let { phase ->
                                {
                                    TypeAnswerInput(
                                        value = card.typedAnswer,
                                        languageTag = state.backLang,
                                        cardKey = card.position,
                                        onValueChange = onAnswerChange,
                                        onCheck = onCheckAnswer,
                                        onGiveUp = onGiveUp,
                                        lastMiss = phase.lastMiss,
                                    )
                                }
                            },
                            answerNote = if (card.typePhase is TypePhase.Correct) {
                                { TypeCorrectNote() }
                            } else {
                                null
                            },
                        )
                    }
                }

                BelowCardRows(
                    state = state,
                    wide = wide,
                    reduceMotion = reduceMotion,
                    onGrade = onGrade,
                    onNextCard = onNextCard,
                )
            }
            // The column's width *is* the animation. As it expands the row re-centres, which
            // slides the card left by half the gutter under the same easing — one spring, two
            // things moving, so the card and the buttons cannot drift out of step. Expanding from
            // the start edge means the buttons grow out of the card's edge rather than flying in
            // from off-screen. The per-button stagger inside `SrsColumn` then lands them in turn.
            AnimatedVisibility(
                visible = showGradeColumn,
                enter = if (reduceMotion) {
                    EnterTransition.None
                } else {
                    expandHorizontally(
                        animationSpec = tween(GRADE_REVEAL_MS, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.Start,
                    ) + fadeIn(animationSpec = tween(GRADE_REVEAL_MS))
                },
                exit = if (reduceMotion) {
                    ExitTransition.None
                } else {
                    shrinkHorizontally(
                        animationSpec = tween(GRADE_REVEAL_MS, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.Start,
                    ) + fadeOut(animationSpec = tween(GRADE_REVEAL_MS))
                },
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = GRADE_COLUMN_GAP)
                        .width(GRADE_COLUMN_WIDTH)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    SrsColumn(
                        intervals = state.intervals,
                        onGrade = onGrade,
                        reduceMotion = reduceMotion,
                    )
                }
            }
        }
    }
}

/**
 * How tall the study card may grow, given how much room the window has.
 *
 * The cap stops a one-word prompt stretching into a near-full-screen rectangle. Keyed on the width
 * class because that separates the cases: a portrait tablet is Medium with ~980dp of column to give,
 * a landscape one is Expanded with ~620, and a phone is Compact where the original ceiling was never
 * the binding constraint. The two tablet ceilings sit *above* what those layouts actually offer, so
 * there the card simply fills its share — this is a guard against a stretched card, not a second
 * layout.
 */
private fun studyCardMaxHeight(widthClass: WindowWidthClass): Dp = when (widthClass) {
    WindowWidthClass.Compact -> COMPACT_CARD_MAX_HEIGHT
    WindowWidthClass.Medium -> MEDIUM_CARD_MAX_HEIGHT
    WindowWidthClass.Expanded -> EXPANDED_CARD_MAX_HEIGHT
}

/**
 * Everything the card face renders, plus the identity the advance transition keys on. `position` is
 * unique per presentation: the VM only moves forward, and a card studied both ways sits at two
 * distinct positions rather than being re-queued at one.
 */
private data class CardSnapshot(
    val position: Int,
    val frontText: String,
    val backText: String,
    val backLabel: String?,
    val frontImageRef: MediaRef.Image?,
    val backImageRef: MediaRef.Image?,
    val revealed: Boolean,
    /**
     * **This card's** place in the type-the-answer flow, not the session's. An outgoing card is
     * still composed while it fades, holding the back face it was graded on — so reading the live
     * phase there would draw the *next* card's input, and its `FocusRequester` would raise the
     * keyboard over a front that has nothing to type into.
     */
    val typePhase: TypePhase = TypePhase.Off,
    val typedAnswer: String = "",
) {
    /** The back is turned but its words are a placeholder — see `Reviewing.answerHidden`. */
    val answerHidden: Boolean get() = typePhase is TypePhase.Answering
}

/**
 * One card, owning its own flip. Composed per [CardSnapshot] by the advance `AnimatedContent`,
 * so a freshly entering card starts at 0f (front, no animation) and an exiting card holds the
 * face it was already showing.
 */
@Composable
private fun AnimatedContentScope.FlippableCard(
    card: CardSnapshot,
    reduceMotion: Boolean,
    listenEnabled: Boolean,
    speakEnabled: Boolean,
    deckId: String,
    authorPubky: String,
    onReveal: () -> Unit,
    onSpeak: () -> Unit,
    onSpeakTest: () -> Unit,
    answerInput: (@Composable () -> Unit)? = null,
    answerNote: (@Composable () -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors
    val rotation by animateFloatAsState(
        targetValue = if (card.revealed) 180f else 0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = 700, easing = FastOutSlowInEasing)
        },
        label = "cardFlip",
    )
    // A card on its way out is still on screen for the fade; taps there would hit the card the
    // VM has already moved past.
    val interactive = transition.targetState == EnterExitState.Visible

    Box(
        modifier = Modifier
            .testTag("study_card")
            .fillMaxSize()
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceCard)
            .clickable(enabled = interactive && !card.revealed, onClick = onReveal)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (rotation < 90f) {
            // Listen/Speak sit on both faces. They act on the side facing the user, so on the front
            // they practise the prompt in the *front* language — the side a foreign word usually sits
            // on. The prompt's picture belongs here too: an Anki front is often nothing else, and
            // passing it only to the back left those cards asking the question with a blank card (#96).
            CardFace(
                label = null,
                text = card.frontText,
                textSize = 48.sp,
                onSpeak = onSpeak,
                showListen = interactive && listenEnabled,
                onSpeakTest = if (interactive && speakEnabled) onSpeakTest else null,
                featureImageRef = card.frontImageRef,
                deckId = deckId,
                authorPubky = authorPubky,
            )
        } else {
            // Counter-rotate so the back content is not mirrored.
            // Listen and Speak come off the masked back: both act on the side facing the user,
            // and here that side is the answer the mask is withholding.
            CardFace(
                label = card.backLabel,
                text = card.backText,
                textSize = 42.sp,
                answerInput = answerInput,
                answerNote = answerNote,
                onSpeak = onSpeak,
                showListen = interactive && listenEnabled && !card.answerHidden,
                onSpeakTest = if (interactive && speakEnabled && !card.answerHidden) onSpeakTest else null,
                recallImageRef = card.frontImageRef,
                featureImageRef = card.backImageRef,
                deckId = deckId,
                authorPubky = authorPubky,
                modifier = Modifier.graphicsLayer { rotationY = 180f },
            )
        }
    }
}

/**
 * The grade row and the flip hint, under the card.
 *
 * Everything typing adds lives on the card itself, so these two are exactly what they were before
 * the mode existed: **reserved in every state**, empty or not, so that the card above — which is
 * the `weight(1f)` this height comes out of — is the same size on both faces and before and after
 * the grades arrive. Collapsing them while a typing card was masked bought the card 132 dp and then
 * took it back the moment the answer was checked, which is a card that jumps and re-flows its text
 * under the finger that just earned it.
 *
 * It is also why this is a cut and not an animation: animating this height re-measures the card on
 * every frame, and the card re-measures `TextAutoSize` text — a full layout per step.
 */
@Composable
private fun BelowCardRows(
    state: StudySessionUiState.Reviewing,
    wide: Boolean,
    reduceMotion: Boolean,
    onGrade: (SrsGrade) -> Unit,
    onNextCard: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(20.dp))

        // Only when the grades are not already standing in a column to the right. The buttons
        // appear once the answer is legible, which on a typing card is later than the flip.
        // (Listen/Speak, by contrast, are on both faces — they live inside the card, not here.)
        if (!wide) {
            GradeOrNextRow(
                state = state,
                onGrade = onGrade,
                onNextCard = onNextCard,
                reduceMotion = reduceMotion,
            )

            Spacer(modifier = Modifier.height(20.dp))
        }

        // Shown on the front only; space is reserved on the back too so the card above keeps the
        // same size across the flip.
        Box(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            if (!state.revealed) {
                FlipHint(modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun FlipHint(modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Autorenew,
            contentDescription = null,
            tint = colors.foregroundMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.study_flip_hint),
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            color = colors.foregroundMuted,
        )
    }
}

/**
 * Why buffered reviews are not reaching the homeserver, for the states that can still show it.
 * Loading/Empty/Error have no session in progress to warn about.
 */
private val StudySessionUiState.syncError: ErrorReason?
    get() = when (this) {
        is StudySessionUiState.Reviewing -> syncError
        is StudySessionUiState.Complete -> syncError
        else -> null
    }

/**
 * Warns that graded reviews are not being written, without ending the session. The failure this
 * exists for is a full homeserver, where the copy has to say what the generic error did not: retrying
 * will not help. The reviews are safe — buffered, journalled, re-sent by the next flush that can.
 */
@Composable
private fun SyncErrorBanner(
    reason: ErrorReason,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Surface(
        color = colors.danger,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("study_sync_error"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.study_sync_error_title),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(errorMessage(reason), color = Color.White, fontSize = 13.sp)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.study_sync_error_dismiss), color = Color.White)
            }
        }
    }
}

/**
 * The daily goal, met. Covers the session because it happens once a day.
 *
 * Both ways out are equal in weight but not emphasis: "Keep studying" is the filled button, because
 * the card behind this is already loaded and the goal withholds nothing.
 */
@Composable
private fun BoxScope.GoalCelebrationScreen(
    celebration: GoalCelebration,
    onKeepStudying: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val reduceMotion = rememberReduceMotion()

    Box(
        modifier = Modifier
            .matchParentSize()
            .background(colors.surfacePrimary)
            // Swallows taps so a stray press does not reach the card underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .testTag("study_goal_reached"),
    ) {
        Confetti(
            colors = listOf(colors.srsGood, colors.accentPrimary, colors.srsEasy, colors.srsHard),
            reduceMotion = reduceMotion,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "🎉", fontSize = 64.sp)
            Text(
                text = stringResource(R.string.study_goal_reached_title),
                fontSize = 26.sp,
                fontWeight = FontWeight.W800,
                color = colors.foregroundPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.study_goal_reached_count,
                    celebration.newCardsToday,
                    celebration.newCardsToday,
                ),
                fontSize = 16.sp,
                color = colors.foregroundPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.study_goal_reached_body),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colors.foregroundMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onKeepStudying,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("study_goal_keep_studying"),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = colors.foregroundOnAccent,
                ),
            ) {
                Text(
                    text = stringResource(R.string.study_goal_keep_studying),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W700,
                )
            }
            TextButton(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("study_goal_done"),
            ) {
                Text(
                    text = stringResource(R.string.study_goal_done),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W600,
                    color = colors.foregroundMuted,
                )
            }
        }
    }
}

private val previewReviewing = StudySessionUiState.Reviewing(
    deckTitle = "Spanish basics",
    position = 3,
    total = 12,
    frontText = "hola",
    backText = "hello",
    backLabel = "MEANING",
    revealed = true,
    intervals = mapOf(
        SrsGrade.Again to "1m",
        SrsGrade.Hard to "10m",
        SrsGrade.Good to "1d",
        SrsGrade.Easy to "4d",
    ),
)

@Preview
@Composable
private fun StudySessionScreenRevealedPreview() {
    LoopkyTheme {
        StudySessionScreen(
            state = previewReviewing,
            onReveal = {},
            onGrade = {},
            onSpeak = {},
            onClose = {},
            onDone = {},
        )
    }
}

@Preview
@Composable
private fun StudySessionScreenFrontPreview() {
    LoopkyTheme {
        StudySessionScreen(
            state = previewReviewing.copy(revealed = false),
            onReveal = {},
            onGrade = {},
            onSpeak = {},
            onClose = {},
            onDone = {},
        )
    }
}

/**
 * Owns the RECORD_AUDIO flow for Speak: rationale before the cold system prompt, a toast on a
 * recoverable denial, and a route into app settings once Android stops asking. Extracted from
 * `StudySessionRoute` to keep that composable under detekt's complexity cap.
 */
@Composable
private fun rememberMicPermissionRequest(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var showBlocked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onGranted()
            return@rememberLauncherForActivityResult
        }
        // Once the system stops offering the prompt a toast is a dead end: Speak would be
        // inert forever with no way to re-enable it from inside the app.
        val canAskAgain =
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: true
        if (canAskAgain) {
            Toast.makeText(context, R.string.speak_permission_denied, Toast.LENGTH_LONG).show()
        } else {
            showBlocked = true
        }
        onDenied()
    }

    MicPermissionDialogs(
        showRationale = showRationale,
        showBlocked = showBlocked,
        onRationaleConfirm = {
            showRationale = false
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onRationaleDismiss = { showRationale = false },
        onBlockedDismiss = { showBlocked = false },
    )

    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onGranted() else showRationale = true
    }
}

/** Split out of `StudySessionRoute` purely to keep its complexity under the detekt cap. */
@Composable
private fun MicPermissionDialogs(
    showRationale: Boolean,
    showBlocked: Boolean,
    onRationaleConfirm: () -> Unit,
    onRationaleDismiss: () -> Unit,
    onBlockedDismiss: () -> Unit,
) {
    if (showRationale) {
        PermissionRationaleDialog(
            title = stringResource(R.string.permission_mic_title),
            message = stringResource(R.string.permission_mic_rationale),
            onConfirm = onRationaleConfirm,
            onDismiss = onRationaleDismiss,
        )
    }
    if (showBlocked) {
        PermissionBlockedDialog(
            title = stringResource(R.string.permission_mic_denied_title),
            message = stringResource(R.string.permission_mic_denied_message),
            onDismiss = onBlockedDismiss,
        )
    }
}

/**
 * How wide the study column may get. Between `PaneWidth.Focused` and `Reading`: a card wants more
 * room than a sign-in form, because its text auto-sizes and a wider box keeps a long sentence large —
 * but less than a settings list, because the grade row is four buttons that should stay thumb-sized.
 */
private val STUDY_PANE_WIDTH = 640.dp

/** The card at its usual width plus the grade column and the gap between them. */
private val STUDY_WIDE_PANE_WIDTH = 880.dp

/** The card keeps the width it has on a phone; only its position changes. */
private val STUDY_CARD_WIDTH = 640.dp

/** Unchanged from before tablets existed: on a phone this ceiling is never the binding one. */
private val COMPACT_CARD_MAX_HEIGHT = 560.dp

/** A portrait tablet has the height to spend; short of the full column so the card still floats. */
private val MEDIUM_CARD_MAX_HEIGHT = 860.dp

/** Above the ~620dp a landscape tablet leaves between the progress bar and the flip hint, so the
 *  card fills that column rather than floating in it. Binds only on a desktop-tall window. */
private val EXPANDED_CARD_MAX_HEIGHT = 720.dp

/** Wide enough for "Again" and its interval on one line each. */
private val GRADE_COLUMN_WIDTH = 200.dp

/** Between the card and the grades. Part of the gutter the card slides across. */
private val GRADE_COLUMN_GAP = 24.dp

/** Short enough to feel like a response to the tap, long enough to read as movement. */
private const val GRADE_REVEAL_MS = 260
