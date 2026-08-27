# PhotoboxCameraCore 0.1

Erster Testbaustein für die Hot-Wheels-Photobox.

## Zweck
- Hintere Kamera öffnen
- Livebild anzeigen
- Camera2-Fähigkeiten des Geräts auslesen
- Prüfen, ob MANUAL_SENSOR / MANUAL_POST_PROCESSING verfügbar sind
- ISO-, Belichtungszeit-, Fokus-, Zoom-, AF- und AWB-Fähigkeiten anzeigen

## Noch nicht enthalten
- Foto speichern
- manuelle Regler
- Tablet-Fernsteuerung
- WLAN-Verbindung
- Plugin-Schnittstelle

## Nächster Entwicklungsschritt
Sobald der Gerätebericht vom S25 Ultra vorliegt, werden nur die tatsächlich unterstützten Regler implementiert. Danach wird die Remote-Schnittstelle für das Tablet ergänzt.

## Automatischer APK-Build über GitHub Actions
Bei jedem Push auf `main` wird automatisch eine Debug-APK gebaut.
Die APK liegt anschließend im Workflow-Lauf als Artifact `PhotoboxCameraCore-0.1-debug-apk` bereit.
