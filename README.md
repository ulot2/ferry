# Ferry

Ferry moves clipboard text between a Windows laptop and an Android phone.

When you copy text on the laptop, the text goes into the clipboard of the phone within a second or two. When you copy text on the phone, one tap sends it to the laptop. You do not need an account. The two devices pair when the phone scans a QR code.

## How it works

The laptop and the phone do not connect to each other directly. Both connect to [ntfy.sh](https://ntfy.sh), a free public relay (a server that passes messages on). Pairing gives both devices the same secret topic (a private channel name on ntfy.sh). Each device sends clipboard text to that topic and receives the text from the other device.

```
Laptop (Ferry.exe)  ⇄  ntfy.sh, secret topic  ⇄  Phone (Ferry app)
```

Laptop to phone is automatic. Phone to laptop takes one tap, because Android does not let apps read the clipboard in the background.

## Install

Download both files from the latest release on the [Releases page](../../releases/latest).

### On the laptop

1. Make sure that the [.NET Desktop Runtime 10](https://dotnet.microsoft.com/download/dotnet/10.0) is installed.
2. Download `Ferry.exe` and put it in a permanent folder, for example `%LOCALAPPDATA%\Programs\Ferry`.
3. Open `Ferry.exe`. If Windows shows "Windows protected your PC", click **More info**, then **Run anyway**.

Ferry opens a window with a QR code. It also adds an icon to the system tray (the icons near the clock) and starts with Windows.

### On the phone

1. Download `Ferry.apk` on the phone and open it.
2. If Android asks, allow installs from that app.
3. Open Ferry.

## Pair the devices

1. On the phone, tap **Scan QR code**.
2. Point the camera at the QR code in the Ferry window on the laptop.

The laptop shows "Phone paired". The two devices now share a secret topic.

If the scanner does not open, tap **Type the code** on the phone. Then type the code that is under the QR code on the laptop.

After pairing, do these steps on the phone:

1. Tap **Allow background use**. Without it, Android cuts the connection when the phone sleeps.
2. On a Xiaomi, Redmi or POCO phone, open **Settings > Apps > Ferry** and turn on **Autostart**.
3. Optional: add the **Send clipboard** tile to the quick settings panel.

## Use Ferry

| To send | Do this |
|---|---|
| Laptop to phone | Copy text on the laptop. It goes into the phone clipboard. |
| Phone to laptop | Copy text on the phone. Then tap **Send clipboard** in the Ferry notification or in the quick settings tile. |
| Selected text on the phone | Select the text and choose **Send to laptop** in the menu. |
| Text from another phone app | Tap **Share** and choose **Send to laptop**. |

Both apps show the last crossing (one transfer): its direction, its time, and the first words of the text.

The status light shows the connection:

- Green: connected.
- Red: offline. Ferry tries again every 5 seconds.
- Gray: connecting or paused. On the phone, gray also means "not paired".

To stop syncing for a while on the laptop, right-click the tray icon and choose **Pause syncing**.

## Privacy and security

- The topic is the only secret. A person who knows the topic can read your clipboard text and send text to it. Do not share the code under the QR code.
- If the topic leaks, click **Reset pairing** in the laptop window. Then scan the new code with the phone.
- Laptop copies pass through ntfy.sh, and ntfy.sh does not store them.
- Phone copies stay on ntfy.sh for up to 12 hours. The laptop uses them to catch up after a short disconnection.
- Ferry does not encrypt the text itself. ntfy.sh can read the text that passes through it.
- On the laptop, Ferry does not send copies from password managers that mark their copies as private.

## Limits

- Ferry moves text only. It skips images and files.
- ntfy.sh limits one message to 4,096 bytes. Ferry sends longer text as a text file, and the other device reads the file.
- Phone to laptop always takes one tap.
- The QR scanner needs Google Play services. On a phone without them, type the code.

## Troubleshooting

**The light is red.** Make sure that the device is online. Ferry connects again by itself.

**Laptop copies stop arriving on the phone.** Open Ferry on the phone. If a "Keep the link open" card shows, tap **Allow background use**. On a Xiaomi phone, also turn on Autostart.

**The phone says "Not sent".** The phone could not reach ntfy.sh. Copy the text again when the phone is online.

**Nothing arrives after you reset pairing.** Scan the new QR code with the phone.

## Build

GitHub Actions builds both apps on every push to `main` or `dev` (see `.github/workflows/build.yml`). Each build publishes a pre-release with `Ferry.apk` and `Ferry.exe`.

The Android build signs the APK with a key from two repository secrets:

| Secret | Value |
|---|---|
| `FERRY_KEY` | The `keys/ferry.p12` file, encoded as Base64 |
| `FERRY_KEY_PASSWORD` | The password in `keys/password.txt` |

The `keys/` folder is not in Git. Keep a backup of it. If you lose the key, you must uninstall Ferry from the phone before you can install a new build.

To build on your own computer:

```bash
gradle assembleRelease
```
```bash
dotnet publish desktop/Ferry.csproj -c Release -o out
```

The Android build needs JDK 21, the Android SDK, and Gradle 8.14. Copy the key to `ferry.p12` in the project folder. Then set the environment variables `FERRY_KEY_PASSWORD` (the password) and `FERRY_KEY_ALIAS` (the key alias, from `keytool -list -keystore ferry.p12`). The Windows build needs the .NET 10 SDK.

## Project layout

| Path | Contents |
|---|---|
| `src/main/` | Android app (Java, Android framework views) |
| `desktop/` | Windows tray app (C#, Windows Forms) |
| `PRODUCT.md` | Who Ferry is for and what it must do |
| `DESIGN.md` | The Harbor design: colors, type, and the crossing ticket |
