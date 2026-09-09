package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * A masked passphrase field with a reveal toggle, shared by the two screens that have one: creating
 * a recovery file and opening one.
 *
 * **The toggle is not a convenience.** A passphrase typed blind into one screen has to be
 * reproduced exactly into another, days or devices later, and the only feedback a masked field
 * gives is a row of dots — so a stray character, an autocapitalised first letter or a keyboard in
 * the wrong layout all look identical to the right answer. On the create side that mistake is
 * silent and permanent: the file is written with a passphrase the user did not think they typed.
 * On the restore side it is indistinguishable from "wrong file". Letting someone look at what they
 * typed is what makes the difference checkable, and the exposure it trades against — a shoulder
 * over the phone, opt-in and momentary — is far smaller than being locked out of your own account.
 *
 * Defaults to hidden, and re-masks whenever the screen is rebuilt — a rotation, a return from the
 * background, a process restart. `remember` rather than `rememberSaveable` is what guarantees that:
 * saving the flag would restore a *visible* passphrase onto a screen the user is coming back to
 * rather than looking at, which is the one moment nobody chose to expose it. Revealing is a
 * deliberate act, so it should have to be repeated.
 *
 * [KeyboardType.Password] survives the toggle deliberately: it is what keeps the IME from learning
 * the passphrase into its shared dictionary, and that has nothing to do with whether the *user* can
 * see it. Swapping to [KeyboardType.Text] while revealed would quietly hand the passphrase to the
 * keyboard's autocorrect store.
 */
@Composable
fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    placeholder: String? = null,
    testTag: String? = null,
) {
    val colors = LoopkyTheme.colors
    var revealed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier),
        enabled = enabled,
        singleLine = true,
        placeholder = placeholder?.let { { Text(text = it, color = colors.foregroundMuted) } },
        visualTransformation = if (revealed) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(
                onClick = { revealed = !revealed },
                // Disabled alongside the field: a reveal button that still works while the field is
                // locked mid-check is a control acting on something the user cannot edit.
                enabled = enabled,
                modifier = Modifier.testTag(
                    testTag?.let { "${it}_reveal" } ?: "passphrase_reveal",
                ),
            ) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (revealed) R.string.passphrase_hide else R.string.passphrase_reveal,
                    ),
                    tint = colors.foregroundMuted,
                )
            }
        },
        isError = isError,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accentPrimary,
            unfocusedBorderColor = colors.borderSubtle,
            cursorColor = colors.accentPrimary,
            errorBorderColor = colors.danger,
        ),
    )
}

@Preview
@Composable
private fun PassphraseFieldPreview() {
    LoopkyTheme {
        PassphraseField(value = "correct horse battery", onValueChange = {})
    }
}
