# pocketdash

An always-on dashboard for a rooted Android phone that doubles as a small home server. Plain Java, no Gradle, no third-party UI libraries.

## What it does

- Flip clock with weather (Open-Meteo, location from IP), auto-dim from the light sensor, and an optional face-presence wake (TensorFlow Lite, on-device).
- Wi-Fi hotspot card: 2.4/5 GHz band switch, connected clients with hostnames and nicknames, throughput graph.
- Per-device control: block, bandwidth limit, daily usage, new-device notifications.
- Services card: live status and restart for the bundled engine (Samba, SSH, Syncthing, Transmission, nginx proxy, WireGuard, SMS bridge).
- Battery protection presets (via [acc](https://github.com/VR-25/acc)) with heat and power-loss notifications.
- Bluetooth speaker (A2DP sink) mode.
- Settings screen that edits the engine's `config.sh`, with backup/restore and a first-run setup flow.
- Drag-to-reorder panels, foreground service that relaunches the app and locks the screen after idle.

## Requirements

- Android 8+ (minSdk 26), **rooted with Magisk**. The app grants itself nothing; root is used for hotspot, firewall, battery and service control.
- Nothing is configured for you: on first run Settings asks for your own SSID, passwords and share paths and writes `config.sh` on the device. No credentials are in this repo.

## Build

Needs JDK 17 and the Android SDK (build-tools 34.0.0, platform android-34) in `~/tools/jdk17` and `~/tools/sdk`.

```sh
./scripts/fetch-deps.sh   # TensorFlow Lite runtime into ~/tools/tflite
./build.sh                # produces dashboard.apk, signed with a local debug key
adb install -r dashboard.apk
```

`assets/engine/prefix.tar.gz` (the ~200 MB Termux toolset used by the engine) is not in the repo. It is only needed on a phone that has never had the toolset; without it the build still works, but the engine cannot be bootstrapped from the APK.

## Layout

- `src/com/tonybenoy/dashboard/`: the app (`MainActivity`, `KeepAliveService`, `BootReceiver`, `ConfigStore`, `Engine`, ...).
- `assets/probe.sh`: long-lived root probe that feeds the UI.
- `assets/engine/`: shell scripts for the hotspot, NAT, device control, battery and services, deployed to `/data/adb/dashboard/engine` on the phone.

## Caution

This runs shell scripts as root and changes firewall and hotspot settings. Read the code before installing it, and use it at your own risk.
