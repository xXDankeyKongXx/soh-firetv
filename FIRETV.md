# Ship of Harkinian: Fire TV build

Tuned for the **Insignia F50 4K Fire TV (NS-43F501NA26, Fire OS 8.1 / Android 11)** and similar 2 GB Fire TV
smart TVs and sticks. It works on any Android-based Fire TV (Fire OS 7 or 8). Vega OS sticks can't sideload, so
they aren't supported.

## Tuned for the Insignia F50

| On this TV | What the build does |
|---|---|
| A 32-bit OS, like every Fire TV smart TV | Builds `armeabi-v7a`. The original APK was 64-bit only and can't install |
| Fire OS 8.1 has no "All files access" screen | Targets SDK 29 with legacy storage, so the normal permission prompt works |
| A budget MediaTek chip with about 2 GB of RAM | RAM is checked at launch. At 2.5 GB or less, the game renders to a 1280×720 surface and the TV's scaler upscales it to the 4K panel at no GPU cost. Larger devices get 1080p |
| A Mali-class GPU | The first-run preset uses hardware bilinear filtering instead of the shader-based 3-point filter (one texture read instead of three), with MSAA off and 20 fps logic (stock) |
| A built-in 60 Hz panel with picture processing | Requests the TV's low-latency game picture mode (`preferMinimalPostProcessing`) |
| A 43" screen viewed from the couch | The Enhancements menu is set to 1.5× scale, with remote and gamepad navigation on |
| Often no gamepad | The remote can play the whole game (see below) |
| No touchscreen | The touch overlay is hidden |

The preset is written to `SOH/shipofharkinian.json` on first run. It only fills in settings you haven't changed.
To reset them, launch once with `--ez applyTvPreset true`. If the game runs smoothly, try **Settings → Graphics**:
set Interpolation FPS to 30 or 60, or switch the texture filter back to 3-point for N64-accurate textures.

## Playing with the remote

| Remote | Game |
|---|---|
| D-pad | Move. It also navigates the Enhancements menu |
| Select (center) | A |
| Back | B |
| Play/Pause | Start |
| Rewind | Z (hold to target) |
| Fast-forward | R (shield) |
| Menu ≡, tap then D-pad | C-up, C-left, C-down or C-right. A yellow **C** badge shows while it's armed |
| Menu ≡, hold | Opens or closes the Enhancements menu |

Home still exits. A Bluetooth gamepad works at the same time, and both control player 1, so pair one for
combat-heavy sections. The remote can only press one direction at a time, so movement is 4-way. Z-targeting helps.

## Enhancements menu with a controller or remote

The menu now opens with the cursor already on it, and you can move across the tabs, the sidebar and the settings
columns in one continuous grid. Before, a controller couldn't get from one panel to the next.

| Action | Gamepad | Remote |
|---|---|---|
| Open or close the menu | Select/Back | Hold Menu ≡ (tap Menu ≡ also closes it) |
| Move the cursor | D-pad | D-pad |
| Choose or change a setting | A | Select |
| Back out (closes the menu when there's nothing to back out of) | B | Back |
| Next or previous top tab | RB / LB | Play/Pause (next) |
| Next or previous sidebar section | RT / LT | Fast-forward / Rewind |
| Adjust a slider | Select it with A, then D-pad left or right | Same, with Select |

A hint bar at the bottom of the menu shows these buttons. The game ignores controller input while the menu is open.
The remote and a Bluetooth gamepad can both drive the menu at the same time. Before, the menu followed only one
controller.

## Voice (Alexa)

Fire TV doesn't let apps add their own Alexa phrases. It does send Alexa's media commands to the app on screen,
so those control the menu. Press the remote's voice button and say:

| Say | Does |
|---|---|
| "Alexa, pause" (or "stop") | Opens the Enhancements menu |
| "Alexa, play" (or "resume") | Closes it |
| "Alexa, next" / "Alexa, previous" | Next or previous menu tab (opens the menu first) |
| "Alexa, fast forward" / "Alexa, rewind" | Next or previous sidebar section |
| "Alexa, move down" / "Alexa, select" | Moves the cursor and chooses. This is Fire TV's own voice D-pad, where Amazon enables it |

**To say "Alexa, open enhancement menu":** in the Alexa phone app, go to **More → Routines → +**.
- **When this happens:** Voice → `open enhancement menu`
- **Add action:** Fire TV (or Devices → this TV) → **Pause**. If your app doesn't list Pause, use
  **Customized** and type `pause`.
- **From:** Brett's Fire TV

Saying the phrase then runs Pause on the TV, which opens the menu. You can add `close enhancement menu` → Play the same way.

Testing without speaking (the app logs each command it receives):
```
adb shell cmd media_session dispatch pause    # or: play, next, previous, fast-forward, rewind
adb logcat -s SoH-Voice
```

## Other fixes

- **Fire OS 7 setup:** the app never continued after the storage permission prompt, leaving a black screen. Fixed.
- **ROM selection:** there's no system file picker, so this is now a remote-friendly list of ROMs found in SOH,
  Download, ROMs and roms/n64, and on USB drives. Cancelling never leaves the game hanging.
- **Launcher:** the app appears in the TV launcher with a banner.

## Building

The patch applies to `Waterdish/Shipwright-Android` at tag `v9.0.2P2`.

**With GitHub Actions (no local Android SDK needed):** fork the repo, then create a branch from `v9.0.2P2`.
Run `git apply soh-firetv.patch`, commit and push. Then go to **Actions → Project Build → Run workflow** and
download the APK from `build-artifacts`.

**Locally:**
```
git clone --recursive -b v9.0.2P2 https://github.com/Waterdish/Shipwright-Android
cd Shipwright-Android && git apply ../soh-firetv.patch
cd docker && make setup && make build_release
```

**Installing on the TV:** enable **Settings → My Fire TV → Developer options → ADB debugging** and
**Install unknown apps**. Then:
```
adb connect <tv-ip>:5555
adb install soh_release_v9.0.2-firetv.apk
```
Put the ROM in the TV's Download folder, or on a USB drive in the TV's USB port. The ROM list finds it there.

**Checking the hardware (optional):**
```
adb shell getprop ro.board.platform        # chip
adb shell cat /proc/meminfo | head -1      # RAM
adb shell dumpsys SurfaceFlinger | grep GLES   # GPU
adb logcat -s SoH                          # shows the render size and preset the app picked
```

## Launching from a front end

The activity is `com.dishii.soh/.MainActivity`. All extras are optional.

| Extra | Type | Meaning |
|---|---|---|
| `rom` | String | Absolute path to the ROM, used on first setup |
| `resolution` | int | Render height: `480`, `720` or `1080`, or `0` for native. It's remembered |
| `remoteControls` | boolean | Remote play on or off (on by default for TVs). It's remembered |
| `touchControls` | boolean | Touch overlay on or off. It's remembered |
| `applyTvPreset` | boolean | Re-applies the TV graphics preset over your current settings |
| `menu` | String | `open`, `close` or `toggle` the Enhancements menu. It also works while the game is running |

A `file://` or `content://` data URI also works as the ROM.
```
adb shell am start -n com.dishii.soh/.MainActivity --es rom /sdcard/Download/oot.z64
```

## Known limits

- The 32-bit native build is the untested part. Upstream already has 32-bit fixes and builds `armeabi-v7a` in debug, but memory on a 2 GB TV needs a real test. If it crashes in-game, try `--ei resolution 480` and keep HD texture packs out of `SOH/mods`.
- If you turn on **Settings → Menu → Search autofocus**, the search box keeps grabbing input and the LB/RB, LT/RT
  and B shortcuts stop working. Leave it off on a TV.
- Alexa routines run Fire TV actions on a best-effort basis. If the Pause action doesn't reach the game on your TV,
  saying "Alexa, pause" directly still works.
- If storage setup still fails, run `adb shell appops set com.dishii.soh MANAGE_EXTERNAL_STORAGE allow`.
