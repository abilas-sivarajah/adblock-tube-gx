# 📱 GX Tube – YouTube ohne Werbung (Android)

Android-App, die aussieht und sich bedient wie die YouTube-App – nur **ohne Werbung**.
Gleiche Idee wie der Desktop-Browser [AdBlock Browser GX](https://github.com/abilas-sivarajah/adblock-browser-gx)
und die Erweiterung [AdBlock GX](https://github.com/abilas-sivarajah/adblock-gx-extension), diesmal fürs Handy.

Statt die YouTube-Seite zu laden und Werbung herauszufiltern, holt GX Tube die Videos **direkt**
über [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (dieselbe Technik wie NewPipe).
Die Werbedaten von YouTube werden gar nicht erst geladen – es gibt also auch keine Wartezeit
anstelle der Werbung, wie sie im Browser vorkommen kann.

---

## 📲 Installieren

1. Auf dem Handy **[Releases → neueste Version](https://github.com/abilas-sivarajah/adblock-tube-gx/releases/latest)** öffnen.
2. `GXTube-….apk` herunterladen und öffnen.
3. Android fragt beim ersten Mal, ob der Browser Apps installieren darf → **erlauben**.

Jeder Push auf `main` baut automatisch eine neue Version (GitHub Actions). Updates einfach drüber
installieren – Abos, Verlauf und Einstellungen bleiben erhalten (alle Versionen sind mit demselben
Schlüssel signiert, siehe unten).

Voraussetzung: Android 8.0 oder neuer.

## 🌟 Funktionen

| | |
|---|---|
| **Keine Werbung** | Keine Videowerbung, keine Banner, keine „Werbeblocker“-Hinweise |
| **SponsorBlock** | Gesponserte Abschnitte *im* Video („Dieses Video wird präsentiert von …“) werden automatisch übersprungen, auf dem Fortschrittsbalken grün markiert. Kategorien einstellbar. |
| **Hintergrundwiedergabe** | Ton läuft weiter bei gesperrtem Bildschirm oder in anderen Apps, mit Steuerung in Benachrichtigung und Sperrbildschirm (bei YouTube nur mit Premium) |
| **Bild-im-Bild** | Video läuft im kleinen Fenster weiter, wenn du zum Startbildschirm wechselst |
| **Startseite** | Mischung aus Videos ähnlich zu dem, was du geschaut hast, neuen Videos deiner Abos und Trends. Kategorien: Musik, Gaming, Live, Filme & Trailer, Podcasts. Shorts-Leiste. |
| **Shorts** | Vollbild zum Hochwischen, Endlosschleife, Mag ich, Kommentare, Teilen, Abonnieren |
| **Abos** | Kanäle abonnieren, Abo-Feed mit „Heute“, „Diese Woche“, „Nicht angesehen“. **Import aus Google Takeout** (ZIP/CSV) und NewPipe, Export als JSON |
| **Video-Seite** | Mini-Player (nach unten wischen), Vollbild beim Drehen, Doppeltippen ±10 s, Qualität (bis 4K), Geschwindigkeit, Wiederholen, Beschreibung mit klickbaren Zeitstempeln, Kommentare mit Antworten, ähnliche Videos, Autoplay |
| **Suche** | Vorschläge, Suchverlauf, Filter (Videos, Kanäle, Playlists), „Meintest du …“ |
| **Kanäle & Playlists** | Banner, Abonnieren, Tabs (Videos, Shorts, Live, Playlists), „Alle abspielen“, Zufallsmix |
| **Du** | Verlauf mit rotem Fortschrittsbalken („Weiter ansehen“), Später ansehen, Videos mit „Mag ich“ |
| **Google-Konto (freiwillig)** | Anmelden unter **Du → Mit Google anmelden**: deine echte YouTube-Startseite, Abos aus dem Konto, Verlauf / Später ansehen / „Mag ich“ von YouTube; Abonnieren, Liken und Speichern landen im Konto. Videos laufen trotzdem ohne Werbung. |
| **Links öffnen** | YouTube-Links aus anderen Apps über „Teilen → GX Tube“ öffnen (auch `youtu.be`, Shorts, Kanäle, Playlists, `?t=` Zeitstempel) |
| **Design** | Wie die YouTube-App, hell/dunkel (folgt dem System oder fest einstellbar) |

Ohne Anmeldung liegen Abos, Verlauf, Likes und Einstellungen nur auf dem Handy (`files/library.json`).
Mit Anmeldung speichert GX Tube nur die youtube.com-Cookies (`files/account.json`, nicht dein Passwort)
und schickt Abonnieren / „Mag ich“ / „Später ansehen“ zusätzlich an dein Konto.

## 🔁 YouTube-Abos übernehmen

1. Im Browser [takeout.google.com](https://takeout.google.com) öffnen.
2. **Alle abwählen**, dann nur **YouTube und YouTube Music** auswählen.
3. Bei „Alle YouTube-Daten enthalten“ nur **Abos** anhaken → Export erstellen → ZIP herunterladen.
4. In GX Tube: **Du → Abos importieren** → ZIP-Datei (oder `subscriptions.csv`) auswählen.

## ⚠️ Grenzen

- **Google-Anmeldung ist inoffiziell** (wie bei Grayjay oder Metrolist): Google erlaubt Apps von Dritten
  das eigentlich nicht. Sperren sind selten, aber möglich – wer sicher gehen will, nimmt ein Zweitkonto.
  Kommentieren, altersbeschränkte Videos und das Melden des Verlaufs an YouTube gehen (noch) nicht.
- YouTube ändert regelmäßig seine Schnittstellen. Wenn Videos plötzlich nicht mehr laden, hilft meist
  eine neuere Version von NewPipe Extractor (`gradle/libs.versions.toml` → `newpipeExtractor` auf den
  Stand von NewPipe setzen) – dann baut GitHub automatisch eine neue APK.
- Wenn YouTube zu viele Anfragen aus deinem Netz sieht, kommt „YouTube blockiert gerade anonyme
  Zugriffe“. Dann hilft warten oder zwischen WLAN und mobilen Daten wechseln.
- Die Trends-Seite gibt es bei YouTube seit Juli 2025 nicht mehr; die Kategorien kommen aus den
  Bereichs-Charts (Musik, Gaming, Filme, Podcasts, Live).

## 🛠️ Selbst bauen

Braucht JDK 17+ und das Android SDK (oder Android Studio):

```bash
git clone https://github.com/abilas-sivarajah/adblock-tube-gx
cd adblock-tube-gx
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # Unit-Tests
GXTUBE_ONLINE_TESTS=1 ./gradlew testDebugUnitTest --tests '*OnlineSmokeTest'   # prüft gegen echtes YouTube
```

**Signierung:** Ohne weitere Angaben wird mit `signing/gxtube.jks` signiert (liegt bewusst im Repo,
damit jede Version über die vorherige installiert werden kann). Für einen eigenen, geheimen Schlüssel
die Umgebungsvariablen `GXTUBE_KEYSTORE`, `GXTUBE_KEYSTORE_PASSWORD`, `GXTUBE_KEY_ALIAS`,
`GXTUBE_KEY_PASSWORD` setzen (z. B. als GitHub-Secrets im Workflow). Achtung: Ein Schlüsselwechsel
bedeutet einmal deinstallieren (vorher Abos exportieren).

## 🧩 Technik

| | |
|---|---|
| Oberfläche | Kotlin, Jetpack Compose, Material 3 (eigenes YouTube-Farbschema) |
| YouTube-Daten | NewPipe Extractor (Startseite, Suche, Videos, Kanäle, Kommentare, RSS-Feeds der Abos) |
| Player | Media3 ExoPlayer: Video- und Tonspur getrennt als DASH (aus NewPipe übernommen), zusammengeführt; Live-Streams per HLS |
| Hintergrund | Media3 `MediaSessionService` (Benachrichtigung, Sperrbildschirm, Kopfhörer-Tasten); im Hintergrund wird nur der Ton geladen |
| Bilder | Coil 3 |
| Werbung im Video | SponsorBlock-API (Abfrage über Hash-Präfix, die Video-ID bleibt privat) |

## 📁 Projektstruktur

```
app/src/main/java/de/abilas/gxtube/
├── GxTubeApp.kt / MainActivity.kt   # Start, Links aus anderen Apps, Bild-im-Bild, Vollbild-Drehung
├── data/
│   ├── YouTubeRepo.kt               # alle YouTube-Abfragen (NewPipe Extractor)
│   ├── Feeds.kt                     # Abo-Feed, Startseiten-Mix, Shorts
│   ├── Library.kt                   # Abos, Verlauf, Später ansehen, Likes, Einstellungen (lokal)
│   ├── Account.kt / Sync.kt         # freiwillige Google-Anmeldung, Abgleich mit dem Konto
│   ├── SponsorBlock.kt              # gesponserte Abschnitte
│   ├── Http.kt / Models.kt
├── player/
│   ├── PlayerController.kt          # ein Player für alles: Laden, Qualität, Autoplay, Fortschritt
│   ├── StreamResolver.kt            # Streams → ExoPlayer-Quelle (DASH, Video+Audio zusammenführen)
│   ├── YoutubeHttpDataSource.java   # aus NewPipe: passende Header/Parameter für YouTube-Streams
│   └── PlaybackService.kt           # Hintergrundwiedergabe + Benachrichtigung
└── ui/
    ├── App.kt                       # Navigation, untere Leiste
    ├── screens/                     # Startseite, Shorts, Abos, Du, Suche, Kanal, Playlist, Einstellungen
    ├── watch/                       # Video-Seite, Mini-Player, Bedienelemente, Kommentare
    └── components/                  # Video-Karten, Chips, Knöpfe …
```

## 📄 Lizenz

GPL-3.0 (wie NewPipe und NewPipe Extractor, von denen Teile übernommen sind).
GX Tube ist ein privates Projekt und steht in keiner Verbindung zu YouTube oder Google.
