# Zamer

[Русская версия](README.ru.md)

Zamer is an open-source Android app for the HOTO Smart Laser Measure QWCJY001 (JY001). It pairs with the rangefinder over Bluetooth and receives every reading you take. It then works out area, volume and height from those readings. It does not need Mi Home, a Xiaomi account or an internet connection.

## Features

- Distance, area, volume and height modes. Switch modes with the buttons or by swiping left and right.
- Each reading fills the next side. Tap a side to measure it again.
- Height mode finds a vertical distance from two readings: one along the floor and one slanted to the target.
- Each mode keeps its own sides, even after the app restarts.
- Completed results go to the history. Give a result a name, such as "Kitchen floor", and search the history by name.
- Every raw reading goes to a log. Tap a recent reading to reuse it for a side.
- Metric or imperial units, 1 to 3 decimal places, light, dark or system theme, and Material You colours.

## Requirements

- Android 12 or later with Bluetooth LE.
- A HOTO QWCJY001 rangefinder. Other HOTO and Xiaomi devices are not tested.

## Pairing

1. Turn on the rangefinder.
2. Open Zamer and allow the Bluetooth permission. Zamer does not ask for location access.
3. Select the rangefinder in the list.

The rangefinder accepts one paired app at a time. If you pair it with another app, Zamer shows "Pair again". Tap **Pair** to pair it with Zamer again. Your history stays.

## Build

You need JDK 17 and the Android SDK (compile SDK 36).

```bash
./gradlew testDebugUnitTest assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

| Path | Contents |
|---|---|
| `app/src/main/java/dev/bezh/zamer/ble/` | Scanning, the GATT connection, Xiaomi auth and AES-CCM |
| `app/src/main/java/dev/bezh/zamer/domain/` | Modes, calculations and number formatting |
| `app/src/main/java/dev/bezh/zamer/data/` | Settings, pairing and history storage (DataStore) |
| `app/src/main/java/dev/bezh/zamer/ui/` | Compose screens (Material 3 Expressive) |
| `docs/PROTOCOL.md` | The rangefinder's Bluetooth protocol |

The unit tests check the crypto against reference vectors and replay a recorded login and real measurements. The recorded pairing token in the tests is no longer valid on any device.

## Disclaimer

Zamer is an independent project. HOTO and Xiaomi do not make, endorse or support it. "HOTO", "Xiaomi" and "Mi" are trademarks of their owners.

## License

Zamer is licensed under the [Apache License 2.0](LICENSE).
