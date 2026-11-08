# Gebetszeiten

Islamische Gebetszeiten-App für Android (läuft auch auf GrapheneOS ohne Google-Dienste).

## Funktionen
- Gebetszeiten offline berechnet (Muslim World League, Diyanet, ISNA, Ägypten, Umm al-Qura, Karachi, Golf, Frankreich, eigene Winkel)
- Asr Standard / Hanafi, Regel für hohe Breitengrade
- Islamische Mitternacht und letztes Drittel der Nacht
- Mitteilungen pro Gebet: Aus / Ton / Adhan (eigene Audiodatei, extra Fajr-Adhan möglich)
- Erinnerung vorher, Live-Countdown mit Ladebalken (ab Android 16 als Live-Update, auch auf dem Sperrbildschirm)
- Zeitkorrektur in Minuten pro Gebet, Hijri-Korrektur, 24-Stunden-Format
- Hell / Dunkel / Automatisch – auch für die Widgets
- Widgets: großes Widget (nächstes Gebet immer in der Mitte) und kleines Widget mit Countdown
- Qibla: rechteckiger Kompass + Moscheen-Karte (MapLibre/OpenStreetMap, ohne Google), nur Moscheen, Tippen zeigt Adhan & Iqamah
- Moschee-Radar: live die nächste Moschee in der Mitteilung, Hinweis wenn man in der Nähe ist und bald gebetet wird
- Kalender: Hijri, Gregorianisch oder beides

## APK bauen
1. Den ganzen Ordnerinhalt in ein GitHub-Repository hochladen (inklusive `.github`-Ordner).
2. Unter **Actions** startet „APK bauen“ automatisch (oder manuell über „Run workflow“).
3. Nach ca. 5 Minuten unter dem Lauf bei **Artifacts** „Gebetszeiten-APK“ herunterladen, entpacken, installieren.

Die App ist immer mit demselben Schlüssel (`app/gebetszeiten.keystore`) signiert –
neue Versionen lassen sich daher einfach drüber installieren.
