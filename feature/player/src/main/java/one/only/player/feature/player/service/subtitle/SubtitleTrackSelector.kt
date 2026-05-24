package one.only.player.feature.player.service.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import one.only.player.feature.player.extensions.switchTrack

internal class SubtitleTrackSelector(
    private val preferredLanguageProvider: () -> String,
) {

    fun switchToRememberedOrBestSubtitleTrack(
        player: androidx.media3.common.Player,
        textTracks: List<Tracks.Group>,
        rememberedSubtitleTrackIndex: Int?,
        shouldFallbackToBest: Boolean,
    ) {
        when {
            rememberedSubtitleTrackIndex == -1 -> player.switchTrack(C.TRACK_TYPE_TEXT, -1)
            rememberedSubtitleTrackIndex in textTracks.indices -> player.switchTrack(C.TRACK_TYPE_TEXT, rememberedSubtitleTrackIndex ?: -1)
            shouldFallbackToBest -> player.switchTrack(C.TRACK_TYPE_TEXT, findBestSubtitleTrackIndex(textTracks))
        }
    }

    fun supportedTextTracks(tracks: Tracks): List<Tracks.Group> = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }

    private fun findBestSubtitleTrackIndex(textTracks: List<Tracks.Group>): Int {
        val preferred = preferredLanguageProvider()
        if (preferred.isBlank()) return 0

        val normalizedPref = normalizeLanguageTag(preferred)
        for (i in textTracks.indices) {
            val format = textTracks[i].getTrackFormat(0)
            if (matchesPreferredLanguage(format, normalizedPref)) return i
        }
        return 0
    }

    private fun matchesPreferredLanguage(format: Format, preferred: String): Boolean {
        val trackLang = format.language?.let(::normalizeLanguageTag) ?: return false

        if (preferred.startsWith("zh-") && (trackLang == "zh" || trackLang.startsWith("zh-"))) {
            return matchesChineseVariantByLabel(format.label, preferred)
        }

        return trackLang.startsWith(preferred) || preferred.startsWith(trackLang)
    }

    private fun matchesChineseVariantByLabel(label: String?, preferred: String): Boolean {
        if (label == null) return preferred == "zh"
        val lower = label.lowercase()
        val isSimplified = preferred.contains("hans") || preferred.contains("cn")
        val isTraditional = preferred.contains("hant") || preferred.contains("tw") || preferred.contains("hk")

        return when {
            isSimplified -> lower.containsAny("简", "chs", "simplified")
            isTraditional -> lower.containsAny("繁", "cht", "traditional")
            else -> true
        }
    }

    private fun normalizeLanguageTag(tag: String): String {
        val lower = tag.lowercase().replace('_', '-')
        return ISO_639_2T_TO_1[lower] ?: ISO_639_2T_TO_1[lower.substringBefore('-')]?.let {
            it + lower.removePrefix(lower.substringBefore('-'))
        } ?: lower
    }

    private fun String.containsAny(vararg keywords: String): Boolean = keywords.any { contains(it, ignoreCase = true) }

    private companion object {
        private val ISO_639_2T_TO_1 = mapOf(
            "zho" to "zh", "chi" to "zh",
            "eng" to "en",
            "jpn" to "ja",
            "kor" to "ko",
            "fra" to "fr", "fre" to "fr",
            "deu" to "de", "ger" to "de",
            "spa" to "es",
            "por" to "pt",
            "rus" to "ru",
            "ara" to "ar",
            "tha" to "th",
            "vie" to "vi",
            "ita" to "it",
            "pol" to "pl",
            "nld" to "nl", "dut" to "nl",
            "tur" to "tr",
            "ind" to "id",
            "msa" to "ms", "may" to "ms",
        )
    }
}
