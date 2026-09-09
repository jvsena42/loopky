package com.github.jvsena42.loopky.ui.search

import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopSearchBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.presentation.discover.SearchEffect
import com.github.jvsena42.loopky.presentation.discover.SearchViewModel
import com.github.jvsena42.loopky.ui.components.SignInPromptDialog
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.discover.scanPubky
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Global search, as a bar on Discover rather than a screen of its own.
 *
 * Search used to be a route reached by a magnifier in the header, which meant leaving the thing you
 * were browsing to look for something in it. [TopSearchBar] collapsed into the header and
 * [ExpandedFullScreenSearchBar] over the results is the Material 3 pattern for that, and it is what
 * the box is: a way *into* Discover's own content, not a different place.
 * *
 * The query is a [TextFieldState] rather than a value read back from the ViewModel. Binding a field
 * to state that round-trips through a VM drops characters under a fast typist, and the state object
 * is what the search bar's own input field is built to take; the ViewModel is fed from a
 * `snapshotFlow` instead, which is also where its own debounce already lives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverSearchBar(
    onOpenProfile: (String) -> Unit,
    onOpenDeck: (deckId: String, author: String?) -> Unit,
    onSignIn: () -> Unit,
    state: SearchBarState,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<SearchViewModel>()
    val context = LocalContext.current
    val colors = LoopkyTheme.colors
    val currentOpenProfile by rememberUpdatedState(onOpenProfile)
    val currentOpenDeck by rememberUpdatedState(onOpenDeck)
    var followError by remember { mutableStateOf<ErrorReason?>(null) }
    var signInPrompt by remember { mutableStateOf<SignInReason?>(null) }
    val textFieldState = rememberTextFieldState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(textFieldState, viewModel) {
        snapshotFlow { textFieldState.text.toString() }
            .collect(viewModel::onQueryChange)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                // The results are a dialog over Discover, so it has to come down before the
                // destination underneath it can be seen at all.
                is SearchEffect.OpenProfile -> {
                    state.animateToCollapsed()
                    currentOpenProfile(effect.pubky)
                }
                is SearchEffect.OpenDeck -> {
                    state.animateToCollapsed()
                    currentOpenDeck(effect.deckId, effect.authorPubky)
                }
                is SearchEffect.ShowFollowError -> followError = effect.reason
                is SearchEffect.RequireSignIn -> signInPrompt = effect.reason
            }
        }
    }

    // Resolved here rather than in the effect collector: errorMessage is @Composable.
    followError?.let { reason ->
        val message = errorMessage(reason)
        LaunchedEffect(reason, message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            followError = null
        }
    }

    signInPrompt?.let { reason ->
        SignInPromptDialog(
            reason = reason,
            onSignIn = {
                signInPrompt = null
                scope.launch {
                    state.animateToCollapsed()
                    onSignIn()
                }
            },
            onDismiss = { signInPrompt = null },
        )
    }

    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = state,
            onSearch = { viewModel.onSubmit() },
            // The expanded bar is its own window, so the activity root's `testTagsAsResourceId`
            // does not reach it and every tag inside would be invisible to `android layout`.
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("search_input"),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = {
                // Expanded, the results are a full-screen dialog: without a back control the only
                // way out is the system gesture, which is not a control at all on a tablet.
                if (state.isExpanded) {
                    IconButton(
                        onClick = { scope.launch { state.animateToCollapsed() } },
                        modifier = Modifier.testTag("search_back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.search_back),
                            tint = colors.foregroundPrimary,
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = colors.foregroundMuted,
                    )
                }
            },
            trailingIcon = {
                if (textFieldState.text.isEmpty()) {
                    // A scan is a paste by another route: it lands in the box, where it reads as
                    // the address it is and can be corrected rather than acted on blindly.
                    IconButton(
                        onClick = { scanPubky(context) { textFieldState.setTextAndPlaceCursorAtEnd(it) } },
                        modifier = Modifier.testTag("search_scan"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.search_scan_qr),
                            tint = colors.foregroundSecondary,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            textFieldState.clearText()
                            viewModel.onClearQuery()
                        },
                        modifier = Modifier.testTag("search_clear"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.search_clear),
                            tint = colors.foregroundMuted,
                        )
                    }
                }
            },
        )
    }

    TopSearchBar(
        state = state,
        inputField = inputField,
        modifier = modifier.testTag("search_bar"),
        colors = SearchBarDefaults.colors(containerColor = colors.surfaceCard),
        // Discover already inset itself below the status bar; the bar's own default would add
        // that height a second time.
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
    ExpandedFullScreenSearchBar(
        state = state,
        inputField = inputField,
        colors = SearchBarDefaults.colors(containerColor = colors.surfacePrimary),
    ) {
        SearchResults(
            state = uiState,
            onOpenLink = viewModel::onOpenLink,
            onOpenProfile = viewModel::onOpenProfile,
            onOpenDeck = viewModel::onOpenDeck,
            onFollowToggle = viewModel::onFollowToggle,
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag("search_screen"),
        )
    }
}

/** True while the results are covering Discover. */
@OptIn(ExperimentalMaterial3Api::class)
val SearchBarState.isExpanded: Boolean get() = targetValue == SearchBarValue.Expanded
