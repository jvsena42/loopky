package com.github.jvsena42.loopky.ui.study

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.TypedAnswerOutcome
import com.github.jvsena42.loopky.presentation.study.TypeMiss
import com.github.jvsena42.loopky.ui.components.rememberReduceMotion
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.delay

/**
 * The input the answer is written into, drawn **on the card back** — in the very space the answer
 * itself occupies once it is revealed. The card is where the question is, so it is where the
 * answer gets written; putting the field below the card made it read as a search box.
 *
 * [cardKey] re-focuses the field per card, so a session of typing is not one tap of setup per
 * card. [languageTag] is the *back's* language when the deck happens to have declared one — the
 * keyboard then comes up with that layout, and its accented keys within reach. Purely additive:
 * typing works with no pair declared at all, which is the whole reason the mode is not gated on
 * one (`Deck.speechReady`).
 */
@Composable
internal fun TypeAnswerInput(
    value: String,
    languageTag: String?,
    cardKey: Int,
    onValueChange: (String) -> Unit,
    onCheck: () -> Unit,
    onGiveUp: () -> Unit,
    modifier: Modifier = Modifier,
    lastMiss: TypeMiss? = null,
) {
    val colors = LoopkyTheme.colors
    val focusRequester = remember { FocusRequester() }
    // Not on composition: this field appears as the card crosses 90°, and taking focus there raises
    // the keyboard *during* the turn. The IME is a window — showing it makes SurfaceFlinger allocate
    // its surface, and every frame the card is drawing then blocks behind that swap. Traced at
    // ~430 ms of `dequeueBuffer`/`present` landing squarely mid-flip, which is the stutter that was
    // visible on every typing card. So the field waits for the card to land, and the keyboard comes
    // up after it rather than through it.
    val reduceMotion = rememberReduceMotion()
    LaunchedEffect(cardKey) {
        if (!reduceMotion) delay(REMAINING_FLIP_MS)
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = {
                Text(
                    text = stringResource(R.string.study_type_placeholder),
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                hintLocales = languageTag?.let { LocaleList(it) },
            ),
            keyboardActions = KeyboardActions(onDone = { onCheck() }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentPrimary,
                unfocusedBorderColor = colors.borderSubtle,
                focusedContainerColor = colors.surfaceCard,
                unfocusedContainerColor = colors.surfaceCard,
                focusedTextColor = colors.foregroundPrimary,
                unfocusedTextColor = colors.foregroundPrimary,
                cursorColor = colors.accentPrimary,
            ),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.W700,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .testTag("study_type_input")
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        // Between the field and Check, because it is about what is still in the field. What was
        // typed is not repeated back: it has not been cleared, so it is right there.
        //
        // It grows in rather than appearing: the line sits above Check, so a hard cut moves the
        // button out from under the finger that is already reaching for it.
        AnimatedVisibility(visible = lastMiss != null) {
            val outcome = lastMiss?.outcome
            Text(
                text = when (outcome) {
                    TypedAnswerOutcome.NearMiss -> stringResource(R.string.study_type_near_miss)
                    else -> stringResource(R.string.study_type_wrong)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.W700,
                textAlign = TextAlign.Center,
                color = when (outcome) {
                    TypedAnswerOutcome.NearMiss -> colors.srsHard
                    else -> colors.srsAgain
                },
                modifier = Modifier.testTag("study_type_miss"),
            )
        }
        Button(
            onClick = onCheck,
            enabled = value.isNotBlank(),
            modifier = Modifier
                .testTag("study_type_check")
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accentPrimary,
                contentColor = colors.foregroundOnAccent,
            ),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.study_type_check),
                fontSize = 15.sp,
                fontWeight = FontWeight.W700,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        // Directly under Check, quiet next to it: the two things you can do with a card you are
        // stuck on belong together, and the escape reads as the smaller of them.
        GiveUpButton(onGiveUp = onGiveUp)
    }
}

/**
 * The way out of a card you cannot answer, sitting under Check on the card back.
 *
 * Always on offer while answering, with no confirm step — a mode that can trap a session is worse
 * than no mode. It reveals the answer and says nothing about how the card should be graded.
 */
@Composable
internal fun GiveUpButton(onGiveUp: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onGiveUp, modifier = modifier.testTag("study_give_up")) {
        Text(
            text = stringResource(R.string.study_type_give_up),
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
            color = LoopkyTheme.colors.foregroundMuted,
        )
    }
}

/**
 * "Correct!", under the answer on the card back.
 *
 * The only Check outcome that gets a line on an open card, because it is the only one that opens
 * it — a miss is reported next to the input it wants you to fix, and giving up says nothing at
 * all, the answer being the only thing that button promised. Reporting, not scoring: nothing here
 * pre-selects or tints an SRS button.
 */
@Composable
internal fun TypeCorrectNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.study_type_correct),
        fontSize = 15.sp,
        fontWeight = FontWeight.W700,
        textAlign = TextAlign.Center,
        color = LoopkyTheme.colors.srsGood,
        modifier = modifier.testTag("study_type_result"),
    )
}

/**
 * What is left of the card's 700 ms turn once the back face — and with it this field — is composed,
 * which happens as the card passes 90°. See [TypeAnswerInput]: the keyboard waits it out.
 */
private const val REMAINING_FLIP_MS = 350L
