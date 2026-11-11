package com.gebetszeiten.app

/** Wiener Bezirke aus der Postleitzahl (1010–1230). */
object Bezirk {
    private val names = listOf(
        "Innere Stadt", "Leopoldstadt", "Landstraße", "Wieden", "Margareten", "Mariahilf", "Neubau", "Josefstadt",
        "Alsergrund", "Favoriten", "Simmering", "Meidling", "Hietzing", "Penzing", "Rudolfsheim-Fünfhaus", "Ottakring",
        "Hernals", "Währing", "Döbling", "Brigittenau", "Floridsdorf", "Donaustadt", "Liesing"
    )

    /** „11. Simmering“ oder null. */
    fun of(address: String): String? {
        val m = Regex("\\b1(\\d{2})0\\b").find(address) ?: return null
        val n = m.groupValues[1].toInt()
        if (n !in 1..23) return null
        if (!address.contains("Wien", true) && !address.contains("Vienna", true) && !Regex("\\b1\\d{2}0\\s").containsMatchIn(address + " ")) return null
        return "$n. ${names[n - 1]}"
    }

    /** Kurz für Listen: „Rinnböckstraße 17 · 11. Simmering“. */
    fun short(address: String): String {
        if (address.isBlank()) return ""
        val street = address.substringBefore(",").trim()
        val b = of(address)
        return if (b != null) "$street · $b" else address
    }

    /** Adresse mit Bezirk: „Rinnböckstraße 17, 1110 Wien · 11. Simmering“. */
    fun label(address: String): String {
        if (address.isBlank()) return ""
        val b = of(address) ?: return address
        return "$address · $b"
    }
}
