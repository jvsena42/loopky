package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AppBarRow
import androidx.compose.material3.AppBarScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * [canEdit] is wider than [isOwned]: a followed deck carries the pencil too, and tapping it offers
 * a copy rather than the editor (#254). That is the whole of the clone flow's discoverability —
 * wanting to change someone's deck is the one moment owning your own version is the answer.
 */
@Composable
internal fun HeaderBar(
    isOwned: Boolean,
    canReport: Boolean,
    canEdit: Boolean,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onReportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCircleButton(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.deck_detail_back),
            iconSize = 24.dp,
            onClick = onBackClick,
        )

        // `AppBarRow`'s content block is not a composable one — it runs inside a `derivedStateOf`
        // to collect the items — so every label has to be resolved out here.
        val editLabel = stringResource(R.string.deck_detail_edit)
        val deleteLabel = stringResource(R.string.deck_detail_delete)
        val shareLabel = stringResource(R.string.deck_detail_share)
        val reportLabel = stringResource(R.string.report_action)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppBarRow(
                overflowIndicator = { menu ->
                    HeaderCircleButton(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.deck_detail_more_actions),
                        onClick = menu::show,
                        modifier = Modifier.testTag("deck_more_actions"),
                    )
                },
            ) {
                if (canEdit) {
                    headerAction(
                        imageVector = Icons.Default.Edit,
                        label = editLabel,
                        onClick = onEditClick,
                        testTag = "deck_edit",
                    )
                }
                if (isOwned) {
                    headerAction(
                        imageVector = Icons.Default.Delete,
                        label = deleteLabel,
                        onClick = onDeleteClick,
                        testTag = "deck_delete",
                        tint = colors.danger,
                    )
                }
                headerAction(
                    imageVector = Icons.Default.Share,
                    label = shareLabel,
                    onClick = onShareClick,
                    testTag = "deck_share",
                )
            }
            // Never a circle on the cover: Report waits behind its own More. AppBarRow cannot keep
            // just its last item in the overflow — it shows all of them or one fewer plus the menu.
            if (canReport) ReportMenu(label = reportLabel, onReportClick = onReportClick)
        }
    }
}

@Composable
private fun ReportMenu(label: String, onReportClick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(start = 8.dp)) {
        HeaderCircleButton(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.deck_detail_more_actions),
            onClick = { expanded = true },
            modifier = Modifier.testTag("deck_overflow"),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(imageVector = Icons.Default.Flag, contentDescription = null) },
                onClick = {
                    expanded = false
                    onReportClick()
                },
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag("deck_report"),
            )
        }
    }
}

/**
 * One header action, drawn as Loopky's circle button in the row and as a labelled row in the
 * overflow menu.
 *
 * [AppBarScope.customItem] rather than `clickableItem` because the stock item is a bare
 * [androidx.compose.material3.IconButton] and these sit on the deck's cover art, where they need
 * the filled circle to stay legible. What the DSL is here for is the *other* rendering: an action
 * that will not fit stops being dropped off the edge and becomes a named menu row, and it names
 * itself in a tooltip on the way.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun AppBarScope.headerAction(
    imageVector: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    tint: Color = Color.Unspecified,
) {
    customItem(
        appbarContent = {
            // The tooltip is what `clickableItem` would have given for free, and the reason to
            // keep it: three unlabelled circles on cover art are three guesses on first sight.
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Below,
                ),
                tooltip = { PlainTooltip { Text(label) } },
                state = rememberTooltipState(),
            ) {
                HeaderCircleButton(
                    imageVector = imageVector,
                    contentDescription = label,
                    tint = tint,
                    onClick = onClick,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .testTag(testTag),
                )
            }
        },
        menuContent = { menu ->
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(imageVector = imageVector, contentDescription = null) },
                onClick = {
                    menu.dismiss()
                    onClick()
                },
                // The menu is its own window, which the root's testTagsAsResourceId does not reach.
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag(testTag),
            )
        },
    )
}

@Composable
internal fun HeaderCircleButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    iconSize: Dp = 20.dp,
) {
    val colors = LoopkyTheme.colors
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(50),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = colors.surfaceCard,
            contentColor = if (tint == Color.Unspecified) colors.foregroundPrimary else tint,
        ),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
}
