package com.github.jvsena42.loopky.domain.model

/**
 * Spelled-out numbers rewritten as digits, so `"ten"` and `"10"` are the same answer.
 *
 * A speech recognizer decides on its own whether an utterance comes back as `"10"` or `"ten"` —
 * the choice is the engine's, varies by engine, locale and even by surrounding words, and is
 * nothing the speaker said differently. That makes it the same class of difference as the accents
 * [AnswerStrictness.Lenient] folds away: not the reader's to get right, so not theirs to fail on.
 * Folding both sides to digits before comparing settles it in whichever direction the engine went.
 *
 * Three deliberate limits:
 *
 * - **Language-keyed, never guessed.** A table is chosen by the *declared* language of the side
 *   being graded; an undeclared or unsupported language folds nothing. Merging every language into
 *   one table would make English `"once"` and `"elf"` match `"11"`, since those are Spanish and
 *   German number words — a wrong answer marked right, which is worse than the miss it fixes.
 * - **Words become digits, never the reverse.** Digits already agree with themselves, and
 *   spelling `1000` out would need the full grammar of each language rather than a word list.
 * - **Only speech folds.** [SpeakMatcher] asks for it; typing does not, because a deck teaching
 *   `"10" → "ten"` would accept `"10"` back and quietly stop testing anything.
 *
 * Coverage is a word list per language, not a grammar, so a form the list does not carry (Italian
 * `"ventotto"`, German `"einundzwanzig"`) is simply left as written — a miss, never a wrong fold.
 * Japanese and Chinese are the exception: their numerals are characters inside a word rather than
 * words, so they fold by run of characters (`三時` → `3時`, `二十五` → `25`) — see [foldHan].
 */
object NumberWords {

    /**
     * [text] with each run of spelled-out numbers replaced by its value in digits, or [text]
     * unchanged when [languageTag]'s language has no table.
     *
     * Everything outside those runs — spacing, punctuation, case — is preserved byte for byte, so
     * this composes in front of [AnswerMatcher]'s own normalization rather than replacing any of
     * it. A run may be joined by spaces or hyphens and may contain the language's number filler
     * (`"one hundred **and** five"`), which is why `"twenty-one"` folds even though a hyphen is
     * not a word boundary once punctuation is stripped.
     */
    fun fold(text: String, languageTag: String?): String {
        if (baseOf(languageTag) in HAN_LANGUAGES) return foldHan(text)
        val numerals = tableFor(languageTag) ?: return text
        val tokens = tokenize(text)
        val out = StringBuilder()
        var cursor = 0
        var i = 0
        while (i < tokens.size) {
            if (!numerals.isNumber(tokens[i].key)) {
                i++
                continue
            }
            val end = runEnd(text, tokens, i, numerals)
            val words = (i..end).map { tokens[it].key }.filter(numerals::isNumber)
            out.append(text, cursor, tokens[i].start).append(valueOf(words, numerals))
            cursor = tokens[end].end
            i = end + 1
        }
        return out.append(text, cursor, text.length).toString()
    }

    /** Index of the last number token in the run starting at [start]. */
    private fun runEnd(text: String, tokens: List<Token>, start: Int, numerals: Numerals): Int {
        var end = start
        var i = start + 1
        while (i < tokens.size && joinable(text, tokens[i - 1].end, tokens[i].start)) {
            val key = tokens[i].key
            when {
                numerals.isNumber(key) -> end = i
                // A filler only stays in the run if a number follows it: the "and" in "bread and
                // butter" is not part of anything.
                key !in numerals.fillers -> return end
            }
            i++
        }
        return end
    }

    /** Whether two tokens are close enough to be one number: only spaces and hyphens between. */
    private fun joinable(text: String, from: Int, to: Int): Boolean =
        (from until to).all { text[it].isWhitespace() || text[it] in HYPHENS }

    /**
     * Fold a run of number words into one value.
     *
     * The usual two-accumulator reading: a scale word multiplies what is pending before it
     * (`"two hundred"`), a thousand-or-larger scale banks it (`"two thousand five hundred"`), and
     * anything else adds (`"twenty one"`). French counts in twenties on top of that, so a unit
     * standing in front of `"vingt"` multiplies it — `"quatre-vingt-dix"` is 4×20+10, not 4+20+10.
     */
    private fun valueOf(words: List<String>, numerals: Numerals): Long {
        var total = 0L
        var current = 0L
        for (word in words) {
            val scale = numerals.scales[word]
            val unit = numerals.units[word]
            when {
                scale != null && scale >= THOUSAND -> {
                    total += maxOf(current, 1L) * scale
                    current = 0L
                }
                scale != null -> current = maxOf(current, 1L) * scale
                unit == SCORE && numerals.vigesimal && current in 2L..9L -> current *= SCORE
                unit != null -> current += unit
                else -> current += numerals.compound(word) ?: 0L
            }
        }
        return total + current
    }

    /** Maximal runs of letters, keyed by the same folding [AnswerMatcher] compares with. */
    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var start = -1
        for (i in text.indices) {
            val isLetter = text[i].isLetter()
            if (isLetter && start < 0) start = i
            if (!isLetter && start >= 0) {
                tokens += token(text, start, i)
                start = -1
            }
        }
        if (start >= 0) tokens += token(text, start, text.length)
        return tokens
    }

    private fun token(text: String, start: Int, end: Int) = Token(
        start = start,
        end = end,
        key = AnswerMatcher.normalize(text.substring(start, end), AnswerStrictness.Lenient),
    )

    private class Token(val start: Int, val end: Int, val key: String)

    /**
     * One language's number words.
     *
     * @property units values that add up (`"twenty"`, `"seven"`), including the glued teens and
     *  twenties of languages that write them as one word (`"veintidos"`).
     * @property scales values that multiply what came before them (`"hundred"`, `"mil"`).
     * @property fillers words that may sit inside a number without ending it (`"and"`, `"y"`).
     * @property vigesimal whether the language counts in twenties above 60, as French does.
     * @property compound the value of a word the tables cannot list, glued from smaller numbers
     *  (Korean `삼십`, `열하나`), or null.
     */
    private class Numerals(
        val units: Map<String, Long>,
        val scales: Map<String, Long>,
        val fillers: Set<String>,
        val vigesimal: Boolean = false,
        val compound: (String) -> Long? = { null },
    ) {
        fun isNumber(key: String): Boolean = key in units || key in scales || compound(key) != null
    }

    private const val THOUSAND = 1_000L
    private const val SCORE = 20L
    private val HYPHENS = charArrayOf('-', '‐', '‑', '–')

    private fun baseOf(languageTag: String?): String? = languageTag?.substringBefore('-')?.trim()?.lowercase()

    private fun tableFor(languageTag: String?): Numerals? = TABLES[baseOf(languageTag) ?: return null]

    private val HAN_LANGUAGES = setOf("ja", "zh")

    /**
     * Each maximal run of Han numerals in [text] rewritten as digits; everything else untouched.
     *
     * Folds inside words too (`一緒` → `1緒`), which is harmless because both sides of a comparison
     * fold the same way: it only changes the outcome where one side wrote the number in digits.
     */
    private fun foldHan(text: String): String {
        if (text.none { it in HAN_DIGITS || it in HAN_UNITS }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] !in HAN_DIGITS && text[i] !in HAN_UNITS) {
                out.append(text[i++])
                continue
            }
            val start = i
            while (i < text.length && (text[i] in HAN_DIGITS || text[i] in HAN_UNITS)) i++
            out.append(hanValue(text.substring(start, i)))
        }
        return out.toString()
    }

    /**
     * A run with no unit character is read digit by digit (`二〇二六` → `2026`); otherwise
     * positionally, a digit before `十`/`百`/`千` multiplying it (default one) and `万`/`億`
     * banking the section before them (`三万五千` → `35000`). `零` is a placeholder (`一百零五`).
     */
    private fun hanValue(run: String): String {
        if (run.none { it in HAN_UNITS }) return run.map { HAN_DIGITS.getValue(it) }.joinToString("")
        var total = 0L
        var section = 0L
        var digit = -1L
        for (ch in run) {
            val unit = HAN_UNITS[ch]
            when {
                unit == null -> digit = HAN_DIGITS.getValue(ch).takeIf { it != 0L } ?: -1L
                unit >= HAN_MYRIAD -> {
                    section += maxOf(digit, 0L)
                    total += maxOf(section, 1L) * unit
                    section = 0L
                    digit = -1L
                }
                else -> {
                    section += (if (digit < 0) 1L else digit) * unit
                    digit = -1L
                }
            }
        }
        return (total + section + maxOf(digit, 0L)).toString()
    }

    private const val HAN_MYRIAD = 10_000L

    private val HAN_DIGITS: Map<Char, Long> = mapOf(
        '〇' to 0L, '零' to 0L, '一' to 1L, '二' to 2L, '两' to 2L, '兩' to 2L, '三' to 3L,
        '四' to 4L, '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L,
    )

    private val HAN_UNITS: Map<Char, Long> = mapOf(
        '十' to 10L, '百' to 100L, '千' to 1_000L, '万' to 10_000L, '萬' to 10_000L,
        '億' to 100_000_000L, '亿' to 100_000_000L,
    )

    /** Keys are diacritic-folded and lowercase, because [token] looks them up that way. */
    private val ENGLISH = Numerals(
        units = mapOf(
            "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L,
            "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L,
            "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L,
            "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L,
            "nineteen" to 19L, "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
            "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
        ),
        scales = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L),
        fillers = setOf("and"),
    )

    private val SPANISH = Numerals(
        units = mapOf(
            "cero" to 0L, "un" to 1L, "uno" to 1L, "una" to 1L, "dos" to 2L, "tres" to 3L,
            "cuatro" to 4L, "cinco" to 5L, "seis" to 6L, "siete" to 7L, "ocho" to 8L,
            "nueve" to 9L, "diez" to 10L, "once" to 11L, "doce" to 12L, "trece" to 13L,
            "catorce" to 14L, "quince" to 15L, "dieciseis" to 16L, "diecisiete" to 17L,
            "dieciocho" to 18L, "diecinueve" to 19L, "veinte" to 20L, "veintiun" to 21L,
            "veintiuno" to 21L, "veintidos" to 22L, "veintitres" to 23L, "veinticuatro" to 24L,
            "veinticinco" to 25L, "veintiseis" to 26L, "veintisiete" to 27L, "veintiocho" to 28L,
            "veintinueve" to 29L, "treinta" to 30L, "cuarenta" to 40L, "cincuenta" to 50L,
            "sesenta" to 60L, "setenta" to 70L, "ochenta" to 80L, "noventa" to 90L,
            "doscientos" to 200L, "trescientos" to 300L, "cuatrocientos" to 400L,
            "quinientos" to 500L, "seiscientos" to 600L, "setecientos" to 700L,
            "ochocientos" to 800L, "novecientos" to 900L,
        ),
        scales = mapOf(
            "cien" to 100L, "ciento" to 100L, "mil" to 1_000L,
            "millon" to 1_000_000L, "millones" to 1_000_000L,
        ),
        fillers = setOf("y"),
    )

    private val PORTUGUESE = Numerals(
        units = mapOf(
            "zero" to 0L, "um" to 1L, "uma" to 1L, "dois" to 2L, "duas" to 2L, "tres" to 3L,
            "quatro" to 4L, "cinco" to 5L, "seis" to 6L, "sete" to 7L, "oito" to 8L,
            "nove" to 9L, "dez" to 10L, "onze" to 11L, "doze" to 12L, "treze" to 13L,
            "catorze" to 14L, "quatorze" to 14L, "quinze" to 15L, "dezesseis" to 16L,
            "dezasseis" to 16L, "dezessete" to 17L, "dezassete" to 17L, "dezoito" to 18L,
            "dezenove" to 19L, "dezanove" to 19L, "vinte" to 20L, "trinta" to 30L,
            "quarenta" to 40L, "cinquenta" to 50L, "sessenta" to 60L, "setenta" to 70L,
            "oitenta" to 80L, "noventa" to 90L, "duzentos" to 200L, "trezentos" to 300L,
            "quatrocentos" to 400L, "quinhentos" to 500L, "seiscentos" to 600L,
            "setecentos" to 700L, "oitocentos" to 800L, "novecentos" to 900L,
        ),
        scales = mapOf(
            "cem" to 100L, "cento" to 100L, "mil" to 1_000L,
            "milhao" to 1_000_000L, "milhoes" to 1_000_000L,
        ),
        fillers = setOf("e"),
    )

    private val FRENCH = Numerals(
        units = mapOf(
            "zero" to 0L, "un" to 1L, "une" to 1L, "deux" to 2L, "trois" to 3L, "quatre" to 4L,
            "cinq" to 5L, "six" to 6L, "sept" to 7L, "huit" to 8L, "neuf" to 9L, "dix" to 10L,
            "onze" to 11L, "douze" to 12L, "treize" to 13L, "quatorze" to 14L, "quinze" to 15L,
            "seize" to 16L, "vingt" to 20L, "vingts" to 20L, "trente" to 30L, "quarante" to 40L,
            "cinquante" to 50L, "soixante" to 60L, "septante" to 70L, "huitante" to 80L,
            "octante" to 80L, "nonante" to 90L,
        ),
        scales = mapOf(
            "cent" to 100L, "cents" to 100L, "mille" to 1_000L,
            "million" to 1_000_000L, "millions" to 1_000_000L,
        ),
        fillers = setOf("et"),
        vigesimal = true,
    )

    private val ITALIAN = Numerals(
        units = mapOf(
            "zero" to 0L, "un" to 1L, "uno" to 1L, "una" to 1L, "due" to 2L, "tre" to 3L,
            "quattro" to 4L, "cinque" to 5L, "sei" to 6L, "sette" to 7L, "otto" to 8L,
            "nove" to 9L, "dieci" to 10L, "undici" to 11L, "dodici" to 12L, "tredici" to 13L,
            "quattordici" to 14L, "quindici" to 15L, "sedici" to 16L, "diciassette" to 17L,
            "diciotto" to 18L, "diciannove" to 19L, "venti" to 20L, "trenta" to 30L,
            "quaranta" to 40L, "cinquanta" to 50L, "sessanta" to 60L, "settanta" to 70L,
            "ottanta" to 80L, "novanta" to 90L,
        ),
        scales = mapOf(
            "cento" to 100L, "mille" to 1_000L, "mila" to 1_000L,
            "milione" to 1_000_000L, "milioni" to 1_000_000L,
        ),
        fillers = setOf("e"),
    )

    private val GERMAN = Numerals(
        units = mapOf(
            "null" to 0L, "ein" to 1L, "eine" to 1L, "eins" to 1L, "zwei" to 2L, "drei" to 3L,
            "vier" to 4L, "funf" to 5L, "sechs" to 6L, "sieben" to 7L, "acht" to 8L,
            "neun" to 9L, "zehn" to 10L, "elf" to 11L, "zwolf" to 12L, "dreizehn" to 13L,
            "vierzehn" to 14L, "funfzehn" to 15L, "sechzehn" to 16L, "siebzehn" to 17L,
            "achtzehn" to 18L, "neunzehn" to 19L, "zwanzig" to 20L, "dreissig" to 30L,
            "dreißig" to 30L, "vierzig" to 40L, "funfzig" to 50L, "sechzig" to 60L,
            "siebzig" to 70L, "achtzig" to 80L, "neunzig" to 90L,
        ),
        scales = mapOf(
            "hundert" to 100L, "tausend" to 1_000L,
            "million" to 1_000_000L, "millionen" to 1_000_000L,
        ),
        fillers = setOf("und"),
    )

    private val SINO_KOREAN_DIGITS = mapOf(
        '영' to '〇', '공' to '〇', '일' to '一', '이' to '二', '삼' to '三', '사' to '四', '오' to '五',
        '육' to '六', '칠' to '七', '팔' to '八', '구' to '九',
    )
    private val SINO_KOREAN_UNITS = mapOf('십' to '十', '백' to '百', '천' to '千', '만' to '万', '억' to '億')

    private val NATIVE_KOREAN_UNITS = mapOf(
        "하나" to 1L, "한" to 1L, "둘" to 2L, "두" to 2L, "셋" to 3L, "세" to 3L, "넷" to 4L, "네" to 4L,
        "다섯" to 5L, "여섯" to 6L, "일곱" to 7L, "여덟" to 8L, "아홉" to 9L,
    )
    private val NATIVE_KOREAN_TENS = mapOf(
        "열" to 10L, "스물" to 20L, "스무" to 20L, "서른" to 30L, "마흔" to 40L, "쉰" to 50L,
        "예순" to 60L, "일흔" to 70L, "여든" to 80L, "아흔" to 90L,
    )

    /**
     * A glued Sino-Korean number (`삼십오`), read through the Han reading it transliterates, or a
     * glued native one (`열하나`). A Sino-Korean word must carry a unit syllable, or `사이` (between)
     * and `오이` (cucumber) would read as 42 and 52.
     */
    private fun koreanCompound(word: String): Long? {
        val sino = word.all { it in SINO_KOREAN_DIGITS || it in SINO_KOREAN_UNITS }
        if (sino && word.length >= 2 && word.any { it in SINO_KOREAN_UNITS }) {
            val han = word.map { SINO_KOREAN_DIGITS[it] ?: SINO_KOREAN_UNITS.getValue(it) }.joinToString("")
            return hanValue(han).toLong()
        }
        val tens = NATIVE_KOREAN_TENS.entries.firstOrNull { word.startsWith(it.key) && word.length > it.key.length }
            ?: return null
        return NATIVE_KOREAN_UNITS[word.removePrefix(tens.key)]?.plus(tens.value)
    }

    /**
     * Both counting systems: Sino-Korean (`삼`, `삼십`) and native (`셋`, and the counter forms
     * `한`/`두`/`세`/`네` of `한 시`). A recognizer writes either as digits (`3시`).
     */
    private val KOREAN = Numerals(
        units = NATIVE_KOREAN_UNITS + NATIVE_KOREAN_TENS + mapOf(
            "영" to 0L, "공" to 0L, "일" to 1L, "이" to 2L, "삼" to 3L, "사" to 4L, "오" to 5L,
            "육" to 6L, "칠" to 7L, "팔" to 8L, "구" to 9L,
        ),
        scales = mapOf("십" to 10L, "백" to 100L, "천" to 1_000L, "만" to 10_000L, "억" to 100_000_000L),
        fillers = emptySet(),
        compound = ::koreanCompound,
    )

    private val TABLES: Map<String, Numerals> = mapOf(
        "ko" to KOREAN,
        "en" to ENGLISH,
        "es" to SPANISH,
        "pt" to PORTUGUESE,
        "fr" to FRENCH,
        "it" to ITALIAN,
        "de" to GERMAN,
    )
}
