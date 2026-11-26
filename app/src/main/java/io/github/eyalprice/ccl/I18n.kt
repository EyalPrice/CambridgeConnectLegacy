package io.github.eyalprice.ccl

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * App translations. All texts live in assets/i18n.csv (one row per text, one column per language).
 * The app follows the phone's language; a choice in Settings overrides it. Unsupported languages
 * fall back to English (US). Changing [lang] re-draws the screens straight away.
 */
object I18n {
    /** Language codes, in the CSV's column order after the key column. */
    val CODES = listOf("en-US", "en-GB", "ca", "da", "de", "es", "fi", "fr", "it", "nb", "nl", "pl", "pt-PT", "ru", "sv", "he", "ar")

    /** Entries of the language picker. Arabic comes twice: with Arabic-Indic digits ("ar") and with Western digits ("ar-latn"). */
    val CHOICES = listOf("en-US", "en-GB", "ca", "da", "de", "es", "fi", "fr", "it", "nb", "nl", "pl", "pt-PT", "ru", "sv", "he", "ar", "ar-latn")

    /** Names shown in the picker, each in its own language. */
    val NAMES = mapOf(
        "en-US" to "English (US)", "en-GB" to "English (UK)", "ca" to "Català", "da" to "Dansk", "de" to "Deutsch",
        "es" to "Español", "fi" to "Suomi", "fr" to "Français", "it" to "Italiano", "nb" to "Norsk (bokmål)",
        "nl" to "Nederlands", "pl" to "Polski", "pt-PT" to "Português", "ru" to "Русский", "sv" to "Svenska",
        "he" to "עברית", "ar" to "العربية (١٢٣)", "ar-latn" to "العربية (123)",
    )

    private var table: Map<String, List<String>> = emptyMap()
    private const val PREF = "appLang"

    /** The language in use. */
    var lang by mutableStateOf("en-US")
        private set

    /** The saved choice: "" means "same as phone". */
    var choice by mutableStateOf("")
        private set

    /** True when the app is shown right to left (Hebrew, Arabic). */
    val rtl: Boolean get() = lang == "he" || lang == "ar"

    /** True when numbers are shown with Arabic-Indic digits (١٢٣). */
    var arabicDigits by mutableStateOf(false)
        private set

    private fun apply(code: String) {
        lang = when (code) { "ar-latn" -> "ar"; in CODES -> code; else -> phoneLanguage() }
        arabicDigits = when (code) {
            "ar" -> true
            "ar-latn" -> false
            "" -> lang == "ar" && java.text.DecimalFormatSymbols.getInstance(Locale.getDefault()).zeroDigit == '\u0660'
            else -> false
        }
    }

    /** A number (or text holding digits) in the digits the user chose. */
    fun num(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (!arabicDigits) s else buildString { s.forEach { append(if (it in '0'..'9') '\u0660' + (it - '0') else it) } }
    }

    fun init(ctx: Context) {
        if (table.isEmpty()) table = try {
            ctx.assets.open("i18n.csv").bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
                .drop(1).filter { it.size > 1 }.associate { it[0] to it.drop(1) }
        } catch (e: Exception) { emptyMap() }
        choice = ctx.getSharedPreferences("ccl", Context.MODE_PRIVATE).getString(PREF, "") ?: ""
        apply(choice)
    }

    fun choose(ctx: Context, code: String) {
        ctx.getSharedPreferences("ccl", Context.MODE_PRIVATE).edit().putString(PREF, code).apply()
        choice = code
        apply(code)
    }

    /** Maps the phone's language to a supported one, else English (US). */
    fun phoneLanguage(): String {
        val l = Locale.getDefault()
        return when (l.language) {
            "en" -> if (l.country in setOf("GB", "IE", "AU", "NZ", "ZA", "IN")) "en-GB" else "en-US"
            "nb", "no", "nn" -> "nb"
            "pt" -> "pt-PT"
            "he", "iw" -> "he"
            "ar" -> "ar"
            "ca", "da", "de", "es", "fi", "fr", "it", "nl", "pl", "ru", "sv" -> l.language
            else -> "en-US"
        }
    }

    /** Translated text for [key]; {0}, {1}… are filled from [args]. Falls back to English, then to the key. */
    fun t(key: String, vararg args: Any?): String {
        val row = table[key]
        var s = row?.getOrNull(CODES.indexOf(lang))?.takeIf { it.isNotBlank() } ?: row?.getOrNull(0) ?: key
        s = s.replace("\\n", "\n")
        args.forEachIndexed { i, a -> s = s.replace("{$i}", if (a is Number) num(a) else a?.toString() ?: "") }
        return s
    }

    /** Minimal CSV reader (quoted fields, doubled quotes, commas and newlines inside quotes). */
    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val f = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                q && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { f.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == ',' -> { row.add(f.toString()); f.clear() }
                !q && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(f.toString()); f.clear(); rows.add(row); row = mutableListOf()
                }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows
    }
}

/** Short form used everywhere: tx("key", args…). */
fun tx(key: String, vararg args: Any?): String = I18n.t(key, *args)

/** Short form for numbers on screen: nx(5) gives "5" or "٥". */
fun nx(v: Any?): String = I18n.num(v)
