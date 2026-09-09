package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Loopky tag chip: native Material 3 [InputChip] tinted with the secondary accent and keeping the
 * Loopky `#` prefix as a brand touch. Removable when [onRemove] is supplied.
 *
 * A chip the reader picks *between* is a [TagFilterChip] instead — this one carries a tag that is
 * already on something.
 */
@Composable
fun TagChip(
    tag: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors
    InputChip(
        selected = false,
        onClick = onClick ?: {},
        modifier = modifier,
        label = { TagLabel(tag) },
        trailingIcon = onRemove?.let { { RemoveButton(onRemove = it) } },
        colors = InputChipDefaults.inputChipColors(
            containerColor = colors.accentSecondarySoft,
            labelColor = colors.accentSecondary,
            trailingIconColor = colors.accentSecondary,
        ),
        border = null,
    )
}

/**
 * A tag offered as one choice among several — the discover topic row.
 *
 * [FilterChip] rather than [InputChip] because the difference is not cosmetic: it draws the leading
 * checkmark that says *this* one is the filter in force, and it carries the selected state into the
 * semantics tree, so a screen reader announces the chosen topic as chosen. An input chip announces
 * four identical buttons and leaves the fill colour as the only evidence of which is on.
 */
@Composable
fun TagFilterChip(
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { TagLabel(tag) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.accentSecondarySoft,
            labelColor = colors.accentSecondary,
            selectedContainerColor = colors.accentSecondary,
            selectedLabelColor = colors.foregroundOnAccent,
            selectedLeadingIconColor = colors.foregroundOnAccent,
        ),
        border = null,
    )
}

@Composable
private fun TagLabel(tag: String) {
    Text(
        text = stringResource(R.string.component_tag_chip_label, tag),
        fontSize = 13.sp,
        fontWeight = FontWeight.W600,
    )
}

/**
 * The remove affordance, drawn as a 16dp glyph inside a 24dp target.
 *
 * The glyph alone was the whole touch target — a third of the 48dp minimum, and no wider than the
 * `#` beside it. A chip has nowhere near 48dp of height to give, so the target grows to the widest
 * the trailing slot allows and the glyph is centred in it.
 */
@Composable
private fun RemoveButton(onRemove: () -> Unit) {
    val label = stringResource(R.string.component_tag_chip_remove)
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Preview
@Composable
private fun TagChipPreview() {
    LoopkyTheme {
        Row(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            Column {
                TagChip(tag = "spanish")
                Spacer(modifier = Modifier.size(8.dp))
                TagChip(tag = "removable", onRemove = {})
                Spacer(modifier = Modifier.size(8.dp))
                TagFilterChip(tag = "filter", selected = false, onClick = {})
                Spacer(modifier = Modifier.size(8.dp))
                TagFilterChip(tag = "filter on", selected = true, onClick = {})
            }
        }
    }
}
