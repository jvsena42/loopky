package com.github.jvsena42.loopky.presentation.signup

/**
 * What counts as a phone number worth spending an SMS on.
 *
 * The field used to gate "Send code" on `isNotBlank()` alone, and the raw string went from the
 * text field into the Homegate request body untouched. That matters more here than on an ordinary
 * form: SMS verifications are rate-limited **per number**, weekly and yearly, and both limits are
 * terminal — `PhoneVerificationUiState.isTerminal` withdraws the send button entirely. A number
 * the server rejects for its shape therefore burns one of the two attempts a user gets per week
 * and takes the button away with it. A client-side check costs nothing and protects something
 * scarce.
 *
 * Deliberately shape-only. This is not a libphonenumber substitute: it does not know which country
 * codes exist, how long a subscriber number is in each, or whether the line is reachable. Only the
 * server can answer those, and pretending otherwise here would reject valid numbers offline.
 */
object PhoneNumberInput {

    /**
     * Strip the separators people type and paste — spaces, dashes, dots, brackets.
     *
     * A paste from a contacts app arrives as "+55 (86) 99800-6407", which is the same number.
     * Rejecting it for its punctuation would be the app being pedantic about something it can fix.
     */
    fun normalize(raw: String): String = raw.filterNot { it in SEPARATORS }

    /**
     * E.164: a leading `+`, a non-zero country code, and 8–15 digits in total.
     *
     * The `+` is required rather than assumed. Prepending one to "5586998006407" would invent a
     * country code the user never typed — right only by luck, and wrong in a way that costs an
     * attempt.
     */
    fun isValid(raw: String): Boolean = E164.matches(normalize(raw))

    private val SEPARATORS = setOf(' ', '-', '.', '(', ')', ' ', '–', '—')
    private val E164 = Regex("""^\+[1-9]\d{7,14}$""")
}
