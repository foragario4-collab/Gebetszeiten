package com.gebetszeiten.app

import android.content.Context
import java.util.Locale

enum class Lang(val code: String, val native: String, val rtl: Boolean = false) {
    DE("de", "Deutsch"),
    EN("en", "English"),
    TR("tr", "Türkçe"),
    BS("bs", "Bosanski"),
    SR("sr", "Srpski"),
    SQ("sq", "Shqip"),
    FR("fr", "Français"),
    RU("ru", "Русский"),
    CE("ce", "Нохчийн"),
    ZH("zh", "中文"),
    AR("ar", "العربية", true);

    /** Locale für Datums- und Zahlenformate (Tschetschenisch nutzt russische Monatsnamen). */
    val locale: Locale
        get() = when (this) {
            CE -> Locale("ru")
            SR -> Locale.forLanguageTag("sr-Latn")
            ZH -> Locale.SIMPLIFIED_CHINESE
            else -> Locale(code)
        }

    companion object {
        fun of(code: String): Lang = entries.firstOrNull { it.code == code } ?: DE

        /** Sprache des Handys, falls unterstützt – sonst Deutsch. */
        fun device(): Lang {
            val l = Locale.getDefault().language
            return when (l) {
                "hr" -> BS
                else -> entries.firstOrNull { it.code == l } ?: DE
            }
        }
    }
}

/** Übersetzungen aus assets/i18n.tsv (Spalten in der Reihenfolge von [Lang]). */
object I18n {
    @Volatile
    private var table: Map<String, Array<String>> = emptyMap()

    fun init(ctx: Context) {
        if (table.isNotEmpty()) return
        try {
            val map = HashMap<String, Array<String>>()
            ctx.applicationContext.assets.open("i18n.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split('\t').map { it.replace("\\n", "\n") }
                    if (parts.size >= 2) map[parts[0]] = parts.drop(1).toTypedArray()
                }
            }
            table = map
        } catch (e: Exception) {
        }
    }

    fun get(key: String): String {
        val row = table[key] ?: return key
        val idx = Prefs.lang.ordinal
        return row.getOrNull(idx)?.takeIf { it.isNotEmpty() } ?: row.getOrNull(1) ?: row[0]
    }
}

/** Übersetzter Text; Platzhalter {0}, {1} … werden ersetzt. */
fun t(key: String, vararg args: Any): String {
    var s = I18n.get(key)
    args.forEachIndexed { i, a -> s = s.replace("{" + i + "}", a.toString()) }
    return s
}
