package com.github.jvsena42.loopky.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.ui.components.CardMediaImage
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

@Composable
@Suppress("LongParameterList")
internal fun CardFace(
    label: String?,
    text: String,
    textSize: TextUnit,
    onSpeak: () -> Unit,
    showListen: Boolean,
    onSpeakTest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * Drawn where [text] would be, and instead of it: the typed-answer input, so the answer is
     * written into the space the answer itself will occupy. Non-null only while answering.
     */
    answerInput: (@Composable () -> Unit)? = null,
    /**
     * A line under the answer, on the card rather than beneath it — "Correct!" after a typed
     * answer lands. Nothing in the study session's chrome grows for it, so the card keeps its
     * size across the flip.
     */
    answerNote: (@Composable () -> Unit)? = null,
    /**
     * The prompt's picture recalled on the back as a small circular cue, so the answer is read
     * against the question it belongs to. Never the content of the side it is drawn on.
     */
    recallImageRef: MediaRef.Image? = null,
    /**
     * The picture that **is** this side — an image-only Anki front, or a picture answer — drawn
     * large enough to read. Anki's `Basic` note type routinely puts nothing but an `<img>` in a
     * field, so a side whose picture is only ever a 96 dp avatar renders as a blank card (#96).
     */
    featureImageRef: MediaRef.Image? = null,
    deckId: String = "",
    authorPubky: String = "",
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        label?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                fontSize = 14.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 1.5.sp,
                color = colors.accentPrimary,
            )
        }
        // Front-side image shown as a circular avatar on the card back.
        recallImageRef?.let { image ->
            CardMediaImage(
                image = image,
                deckId = deckId,
                authorPubky = authorPubky,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(colors.accentPrimarySoft),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        // The side itself, when it is a picture rather than words.
        featureImageRef?.let { image ->
            CardMediaImage(
                image = image,
                deckId = deckId,
                authorPubky = authorPubky,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
        if (answerInput != null) {
            // Deliberately un-padded for the keyboard, and centred rather than pinned. The screen
            // does not resize for the IME any more (see `StudySessionScreen`), so the card keeps
            // its height and its lower edge simply passes behind the keyboard — the block sits at
            // the card's middle, which is clear of it. `imePadding()` here is not the fix it looks
            // like: it pads by the keyboard's *whole* height rather than the part that overlaps,
            // which shrank this viewport to less than the block and sheared Give up off the bottom.
            //
            // It scrolls rather than clips, for the windows where the middle is not clear either: a
            // Give up button that cannot be reached is the one control in this mode that must never
            // be unreachable.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) { answerInput() }
        } else if (text.isNotBlank()) {
            CardText(
                text = text,
                maxTextSize = textSize,
                // An image-only side has no text to give room to, and weight(1f) twice would
                // halve the picture for the sake of an empty line.
                modifier = if (featureImageRef == null) Modifier.weight(1f) else Modifier,
            )
        }
        answerNote?.let {
            Spacer(modifier = Modifier.size(12.dp))
            it()
        }
        // Skipped entirely while answering: both buttons are off a card whose answer is hidden,
        // so the row and its spacer would only be taking height the input needs.
        if (answerInput != null) return@Column
        Spacer(modifier = Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showListen) {
                FilledTonalButton(
                    onClick = onSpeak,
                    modifier = Modifier.testTag("study_listen"),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.accentPrimarySoft,
                        contentColor = colors.accentPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.study_listen),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(R.string.study_listen), fontSize = 14.sp, fontWeight = FontWeight.W700)
                }
            }
            if (onSpeakTest != null) {
                FilledTonalButton(
                    onClick = onSpeakTest,
                    modifier = Modifier.testTag("study_speak"),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.accentSecondarySoft,
                        contentColor = colors.accentSecondary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.study_speak),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(R.string.study_speak), fontSize = 14.sp, fontWeight = FontWeight.W700)
                }
            }
        }
    }
}

/**
 * The card's prompt or answer, sized to fit the space it has.
 *
 * Three things were missing and each one alone breaks a real deck (#96). Overriding `fontSize` to
 * 48 sp left Material's `bodyLarge` 24 sp **leading** in place, so every line after the first drew
 * on top of the one above — a 70-character answer already overlapped, and 94% of one real
 * chemistry deck has a side longer than that. `lineHeight` is therefore relative: with autosize
 * shrinking the glyphs, a fixed `sp` leading would stop tracking them.
 *
 * Then the size itself. A 467-character answer at a fixed 48 sp is a solid black smear off both
 * edges of the card, so the text steps down to fit — and past the floor, scrolls. Order matters
 * here: `autoSize` needs a **bounded** height to shrink against, and a scroll container offers its
 * child infinite height, so the constraint is captured outside the scroll and handed back in.
 *
 * Lastly the weight. Without it a long answer pushed the Listen/Speak row clean off the card.
 */
@Composable
private fun ColumnScope.CardText(text: String, maxTextSize: TextUnit, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val available = maxHeight
        Box(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = MIN_CARD_TEXT_SIZE,
                    maxFontSize = maxTextSize,
                    stepSize = CARD_TEXT_STEP,
                ),
                lineHeight = CARD_LINE_HEIGHT,
                fontWeight = FontWeight.W800,
                color = colors.foregroundPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .testTag("study_card_text")
                    .heightIn(max = available),
            )
        }
    }
}

/** Below this the text scrolls instead of shrinking further; smaller stops being readable. */
private val MIN_CARD_TEXT_SIZE = 16.sp
private val CARD_TEXT_STEP = 1.sp

/** Relative, so the leading follows the font autosize settles on rather than a fixed 24 sp. */
private val CARD_LINE_HEIGHT = 1.2.em
