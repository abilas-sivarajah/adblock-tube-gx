# 📱 GX Tube – YouTube und Twitch ohne Werbung (Android)

Android-App, die aussieht und sich bedient wie die YouTube-App – nur **ohne Werbung**.
Gleiche Idee wie der Desktop-Browser [AdBlock Browser GX](https://github.com/abilas-sivarajah/adblock-browser-gx)
und die Erweiterung [AdBlock GX](https://github.com/abilas-sivarajah/adblock-gx-extension), diesmal fürs Handy.

Statt die YouTube-Seite zu laden und Werbung herauszufiltern, holt GX Tube die Videos **direkt**
über [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (dieselbe Technik wie NewPipe).
Die Werbedaten von YouTube werden gar nicht erst geladen – es gibt also auch keine Wartezeit
anstelle der Werbung, wie sie im Browser vorkommen kann.

Oben neben dem Logo schaltest du auf **Twitch** um: Live-Streams, vergangene Streams (VODs), Kategorien
und Chat – die Werbung entfernt der Twitch-Werbeblocker aus [AdBlock GX](https://github.com/abilas-sivarajah/adblock-gx-extension).

---

## 📲 Installieren

1. Auf dem Handy **[Releases → neueste Version](https://github.com/abilas-sivarajah/adblock-tube-gx/releases/latest)** öffnen.
2. `GXTube-….apk` herunterladen und öffnen.
3. Android fragt beim ersten Mal, ob der Browser Apps installieren darf → **erlauben**.

Jeder Push auf `main` baut automatisch eine neue Version (GitHub Actions).

**Updates:** Ab Version 0.1.5 sucht GX Tube beim Öffnen selbst nach einer neuen Version (höchstens alle
15 Minuten) und installiert sie mit einem Tipp – ohne Browser. Beim ersten Mal einmalig erlauben, dass
GX Tube Apps installieren darf. „Später“ fragt erst nach 12 Stunden wieder. Abschaltbar und manuell
auslösbar unter Einstellungen → Über. Abos, Verlauf und Einstellungen bleiben erhalten (alle Versionen
sind mit demselben Schlüssel signiert, siehe unten).

Voraussetzung: Android 8.0 oder neuer.

## 🌟 Funktionen

| | |
|---|---|
| **Keine Werbung** | Keine Videowerbung, keine Banner, keine „Werbeblocker“-Hinweise |
| **SponsorBlock** | Gesponserte Abschnitte *im* Video („Dieses Video wird präsentiert von …“) werden automatisch übersprungen, auf dem Fortschrittsbalken grün markiert. Kategorien einstellbar. |
| **Hintergrundwiedergabe** | Ton läuft weiter bei gesperrtem Bildschirm oder in anderen Apps, mit Steuerung in Benachrichtigung und Sperrbildschirm (bei YouTube nur mit Premium) |
| **Bild-im-Bild** | Video läuft im kleinen Fenster weiter, wenn du zum Startbildschirm wechselst |
| **Startseite** | Mischung aus Videos ähnlich zu dem, was du geschaut hast, neuen Videos deiner Abos und Trends. Kategorien: Musik, Gaming, Live, Filme & Trailer, Podcasts. Shorts-Leiste. |
| **Shorts** | Vollbild zum Hochwischen, Endlosschleife, Mag ich, Kommentare, Teilen, Abonnieren – oder in den Einstellungen **komplett ausschalten** (Tab, Startseite, Abos, Suche, Kanäle, ähnliche Videos) |
| **Abos** | Kanäle abonnieren, Abo-Feed mit „Heute“, „Diese Woche“, „Nicht angesehen“. **Import aus Google Takeout** (ZIP/CSV) und NewPipe, Export als JSON |
| **Video-Seite** | Mini-Player (nach unten wischen), Vollbild beim Drehen, Doppeltippen ±10 s, Qualität (bis 4K), Geschwindigkeit, Wiederholen, Beschreibung mit klickbaren Zeitstempeln, Kommentare mit Antworten, ähnliche Videos, Autoplay |
| **Suche** | Vorschläge, Suchverlauf, Filter (Videos, Kanäle, Playlists), „Meintest du …“ |
| **Kanäle & Playlists** | Banner, Abonnieren, Tabs (Videos, Shorts, Live, Playlists), „Alle abspielen“, Zufallsmix |
| **Du** | Verlauf mit rotem Fortschrittsbalken („Weiter ansehen“), Später ansehen, Videos mit „Mag ich“ |
| **Google-Konto (freiwillig)** | Anmelden unter **Du → Mit Google anmelden**: deine echte YouTube-Startseite, Abos aus dem Konto, Verlauf / Später ansehen / „Mag ich“ von YouTube; Abonnieren, Liken und Speichern landen im Konto. Videos laufen trotzdem ohne Werbung. |
| **Auf PC streamen** | Wie „Auf dem Fernseher abspielen“ bei YouTube: oben auf das Streamen-Symbol tippen, PC auswählen – Videos laufen dann werbefrei auf dem PC mit [GX Tube Desktop](https://github.com/abilas-sivarajah/adblock-tube-gx-desktop). Das Handy wird zur Fernbedienung (Pause, Springen, Weiter, Lautstärke über die Lautstärketasten, Warteschlange „Als Nächstes abspielen“), auch aus der Benachrichtigung und dem Sperrbildschirm. Siehe unten. |
| **Twitch** | Umschalter oben (YouTube \| Twitch): Startseite mit gefolgten Live-Kanälen, beliebten Streams (Deutsch/Englisch/alle) und Kategorien, Tab **„Folge ich“**, Suche nach Kanälen und Kategorien, Kanalseiten mit VODs, **Chat** unter Live-Streams (mit 7TV-, BTTV- und FFZ-Emotes), Qualität wählbar, **ohne Werbung** (siehe unten), auch **auf den PC streamen** |
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

## 🖥️ Auf den PC streamen

1. Auf dem PC **[GX Tube Desktop](https://github.com/abilas-sivarajah/adblock-tube-gx-desktop/releases/latest)** installieren und starten
   (Windows, Linux, macOS). Unter Windows beim ersten Start den Zugriff für **private Netzwerke** erlauben.
2. Handy und PC ins selbe WLAN.
3. In GX Tube oben (oder im Player) auf das **Streamen-Symbol** tippen und den PC auswählen.
   Wird er nicht gefunden: **„Per Adresse verbinden“** und die Adresse eingeben, die GX Tube Desktop anzeigt.
4. Ab jetzt läuft jedes Video, das du antippst, auf dem PC. Läuft beim Verbinden schon ein Video auf dem Handy,
   geht es auf dem PC an derselben Stelle weiter.

Im Video-Menü (⋮) gibt es beim Streamen **„Als Nächstes abspielen“** und **„Zur Warteschlange hinzufügen“**.
Über das Streamen-Symbol: Lautstärke am PC, **„Auf dem Handy weiterschauen“** (hält am PC an und macht hier weiter)
oder **„Verbindung trennen“** (das Video läuft am PC weiter). Shorts laufen immer auf dem Handy.
Wurde die App beim Streamen geschlossen, verbindet sie sich beim nächsten Öffnen von selbst wieder (innerhalb von 12 Stunden).

Technisch: Suche per UDP-Broadcast (Port 8766), Steuerung per WebSocket (Port 8765), nur im lokalen Netz –
Protokoll: [PROTOCOL.md](https://github.com/abilas-sivarajah/adblock-tube-gx-desktop/blob/main/PROTOCOL.md).

## 🟣 Twitch

- **Werbung:** Twitch schneidet die Werbung in den Stream. Der Werbeblocker aus AdBlock GX (hier in Kotlin für
  ExoPlayer) holt während einer Werbepause dieselbe Sendung über eine zweite, werbefreie Twitch-Sitzung –
  kurz in 360p, dann wieder in voller Qualität – und kehrt danach zum eigenen Stream zurück. Findet er keine,
  läuft für die Dauer der Werbung ein schwarzes Bild („Werbepause – GX Tube blendet sie aus“).
  Werbung wird nie als „gesehen“ gemeldet.
- **Chat:** Mitlesen geht ohne Konto. Zum **Schreiben** unter Einstellungen → Twitch (oder direkt im Chat)
  **bei Twitch anmelden** – wie bei Twitch auf dem Fernseher: „Hier in der App anmelden“ (der Code ist schon
  eingetragen) oder auf einem anderen Gerät `twitch.tv/activate` öffnen und den Code eingeben.
  GX Tube bekommt dein Passwort nie; das Token bleibt nur auf dem Handy (`files/twitch-account.json`).
- **Folgen** speichert den Kanal auf dem Handy, angemeldet auch in deinem Twitch-Konto; deine gefolgten
  Kanäle aus dem Konto kommen beim Anmelden dazu.
- **Auf den PC streamen** geht wie bei YouTube (GX Tube Desktop ab 0.1.18) – der Chat bleibt auf dem Handy.
- Twitch-Links lassen sich über „Teilen → GX Tube“ öffnen (`twitch.tv/<kanal>`, `twitch.tv/videos/<id>`).

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
GXTUBE_ONLINE_TESTS=1 ./gradlew testDebugUnitTest --tests '*TwitchAdBlockTest'  # Werbeblocker mit echtem Twitch-Stream
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
| Twitch | GraphQL-Abfragen wie twitch.tv (ohne Konto mit der Kennung der Fernseher-App), HLS über `usher.ttvnw.net`. Werbeblocker `twitch/TwitchAdBlock.kt`: Kotlin-Fassung von `twitch_worker.js` aus AdBlock GX (Techniken nach [TTV-AB](https://github.com/GosuDRM/TTV-AB), MIT) – eine ExoPlayer-Datenquelle schreibt die Playlists um. Chat über IRC-WebSocket, Anmeldung per OAuth-Geräteanmeldung (twitch.tv/activate) |

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
├── twitch/                          # Twitch: Abfragen, Werbeblocker (ExoPlayer-Datenquelle), Chat, Konto, Folgen
├── cast/                            # Auf den PC streamen (GX Tube Desktop)
│   ├── Cast.kt                      # Verbindung (WebSocket), Zustand und Warteschlange vom PC, Bedienung
│   ├── CastDiscovery.kt             # PCs im WLAN finden (UDP-Broadcast)
│   ├── CastProtocol.kt              # Nachrichten-Format
│   └── CastPlayer.kt                # Benachrichtigung/Sperrbildschirm + Lautstärketasten steuern den PC
├── player/
│   ├── PlayerController.kt          # ein Player für alles: Laden, Qualität, Autoplay, Fortschritt, Streamen
│   ├── StreamResolver.kt            # Streams → ExoPlayer-Quelle (DASH, Video+Audio zusammenführen)
│   ├── YoutubeHttpDataSource.java   # aus NewPipe: passende Header/Parameter für YouTube-Streams
│   └── PlaybackService.kt           # Hintergrundwiedergabe + Benachrichtigung
└── ui/
    ├── App.kt                       # Navigation, untere Leiste
    ├── screens/                     # Startseite, Shorts, Abos, Du, Suche, Kanal, Playlist, Einstellungen
    ├── watch/                       # Video-Seite, Mini-Player, Bedienelemente, Kommentare
    ├── twitch/                      # Twitch-Startseite, Folge ich, Suche, Kategorie, Kanal, Chat, Anmeldung
    ├── cast/                        # Streamen-Symbol, Geräteauswahl, Anzeige "Wird auf … abgespielt"
    └── components/                  # Video-Karten, Chips, Knöpfe …
```

## 📄 Lizenz

GPL-3.0 (wie NewPipe und NewPipe Extractor, von denen Teile übernommen sind).
GX Tube ist ein privates Projekt und steht in keiner Verbindung zu YouTube, Google oder Twitch.
