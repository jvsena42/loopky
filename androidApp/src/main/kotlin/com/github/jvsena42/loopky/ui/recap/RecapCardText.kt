package com.github.jvsena42.loopky.ui.recap

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.RecapPersona
import com.github.jvsena42.loopky.domain.model.RecapShareTarget
import com.github.jvsena42.loopky.domain.model.YearRecap
import java.util.Locale

/** Everything the share image says, already localized — the drawing code holds no words. */
data class RecapCardText(
    val title: String,
    val headline: String,
    val stats: List<String>,
    val persona: String,
    val tagline: String,
)

@Composable
internal fun createdLine(recap: YearRecap): String? {
    if (recap.decksCreated == 0) return null
    val decks = pluralStringResource(R.plurals.recap_stat_decks_created, recap.decksCreated, recap.decksCreated)
    val cards = pluralStringResource(R.plurals.recap_stat_cards_created, recap.cardsCreated, recap.cardsCreated)
    return "$decks · $cards"
}

@Composable
internal fun recapCardText(recap: YearRecap): RecapCardText = RecapCardText(
    title = stringResource(R.string.recap_image_title, recap.year),
    headline = pluralStringResource(R.plurals.recap_stat_cards_studied, recap.cardsStudied, recap.cardsStudied),
    stats = listOfNotNull(
        pluralStringResource(R.plurals.recap_stat_cards_mastered, recap.cardsMastered, recap.cardsMastered)
            .takeIf { recap.cardsMastered > 0 },
        pluralStringResource(R.plurals.recap_stat_decks_studied, recap.decksStudied, recap.decksStudied),
        createdLine(recap),
    ),
    persona = "${recap.persona.emoji()} ${stringResource(recap.persona.titleRes())}",
    tagline = stringResource(R.string.brand_tagline).replace("\n", " "),
)

/** One caption per network, each written for how that network reads: short, a list, or a chat line. */
@Composable
internal fun recapCaptions(recap: YearRecap): Map<RecapShareTarget, String> {
    val studied = pluralStringResource(R.plurals.recap_stat_cards_studied, recap.cardsStudied, recap.cardsStudied)
    val mastered = pluralStringResource(R.plurals.recap_stat_cards_mastered, recap.cardsMastered, recap.cardsMastered)
    val decks = pluralStringResource(R.plurals.recap_stat_decks_studied, recap.decksStudied, recap.decksStudied)
    val short = stringResource(R.string.recap_share_text_short, recap.year, studied)
    val long = stringResource(R.string.recap_share_text_long, recap.year, studied, mastered, decks)
    val chat = stringResource(R.string.recap_share_text_chat, recap.year, studied, mastered)
    return mapOf(
        RecapShareTarget.X to short,
        RecapShareTarget.Pubky to long,
        RecapShareTarget.Threads to long,
        RecapShareTarget.WhatsApp to chat,
        RecapShareTarget.More to chat,
        // Instagram discards a caption; the story image carries everything.
        RecapShareTarget.Instagram to short,
    )
}

internal fun languageName(code: String, locale: Locale): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        .ifEmpty { code }

internal fun RecapPersona.emoji(): String = when (this) {
    RecapPersona.Polyglot -> "🌍"
    RecapPersona.Creator -> "✍️"
    RecapPersona.Master -> "🏆"
    RecapPersona.Explorer -> "🧭"
}

internal fun RecapPersona.titleRes(): Int = when (this) {
    RecapPersona.Polyglot -> R.string.recap_persona_polyglot
    RecapPersona.Creator -> R.string.recap_persona_creator
    RecapPersona.Master -> R.string.recap_persona_master
    RecapPersona.Explorer -> R.string.recap_persona_explorer
}

internal fun RecapPersona.bodyRes(): Int = when (this) {
    RecapPersona.Polyglot -> R.string.recap_persona_polyglot_body
    RecapPersona.Creator -> R.string.recap_persona_creator_body
    RecapPersona.Master -> R.string.recap_persona_master_body
    RecapPersona.Explorer -> R.string.recap_persona_explorer_body
}

internal fun RecapShareTarget.labelRes(): Int = when (this) {
    RecapShareTarget.Pubky -> R.string.recap_share_pubky
    RecapShareTarget.X -> R.string.recap_share_x
    RecapShareTarget.Threads -> R.string.recap_share_threads
    RecapShareTarget.Instagram -> R.string.recap_share_instagram
    RecapShareTarget.WhatsApp -> R.string.recap_share_whatsapp
    RecapShareTarget.More -> R.string.recap_share_more
}
