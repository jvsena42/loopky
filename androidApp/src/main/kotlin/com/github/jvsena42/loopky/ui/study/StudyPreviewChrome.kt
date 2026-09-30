package com.github.jvsena42.loopky.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.presentation.study.StudySessionUiState
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.relativeFromNow

// The end-of-session panel and the two controls a preview swaps in, lifted out of
// StudySessionScreen.kt — which is at detekt's per-file function ceiling, and whose two big
// composables were at the complexity one.

/**
 * "Preview · progress isn't saved" — a quiet pill, present for the whole session.
 *
 * Takes [visible] rather than being wrapped in an `if` at the call site: the study screen is at
 * detekt's complexity ceiling, and this is a branch about this pill rather than about that layout.
 * Nothing is reserved when it is hidden — a real session has no gap where it would have been.
 */
@Composable
internal fun ColumnScope.PreviewBadge(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    val colors = LoopkyTheme.colors
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.study_preview_badge),
        fontSize = 12.sp,
        fontWeight = FontWeight.W600,
        color = colors.foregroundMuted,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceCard)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("study_preview_badge"),
    )
}

/** What stands in for the four grade buttons in a preview: move on, decide nothing. */
@Composable
internal fun PreviewNextButton(onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accentPrimary,
            contentColor = colors.foregroundOnAccent,
        ),
        modifier = Modifier.testTag("study_preview_next"),
    ) {
        Text(
            text = stringResource(R.string.study_preview_next),
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
        )
    }
}

@Composable
internal fun BoxScope.CenteredMessage(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    /** Quieter lines below the subtitle. Their own Texts rather than appended prose, so each wraps. */
    details: List<String> = emptyList(),
    /**
     * A second, quieter way out, under the primary button. Null for the states that have exactly
     * one thing to do next — which is all of them except the end of a guest's preview, where the
     * offer is the primary and simply leaving must stay available beside it.
     */
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.W800,
            color = colors.foregroundPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            fontSize = 15.sp,
            color = colors.foregroundMuted,
            textAlign = TextAlign.Center,
        )
        details.forEach { line ->
            Text(
                text = line,
                fontSize = 14.sp,
                color = colors.foregroundMuted,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accentPrimary,
                contentColor = colors.foregroundOnAccent,
            ),
        ) {
            Text(
                text = actionLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.W700,
            )
        }
        if (secondaryLabel != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.testTag("study_secondary_action")) {
                Text(
                    text = secondaryLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W600,
                    color = colors.foregroundMuted,
                )
            }
        }
    }
}

/**
 * The row under the card: four grade buttons, or the one a preview replaces them with.
 *
 * Reserved rather than conditional, so the card keeps one size across the flip — the height is
 * held open whether or not anything is standing in it.
 *
 * A preview never grades. Offering Again/Hard/Good/Easy for a review that is discarded would be
 * four buttons that all do the same nothing, dressed up as a scheduling decision.
 */
@Composable
internal fun GradeOrNextRow(
    state: StudySessionUiState.Reviewing,
    onGrade: (SrsGrade) -> Unit,
    onNextCard: () -> Unit,
    reduceMotion: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(GRADE_ROW_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.gradesAvailable -> SrsRow(
                intervals = state.intervals,
                onGrade = onGrade,
                reduceMotion = reduceMotion,
            )
            state.previewAdvanceAvailable -> PreviewNextButton(onClick = onNextCard)
        }
    }
}

/**
 * "All done!", or the end of a preview.
 *
 * A preview has nothing to report: no reviews were stored, so there is no next-due date and no
 * daily tally. What it owes the reader instead is how to keep the progress it did not — an
 * account for a visitor, a follow or a clone for someone who already has one. Both of those are
 * true statements about what would have to change, which is why the two branches word it
 * differently rather than sharing one vague line.
 */
@Composable
internal fun BoxScope.CompleteMessage(
    state: StudySessionUiState.Complete,
    onDone: () -> Unit,
    onSignIn: () -> Unit,
) {
    if (state.isPreview) {
        CenteredMessage(
            title = stringResource(R.string.study_preview_complete_title),
            subtitle = pluralStringResource(R.plurals.cards_tried, state.reviewed, state.reviewed),
            details = listOf(
                stringResource(
                    if (state.isSignedIn) {
                        R.string.study_preview_member_detail
                    } else {
                        R.string.study_preview_guest_detail
                    },
                ),
            ),
            actionLabel = if (state.isSignedIn) {
                stringResource(R.string.study_preview_back)
            } else {
                stringResource(R.string.study_preview_action_guest)
            },
            onAction = if (state.isSignedIn) onDone else onSignIn,
            // Only for a guest: with an account, "Back to deck" is already the one button, and a
            // second control saying the same thing is noise.
            secondaryLabel = stringResource(R.string.study_preview_back).takeUnless { state.isSignedIn },
            onSecondary = onDone,
        )
        return
    }
    CenteredMessage(
        title = stringResource(R.string.study_complete_title),
        subtitle = pluralStringResource(R.plurals.cards_reviewed, state.reviewed, state.reviewed),
        actionLabel = stringResource(R.string.study_back),
        onAction = onDone,
        details = listOfNotNull(
            // Saying when the next review lands is what makes an empty queue read as earned
            // rather than as a dead end (#101 §5).
            state.nextDueAtMillis
                ?.let { stringResource(R.string.home_caught_up_next_due, relativeFromNow(it)) },
            // The day's tally, which is the number that actually accumulates.
            if (state.newCardsToday >= state.newCardsGoal) {
                stringResource(R.string.home_new_cards_goal_reached, state.newCardsGoal)
            } else {
                stringResource(R.string.home_new_cards_goal, state.newCardsToday, state.newCardsGoal)
            },
        ),
    )
}

/** The height the grade row holds open whether or not anything is standing in it. */
private val GRADE_ROW_HEIGHT = 72.dp

@Preview
@Composable
private fun PreviewCompletePreview() {
    LoopkyTheme {
        Box(modifier = Modifier.background(LoopkyTheme.colors.surfaceSecondary).padding(20.dp)) {
            CompleteMessage(
                state = StudySessionUiState.Complete(reviewed = 10, isPreview = true, isSignedIn = false),
                onDone = {},
                onSignIn = {},
            )
        }
    }
}
