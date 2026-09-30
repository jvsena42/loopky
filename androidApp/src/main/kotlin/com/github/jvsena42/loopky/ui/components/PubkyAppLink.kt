package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The two ways Loopky points at pubky.app.
 *
 * A Loopky account *is* a Pubky account — the same `profile.json`, the same follow graph, the same
 * key — and until now nothing in the app said so out loud. Both of these are deliberately quiet:
 * the network underneath is worth knowing about, but it is not what someone opened a flashcards
 * app to do.
 *
 * Neither builds its own URL. The address comes from the ViewModel, which reads it off
 * `PubkyEnvironment` — a debug build points at staging, where its account actually exists.
 */

/**
 * The button that leaves for pubky.app: the mark alone, in the same outlined 48dp circle Copy and
 * Share wear, so it carries no more weight in the row than they do — lit by [PubkyLime].
 *
 * The mark stays monochrome, in Share's grey. pubky.app sets it in lime on black, but that disc
 * was the highest-contrast thing on a cream screen and pulled the eye before the primary action
 * beside it — and the lime *as ink* is the faintest thing on the screen, 1.3:1 on cream. The
 * brand colour is carried by the glow underneath instead, where being pale costs it nothing: a
 * shadow is spread light, not a legibility surface. That is also why the alpha is this high next
 * to the accent's `0x33` — lime has nowhere near orange's density against cream.
 */
@Composable
fun PubkyAppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val label = stringResource(R.string.pubky_app_open_profile)

    Box(
        modifier = modifier
            .size(48.dp)
            // `dropShadow`, not the elevation `shadow` the accent hero uses: an elevation shadow
            // is cast by a light above the screen, so it lands offset to one side and its colour
            // survives only as a tint — lime came out as an olive smudge under the lower-right
            // edge. This one draws the colour as given, centred, so the circle sits in a halo.
            .dropShadow(CircleShape, pubkyGlow())
            .clip(CircleShape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.borderSubtle, CircleShape)
            .clickable(onClick = onClick)
            // The mark carries the meaning visually and has no text of its own, so the label goes
            // on the button rather than on the image inside it.
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        PubkyMark(size = 22.dp)
    }
}

/**
 * The self-profile call to action: one soft row explaining what the button above it does, for the
 * person who has no reason to know that the key they signed in with is also a social account.
 *
 * A card rather than a banner, and it never claims a Loopky deck appears there — it does not. What
 * travels is the profile and, when they choose to announce one, the post.
 */
@Composable
fun PubkyAppProfileCta(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary)
            .clickable(onClick = onClick)
            .testTag("profile_pubky_app_cta")
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same mark the button wears, on the card's own surface.
        PubkyMark(size = 26.dp)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.pubky_app_cta_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.W700,
                color = colors.foregroundPrimary,
            )
            Text(
                text = stringResource(R.string.pubky_app_cta_body),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.foregroundMuted,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = colors.foregroundMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * pubky.app's lime, `#C8FF00` — the `--brand` of pubky-app's own stylesheet.
 *
 * Deliberately *not* a `LoopkyColors` token: it is another product's identity, and the palette is
 * Loopky's. It lives beside the mark it belongs to, and `PubkyAppLink.swift` holds the same value
 * for the same reason.
 */
private val PubkyLime = Color(0xFFC8FF00)

/**
 * The halo under the button: one lime for both palettes, no offset — nothing here is lit from
 * above — and two alphas.
 *
 * The alpha has to move because a shadow *composites* rather than adds: `#C8FF00` at 0.6 over the
 * dark ground lands on `#7D9D07`, an olive that is no longer the brand colour, while the same 0.6
 * on cream is already a strong lime. The ground is read off the palette rather than from
 * `isSystemInDarkTheme()`, which answers about the device and so is wrong for anyone who has
 * chosen a theme in Settings.
 */
@Composable
private fun pubkyGlow(): Shadow {
    val onDark = LoopkyTheme.colors.surfacePrimary.luminance() < 0.5f
    return Shadow(
        radius = 12.dp,
        color = PubkyLime,
        spread = 1.dp,
        alpha = if (onDark) 0.95f else 0.6f,
    )
}

/**
 * pubky.app's mark, at [size], tinted like every other icon on the screen.
 */
@Composable
private fun PubkyMark(size: Dp) {
    Icon(
        painter = painterResource(R.drawable.ic_pubky),
        contentDescription = null,
        tint = LoopkyTheme.colors.foregroundSecondary,
        modifier = Modifier.size(size),
    )
}

@Preview
@Composable
private fun PubkyAppLinkPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PubkyAppIconButton(onClick = {})
            PubkyAppProfileCta(onClick = {})
        }
    }
}
