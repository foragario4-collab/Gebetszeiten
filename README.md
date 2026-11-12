# Gebetszeiten

Islamische Gebetszeiten-App für Android (läuft auch auf GrapheneOS ohne Google-Dienste).

## Funktionen
- Tabs: Heute · Qibla · Karte; Einstellungen oben rechts in „Heute“; Tage mit Pfeilen wechseln
- Karte: Fahr-/Gehzeit, Ankunft vor/nach Iqamah, Sortierung nach Nähe oder Iqamah
- 11 Sprachen: Deutsch, English, Türkçe, Bosanski, Srpski, Shqip, Français, Русский, Нохчийн, 中文, العربية (mit Rechts-nach-links)
- Einrichtung beim ersten Start (Sprache, Standort, Berechnung – Standard ISNA, Mitteilungen)
- Gebetszeiten offline berechnet (Muslim World League, Diyanet, ISNA, Ägypten, Umm al-Qura, Karachi, Golf, Frankreich, eigene Winkel)
- Asr Standard / Hanafi, Regel für hohe Breitengrade
- Islamische Mitternacht und letztes Drittel der Nacht
- Mitteilungen pro Gebet: Aus / Ton / Adhan (eigene Audiodatei, extra Fajr-Adhan möglich)
- Erinnerung vorher, Live-Countdown mit Ladebalken (ab Android 16 als Live-Update, auch auf dem Sperrbildschirm)
- Zeitkorrektur in Minuten pro Gebet, Hijri-Korrektur, 24-Stunden-Format
- Hell / Dunkel / Automatisch – auch für die Widgets
- Widgets: großes Widget (nächstes Gebet immer in der Mitte) und kleines Widget mit Countdown
- Qibla: rechteckiger Kompass + Moscheen-Karte (MapLibre/OpenStreetMap, ohne Google), nur Moscheen, Tippen zeigt Adhan & Iqamah
- Moscheen-Blatt zum Hoch-/Runterziehen: Liste nach Entfernung, nächstes Gebet + Iqamah, „Keine Zeiten verfügbar“ mit Link hinzufügen
- Findet Moscheen und Gebetsräume/Kulturvereine (OpenStreetMap)
- Diagnose in den Einstellungen
- Moschee hinzufügen per MasjidBox-/Mawaqit-Link, Punkt auf der Karte oder Adresse; Iqamah von Hand bearbeitbar
- Live-Countdown nach Isha bis Mitternacht, danach bis Fajr
- Moschee-Radar: live die nächste Moschee in der Mitteilung, Hinweis wenn man in der Nähe ist und bald gebetet wird
- Kalender: Hijri, Gregorianisch oder beides

## APK bauen
1. Den ganzen Ordnerinhalt in ein GitHub-Repository hochladen (inklusive `.github`-Ordner).
2. Unter **Actions** startet „APK bauen“ automatisch (oder manuell über „Run workflow“).
3. Nach ca. 5 Minuten unter dem Lauf bei **Artifacts** „Gebetszeiten-APK“ herunterladen, entpacken, installieren.

Die App ist immer mit demselben Schlüssel (`app/gebetszeiten.keystore`) signiert –
neue Versionen lassen sich daher einfach drüber installieren.


## Version 5.0
- Startseite mit lebendem Himmel (Sonnenstand, Mond mit Phase, Volumenwolken, Regen, Schnee, Nebel, Gewitter) nach echtem Wetter (MET Norway)
- Eine Mitteilung für Live-Anzeige und Live-Radar, mit „Route“ und „Andere Moschee“
- Einheitliche Zeitangaben (45 Min · 2 Std · 1 Std 10 Min)
- Islam-Landkarte wieder aktiv, gefiltert auf Moscheen/Gebetsräume (IGGÖ-Gemeinden, Verbände); Dua Center (1010) fest eingetragen
- Sicherheit: keine Datensicherung privater Daten, Links geprüft, Seiten-Größe begrenzt, gleichzeitige Zugriffe abgesichert, Signaturschlüssel über GitHub-Secrets möglich

### Signaturschlüssel absichern (empfohlen)
In GitHub unter Settings → Secrets and variables → Actions drei Secrets anlegen:
SIGNING_KEYSTORE_B64 (Keystore als Base64), SIGNING_STORE_PASSWORD, SIGNING_KEY_PASSWORD.
Danach die Datei app/gebetszeiten.keystore aus dem Repo löschen.
