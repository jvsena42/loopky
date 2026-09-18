package com.github.jvsena42.loopky.presentation.signup

/**
 * A region and the country calling code its numbers are dialled with.
 *
 * Names are deliberately absent: they come from the platform's own locale data at render time
 * (`Locale.getDisplayCountry`, `Locale.localizedString(forRegionCode:)`), which already speaks
 * all eleven of the app's languages.
 */
data class DialingCountry(val regionCode: String, val dialCode: String) {

    /** `+55`, as shown on the picker and in front of the number. */
    val prefix: String get() = "+$dialCode"

    /** The flag emoji, built from the two regional-indicator symbols of [regionCode]. */
    val flag: String
        get() = buildString {
            regionCode.forEach { letter ->
                append(REGIONAL_INDICATOR_HIGH)
                append(REGIONAL_INDICATOR_LOW_A + (letter - 'A'))
            }
        }

    private companion object {
        // U+1F1E6 (REGIONAL INDICATOR SYMBOL LETTER A) as a UTF-16 surrogate pair; the letters
        // B–Z follow it contiguously in the low surrogate.
        const val REGIONAL_INDICATOR_HIGH = '\uD83C'
        const val REGIONAL_INDICATOR_LOW_A = '\uDDE6'
    }
}

object DialingCountries {

    /**
     * The fallback when the device reports no region, or one with no calling code of its own.
     * Visible on the field, so it is a starting point the user corrects, never a silent guess.
     */
    val fallback: DialingCountry = DialingCountry("US", "1")

    /**
     * Regions where the leading `0` is part of the international number rather than a trunk
     * prefix to drop — Italy's landlines and the ranges that share its plan, and Côte d'Ivoire
     * since its 2021 move to ten digits.
     */
    private val KEEPS_LEADING_ZERO = setOf("IT", "VA", "SM", "CI")

    /**
     * The first region listed for a shared calling code (`+1`, `+7`, `+44` …) is the one a pasted
     * number resolves to when the current selection does not already use that code. Which region
     * is picked cannot change the number sent — the digits are the same.
     */
    val all: List<DialingCountry> = listOf(
        "US" to "1", "CA" to "1", "AG" to "1", "AI" to "1", "AS" to "1", "BB" to "1", "BM" to "1",
        "BS" to "1", "DM" to "1", "DO" to "1", "GD" to "1", "GU" to "1", "JM" to "1", "KN" to "1",
        "KY" to "1", "LC" to "1", "MP" to "1", "MS" to "1", "PR" to "1", "SX" to "1", "TC" to "1",
        "TT" to "1", "VC" to "1", "VG" to "1", "VI" to "1",
        "RU" to "7", "KZ" to "7",
        "EG" to "20", "ZA" to "27", "GR" to "30", "NL" to "31", "BE" to "32", "FR" to "33",
        "ES" to "34", "HU" to "36", "IT" to "39", "VA" to "39", "RO" to "40", "CH" to "41",
        "AT" to "43", "GB" to "44", "GG" to "44", "IM" to "44", "JE" to "44", "DK" to "45",
        "SE" to "46", "NO" to "47", "SJ" to "47", "PL" to "48", "DE" to "49", "PE" to "51",
        "MX" to "52", "CU" to "53", "AR" to "54", "BR" to "55", "CL" to "56", "CO" to "57",
        "VE" to "58", "MY" to "60", "AU" to "61", "CC" to "61", "CX" to "61", "ID" to "62",
        "PH" to "63", "NZ" to "64", "SG" to "65", "TH" to "66", "JP" to "81", "KR" to "82",
        "VN" to "84", "CN" to "86", "TR" to "90", "IN" to "91", "PK" to "92", "AF" to "93",
        "LK" to "94", "MM" to "95", "IR" to "98",
        "SS" to "211", "MA" to "212", "EH" to "212", "DZ" to "213", "TN" to "216", "LY" to "218",
        "GM" to "220", "SN" to "221", "MR" to "222", "ML" to "223", "GN" to "224", "CI" to "225",
        "BF" to "226", "NE" to "227", "TG" to "228", "BJ" to "229", "MU" to "230", "LR" to "231",
        "SL" to "232", "GH" to "233", "NG" to "234", "TD" to "235", "CF" to "236", "CM" to "237",
        "CV" to "238", "ST" to "239", "GQ" to "240", "GA" to "241", "CG" to "242", "CD" to "243",
        "AO" to "244", "GW" to "245", "IO" to "246", "AC" to "247", "SC" to "248", "SD" to "249",
        "RW" to "250", "ET" to "251", "SO" to "252", "DJ" to "253", "KE" to "254", "TZ" to "255",
        "UG" to "256", "BI" to "257", "MZ" to "258", "ZM" to "260", "MG" to "261", "RE" to "262",
        "YT" to "262", "ZW" to "263", "NA" to "264", "MW" to "265", "LS" to "266", "BW" to "267",
        "SZ" to "268", "KM" to "269", "SH" to "290", "TA" to "290", "ER" to "291", "AW" to "297",
        "FO" to "298", "GL" to "299",
        "GI" to "350", "PT" to "351", "LU" to "352", "IE" to "353", "IS" to "354", "AL" to "355",
        "MT" to "356", "CY" to "357", "FI" to "358", "AX" to "358", "BG" to "359", "LT" to "370",
        "LV" to "371", "EE" to "372", "MD" to "373", "AM" to "374", "BY" to "375", "AD" to "376",
        "MC" to "377", "SM" to "378", "UA" to "380", "RS" to "381", "ME" to "382", "XK" to "383",
        "HR" to "385", "SI" to "386", "BA" to "387", "MK" to "389", "CZ" to "420", "SK" to "421",
        "LI" to "423",
        "FK" to "500", "BZ" to "501", "GT" to "502", "SV" to "503", "HN" to "504", "NI" to "505",
        "CR" to "506", "PA" to "507", "PM" to "508", "HT" to "509", "GP" to "590", "BL" to "590",
        "MF" to "590", "BO" to "591", "GY" to "592", "EC" to "593", "GF" to "594", "PY" to "595",
        "MQ" to "596", "SR" to "597", "UY" to "598", "CW" to "599", "BQ" to "599",
        "TL" to "670", "NF" to "672", "BN" to "673", "NR" to "674", "PG" to "675", "TO" to "676",
        "SB" to "677", "VU" to "678", "FJ" to "679", "PW" to "680", "WF" to "681", "CK" to "682",
        "NU" to "683", "WS" to "685", "KI" to "686", "NC" to "687", "TV" to "688", "PF" to "689",
        "TK" to "690", "FM" to "691", "MH" to "692",
        "KP" to "850", "HK" to "852", "MO" to "853", "KH" to "855", "LA" to "856", "BD" to "880",
        "TW" to "886",
        "MV" to "960", "LB" to "961", "JO" to "962", "SY" to "963", "IQ" to "964", "KW" to "965",
        "SA" to "966", "YE" to "967", "OM" to "968", "PS" to "970", "AE" to "971", "IL" to "972",
        "BH" to "973", "QA" to "974", "BT" to "975", "MN" to "976", "NP" to "977", "TJ" to "992",
        "TM" to "993", "AZ" to "994", "GE" to "995", "KG" to "996", "UZ" to "998",
    ).map { (region, dial) -> DialingCountry(region, dial) }

    private val byRegion = all.associateBy { it.regionCode }

    fun forRegion(regionCode: String?): DialingCountry? = regionCode?.uppercase()?.let(byRegion::get)

    /** Calling codes are prefix-free, so at most one length can match. */
    private val dialCodeLengths = all.map { it.dialCode.length }.distinct()

    /**
     * Split an international number (`+44 7700 900123`) into its country and what follows the
     * calling code. Keeps [current] when it already uses the matched code, so pasting a `+1` number
     * with Canada selected does not flip the picker to the United States. `null` when the digits
     * after the `+` start with no known calling code.
     */
    fun splitInternational(number: String, current: DialingCountry?): Pair<DialingCountry, String>? {
        val digits = number.removePrefix("+").filter(Char::isDigit)
        val dialCode = dialCodeLengths
            .filter { it <= digits.length }
            .map { digits.take(it) }
            .firstOrNull { code -> all.any { it.dialCode == code } }
            ?: return null
        val country = current?.takeIf { it.dialCode == dialCode }
            ?: all.first { it.dialCode == dialCode }
        return country to digits.drop(dialCode.length)
    }

    /**
     * The national number as it goes after the calling code. A leading trunk `0` is dropped
     * (`07700 900123` in the UK is `+44 7700900123`) except where [KEEPS_LEADING_ZERO] says the
     * zero is part of the number — typing the national form is what people do, and sending the
     * zero would spend one of the two weekly SMS attempts on a number that does not exist.
     */
    fun nationalPart(country: DialingCountry, national: String): String {
        val normalized = PhoneNumberInput.normalize(national)
        return if (country.regionCode in KEEPS_LEADING_ZERO) normalized else normalized.trimStart('0')
    }
}
