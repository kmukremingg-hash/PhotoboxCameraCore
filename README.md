# Collectooow! Photobox Camera 0.8

Android-Fotobox-Kamera für zwei Geräte im selben WLAN.

## Geräteaufteilung

- **S25 / Smartphone:** Kamera-Host. Liefert Livebild und Original-JPEG über das lokale WLAN.
- **Tablet:** Remote-Steuerung und **einziger dauerhafter Speicherort** für ausgelöste Fotos.

## Version 0.8 – Änderungen

- Originalfoto wird bei Remote-Aufnahme **nicht auf dem Handy gespeichert**.
- Das Tablet ruft das Full-Resolution-JPEG direkt vom Kamera-Host ab und speichert es unter:
  `Pictures/Collectooow Photobox/Rohbilder`
- Kameraeinstellungen werden dauerhaft lokal gespeichert und beim Neustart wiederhergestellt:
  - Belichtung AUTO/MANUELL
  - ISO-Regler
  - Belichtungszeit
  - Fokus AUTO/MANUELL
  - Zoom
  - Weißabgleich
- **Rückgängig** und **Wiederholen** für Kameraeinstellungen.
- **Autofokus** als eigene Taste.
- **Tap-to-Focus:** Im Tablet-Livebild auf das gewünschte Motiv tippen; der Fokuspunkt wird an das S25 übertragen.
- Fokusregler auf 0 = Autofokus / Continuous Picture AF.

## Verwendung

### Auf dem S25
1. App öffnen.
2. `S25 Kamera / Host` wählen.
3. `Kamera starten` drücken.
4. Angezeigte WLAN-IP merken.

### Auf dem Tablet
1. Dieselbe App öffnen.
2. `Tablet / Remote` wählen.
3. IP des S25 eintragen und verbinden.
4. Livebild erscheint.
5. Optional ins Livebild tippen, um den Fokuspunkt zu setzen.
6. `FOTO AUFNEHMEN → TABLET` drücken.

Das S25 hält das JPEG nur kurz im Arbeitsspeicher für die Übertragung. Es wird dort nicht in MediaStore/Galerie geschrieben.
