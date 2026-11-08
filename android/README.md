# Pokémon Yellow for Google TV

An Android TV / Google TV app that runs the `pokeyellow.gbc` built from this
repository. It contains a small Game Boy Color emulator written in plain Java
(`core/`) and a TV front end (`app/`). It has no third-party dependencies.

## Getting the APK

**From CI:** every push runs the *Google TV APK* workflow
(`.github/workflows/android-tv.yml`). It builds the ROM, runs the emulator
tests and uploads `app-release.apk` as a workflow artifact.

**Locally:**

```sh
make                         # in the repository root: builds pokeyellow.gbc
cd android
gradle :app:assembleRelease  # needs JDK 17+ and the Android SDK (ANDROID_HOME)
```

The ROM is copied into the APK at build time. If it's missing, the app starts
anyway and lets you pick a `.gbc` file, or loads one copied to
`/sdcard/Android/data/com.pokeyellow.tv/files/pokeyellow.gbc`.

## Installing on Google TV

1. On the TV: *Settings → System → About*, click *Android TV OS build* 7 times
   to unlock developer options, then enable *USB debugging* / *Network debugging*
   under *Settings → System → Developer options*.
2. From a computer on the same network:
   ```sh
   adb connect <tv-ip-address>
   adb install -r app-release.apk
   ```
   (Or sideload with an app such as *Send Files to TV* / *Downloader*.)

The game shows up in the Apps row with a yellow banner.

## Controls

| Input                  | Game Boy                          |
|------------------------|-----------------------------------|
| D-pad                  | D-pad                             |
| OK / Select            | A                                 |
| Back (tap)             | B                                 |
| Back (hold)            | START                             |
| OK (hold ~1 s)         | Menu: START, SELECT, fast-forward, exit |
| Play/Pause, Menu       | START                             |
| Gamepad A / B          | A / B                             |
| Gamepad Start / Select | START / SELECT                    |
| Gamepad stick or hat   | D-pad                             |
| Gamepad R1 / R2 (hold) | Fast-forward                      |
| Gamepad L1 / Mode      | Menu                              |
| Keyboard               | Arrows/WASD, X = A, Z = B, Space = START, Tab = SELECT |

## Saving

Saving in-game (*SAVE* in the START menu) writes cartridge RAM to the app's
private storage. It is written every couple of seconds and whenever you leave
the app. It is also included in Android's automatic backup.

## Emulator notes

- Supports the CGB features Yellow uses: colour palettes, VRAM/WRAM banking,
  HDMA, double speed, and MBC5 (also MBC1/MBC3 without a real-time clock).
- Each scanline is rendered in one pass at HBlank, and the CPU is timed per
  instruction. That's plenty for Yellow but not cycle-accurate.
- The audio mixer box-filters each output sample, so Pikachu's sampled voice
  clips (which toggle the wave channel's volume) still come through.
- Link cable features (trading, battles) aren't supported.
