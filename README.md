# Ferry

Ferry moves clipboard text and images between a Windows laptop and an Android phone. It also sends files when you choose to: any size on the same Wi-Fi, and up to 90 MB from anywhere.

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

Files take a faster road when the two devices are on the same Wi-Fi. The phone opens a port on the Wi-Fi network and tells the laptop the address through ntfy.sh. The laptop then connects to the phone directly, and the file goes from device to device. Ferry encrypts the file with the same key, in parts of 1 MB. Because the laptop always makes the connection, Windows Firewall does not ask for permission.

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

1. Tap **Allow background use**. Without it, Android cuts the connection when the phone sleeps. This step is also in Ferry under **Settings > Keep Ferry running**.
2. On a Xiaomi, Redmi or POCO phone, open Ferry **Settings**, then tap **Open app settings** and turn on **Autostart**.
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
- On the laptop, a pop-up shows the text. To stop the pop-ups, turn off **Pop-ups** in Settings in the window, or clear **Show pop-ups** in the tray menu.
- If the text is one web link, the notice offers to open it. On the phone, tap the notification or **Open link**. On the laptop, click the pop-up. Ferry never opens a link by itself, and it only opens web links (http and https).

### What the screen shows

Both apps show your clipboard as a sea chart. The laptop and the phone are two harbors, and a magenta course line joins them. The arrow on the line points the way the last crossing (transfer) went, and the label on the line gives its time.

Under the chart, the newest crossing shows in large text. The earlier ones are in the **logbook**: the app keeps the last 10. The logbook groups them under **Today**, **Yesterday** and **Earlier**. An icon on each row shows what crossed: text, a web link, an image or a file. To copy an earlier one again, tap it on the phone, or double-click it on the laptop.

The pill in the top-right corner of the chart shows the connection:

- **Steady link** (green dot): connected.
- **Adrift** (red dot): offline. Ferry tries again every 5 seconds. When the device changes network or wakes from sleep, Ferry connects again at once.
- **Connecting**, **Paused** or **Not paired** (grey dot).

The main screen shows only the chart, the last crossing and the logbook. On the phone it also shows **Send clipboard** and **Helm** (see [Helm](#helm-the-phone-as-a-remote)), and on the laptop **Send a file…** and **Phone photos…** (see [Phone photos](#phone-photos)). When something needs action, one notice shows under it, for example an update or background use.

Everything that you set once is in **Settings**. To open it, click or tap the round button with three sliders, right of the pill. In the Settings of each app:

- **Appearance**: **System**, **Light** or **Dark**. Light is a day chart (pale blue water). Dark is a soft charcoal night chart. System follows the light or dark mode of the device. Each device keeps its own choice.
- **Syncing**: images, screenshots and automatic sending on the phone. Pause syncing, pop-ups, images and Start with Windows on the laptop. Each is a switch with a short line that says what it does.
- **Pairing**: the paired device, **Unpair** on the phone, and **Show pairing code** and **Reset pairing** on the laptop.
- **Keep Ferry running** (phone): background use, and Autostart on Xiaomi phones.
- **About**: the version, and **Check for updates**.

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
| Phone to laptop, screenshots | Turn on **Send screenshots** in Ferry Settings (see below). |

On the laptop, an image from the phone goes into the clipboard (press **Ctrl+V** to paste) and into **Pictures\Ferry**.

**Send screenshots** is off until you turn it on, because screenshots can show private things. When you turn it on, Android asks for the Photos permission. Choose **Allow all**: with "Allow limited access", Ferry cannot see new screenshots.

To stop images in either direction, turn off **Images** in Ferry Settings on the phone or in the laptop window. On the laptop, **Send and receive images** in the tray menu does the same.

Limits for images:

- Ferry makes an image smaller before it sends it when the file is larger than 1.5 MB. It saves the image as a JPEG of at most 2560 pixels on the long side.
- Ferry does not send an image that is still larger than 10 MB.
- On a slow connection, an image takes a few seconds.
- ntfy.sh keeps image files for 3 hours. A device that is offline for longer does not get the image.

## Files

Ferry sends other files too, for example videos. Files are encrypted the same way as text, and the file name is encrypted too. Ferry never sends a file because you copied it. You send each file yourself.

Ferry sends a file in one of two ways:

- Direct: when the phone and the laptop are on the same Wi-Fi, the file goes straight from one device to the other. A file of any size can go this way. The speed depends on your Wi-Fi, often 20 to 80 MB per second. This also works when the laptop uses the phone's hotspot.
- Through ntfy.sh: when the devices are on different networks, or when the direct connection fails, the file goes through ntfy.sh. This works for files up to 90 MB.

Ferry tries the direct way first. If the other device does not answer within a few seconds, Ferry uses ntfy.sh.

| To send | Do this |
|---|---|
| Laptop to phone, from File Explorer | Right-click the file, then choose **Send to > Ferry (phone)**. On Windows 11, **Send to** is under **Show more options**. |
| Laptop to phone, from the Ferry window | Drag the file onto the window. |
| Laptop to phone, from the tray | Click **Send a file to the phone…** in the tray menu, then choose the file. |
| Phone to laptop | Tap **Share** on the file and choose **Send to laptop**. |

You can send more than one file at a time on both devices. Ferry sends them one after the other. If you share several photos from the phone, they arrive as files in Downloads, not in the clipboard.

While a file is sent, a bar shows how much of it is sent. On the laptop, the bar is in the Ferry window, which opens by itself. On the phone, the bar is in a notification.

When a file starts to cross, the other device shows a pop-up with the name and size of the file. A large file takes a few minutes, so this pop-up tells you that it is on its way.

When a file arrives:

- On the laptop, it goes into **Downloads\Ferry**. Click the pop-up to show the file in File Explorer. Windows marks the file as from the internet, as it does for a browser download, so Windows warns before it runs a program from it.
- On the phone, it goes into **Downloads/Ferry**. Tap the notification to open the file.
- If a file with the same name is already there, Ferry keeps both. The new file gets a number, for example `report (2).pdf`.

To use an earlier file again, find it in the logbook. On the laptop, double-click it to copy the file, then paste it in a folder or an email. On the phone, tap a file that came to the phone to open it.

Limits for direct files:

- Both devices must be on the same network. Some public and guest Wi-Fi networks stop devices from connecting to each other. On such a network, Ferry uses ntfy.sh.
- If the Wi-Fi connection breaks while a file crosses, Ferry sends a file up to 90 MB again through ntfy.sh. Send a larger file again yourself.

Limits for files through ntfy.sh:

- Ferry does not send a file that is larger than 90 MB this way.
- ntfy.sh takes one file of at most 15 MB. Ferry sends a larger file in parts of 14 MB, and the other device puts the parts together.
- ntfy.sh keeps at most 100 MB of your files at a time, for 3 hours. If you send more, ntfy.sh refuses the file until older files expire. As a result, you can send one 90 MB file in 3 hours, but not two.
- A device that is offline for more than 3 hours does not get the file.
- A file that does not reach ntfy.sh does not wait. Send it again when you are online.
- If Ferry restarts while a large file arrives, the other device does not get that file. Send it again.
- A phone or laptop with an older Ferry gets files up to 14 MB only, and never directly. Update both apps.

## Helm: the phone as a remote

Helm makes the phone a trackpad, a keyboard and a slide clicker for the laptop. Helm works only when both devices are on the same Wi-Fi. It also needs the internet for about one second to start.

### Start Helm

1. On the phone, tap **Helm: use this phone as a remote** on the main screen. You can also add the **Helm** tile to the quick settings panel.
2. The first time, the laptop asks if the phone can control it. Click **Yes** on the laptop.

The laptop keeps your answer. To change it, use **Let the phone control this laptop** in Settings in the Ferry window, or in the tray menu.

While the phone has the helm, the Ferry icon in the tray turns magenta and a pop-up says so. To end Helm from the laptop, click the pop-up, or click **End Helm session** in the tray menu. Helm also ends when you leave the Helm screen on the phone.

### Use Helm

| To do this | Do this on the phone |
|---|---|
| Move the pointer | Move one finger on the trackpad. |
| Click | Tap. |
| Right-click | Tap with two fingers. |
| Scroll | Move two fingers. |
| Drag | Tap, then touch again at once and hold. Move the finger, then lift it. |
| Type | Tap **Type here to type on the laptop**, then type. The text goes to the window that is active on the laptop. |
| Press Esc, Tab, Backspace, Enter or an arrow key | Tap the key under the trackpad. |
| Play, pause, skip a track, change the volume | Tap the media keys, or press the volume buttons of the phone. |
| Change slides | Tap **Slide clicker** at the top. Then tap **Next slide** or **Previous slide**, or press the volume buttons: up for the next slide, down for the previous one. |
| Lock the laptop | Tap **Lock**. |
| Put the laptop to sleep | Tap **Sleep**, then tap **Sleep** again. |

If the pointer moves too slowly or too fast, change **Pointer speed** under the media keys.

### Limits of Helm

- Windows does not let Helm control a program that runs as administrator, a "Do you want to allow this app" prompt, or the lock screen. After **Lock**, unlock the laptop on the laptop itself.
- After **Sleep**, the phone cannot wake the laptop.
- Helm presses only the keys on the Helm screen. It cannot press the Windows key or shortcuts such as Ctrl+C.
- Some public and guest Wi-Fi networks stop devices from connecting to each other. Helm does not work on such a network.
- A laptop or phone with an older Ferry does not answer Helm. Update both apps.

## Phone photos

Phone photos shows the photos of the phone in a grid on the laptop, newest first. It works only when both devices are on the same Wi-Fi. It also needs the internet for about one second to start.

### Turn on Phone photos

Phone photos is off until you turn it on, on the phone.

1. On the phone, open Ferry **Settings**.
2. Turn on **Let the laptop see photos**.
3. If Android asks for the Photos permission, choose **Allow all**.

### Use Phone photos

1. On the laptop, click **Phone photos…** in the Ferry window or in the tray menu.
2. To open a photo, double-click it. You can also select it and press Enter.
3. To copy photos to a folder or to the desktop, select them and drag them there. Hold the mouse button until the photos come from the phone.

While the window is open, the phone shows a notification that the laptop is looking at your photos. To stop it from the phone, tap **Stop** in that notification. To stop it from the laptop, close the window.

### Limits of Phone photos

- Phone photos shows photos only, not videos. It has no albums and no search.
- Android removes the location from each photo that Ferry reads, because Ferry does not ask for the location permission.
- A photo that you open or drag goes into a temporary folder on the laptop. Ferry empties this folder the next time you open Phone photos.
- If you release the mouse button before the photos come, drag them again. They are ready then.
- A phone with an older Ferry does not answer. Update both apps.

## Updates

Both apps look for a newer release on GitHub:

- On the laptop, a pop-up shows when an update is ready. Click it, or choose **Update to Ferry x.y.z** in the tray menu. Ferry downloads the update and restarts by itself.
- On the phone, an "update is ready" card shows. In Ferry, tap **Download update**, then install the file over the current app. In Ferry Auto, run the install script again on the computer.

Your pairing and history stay after an update.

## Privacy and security

- Text is encrypted end to end. ntfy.sh stores encrypted copies for up to 12 hours, so a device that was offline can catch up. ntfy.sh cannot read them.
- ntfy.sh can see when a message is sent, how large it is, and which device type sent it.
- A direct file crosses your own Wi-Fi, encrypted. Another device on the network can see that a transfer occurs, but it cannot read the file. The phone accepts a connection only from a device that has your pairing code. The laptop connects only to a private address on a network that the laptop is on, never to the internet.
- The pairing code is the only secret. A person who has the code can read your clipboard text and send text to it. Do not share the code or the QR code.
- Helm gives more control. After you allow Helm on the laptop, a person who has the pairing code and is on the same Wi-Fi can control the laptop. The tray icon turns magenta and a pop-up shows while a phone has the helm. Each Helm message is encrypted with your key and numbered, so a recorded message cannot be sent again.
- Phone photos is off until you turn it on, on the phone. When it is on, a person who has the pairing code and is on the same Wi-Fi can see your photos. The phone shows a notification while the laptop looks, with a **Stop** button. The photos cross your Wi-Fi encrypted, and do not go through ntfy.sh.
- If the code leaks, click **Reset pairing** in Settings in the laptop window. Then scan the new code with the phone.
- When you unpair on one device, the other device unpairs too: **Reset pairing** on the laptop, or **Unpair** in Settings on the phone. The device that unpairs must be online to tell the other one. After an unpair, the laptop shows a new code to scan.
- On the laptop, Ferry does not send copies that apps mark as private or as "do not share to other devices" (password managers do this).
- On the phone, Ferry Auto does not send copies that apps mark as sensitive (password managers do this on Android 13 and later). If you tap **Send clipboard** yourself, Ferry sends the copy, because you chose to.
- The history stays on each device, in storage that only Ferry (phone) or your Windows user (laptop) can read. **Clear** removes it.

## Limits

- Ferry sends a file larger than 90 MB only when both devices are on the same Wi-Fi. It does not send folders.
- Ferry is not in the Windows **Share** window. Use **Send to** (see Files).
- ntfy.sh limits one message to 4,096 bytes. Ferry sends longer text as a text file, and the other device reads the file.
- The QR scanner needs Google Play services. On a phone without them, type the code.

## Troubleshooting

**I cannot find the Ferry icon on the laptop.** Windows 11 can hide new tray icons. Click the **^** arrow next to the clock. If Ferry is not there, it is not running: open **Ferry** from the Start menu. (**Quit Ferry** closes it completely.)

**The light is red.** Make sure that the device is online. Ferry connects again by itself.

**Laptop copies stop arriving on the phone.** Open Ferry on the phone. If a "Keep the link open" notice shows, tap **Allow background use**. On a Xiaomi phone, also turn on Autostart (see **Settings > Keep Ferry running**).

**Automatic sending stopped.** Some phones turn off accessibility services to save power. Open Ferry and look at the "Send copies automatically" card. If it says **Turn on**, turn it on again.

**The phone says "Pair again".** This version encrypts the text, so phones paired with an older version must pair again. Update Ferry on the laptop, then scan the new QR code.

**The phone or the laptop says "Not sent yet".** The device could not reach ntfy.sh. Ferry keeps the text and sends it by itself when the link is back. If the link is not back within 10 minutes, Ferry drops the text. Then copy it again. Images do not wait: copy an image again when the device is online.

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
| `src/main/java/app/ferry/Session.java`, `desktop/Session.cs` | The session link: one encrypted connection that stays open on the same Wi-Fi, for Helm and Phone photos |
| `src/main/java/app/ferry/Photos.java`, `desktop/PhotosWindow.cs` | Phone photos: the photo list, thumbnails and files on the phone, and the grid on the laptop. Both files must use the same message kinds. |
| `src/main/java/app/ferry/HelmActivity.java`, `desktop/Helm.cs` | Helm: the remote screen on the phone, and the input on the laptop. Both files must use the same message kinds. |
| `PRODUCT.md` | Who Ferry is for and what it must do |
| `DESIGN.md` | The Nautical chart design: colors for light and dark mode, type, and the chart |
| `desktop/ferry.ico` | The Windows icon (taskbar, Start menu, notification pop-ups) |
| `FONT-LICENSE-Barlow.txt` | License of the Barlow font that both apps use |
