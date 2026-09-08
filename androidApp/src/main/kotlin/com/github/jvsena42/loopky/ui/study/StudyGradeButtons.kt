package com.github.jvsena42.loopky.ui.study

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The four SRS grade buttons, in the two arrangements the study screen uses: a row beneath the
 * card on a phone, a column beside it in landscape.
 */

@Composable
internal fun SrsRow(
    intervals: Map<SrsGrade, String>,
    onGrade: (SrsGrade) -> Unit,
    reduceMotion: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        srsButtonSpecs().forEachIndexed { index, (grade, color) ->
            SrsButton(
                grade = grade,
                color = color,
                interval = intervals[grade] ?: "",
                index = index,
                reduceMotion = reduceMotion,
                onGrade = onGrade,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The same four grades stacked, for the column beside the card in landscape.
 *
 * Same order, same colours, same stagger as [SrsRow] — hardest at the top, easiest at the bottom
 * — because the buttons are muscle memory and a landscape session must not re-teach them. Nothing
 * here is weighted: the four keep the height they have on a phone rather than stretching to fill
 * the card's, which would turn "Again" into a banner and lose the row's thumb-sized rhythm.
 */
@Composable
internal fun SrsColumn(
    intervals: Map<SrsGrade, String>,
    onGrade: (SrsGrade) -> Unit,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        srsButtonSpecs().forEachIndexed { index, (grade, color) ->
            SrsButton(
                grade = grade,
                color = color,
                interval = intervals[grade] ?: "",
                index = index,
                reduceMotion = reduceMotion,
                onGrade = onGrade,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The button's own label.
 *
 * Never `SrsGrade.name`: that is the Kotlin enum entry, so every language got "Again"/"Hard"/
 * "Good"/"Easy". The `study_*` test tags still come from `name` and must keep doing so — the
 * android-cli journeys target them, and a localized id would only be findable in English.
 */
private val SrsGrade.labelRes: Int
    get() = when (this) {
        SrsGrade.Again -> R.string.srs_grade_again
        SrsGrade.Hard -> R.string.srs_grade_hard
        SrsGrade.Good -> R.string.srs_grade_good
        SrsGrade.Easy -> R.string.srs_grade_easy
    }

/**
 * Grade-to-colour, in the order the buttons are always drawn.
 *
 * Shared by the row and the column so the two arrangements cannot drift into different orders —
 * which would be the one difference on this screen a user could actually be punished for.
 */
@Composable
private fun srsButtonSpecs(): List<Pair<SrsGrade, Color>> {
    val colors = LoopkyTheme.colors
    return listOf(
        SrsGrade.Again to colors.srsAgain,
        SrsGrade.Hard to colors.srsHard,
        SrsGrade.Good to colors.srsGood,
        SrsGrade.Easy to colors.srsEasy,
    )
}

/**
 * One grade button. On reveal the buttons fade + rise + scale in with a per-index
 * stagger; pressing dips the scale for tactile feedback. Both effects are skipped when
 * the OS has animations disabled.
 */
@Composable
private fun SrsButton(
    grade: SrsGrade,
    color: Color,
    interval: String,
    index: Int,
    reduceMotion: Boolean,
    onGrade: (SrsGrade) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val enter by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = 280, delayMillis = index * 60, easing = FastOutSlowInEasing)
        },
        label = "srsEnter",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.94f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "srsPress",
    )

    Button(
        onClick = { onGrade(grade) },
        interactionSource = interactionSource,
        modifier = modifier
            .testTag("study_${grade.name.lowercase()}")
            .height(72.dp)
            .graphicsLayer {
                alpha = enter
                val scale = (0.85f + 0.15f * enter) * pressScale
                scaleX = scale
                scaleY = scale
                translationY = (1f - enter) * 20.dp.toPx()
            },
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(grade.labelRes),
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 11.sp,
                    maxFontSize = 15.sp,
                    stepSize = 0.5.sp,
                ),
                fontWeight = FontWeight.W700,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = interval,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 8.sp,
                    maxFontSize = 11.sp,
                    stepSize = 0.5.sp,
                ),
                fontWeight = FontWeight.W500,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
