# Ferry

Ferry moves clipboard text and images between a Windows laptop and an Android phone.

When you copy text on the laptop, the text goes into the clipboard of the phone within a second or two. When you copy text on the phone, Ferry sends it to the laptop with one tap, or by itself in Ferry Auto. You do not need an account. The two devices pair when the phone scans a QR code, and all text is encrypted end to end (only your two devices can read it).

## How it works

The laptop and the phone do not connect to each other directly. Both connect to [ntfy.sh](https://ntfy.sh), a free public relay (a server that passes messages on).

```
Laptop (Ferry.exe)  ⇄  ntfy.sh  ⇄  Phone (Ferry app)
```

The QR code holds a pairing code: 26 random letters and numbers. Both devices make two things from this code:

- A topic: a private channel name on ntfy.sh.
- An encryption key. The key never leaves your devices.

Each device encrypts the text with the key (AES-256-GCM) before it sends the text to the topic. ntfy.sh only sees encrypted text. Each device ignores messages that were not encrypted with your key.

## Install

Download both files from the latest release on the [Releases page](../../releases/latest).

### On the laptop

1. Download `Ferry.exe` and put it in a permanent folder, for example `%LOCALAPPDATA%\Programs\Ferry`.
2. Open `Ferry.exe`. If Windows shows "Windows protected your PC", click **More info**, then **Run anyway**. Windows shows this message because Ferry is not signed with a paid certificate.

Ferry opens a window with a QR code. It also adds an icon to the system tray (the icons near the clock), adds **Ferry** to the Start menu, and starts with Windows. You do not need to install anything else.

### On the phone

Ferry for Android comes in two editions. Both work with the same laptop app, and you can change from one to the other later without pairing again.

| Edition | Phone to laptop | How to install |
|---|---|---|
| **Ferry** (`Ferry.apk`) | One tap: the notification button, the tile, or Share | On the phone, from the browser |
| **Ferry Auto** (`Ferry-Auto.apk`) | By itself, after each copy | From a Windows computer (see [Ferry Auto](#ferry-auto-automatic-sending)) |

If you are not sure, install Ferry.

1. Download `Ferry.apk` on the phone and open it.
2. If Android asks, allow installs from that app.
3. Open Ferry.

## Pair the devices

1. On the phone, tap **Scan QR code**.
2. Point the camera at the QR code in the Ferry window on the laptop.

The laptop shows "Phone paired".

If the scanner does not open, tap **Type the code** on the phone. Then type the code that is under the QR code on the laptop. Spaces do not matter.

After pairing, do these steps on the phone:

1. Tap **Allow background use**. Without it, Android cuts the connection when the phone sleeps.
2. On a Xiaomi, Redmi or POCO phone, open **Settings > Apps > Ferry** and turn on **Autostart**.
3. In Ferry Auto: turn on automatic sending (see [Ferry Auto](#ferry-auto-automatic-sending)).
4. Optional: add the **Send clipboard** tile to the quick settings panel.

## Use Ferry

| To send | Do this |
|---|---|
| Laptop to phone | Copy text on the laptop. It goes into the phone clipboard. |
| Phone to laptop, automatic (Ferry Auto) | Turn on automatic sending once. Then copy text on the phone as usual. |
| Phone to laptop, by hand | Copy text on the phone. Then tap **Send clipboard** in the Ferry notification or in the quick settings tile. |
| Selected text on the phone | Select the text and choose **Send to laptop** in the menu. |
| Text from another phone app | Tap **Share** and choose **Send to laptop**. |

When text arrives, the other device shows it:

- On the phone, a notification without sound shows the text for 8 seconds.
- On the laptop, a pop-up shows the text. To stop the pop-ups, turn off the **Pop-ups** chip in the window, or clear **Show pop-ups** in the tray menu.
- If the text is one web link, the notice offers to open it. On the phone, tap the notification or **Open link**. On the laptop, click the pop-up. Ferry never opens a link by itself, and it only opens web links (http and https).

### What the screen shows

Both apps show your clipboard as a sea chart. The laptop and the phone are two harbors, and a magenta course line joins them. The arrow on the line points the way the last crossing (transfer) went, and the label on the line gives its time.

Under the chart, the newest crossing shows in large text. The earlier ones are in the **logbook**: the app keeps the last 10. To copy an earlier one again, tap it on the phone, or double-click it on the laptop.

The pill in the top-right corner of the chart shows the connection:

- **Steady link** (green dot): connected.
- **Adrift** (red dot): offline. Ferry tries again every 5 seconds.
- **Connecting**, **Paused** or **Not paired** (grey dot).

The switches are chips under the logbook: a filled chip is on, an outlined chip is off.

Both apps follow the light or dark setting of the device. Light mode is a day chart (pale blue water). Dark mode is a soft charcoal night chart.

If a device is offline for a short time, it gets the missed copies when it connects again. A copy that is more than 10 minutes old goes into the history only. It does not replace what is in your clipboard now.

## Ferry Auto (automatic sending)

Android does not let apps read the clipboard in the background. To send copies by itself, Ferry Auto uses Android's accessibility permission. With this permission, Ferry notices when you copy: a tap on a **Copy** button, or a "Copied" message. Then it reads the clipboard and sends the text. When you tap something, Ferry reads only the label of what you tapped, to find Copy buttons. It does not read the rest of the screen, and it never logs what you copy.

Google Play Protect blocks apps with this permission when you install them from a phone browser. It does not block installs from a computer. For this reason, Ferry Auto is a separate edition that you install from a Windows computer.

### Install or update Ferry Auto

1. On the Windows computer, download `install-ferry-auto.ps1` from the latest release on the [Releases page](../../releases/latest).
2. Open PowerShell in the download folder.
3. Run this command:

```bash
powershell -ExecutionPolicy Bypass -File install-ferry-auto.ps1
```

4. Follow the steps that the script shows. The script turns on a connection to the phone over Wi-Fi ("Wireless debugging"), and you type a 6-digit pairing code from the phone. Then the script installs Ferry Auto.

Ferry Auto installs over Ferry. Your pairing and history stay. To update Ferry Auto later, run the script again.

### Turn on automatic sending

1. In Ferry Auto, tap **Turn on** on the "Send copies automatically" card.
2. In the accessibility settings, open **Ferry automatic sending** and turn it on.

If the switch is greyed out, Android blocks it because the app did not come from an app store. Do these steps:

1. In Ferry, tap **App info**.
2. Tap the ⋮ menu in the top-right corner, then tap **Allow restricted settings**.
3. Go back to the accessibility settings and turn on Ferry.

Automatic sending works for Copy buttons with a label such as "Copy" or "Copy link" (in the language of the phone or in English). If an app uses a Copy icon without any label, use **Send clipboard**.

## Images

Ferry moves images too. Images are encrypted the same way as text.

| To send | Do this |
|---|---|
| Laptop to phone | Copy an image, or take a screenshot with **Win+Shift+S** or **PrtScn**. It goes into the phone clipboard and into **Pictures/Ferry** on the phone. |
| Phone to laptop, a copied image | Copy the image (for example **Copy image** in Chrome). Ferry Auto sends it by itself. In Ferry, tap **Send clipboard**. |
| Phone to laptop, any image | Tap **Share** and choose **Send to laptop**. |
| Phone to laptop, screenshots | Turn on the **Screenshots** chip in Ferry (see below). |

On the laptop, an image from the phone goes into the clipboard (press **Ctrl+V** to paste) and into **Pictures\Ferry**.

The **Screenshots** chip is off until you turn it on, because screenshots can show private things. When you turn it on, Android asks for the Photos permission. Choose **Allow all**: with "Allow limited access", Ferry cannot see new screenshots.

To stop images in either direction, turn off the **Images** chip in Ferry on the phone or in the laptop window. On the laptop, **Send and receive images** in the tray menu does the same.

Limits for images:

- Ferry makes an image smaller before it sends it when the file is larger than 1.5 MB. It saves the image as a JPEG of at most 2560 pixels on the long side.
- Ferry does not send an image that is still larger than 10 MB.
- On a slow connection, an image takes a few seconds.
- ntfy.sh keeps image files for 3 hours. A device that is offline for longer does not get the image.

## Updates

Both apps look for a newer release on GitHub:

- On the laptop, a pop-up shows when an update is ready. Click it, or choose **Update to Ferry x.y.z** in the tray menu. Ferry downloads the update and restarts by itself.
- On the phone, an "update is ready" card shows. In Ferry, tap **Download update**, then install the file over the current app. In Ferry Auto, run the install script again on the computer.

Your pairing and history stay after an update.

## Privacy and security

- Text is encrypted end to end. ntfy.sh stores encrypted copies for up to 12 hours, so a device that was offline can catch up. ntfy.sh cannot read them.
- ntfy.sh can see when a message is sent, how large it is, and which device type sent it.
- The pairing code is the only secret. A person who has the code can read your clipboard text and send text to it. Do not share the code or the QR code.
- If the code leaks, click **Reset pairing** in the laptop window. Then scan the new code with the phone.
- On the laptop, Ferry does not send copies that apps mark as private or as "do not share to other devices" (password managers do this).
- On the phone, Ferry Auto does not send copies that apps mark as sensitive (password managers do this on Android 13 and later). If you tap **Send clipboard** yourself, Ferry sends the copy, because you chose to.
- The history stays on each device, in storage that only Ferry (phone) or your Windows user (laptop) can read. **Clear** removes it.

## Limits

- Ferry moves text and images. It does not move other files.
- ntfy.sh limits one message to 4,096 bytes. Ferry sends longer text as a text file, and the other device reads the file.
- The QR scanner needs Google Play services. On a phone without them, type the code.

## Troubleshooting

**I cannot find the Ferry icon on the laptop.** Windows 11 can hide new tray icons. Click the **^** arrow next to the clock. If Ferry is not there, it is not running: open **Ferry** from the Start menu. (**Quit Ferry** closes it completely.)

**The light is red.** Make sure that the device is online. Ferry connects again by itself.

**Laptop copies stop arriving on the phone.** Open Ferry on the phone. If a "Keep the link open" card shows, tap **Allow background use**. On a Xiaomi phone, also turn on Autostart.

**Automatic sending stopped.** Some phones turn off accessibility services to save power. Open Ferry and look at the "Send copies automatically" card. If it says **Turn on**, turn it on again.

**The phone says "Pair again".** This version encrypts the text, so phones paired with an older version must pair again. Update Ferry on the laptop, then scan the new QR code.

**The phone says "Not sent".** The phone could not reach ntfy.sh. Copy the text again when the phone is online.

## Build and release

GitHub Actions builds both apps (see `.github/workflows/build.yml`):

- A push to `main` or `dev` only builds the apps, to check that the code still compiles. It does not make a release. To test a build, download its files from the run page, or run `gh run download` with the run number.
- A pushed tag such as `v1.2.0` makes a real release. The apps offer it as an update.

To make a release, run these commands on `main`:

```bash
git tag v1.2.0
```
```bash
git push origin v1.2.0
```

The Android build signs the APK with a key from two repository secrets:

| Secret | Value |
|---|---|
| `FERRY_KEY` | The `keys/ferry.p12` file, encoded as Base64 |
| `FERRY_KEY_PASSWORD` | The password in `keys/password.txt` |

The `keys/` folder is not in Git. Keep a backup of it. If you lose the key, you must uninstall Ferry from the phone before you can install a new build.

To build on your own computer:

```bash
gradle assembleStandardRelease assembleAutoRelease
```
```bash
dotnet publish desktop/Ferry.csproj -c Release -o out
```

The Android build needs JDK 21, the Android SDK, and Gradle 8.14. Copy the key to `ferry.p12` in the project folder. Then set the environment variables `FERRY_KEY_PASSWORD` (the password) and `FERRY_KEY_ALIAS` (the key alias, from `keytool -list -keystore ferry.p12`). The Windows build needs the .NET 10 SDK.

## Project layout

| Path | Contents |
|---|---|
| `src/main/` | Android app (Java, Android framework views) |
| `src/auto/` | Ferry Auto only: the automatic-sending service |
| `desktop/` | Windows tray app (C#, Windows Forms) |
| `tools/install-ferry-auto.ps1` | Installs Ferry Auto from a Windows computer |
| `src/main/java/app/ferry/Crypto.java`, `desktop/Crypto.cs` | The encryption. Both files must stay identical in behavior. |
| `PRODUCT.md` | Who Ferry is for and what it must do |
| `DESIGN.md` | The Nautical chart design: colors for light and dark mode, type, and the chart |
| `desktop/ferry.ico` | The Windows icon (taskbar, Start menu, notification pop-ups) |
| `FONT-LICENSE-Barlow.txt` | License of the Barlow font that both apps use |
