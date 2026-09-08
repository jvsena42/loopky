package com.github.jvsena42.loopky.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingEffect
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingUiState
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.loopky.presentation.onboarding.RingHandoff
import com.github.jvsena42.loopky.ui.components.FoxPlate
import com.github.jvsena42.loopky.ui.components.SignInProviderButton
import com.github.jvsena42.loopky.ui.components.SignInProviderVariant
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.LICENSE_URL
import com.github.jvsena42.loopky.ui.util.PRIVACY_POLICY_URL
import com.github.jvsena42.loopky.ui.util.openUrl
import com.github.jvsena42.loopky.ui.util.rememberAppVersion
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnboardingRoute(
    onNavigateHome: () -> Unit,
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
    onUnregistered: (String) -> Unit,
    onExplore: () -> Unit = {},
    autoExplore: Boolean = false,
) {
    val viewModel = koinViewModel<OnboardingViewModel>()
    OnboardingScreen(
        viewModel = viewModel,
        onNavigateHome = onNavigateHome,
        onCreatePubky = onCreatePubky,
        onRestore = onRestore,
        onUnregistered = onUnregistered,
        onExplore = onExplore,
        autoExplore = autoExplore,
    )
}

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onNavigateHome: () -> Unit,
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
    onUnregistered: (String) -> Unit,
    onExplore: () -> Unit = {},
    /**
     * Hand a signed-out visitor straight to the browsing shell instead of drawing this screen.
     *
     * Set only for the cold start that lands here as the app's first destination: without an
     * account there is nothing to restore, and the public half of Loopky is a better first
     * impression than a wall of three unfamiliar words. Every *deliberate* arrival — the guest
     * shell's "Get started", a sign-in prompt raised by a write, signing out — passes false, or
     * the screen would bounce back to browsing the instant it was asked for.
     */
    autoExplore: Boolean = false,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnNavigateHome by rememberUpdatedState(onNavigateHome)
    val currentOnUnregistered by rememberUpdatedState(onUnregistered)
    val currentOnExplore by rememberUpdatedState(onExplore)

    // Driven off the state rather than an effect on purpose. `effects` is a zero-replay SharedFlow,
    // so anything emitted from the ViewModel's init can be dropped if this collector has not
    // attached yet — and "no persisted session" is decided in exactly that window. Idle is the
    // ViewModel's word for it, and a StateFlow cannot lose it.
    val noSession = state is OnboardingUiState.Idle
    LaunchedEffect(autoExplore, noSession) {
        if (autoExplore && noSession) currentOnExplore()
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is OnboardingEffect.OpenDeeplink -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val canResolve = intent.resolveActivity(context.packageManager) != null
                    if (!canResolve) {
                        Log.w("Loopky/OnboardingScreen", "No handler for ${effect.url} — Pubky Ring not installed")
                        viewModel.onDeeplinkUnavailable()
                    } else {
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Log.w("Loopky/OnboardingScreen", "startActivity ActivityNotFoundException", e)
                            viewModel.onDeeplinkUnavailable()
                        }
                    }
                }
                is OnboardingEffect.OpenInstallPage -> {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
                OnboardingEffect.NavigateHome -> currentOnNavigateHome()
                is OnboardingEffect.NavigateUnregistered -> currentOnUnregistered(effect.pubky)
            }
        }
    }

    OnboardingContent(
        state = state,
        onSignInClick = viewModel::onSignInClick,
        onCreatePubky = onCreatePubky,
        onRestore = onRestore,
        onOpenRingHere = viewModel::onOpenRingOnThisDevice,
        onGetRing = viewModel::onGetRingClick,
        onCancelSignIn = viewModel::onCancelSignIn,
        leaving = autoExplore && noSession,
    )
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onSignInClick: (RingHandoff) -> Unit,
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
    onOpenRingHere: () -> Unit,
    onGetRing: () -> Unit,
    onCancelSignIn: () -> Unit,
    /** This screen is about to hand off to the browsing shell — hold the splash rather than
     * flashing a call to action nobody is going to be given the chance to tap. */
    leaving: Boolean = false,
) {
    if (leaving || state is OnboardingUiState.Restoring) {
        SplashContent()
        return
    }

    val colors = LoopkyTheme.colors
    val isWorking = state is OnboardingUiState.Starting ||
        state is OnboardingUiState.AwaitingApproval ||
        state is OnboardingUiState.Verifying

    // Local rather than in the ViewModel on purpose. OnboardingUiState is a sealed interface over
    // modes (Restoring/Starting/AwaitingApproval/…), so a cross-cutting flag would have to be
    // carried on every one of them to say something none of them is about. Nothing is persisted
    // across launches either: this screen is only reachable while signed out, so the question is
    // asked once per account, which is when consent is actually meant to be given. Starts ticked —
    // the policy is stated in the label above it, and un-ticking is the deliberate act.
    var policyAccepted by rememberSaveable { mutableStateOf(true) }

    val widthClass = windowWidthClass()
    // The whole reason this screen knows about window size. A phone's key is in Ring on that same
    // phone, so the deeplink is the shortest path; a tablet's owner keeps their key on their phone,
    // where the deeplink cannot reach, so the way in is a code that phone can scan. Ring being
    // installed *here* doesn't change it — the panel offers that as a second option rather than
    // guessing, because a tablet that happens to have Ring may still not have this user's key.
    val handoff = if (widthClass.isAtLeastMedium) RingHandoff.AnotherDevice else RingHandoff.ThisDevice
    val awaitingScan = (state as? OnboardingUiState.AwaitingApproval)
        ?.takeIf { it.handoff == RingHandoff.AnotherDevice }
    // The phone's half of the same wait, and only for the phone that cannot take the deeplink.
    // With Ring installed the handoff is a tap and Ring is already in the foreground — a sheet
    // behind it would be something to dismiss on the way back. Without it, the authorisation is
    // still live and the key is presumably in Ring on another phone, so the code it can scan is
    // the way in rather than the "Pubky Ring isn't installed" dead end this used to be.
    val awaitingHere = (state as? OnboardingUiState.AwaitingApproval)
        ?.takeIf { it.handoff == RingHandoff.ThisDevice && !it.ringInstalledHere }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BrandRow()

        // Landscape tablets get the hero and the sign-in side by side. Stacked, the same content
        // on a 1280x800 window puts the fox against the ceiling and the button against the floor
        // with a screen's worth of cream between them; side by side each half is a normal size.
        if (widthClass.isExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hero(modifier = Modifier.widthIn(max = HERO_MAX_WIDTH))
                SignInPanel(
                    state = state,
                    awaitingScan = awaitingScan,
                    isWorking = isWorking,
                    policyAccepted = policyAccepted,
                    onPolicyAcceptedChange = { policyAccepted = it },
                    onSignInClick = { onSignInClick(handoff) },
                    onCreatePubky = onCreatePubky,
                    onRestore = onRestore,
                    onOpenRingHere = onOpenRingHere,
                    onCancelSignIn = onCancelSignIn,
                    // Scrollable, because this Row bounds the panel to the window height and a
                    // Column that overflows a bounded parent clips in silence — no error, no
                    // ellipsis, just a button sliced in half. A landscape phone is the tightest
                    // case, and the panel grew a third door (#147).
                    modifier = Modifier
                        .widthIn(max = PaneWidth.Focused)
                        .verticalScroll(rememberScrollState()),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Hero()
            }
            SignInPanel(
                state = state,
                awaitingScan = awaitingScan,
                isWorking = isWorking,
                policyAccepted = policyAccepted,
                onPolicyAcceptedChange = { policyAccepted = it },
                onSignInClick = { onSignInClick(handoff) },
                onCreatePubky = onCreatePubky,
                onRestore = onRestore,
                onOpenRingHere = onOpenRingHere,
                onCancelSignIn = onCancelSignIn,
                modifier = Modifier.contentPane(PaneWidth.Focused),
            )
        }

        // Last, because a bug report from someone who cannot get past this screen is exactly the
        // one where knowing the build matters.
        Text(
            text = stringResource(R.string.onboarding_app_version, rememberAppVersion()),
            modifier = Modifier.testTag("onboarding_app_version"),
            color = colors.foregroundMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }

    // Outside the Column on purpose: a ModalBottomSheet lives in its own window and contributes no
    // layout here, so nesting it in a `spacedBy` Column would add a phantom gap to the screen
    // underneath it.
    if (awaitingHere != null) {
        RingScanSheet(
            authUrl = awaitingHere.authUrl,
            onGetRing = onGetRing,
            onDismiss = onCancelSignIn,
        )
    }
}

/** Fox, tagline, subtitle — the half of the screen that is pure brand. */
@Composable
private fun Hero(modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FoxPlate(
            size = 160.dp,
            shape = CircleShape,
            glyphSize = 96.sp,
            containerColor = colors.accentPrimarySoft,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.brand_tagline),
            color = colors.foregroundPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            lineHeight = 34.sp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.onboarding_hero_subtitle),
            color = colors.foregroundSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}

/**
 * Everything that acts: the call to action (or the QR handoff that replaces it) plus the consent
 * gate that governs it.
 *
 * One composable rather than two so the two layouts above cannot drift — the consent tick and the
 * button it enables have to stay together, and on the wide layout they belong in the same column
 * rather than one of them stranded under the hero.
 */
@Composable
private fun SignInPanel(
    state: OnboardingUiState,
    awaitingScan: OnboardingUiState.AwaitingApproval?,
    isWorking: Boolean,
    policyAccepted: Boolean,
    onPolicyAcceptedChange: (Boolean) -> Unit,
    onSignInClick: () -> Unit,
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
    onOpenRingHere: () -> Unit,
    onCancelSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (awaitingScan != null) {
            RingScanPanel(
                authUrl = awaitingScan.authUrl,
                ringInstalledHere = awaitingScan.ringInstalledHere,
                onOpenRingHere = onOpenRingHere,
                onCancel = onCancelSignIn,
            )
        } else {
            CtaBlock(
                state = state,
                isWorking = isWorking,
                policyAccepted = policyAccepted,
                onSignInClick = onSignInClick,
                onCreatePubky = onCreatePubky,
                onRestore = onRestore,
            )
            // Under the calls to action: the gate has to be visible before the buttons are usable,
            // but it is fine print rather than a step, and putting it between the hero and the
            // primary button pushed the thing people came here to tap down the page.
            PolicyConsentRow(
                accepted = policyAccepted,
                enabled = !isWorking,
                onAcceptedChange = onPolicyAcceptedChange,
            )
        }
    }
}

@Composable
private fun BrandRow() {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.onboarding_brand_name),
            color = colors.foregroundPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun CtaBlock(
    state: OnboardingUiState,
    isWorking: Boolean,
    policyAccepted: Boolean,
    onSignInClick: () -> Unit,
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Two ways *in*, presented as a set the way a social sign-in screen presents its
        // providers: same silhouette, marks on the same line, and only one of them filled. Ring is
        // the filled one because it keeps the key in a separate app, which is the arrangement
        // Loopky recommends — the ranking is the recommendation, so it has to be legible at a
        // glance rather than explained.
        val ringButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            SignInProviderButton(
                label = when (state) {
                    OnboardingUiState.Starting,
                    is OnboardingUiState.AwaitingApproval,
                    -> stringResource(R.string.onboarding_signin_waiting)
                    OnboardingUiState.Verifying -> stringResource(R.string.onboarding_signin_verifying)
                    else -> stringResource(R.string.onboarding_signin_default)
                },
                // The crowned keyhole is the Pubky mark, shared by Pubky Ring and pubky.app — the
                // thing a user recognises from the app they are being sent to.
                icon = painterResource(R.drawable.ic_pubky),
                // Also the recovery path: a failed approval consumes the FFI's auth flow, so
                // retrying means a whole new one. Clearing the error first would cost a tap (#59).
                onClick = onSignInClick,
                variant = SignInProviderVariant.Primary,
                loading = isWorking,
                enabled = !isWorking && policyAccepted,
                contentDescription = stringResource(R.string.onboarding_ring_icon_description),
                modifier = buttonModifier.testTag("onboarding_signin"),
            )
        }
        // The other way in, for someone who has a pubky but no working Ring — a dead phone, a
        // reinstall. A button rather than the text link it used to be: for the person who needs
        // it, it is the only control on this screen that does anything, and a text link at the
        // bottom is where an option goes to be missed.
        val restoreButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            SignInProviderButton(
                label = stringResource(R.string.onboarding_restore),
                icon = painterResource(R.drawable.ic_recovery_key),
                onClick = onRestore,
                variant = SignInProviderVariant.Secondary,
                // Gated on consent like the button above it. Restoring signs you in, so letting it
                // through while the policy is declined meant the tick governed one of three ways
                // into the same account.
                enabled = !isWorking && policyAccepted,
                contentDescription = stringResource(R.string.onboarding_recovery_icon_description),
                modifier = buttonModifier.testTag("onboarding_restore"),
            )
        }

        // Stacked, always. Side by side was tried and abandoned: "Continue with Pubky Ring" and
        // "Use a recovery phrase or file" do not fit in half a panel at any width this layout can
        // spare from the hero, and they truncated to "Continue with Pubky" / "Use a recovery" —
        // which is worse than a scroll, because a clipped label reads as the whole label.
        ringButton(Modifier)
        restoreButton(Modifier)
        Text(
            text = stringResource(R.string.onboarding_no_email_notice),
            color = colors.foregroundMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        if (state is OnboardingUiState.Error) {
            Text(
                text = errorMessage(state.reason),
                color = colors.danger,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        // Signing up, not signing in — a different intent, so it keeps the text-link treatment
        // that sign-in screens conventionally give it rather than becoming a third button in the
        // set above.
        //
        // Gated like the two buttons above it. This used to be deliberately always-live, on the
        // reasoning that a dead end here leaves a new user nowhere to go — but every one of these
        // three ends in an account, and a consent that governs one of three doors is not a consent.
        // The way out of the dead end is the tick itself, which starts ticked and sits directly
        // below.
        TextButton(
            onClick = onCreatePubky,
            enabled = policyAccepted,
            modifier = Modifier.testTag("onboarding_create_pubky"),
            // Purple rather than the brand orange: the primary button above is orange, and two
            // orange calls to action read as one control with a stray second line.
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accentSecondary),
        ) {
            Text(
                text = stringResource(R.string.onboarding_create_pubky),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // There is deliberately no "Look around first" here any more. Browsing is not one option
        // among four on this screen — it is where a signed-out launch lands in the first place,
        // and this screen is what the visitor asks for once they want an account. Offering the
        // detour again on the way in would point back at the screen they came from.
    }
}

/**
 * The consent gate on sign-in.
 *
 * Google Play requires the privacy policy to be agreed to at the point an account is created, so it
 * is stated on this screen rather than behind a link somewhere in Settings.
 *
 * Un-ticking it blocks **all three** ways in — Pubky Ring, restore, and create-account — because
 * all three end in an account and a gate on one of three doors is decoration. It starts ticked, so
 * the state where nothing on the screen works is one the user chose and can undo in one tap,
 * directly beneath the controls it disabled.
 *
 * The two document names inside the sentence are real links. They are located by [indexOf] rather
 * than assembled from fragments so a translation can put them wherever its grammar wants; a name
 * that a translator rewords simply stops being a link, which is a missing underline rather than a
 * broken screen.
 */
@Composable
private fun PolicyConsentRow(
    accepted: Boolean,
    enabled: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
) {
    val colors = LoopkyTheme.colors
    val context = LocalContext.current
    val privacyLabel = stringResource(R.string.onboarding_policy_privacy)
    val licenseLabel = stringResource(R.string.onboarding_policy_license)
    val sentence = stringResource(R.string.onboarding_policy_consent, privacyLabel, licenseLabel)

    val label = remember(sentence, privacyLabel, licenseLabel, colors.accentPrimary) {
        val linkStyles = TextLinkStyles(
            style = SpanStyle(
                color = colors.accentPrimary,
                textDecoration = TextDecoration.Underline,
            ),
        )
        buildAnnotatedString {
            append(sentence)
            listOf(privacyLabel to PRIVACY_POLICY_URL, licenseLabel to LICENSE_URL).forEach { (name, url) ->
                val start = sentence.indexOf(name)
                if (start >= 0) {
                    addLink(
                        LinkAnnotation.Url(url, linkStyles) { context.openUrl(url) },
                        start,
                        start + name.length,
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_policy_consent")
            .toggleable(
                value = accepted,
                enabled = enabled,
                role = Role.Checkbox,
                // Off the Checkbox itself so the label is part of the target. Taps on the two
                // links are handled by the text, which sits below this in the hierarchy.
                onValueChange = onAcceptedChange,
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = accepted,
            // Null so the Row above owns the click; a Checkbox with its own handler would swallow
            // the tap and leave the label inert.
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accentPrimary,
                uncheckedColor = colors.foregroundMuted,
            ),
        )
        Text(
            text = label,
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

/**
 * The branded splash. Drawn while the persisted session is being read back, and deliberately a
 * continuation of the system splash window (same cream surface, same fox on the same accent
 * circle, same position) so the two read as one screen — this one just adds the words.
 */
@Composable
private fun SplashContent() {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .padding(horizontal = 32.dp)
            .testTag("splash"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FoxPlate(
            size = 160.dp,
            shape = CircleShape,
            glyphSize = 96.sp,
            containerColor = colors.accentPrimarySoft,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_brand_name),
            color = colors.foregroundPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.brand_tagline),
            color = colors.foregroundSecondary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}

@Preview
@Composable
private fun SplashContentPreview() {
    LoopkyTheme {
        SplashContent()
    }
}

@Preview
@Composable
private fun OnboardingContentPreview() {
    LoopkyTheme {
        OnboardingContent(
            state = OnboardingUiState.Idle,
            onSignInClick = {},
            onCreatePubky = {},
            onRestore = {},
            onOpenRingHere = {},
            onGetRing = {},
            onCancelSignIn = {},
        )
    }
}

private val HERO_MAX_WIDTH = 420.dp
