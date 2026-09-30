package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.openUrl
import com.github.jvsena42.loopky.util.findLinks

/**
 * Free text — a deck description, say — with its links tapped through to the browser and the body
 * clamped to [collapsedMaxLines] behind a "Read more" toggle.
 *
 * The toggle only appears once the text has actually overflowed, which is measured rather than
 * guessed at from the character count: a 500-character cap says nothing about how many lines the
 * text takes at this width and font scale.
 */
@Composable
fun ExpandableLinkedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LoopkyTheme.colors.foregroundSecondary,
    linkColor: Color = LoopkyTheme.colors.accentPrimary,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 20.sp,
    collapsedMaxLines: Int = COLLAPSED_MAX_LINES,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    // Sticky: once expanded, the layout no longer overflows, and the toggle must not vanish.
    var overflowed by rememberSaveable(text) { mutableStateOf(false) }

    val annotated = remember(text, linkColor) {
        val linkStyles = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        )
        buildAnnotatedString {
            append(text)
            findLinks(text).forEach { link ->
                addLink(
                    LinkAnnotation.Url(link.url, linkStyles) { context.openUrl(link.url) },
                    link.start,
                    link.end,
                )
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = annotated,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded && result.hasVisualOverflow) overflowed = true
            },
        )
        if (overflowed) {
            Text(
                text = stringResource(
                    if (expanded) R.string.read_less else R.string.read_more,
                ),
                color = linkColor,
                fontSize = fontSize,
                fontWeight = FontWeight.W600,
                modifier = Modifier
                    .testTag("expandable_text_toggle")
                    .clickable { expanded = !expanded },
            )
        }
    }
}

private const val COLLAPSED_MAX_LINES = 4
