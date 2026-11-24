# DuoTune

Match the colours of the Retroid Pocket Duo's two screens, **without rooting your Duo**.

DuoTune uses the Duo's own built-in **Run script as root** option for the few steps that need system access.
No Magisk, no unlocked bootloader, and your system stays stock.

The Duo's top and bottom OLED panels often show a different white (for example a pinkish top and a bluish bottom).
Android's colour settings always change both screens together, so they can't fix that. DuoTune gives each screen
its own colour wheel, shows the result live, and saves it so it loads from the first frame after every boot and sleep.

> **Pre-release (0.7.0).** Tested on one Retroid Pocket Duo. If something looks off on yours, please
> [open an issue](../../issues) and include your firmware version (Settings → About handheld).

> DuoTune is an independent project. It is not affiliated with or endorsed by Retroid. Use it at your own risk.

## Requirements

- Retroid Pocket Duo with stock firmware (tested on the 14 November 2026 build)
- The Duo's built-in **Run script as root** option (Settings → Handheld settings → Advanced)

No computer, no Magisk and no unlocked bootloader needed.

## Install and use

Install the APK from the [Releases](../../releases) page and open DuoTune. It guides you through three steps:

1. **Connect:** open **Settings → Handheld settings → Advanced → Run script as root** and pick `duotune.sh` in Download. The wheels appear as soon as DuoTune is connected.
2. **Tune:** each screen shows its own wheel. Drag away from the tint you see: a blue screen toward yellow, a pink screen toward green. ☀ matches brightness. **Y** (Toggle white) turns both screens fully white to compare.
3. **Save:** tap **Save permanently**, then run `duotune.sh` once more the same way. The Duo reboots once to load your colours.

Next time you open DuoTune it shows your saved colours, with **Adjust again** and **Remove saved colours**.
Remove brings back the factory colours after one more run of `duotune.sh` and a reboot. The **?** in the corner shows all controls.

### Controller

| Control | Does |
| --- | --- |
| A / B | Next / back, as shown on screen |
| Y | Toggle white (both screens fully white), on the wheel screen and the Saved screen |
| L1 / R1 | Switch screen |
| Left stick | Move the dot (a light tilt moves it slowly) |
| D-pad | Nudge the dot one step |
| Right stick up/down, or L2 / R2 | Brightness |
| X | Reset the active screen |
| B | Leave white screens |

## How it works

Each screen has its own colour-correction stage (PCC) in the Duo's Qualcomm display hardware.

- **Live tuning:** `duotune.sh` starts a small helper (`pcctool`) through the Duo's own root option. It reads six numbers
  (red, green and blue for each screen) from a file only DuoTune can write, and applies them through the display service.
  It accepts nothing else, and stops when DuoTune closes or after 10 idle minutes.
- **Saving:** DuoTune reads the factory calibration files from `/vendor/etc/display`, writes your values into copies,
  and the helper places those copies in `/data/vendor/display`, where the display service looks first at boot.
- **The originals are never changed.** Removing the copies, or a factory reset, brings back the stock colours.

## Privacy and safety

- No permissions, no internet access, no background service. The app itself has no root: it only gets system access
  for a moment when *you* run `duotune.sh` through Retroid's option.
- `duotune.sh` is a single line that starts the helper from DuoTune's install folder. You can read it yourself.
- **No free-form root script.** The helper only knows three fixed actions: start live tuning, save, and remove.
  The app only chooses which one. Save only accepts files named `qdcm_calib_data_*.json` and only writes to
  `/data/vendor/display`; remove only deletes those files.
- **Uninstalling:** the old `duotune.sh` then points at a folder that no longer exists and does nothing. You can delete it from Download.
- **After a Retroid system update:** open DuoTune, tap **Remove saved colours**, and save again, so your colours are based on the new factory calibration.
- Only download DuoTune from this repository's [Releases](../../releases) page.

## Verify your download

Only download DuoTune from this repository's [Releases](../../releases) page. Every release is signed with the same key.
To check an APK before installing it (needs the Android SDK build-tools):

```
apksigner verify --print-certs DuoTune-0.7.0.apk
```

The certificate must be `CN=NouraWhyNot` with this SHA-256 digest:

```
04c3895982c3377d27f47f2786458ab7696b01997b5013065f3e3883cc6d5437
```

## Building

Open the project in Android Studio (it creates the Gradle wrapper if needed) and build the `release` variant.

**Signing:** copy `keystore.properties.example` to `keystore.properties` and fill in your own key. That file and any
`.jks` file are git-ignored; never commit them.

**Native helper:** `app/src/main/jniLibs/arm64-v8a/libpcctool.so` is built from `native/pcctool.c`. To rebuild it:

```
ANDROID_NDK_HOME=/path/to/ndk ./native/build.sh
```

## Licence

MIT. See [LICENSE](LICENSE).
