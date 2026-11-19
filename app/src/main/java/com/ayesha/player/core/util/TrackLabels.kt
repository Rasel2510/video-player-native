package com.ayesha.player.core.util

/** Port of track_labels.dart: naming and filtering audio/subtitle tracks. */
object TrackLabels {
    /** libmpv injects synthetic `auto` / `no` track ids; they aren't real tracks in the file. */
    fun isPlaceholderId(id: String): Boolean = id == "auto" || id == "no"

    private val languageNames = mapOf(
        "en" to "English", "eng" to "English",
        "es" to "Spanish", "spa" to "Spanish",
        "fr" to "French", "fre" to "French", "fra" to "French",
        "de" to "German", "ger" to "German", "deu" to "German",
        "it" to "Italian", "ita" to "Italian",
        "pt" to "Portuguese", "por" to "Portuguese",
        "ru" to "Russian", "rus" to "Russian",
        "ja" to "Japanese", "jpn" to "Japanese",
        "ko" to "Korean", "kor" to "Korean",
        "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
        "ar" to "Arabic", "ara" to "Arabic",
        "hi" to "Hindi", "hin" to "Hindi",
        "bn" to "Bengali", "ben" to "Bengali",
        "ur" to "Urdu", "urd" to "Urdu",
        "tr" to "Turkish", "tur" to "Turkish",
        "vi" to "Vietnamese", "vie" to "Vietnamese",
        "th" to "Thai", "tha" to "Thai",
        "id" to "Indonesian", "ind" to "Indonesian",
        "nl" to "Dutch", "dut" to "Dutch", "nld" to "Dutch",
        "pl" to "Polish", "pol" to "Polish",
        "uk" to "Ukrainian", "ukr" to "Ukrainian",
    )

    /** Display name for a language code; null when missing or `und`; unknown codes upper-cased. */
    fun languageName(code: String?): String? {
        if (code == null) return null
        val normalized = code.lowercase().trim()
        if (normalized.isEmpty() || normalized == "und") return null
        return languageNames[normalized] ?: code.uppercase()
    }

    /**
     * Row label: an embedded title wins; a lone track reads "Default";
     * otherwise the language name, then "Track N".
     */
    fun trackLabel(title: String?, language: String?, index: Int, total: Int): String {
        val t = title?.trim()
        if (!t.isNullOrEmpty()) return t
        if (total <= 1) return "Default"
        return languageName(language) ?: "Track ${index + 1}"
    }
}
