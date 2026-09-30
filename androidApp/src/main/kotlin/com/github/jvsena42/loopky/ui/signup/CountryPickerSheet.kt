package com.github.jvsena42.loopky.ui.signup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.DialingCountries
import com.github.jvsena42.loopky.presentation.signup.DialingCountry
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** The flag and calling code in front of the number; opens [CountryPickerSheet]. */
@Composable
fun CountryCodeButton(
    country: DialingCountry,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val description = stringResource(
        R.string.signup_phone_country_button,
        country.displayName(currentLocale()),
        country.prefix,
    )
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = country.flag, fontSize = 20.sp)
        Text(text = country.prefix, color = colors.foregroundPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.foregroundMuted)
    }
}

/**
 * A searchable list of every country, sorted by its name in the app's language.
 *
 * A sheet rather than a dropdown menu: two hundred-odd rows are only usable with a search field,
 * and a menu anchored to the field would sit under the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerSheet(
    selected: DialingCountry,
    onSelect: (DialingCountry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val locale = currentLocale()
    var query by rememberSaveable { mutableStateOf("") }
    val sorted = remember(locale) {
        val collator = Collator.getInstance(locale)
        DialingCountries.all
            .map { it to it.displayName(locale) }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
    }
    val visible = remember(sorted, query) { sorted.filter { (country, name) -> matches(query, country, name) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard,
    ) {
        // Full height whatever the filter leaves, so the search field does not ride up and down
        // under the finger as the list shrinks.
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.signup_phone_country),
                modifier = Modifier.padding(horizontal = 20.dp),
                color = colors.foregroundPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("signup_country_search"),
                placeholder = {
                    Text(text = stringResource(R.string.signup_phone_country_search), color = colors.foregroundMuted)
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.foregroundMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentPrimary,
                    unfocusedBorderColor = colors.borderSubtle,
                    cursorColor = colors.accentPrimary,
                ),
            )
            if (visible.isEmpty()) {
                Text(
                    text = stringResource(R.string.signup_phone_country_empty),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    color = colors.foregroundMuted,
                    fontSize = 14.sp,
                )
            }
            LazyColumn(modifier = Modifier.testTag("signup_country_list")) {
                items(visible, key = { (country, _) -> country.regionCode }) { (country, name) ->
                    val isSelected = country == selected
                    ListItem(
                        headlineContent = { Text(text = name, color = colors.foregroundPrimary) },
                        leadingContent = { Text(text = country.flag, fontSize = 22.sp) },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(text = country.prefix, color = colors.foregroundMuted, fontSize = 14.sp)
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = colors.accentPrimary)
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = colors.surfaceCard),
                        modifier = Modifier
                            .clickable { onSelect(country) }
                            .clearAndSetSemantics {
                                contentDescription = "$name, ${country.prefix}"
                                role = Role.Button
                                this.selected = isSelected
                            }
                            .testTag("signup_country_${country.regionCode}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

private fun DialingCountry.displayName(locale: Locale): String =
    Locale.Builder().setRegion(regionCode).build().getDisplayCountry(locale)

/** By name (ignoring case and accents), by ISO code, or by calling code with or without its `+`. */
private fun matches(query: String, country: DialingCountry, name: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    val digits = needle.removePrefix("+")
    if (digits.isNotEmpty() && digits.all(Char::isDigit)) return country.dialCode.startsWith(digits)
    return fold(name).contains(fold(needle)) || country.regionCode.equals(needle, ignoreCase = true)
}

private val COMBINING_MARKS = Regex("\\p{Mn}+")

private fun fold(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
