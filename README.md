# Focus

Konzentrationstimer für Android. Dauer wird über einen Kreisring eingestellt (ein voller Ring =
eine Stunde, Granularität eine Minute), gestartet über den großen Play-Button. Am Ende erscheint
eine Benachrichtigung und der Alarmton des Geräts wird abgespielt.

## Bedienung

- **Ring ziehen** — Dauer in Minutenschritten einstellen, die Zeit steht in der Mitte des Rings.
  Über 12 Uhr hinaus klemmt der Ring bei 0 bzw. 60 Minuten, er springt nicht um.
- **Play-Button** — Timer starten. Während des Laufs zeigt die Mitte die Restzeit sekundengenau,
  das Kreissegment schrumpft mit. Derselbe Button bricht ab bzw. stoppt den Alarm.
- **Drei-Punkte-Menü** — Shortcuts für 1 Stunde, 45, 30, 20, 15, 10 und 5 Minuten.
- Während der Timer läuft, bleibt der Bildschirm an (`FLAG_KEEP_SCREEN_ON`, wirkt solange die App
  im Vordergrund ist).

## Technik

- Java, Android Views (kein Compose), Material 3, minSdk 26 / targetSdk 34.
- `TimerService` ist ein Foreground-Service mit Countdown-Benachrichtigung; ausgelöst wird der
  Alarm vom `AlarmManager` per `setAlarmClock`, damit er auch im Doze punktgenau feuert und selbst
  dann, wenn das System den Prozess zwischenzeitlich beendet hat.
- `TimerState` hält den Zustand in den SharedPreferences, damit Activity und Service ihn nach einem
  Neustart wiederfinden.
- `RingTimerView` ist die eigene View für Ring, Ticks, Griff und Zentrumstext.

## Download

Signierte APKs hängen an den [Releases](https://gitlab.noisebleed.de/pub/focus/-/releases). Der
Direktlink der jeweils aktuellen Version:

```
https://gitlab.noisebleed.de/api/v4/projects/51/packages/generic/focus/1.1/Focus-1.1.apk
```

Zum Installieren die Datei auf dem Gerät öffnen. Android fragt einmalig nach der Erlaubnis, Apps aus
dieser Quelle zu installieren; Play Protect zeigt bei selbst signierten Apps eine Warnung, die man
bestätigen kann. Voraussetzung ist Android 8 oder neuer.

## Bauen und installieren

Gebraucht wird ein **JDK 17**. Das in Android Studio mitgelieferte JBR ist inzwischen ein JDK 25,
das Gradle 8.7 mit `Unsupported class file major version 69` ablehnt — der frühere Pfad
`/opt/JetBrains/android-studio/jbr` funktioniert deshalb nicht mehr. Ein JDK 17 gibt es als Temurin
von [adoptium.net](https://adoptium.net), hier liegt es unter `~/.jdks/jdk-17.0.20.1+1`:

```bash
JAVA_HOME=~/.jdks/jdk-17.0.20.1+1 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release

Der Signaturschlüssel liegt **nicht im Repo** (das ist öffentlich), sondern in
`~/Documents/Android/own/focus/` — per Syncthing zwischen den eigenen Rechnern gespiegelt und im
Backup. `keystore.properties` daneben nennt Alias und Passwörter, `storeFile` wird relativ zu dieser
Datei aufgelöst, der Ordner darf also auf jedem Rechner anders heißen. Ein abweichender Pfad geht
über `-Pfocus.signing=…`; fehlt der Schlüssel ganz, bleibt das Release-APK unsigniert statt den Build
zu brechen.

Ohne diesen Schlüssel lässt sich kein Update über eine installierte Version schieben — Verlust
bedeutet, dass alle Nutzer erst deinstallieren müssten.

```bash
JAVA_HOME=~/.jdks/jdk-17.0.20.1+1 ./gradlew assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Anschließend Tag setzen, die APK in die Paket-Registry legen und das Release anlegen. Die Registry
statt der Projekt-Uploads, weil nur ihre URL bei öffentlichen Projekten ohne Anmeldung funktioniert
und einen Namespace-Wechsel übersteht:

```bash
git tag -a v1.1 -m "Focus 1.1" && git push origin v1.1
curl --request PUT --header "PRIVATE-TOKEN: $TOKEN" --upload-file Focus-1.1.apk \
  "https://gitlab.noisebleed.de/api/v4/projects/51/packages/generic/focus/1.1/Focus-1.1.apk"
glab release create v1.1 --name "Focus 1.1" --notes "…" \
  --assets-links "[{\"name\":\"Focus 1.1 (Android-APK)\",\"url\":\"…\",\"link_type\":\"package\"}]"
```

Achtung: `glab release create` mit angehängter Datei schreibt die Asset-URL mit dem Host
`gitlab.com` statt dem der eigenen Instanz — deshalb der Weg über `--assets-links` mit fertiger URL.

## Release über CI

`.gitlab-ci.yml` erledigt das alles selbst, sobald ein Tag der Form `v<Ziffer>` gepusht wird: bauen,
signieren, in die Paket-Registry laden, Release mit Asset-Link anlegen. Für Branches läuft die
Pipeline bewusst gar nicht.

Der übliche Ablauf ist damit:

```bash
# versionCode und versionName in app/build.gradle anheben, committen
git tag -a v1.2 -m "Focus 1.2" && git push origin main && git push origin v1.2
```

Zwei Prüfungen brechen den Job absichtlich ab, statt ein kaputtes Release zu veröffentlichen: das
Umbenennen der APK schlägt fehl, wenn mangels Schlüssel nur `app-release-unsigned.apk` entstanden
ist, und `versionName` im APK muss zum Tag passen — sonst zeigt der About-Dialog etwas anderes an,
als der Download heißt.

### Einmalige Einrichtung

**Runner mit Docker-Executor.** Das Job-Image ist auf `mobiledevops/android-sdk-image:34.0.0-jdk17`
gepinnt (JDK 17, `build-tools 34.0.0`, `android-34` — dieselbe Kombination wie lokal). Der Runner
braucht den Tag `docker`:

```bash
gitlab-runner register --non-interactive --url https://gitlab.noisebleed.de/ \
  --token <Runner-Authentifizierungstoken aus Settings → CI/CD → Runners> \
  --executor docker --docker-image mobiledevops/android-sdk-image:34.0.0-jdk17 \
  --tag-list docker
```

**Geschützte CI-Variablen** (Settings → CI/CD → Variables). Alle vier auf *protected*, damit sie nur
an geschützte Refs herausgegeben werden; das Tag-Muster `v*` ist bereits geschützt:

| Variable | Typ | maskiert | Inhalt |
|---|---|---|---|
| `ANDROID_KEYSTORE_B64` | File | nein | `base64 -w0 focus-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Variable | ja | `storePassword` |
| `ANDROID_KEY_ALIAS` | Variable | nein | `focus` |
| `ANDROID_KEY_PASSWORD` | Variable | ja | `keyPassword` |

Der Keystore liegt base64-kodiert in der Variablen, weil CI-Variablen Text sind. Damit existiert eine
zweite Kopie des Signaturschlüssels in GitLab — das ist der Preis dafür, dass die CI signieren kann.
Wer das nicht will, baut Releases weiter lokal; die Pipeline schlägt dann fehl, statt unsigniert
durchzulaufen.
