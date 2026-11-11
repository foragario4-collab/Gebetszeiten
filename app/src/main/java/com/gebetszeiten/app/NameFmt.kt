package com.gebetszeiten.app

/**
 * Macht aus offiziellen Vereinsnamen den bekannten Namen + kurze Art.
 * Beispiel: „Islamisches Kultur- und Bildungszentrum "EN-NUUR" (Das Licht)“ → („EN-NUUR“, „Kultur- und Bildungszentrum“)
 */
object NameFmt {
    private val cache = HashMap<String, Pair<String, String>>()

    private val legalTail = Regex(
        "(?i)[,;]?\\s*-?\\s*(moschee[- ]*)?(errichtungs-?,?\\s*)?erhaltungs[- ]*(und|u\\.)\\s*verwaltungsverein.*$|" +
            "[,;]?\\s*(kurzbezeichnung|kurzform|kurz|abkürzung)\\s*:?.*$"
    )

    private val types = listOf(
        "Islamisches Zentrum" to "Islamzentrum",
        "Islamzentrum" to "Islamzentrum",
        "Kultur- und Bildungszentrum" to "Kultur- und Bildungszentrum",
        "Kulturzentrum" to "Kulturzentrum",
        "Moscheegemeinde" to "Moscheegemeinde",
        "Gebetsraum" to "Gebetsraum",
        "Gebetshaus" to "Gebetshaus",
        "Islamische Föderation" to "Islamische Föderation",
        "Kulturverein" to "Kulturverein",
        "Kultur Verein" to "Kulturverein",
        "Kulturvereinigung" to "Kulturverein",
        "Integrationsverein" to "Verein",
        "Forum" to "Forum",
        "Verein" to "Verein",
        "Džemat" to "Džemat",
        "Dzemat" to "Džemat"
    )

    private fun typeOf(full: String): String =
        types.firstOrNull { full.contains(it.first, ignoreCase = true) }?.second ?: ""

    private fun clean(s: String) = s.trim().trim(',', ';', '-', '–', ' ', '"', '„', '“', '”', '\'').replace(Regex("\\s+"), " ")

    /** (bekannter Name, Art). Art ist leer, wenn sie schon im Namen steckt oder unbekannt ist. */
    fun split(full: String): Pair<String, String> = cache.getOrPut(full) { compute(full) }

    private fun compute(raw: String): Pair<String, String> {
        val full = raw.replace(legalTail, "").trim()
        val type = typeOf(full)
        var name: String? = null

        // 1) ATIB-Vereine
        if (full.contains("Türkisch-islamische", true) && full.contains("Zusammenarbeit", true)) {
            val loc = Regex("(?i)zusammenarbeit\\s*(in|im)?\\s*(.*)$").find(full)?.groupValues?.get(2)?.let { clean(it) }.orEmpty()
            val paren = Regex("\\(([^)]*)\\)").find(full)?.groupValues?.get(1)?.let { clean(it) }
            val atib: String = when {
                paren != null && paren.contains("ATIB", true) -> paren
                loc.isNotBlank() -> "ATIB " + loc.replace(Regex("(?i)\\(.*\\)"), "").trim()
                else -> "ATIB"
            }
            return atib to "Verein"
        }
        // 2) Name in Anführungszeichen
        val quoted = Regex("[\"„“”']([^\"„“”']{2,40})[\"„“”']").find(full)?.groupValues?.get(1)
        if (quoted != null && !quoted.contains("Moschee -", true)) name = clean(quoted)
        // 3) Kurzer Name in Klammern, z. B. (Mohajerin), (= Baiytul Muhtadin), (Al Taqwa)
        if (name == null) {
            val parens = Regex("\\(([^)]{2,40})\\)").findAll(full).map { clean(it.groupValues[1].removePrefix("=").trim()) }.toList()
            val good = parens.lastOrNull { p ->
                p.isNotBlank() && p.length <= 32 && !p.equals("Das Licht", true) &&
                    !Regex("(?i)verein|österreich|kurz|^[A-ZÄÖÜ]{2,6}$").containsMatchIn(p)
            }
            if (good != null) name = good
        }
        // 4) „… Moschee“ am Ende eines langen Vereinsnamens
        if (name == null) {
            val endM = Regex("(?i)([A-ZÄÖÜa-zäöüß'\\-]+(?:\\s+[A-ZÄÖÜa-zäöüß'\\-]+)?\\s+(Moschee|Camii|Mescid|Masjid))\\s*$").find(full)
            if (endM != null) {
                val it = endM
                if (full.length > it.value.length + 10) {
                    val stop = setOf("österreich", "wien", "in", "der", "die", "das", "kultur", "islamischen", "islamische", "e.v.")
                    val words = clean(it.groupValues[1]).split(" ").toMutableList()
                    while (words.size > 2 && words.first().lowercase() in stop) words.removeAt(0)
                    if (words.size == 3 && words.first().lowercase() in stop) words.removeAt(0)
                    if (words.size >= 3 && words.first().lowercase() in stop) words.removeAt(0)
                    while (words.size > 1 && words.first().lowercase() in stop) words.removeAt(0)
                    name = words.joinToString(" ")
                }
            }
        }
        // 5) Islamische Föderation X → X
        if (name == null) {
            val fed = Regex("(?i)^Islamische Föderation\\s+(.+)$").find(full)
            if (fed != null) name = clean(fed.groupValues[1])
        }
        // 6) Moschee am Anfang behalten, Vereinszusatz abschneiden: „As-Sunna Moschee VSC-Kulturverein“
        if (name == null) {
            val st = Regex("(?i)^(.{2,40}?\\s(Moschee|Camii|Mescid|Masjid|Dzemat|Džemat))\\b").find(full)
            if (st != null && full.length > st.value.length + 6) name = clean(st.groupValues[1])
        }
        val n = name ?: clean(full)
        // Art nicht doppelt anzeigen
        val t = if (type.isNotBlank() && n.contains(type, ignoreCase = true)) "" else type
        return n to t
    }

    fun name(full: String) = split(full).first
    fun type(full: String) = split(full).second
    fun changed(full: String) = name(full) != full.trim()
}
