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

## Bauen und installieren

```bash
JAVA_HOME=/opt/JetBrains/android-studio/jbr ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
