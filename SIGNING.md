# Release-Signierschlüssel dauerhaft speichern

Der GitHub-Runner ist nur vorübergehend. Eine `.jks`-Datei, die dort erzeugt wird, geht nach dem Build verloren.
Der private Schlüssel kann aus einer bereits signierten APK nicht zurückgewonnen werden.

## 1. Keystore einmalig auf dem eigenen Computer erzeugen

Voraussetzung: Java/JDK ist installiert (Befehl `keytool` muss verfügbar sein).

```bash
keytool -genkeypair -v \
  -keystore vellum-release.jks \
  -alias episteme \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Ein sicheres Passwort vergeben und sicher notieren. Den Alias `episteme` beibehalten.
Die Datei `vellum-release.jks` sicher und zusätzlich offline sichern. Niemals ins Git-Repository hochladen.

## 2. Keystore in Base64 umwandeln

Linux/macOS:
```bash
base64 -w 0 vellum-release.jks > keystore-base64.txt
```

Windows PowerShell:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("vellum-release.jks")) | Set-Content -NoNewline keystore-base64.txt
```

## 3. Zwei GitHub Secrets anlegen

Im GitHub-Repository:

**Settings → Secrets and variables → Actions → New repository secret**

Diese beiden Secrets exakt so anlegen:

- `KEYSTORE_BASE64` – den kompletten Inhalt aus `keystore-base64.txt` einfügen
- `KEYSTORE_PASSWORD` – das Passwort, das beim Erzeugen des Keystores vergeben wurde

Danach die lokale Textdatei `keystore-base64.txt` sicher löschen. Die `.jks`-Datei und das Passwort sicher aufbewahren.

## Wichtig

Der Workflow verwendet den Alias `episteme` und dasselbe Passwort für Store und Key.
Wenn bereits ein früherer Workflow-Lauf einen Schlüssel nur auf dem GitHub-Runner erzeugt hat, ist dieser Schlüssel nicht dauerhaft gespeichert und kann nicht aus dem alten APK-Release rekonstruiert werden. In diesem Fall muss jetzt ein neuer Schlüssel erzeugt und ab dann für jedes Update unverändert verwendet werden.
