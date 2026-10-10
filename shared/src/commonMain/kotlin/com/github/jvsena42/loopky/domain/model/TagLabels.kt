package com.github.jvsena42.loopky.domain.model

/**
 * The one shape a tag label is stored and looked up in (#479).
 *
 * Tag records are Loopky's only global index and the indexer matches labels byte for byte, so
 * `café`, `cafe`, `Foo Bar` and `foo_bar` are four shelves unless every writer folds them first.
 * Every path that writes a deck tag goes through here — the view models so the chips show what
 * will be published, `DeckRepositoryImpl` so no caller can skip it — and a search folds its query
 * the same way.
 *
 * **Only Latin letters lose their marks.** Stripping every combining mark would turn Cyrillic
 * `й` into `и`, Japanese `が` into `か`, and take the vowels out of Arabic and Devanagari, so the
 * fold is a table of precomposed Latin letters rather than NFD plus a category filter; anything
 * outside it passes through untouched. `TagLabelsJvmTest` holds the table to `java.text.Normalizer`.
 */
object TagLabels {

    /** pubky-app-specs' ceiling for a label; one over it is rejected by the indexer, silently. */
    const val MAX_LENGTH = 20

    /**
     * [label]'s length the way the spec counts it: in code points, not UTF-16 units. `String.length`
     * counts an emoji twice, which refused a label of eleven emoji the indexer accepts.
     */
    fun lengthOf(label: String): Int = label.count { !it.isLowSurrogate() }

    /**
     * [raw] in canonical shape: lowercased, Latin diacritics stripped, and every run of
     * whitespace, `_` and `-` collapsed to one `-`, with none left at either end. Says nothing
     * about whether the result is a label Loopky will store — that is [normalize].
     */
    fun fold(raw: String): String {
        val out = StringBuilder(raw.length)
        var separatorPending = false
        for (char in raw.lowercase()) {
            when {
                char.isSeparator() -> separatorPending = out.isNotEmpty()
                char in COMBINING_MARKS && out.endsInLatin() -> Unit
                else -> {
                    if (separatorPending) out.append(SEPARATOR)
                    separatorPending = false
                    out.append(FOLDS[char] ?: char)
                }
            }
        }
        return out.toString()
    }

    /** [raw] folded, or null when what is left is empty, reserved or longer than [MAX_LENGTH]. */
    fun normalize(raw: String): String? =
        fold(raw).takeIf { it.isNotEmpty() && lengthOf(it) <= MAX_LENGTH && !ReservedTags.isReserved(it) }

    /** Every label of [raw] that survives [normalize], first occurrence winning after the fold. */
    fun normalizeAll(raw: List<String>): List<String> = raw.mapNotNull(::normalize).distinct()

    private fun Char.isSeparator(): Boolean = isWhitespace() || this == '_' || this == SEPARATOR

    private fun StringBuilder.endsInLatin(): Boolean = isNotEmpty() && last() in LATIN

    private const val SEPARATOR = '-'
    private val LATIN = 'a'..'z'
    private val COMBINING_MARKS = '\u0300'..'\u036F'

    /** Letters Unicode gives no decomposition, so no normalization form would ever fold them. */
    private val UNDECOMPOSABLE: Map<Char, String> = mapOf(
        'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'đ' to "d", 'ł' to "l", 'ı' to "i",
    )

    /** Precomposed lowercase Latin letter, then its base letter — generated, do not hand-edit. */
    private const val DECOMPOSABLE =
        "àaáaâaãaäaåaçcèeéeêeëeìiíiîiïiñnòoóoôoõoöoùuúuûuüuýyÿyāaăaąaćcĉcċcčcďdēe" +
            "ĕeėeęeěeĝgğgġgģgĥhĩiīiĭiįiĵjķkĺlļlľlńnņnňnōoŏoőoŕrŗrřrśsŝsşsšsţtťtũuūuŭu" +
            "ůuűuųuŵwŷyźzżzžzơoưuǎaǐiǒoǔuǖuǘuǚuǜuǟaǡaǣæǧgǩkǫoǭoǰjǵgǹnǻaǽæǿøȁaȃaȅeȇeȉi" +
            "ȋiȍoȏoȑrȓrȕuȗușsțtȟhȧaȩeȫoȭoȯoȱoȳyḁaḃbḅbḇbḉcḋdḍdḏdḑdḓdḕeḗeḙeḛeḝeḟfḡgḣhḥh" +
            "ḧhḩhḫhḭiḯiḱkḳkḵkḷlḹlḻlḽlḿmṁmṃmṅnṇnṉnṋnṍoṏoṑoṓoṕpṗpṙrṛrṝrṟrṡsṣsṥsṧsṩsṫtṭt" +
            "ṯtṱtṳuṵuṷuṹuṻuṽvṿvẁwẃwẅwẇwẉwẋxẍxẏyẑzẓzẕzẖhẗtẘwẙyạaảaấaầaẩaẫaậaắaằaẳaẵaặa" +
            "ẹeẻeẽeếeềeểeễeệeỉiịiọoỏoốoồoổoỗoộoớoờoởoỡoợoụuủuứuừuửuữuựuỳyỵyỷyỹy"

    private val FOLDS: Map<Char, String> = UNDECOMPOSABLE +
        DECOMPOSABLE.chunked(2).associate { pair -> pair[0] to (UNDECOMPOSABLE[pair[1]] ?: pair[1].toString()) }
}

fun List<Tag>.normalized(): List<Tag> = TagLabels.normalizeAll(map { it.value }).map(::Tag)
