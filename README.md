# Focus

Konzentrationstimer für Android. Dauer wird über einen Kreisring eingestellt (ein voller Ring =
eine Stunde, Granularität eine Minute), gestartet über den großen Play-Button. Am Ende erscheint
eine Benachrichtigung und der Alarmton des Geräts wird abgespielt.

Eine bebilderte Kurzbeschreibung steht in [`docs/README.md`](docs/README.md).

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

Signierte APKs hängen an den [Releases](https://github.com/hansmaulwurf23/focus/releases), das
neueste unter [releases/latest](https://github.com/hansmaulwurf23/focus/releases/latest).

Zum Installieren die Datei auf dem Gerät öffnen. Android fragt einmalig nach der Erlaubnis, Apps aus
dieser Quelle zu installieren; Play Protect zeigt bei selbst signierten Apps eine Warnung, die man
bestätigen kann. Voraussetzung ist Android 8 oder neuer. Eine neuere Version wird einfach über die
installierte installiert.

Bis Version 1.1 lag das Projekt auf gitlab.noisebleed.de (`pub/focus`); das Projekt dort ist
archiviert, die alten Releases sind hierher übernommen.

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

Signaturschlüssel und `keystore.properties` liegen **nicht im Repo**, sondern in
`~/Documents/Android/own/focus/` (per Syncthing gespiegelt und im Backup); `storeFile` wird relativ
zur Properties-Datei aufgelöst. Ein abweichender Pfad geht über `-Pfocus.signing=…`; fehlt der
Schlüssel ganz, bleibt das Release-APK unsigniert statt den Build zu brechen.

Ohne diesen Schlüssel lässt sich kein Update über eine installierte Version schieben — Verlust
bedeutet, dass alle Nutzer erst deinstallieren müssten.

Lokal bauen und prüfen:

```bash
JAVA_HOME=~/.jdks/jdk-17.0.20.1+1 ./gradlew assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

`.github/workflows/release.yml` testet, baut, signiert und veröffentlicht, sobald ein Tag `v<Ziffer>`
gepusht wird. Der Text des annotierten Tags (ohne die erste Zeile) wird zur Release-Beschreibung.
Zwei Prüfungen brechen absichtlich ab, statt ein kaputtes Release zu veröffentlichen: ein
unsigniertes APK und ein `versionName`, der nicht zum Tag passt.

Dafür braucht es die Repository-Secrets `ANDROID_KEYSTORE_B64` (`base64 -w0 focus-release.jks`),
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`focus`) und `ANDROID_KEY_PASSWORD`, gesetzt mit
`~/ops-scripts/android-github-secrets.sh focus`; damit liegt eine zweite Kopie des Schlüssels bei
GitHub.

```bash
# versionCode und versionName in app/build.gradle anheben, committen
git tag -a v1.2 -m "Focus 1.2" -m "- Was sich geändert hat" && git push origin main v1.2
```

## Lizenz

Der Code steht unter der [MIT-Lizenz](LICENSE).
