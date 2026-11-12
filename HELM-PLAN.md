# Helm: the plan

This plan adds two groups of features to Ferry. The phone controls the laptop (the remote, named Helm). The laptop reaches the phone (photos, SMS, the mirror).

We build the plan in six phases. Each phase is one release that works by itself.

| Phase | What you get | Edition of the phone app | New permission on the phone |
|---|---|---|---|
| 0 | The session link (no feature of its own, ships with phase 1) | Both | None |
| 1 | Helm: trackpad, keyboard, media keys, volume, slide clicker, lock, sleep | Both | None |
| 2 | Phone photos in a grid on the laptop, drag to the desktop | Both | None (Photos is already there) |
| 3 | Read and send SMS from the laptop | Ferry Auto (see phase 3) | SMS, Contacts |
| 4 | Mirror: see the phone screen on the laptop | Both | Screen capture, asked at each start |
| 5 | Clicks on the mirror tap the phone | Ferry Auto | A new ability for the accessibility service |

## Status

| Phase | State |
|---|---|
| 0 | Code written. The laptop side passed tests on the laptop. The phone side is not compiled or tested yet. |
| 1 | Code written. Not tested on the phone and the laptop together yet. |
| 2 to 5 | Not started |

Changes to phases 0 and 1 while we built them:

- A session that the laptop starts (`"t":"open"`) moves to phase 2. Helm does not use it, and phase 2 is the first feature that needs it.
- The laptop file is `desktop/Helm.cs`, not `desktop/Input.cs`.
- The phone keyboard is a type field on the Helm screen, not a button. The field sends only what changed, so the corrections of the keyboard also reach the laptop.
- Helm sends only the keys on its screen. The laptop ignores all other keys, so a phone cannot press the Windows key or a shortcut.
- The ping time is not in a log. We measure it on the real devices before phase 2.

## What the code has today, and what it does not

I read the code before I wrote this plan. Four points in the brief do not match the code. Each one changes the plan.

1. Ferry has no link that stays open. Today the phone opens a port for one file, the laptop connects, the file crosses, and the connection closes. The two devices agree on each connection through ntfy.sh (`Lan.java`, `Lan.cs`). Helm needs a connection that stays open and carries small messages both ways. That is phase 0.
2. Notification mirroring is not in Ferry. Roost (a separate app, in `pigeonhole/`) reads the notifications and hands Ferry the app name, the title and the text. Ferry cannot reply through that path. SMS in phase 3 uses the SMS permissions and does not use Roost.
3. The accessibility service of Ferry Auto cannot tap. It only listens for taps and "Copied" messages (`src/auto/res/xml/auto_send.xml`). Phase 5 adds the ability to tap, and the privacy text in the README changes with it.
4. Android does not let an app record the screen without a question. On Android 14 and later, Android shows a consent window on the phone at each start of a capture. As a result, you start the mirror with one tap on the phone, each time.

Three parts of Ferry carry over without change:

- The pairing code and the encryption (`Crypto.java`, `Crypto.cs`)
- The rule that the phone listens and the laptop connects, so Windows Firewall never asks
- The start messages through ntfy.sh (the `lan` tag), which an older Ferry ignores when it does not know the type

## Decisions in this plan

Each decision has a reason. Tell me which ones you want to change before we start the phase.

| Decision | Reason |
|---|---|
| TCP for the session link, with the delay option turned off (`TCP_NODELAY`) | On one Wi-Fi network, TCP adds a few milliseconds. UDP needs its own code for lost and reordered messages. |
| ntfy.sh starts each session, then the session is local | The start costs about one second and needs the internet. It reuses code that works today. |
| Each feature opens its own session | One connection for one job needs no channel numbers and no shared state. |
| The same key and the same sealed-frame format as file transfer, with a new label | No new cryptography. A counter and a direction in each frame stop a replayed or reflected message. |
| SMS only in Ferry Auto, unless a test on your phone shows that Play Protect accepts it | Play Protect blocks browser installs of apps that ask for SMS access in some countries, as it does for accessibility. |
| The mirror sends JPEG pictures first, not H.264 video | JPEG needs no video decoder on the laptop and adds no buffer delay. H.264 comes only if the JPEG mirror is not smooth enough. |
| Plain names for the laptop features: **Phone photos**, **Messages**, **Mirror** | "Helm" explains itself on a remote. A person who installs Ferry later understands plain names without help. |

One alternative for the mirror exists already: scrcpy, a free program that mirrors and controls an Android phone over wireless debugging. The Ferry Auto installer already turns wireless debugging on. Try scrcpy for one evening before phase 4. It shows how a good mirror feels, and it can show that phases 4 and 5 are not worth the work.

## Phase 0: the session link

A session is one encrypted connection that stays open while a feature is in use. Phase 0 has no screen of its own. It ships with phase 1.

How a session starts:

- Phone starts (the remote): the phone opens a port and posts `{"t":"session","id":…,"port":…,"ips":…,"for":"helm"}` through ntfy.sh. The laptop connects.
- Laptop starts (photos, SMS, the mirror): the laptop posts `{"t":"open","id":…,"for":"photos"}`. The phone opens a port and answers with the same `session` message. The laptop connects.

After the connection, the laptop sends the sealed hello that file transfer uses today. Then both sides send frames:

- A frame is a 4-byte length and then the sealed bytes.
- The sealed text is one byte for the kind of message and then the body.
- The extra authenticated data (AAD) holds a new label, the session id, one byte for the direction, and an 8-byte counter.
- The receiver accepts only the next counter. A frame that does not open closes the session.

Rules of the link:

- Both sides send a ping every 5 seconds. A side that hears nothing for 15 seconds closes the session.
- The session closes when its screen or window closes.
- The laptop connects only to an address that is private and on a network the laptop is on. File transfer has this rule already (`OnOurNetworks` in `Lan.cs`), and sessions use the same `Connect`.

New files: `desktop/Session.cs` and `src/main/java/app/ferry/Session.java`. `Lan.cs` and `Lan.java` keep the connection code, and the sessions call it.

Done when:

- A frame sealed by the laptop code opens in the phone code, and the reverse. One fixed sample in each `Crypto` file proves it.
- A ping makes the round trip in under 10 ms on your home Wi-Fi. The debug log shows the time.
- An older Ferry on the other device ignores the new start messages.

## Phase 1: Helm, the remote

On the phone, a new screen named Helm:

- A trackpad area: move, tap to click, two-finger tap for the right button, two-finger move to scroll, double-tap and hold to drag
- A keyboard button that opens the phone keyboard
- A media row: previous, play or pause, next, volume down, mute, volume up
- A clicker mode with two large buttons for the next and the previous slide
- **Lock** and **Sleep**. **Sleep** asks first.
- The volume buttons of the phone change the laptop volume. In clicker mode they change the slide.
- A sensitivity slider for the pointer, because each hand and each screen is different

Helm opens from a button on the main screen and from a quick settings tile. The screen stays on while Helm is open.

On the laptop, a new file `desktop/Helm.cs`:

- `SendInput` moves the pointer, presses buttons, scrolls and presses keys. Typed text goes as Unicode characters, so any phone keyboard language works.
- `LockWorkStation` locks the laptop. `Application.SetSuspendState` puts it to sleep.
- The first time a phone takes the helm, the laptop asks: "Let Redmi Note 14 control this laptop?" The answer is saved. A switch in **Settings** changes it later.
- While the phone has the helm, the tray icon shows it.

Limits that the README must state:

- Helm works only when both devices are on the same Wi-Fi, and the start needs the internet.
- Windows does not let Ferry control a window that runs as administrator, a "Do you want to allow" prompt, or the lock screen. After **Lock**, you unlock on the laptop itself.
- **Sleep** ends the session. The phone cannot wake the laptop.

Done when:

- The pointer follows your finger with no delay that you can see.
- Text that you type on the phone appears in Notepad.
- The media keys control a video in the browser.

## Phase 2: phone photos

On the laptop, **Phone photos…** in the tray menu and in the window opens a new window with a grid.

- The phone sends the list of photos, newest first, 200 at a time: id, date, size and name.
- The laptop asks for a thumbnail (a small copy, at most 256 pixels) only for the photos on screen. Android makes the thumbnail (`ContentResolver.loadThumbnail`).
- A double-click fetches the full photo and opens it. A drag fetches the full photo first and then drops it as a file.
- The laptop keeps thumbnails in memory only, because the laptop has little free disk space.

On the phone:

- A switch in **Settings**, off at first: "Let the laptop see photos". It needs the Photos permission with **Allow all**, the same one that **Send screenshots** uses.
- While the laptop looks at photos, the phone shows a notification that says so.
- The code runs in `SyncService`, on a thread of its own, as file transfer does today.

New files: `desktop/PhotosWindow.cs` and `src/main/java/app/ferry/Photos.java`.

Done when: a library of some thousand photos scrolls without a stop, and a photo that you drag to the desktop is the original file.

Skipped at first: videos, albums, search, delete. Each one can use the same session later.

## Phase 3: SMS

Do this test before any code: add the SMS permissions to a test build of Ferry and install it from the phone browser. If Play Protect blocks the install, SMS goes into Ferry Auto. If Play Protect accepts it, SMS can go into both editions.

On Android 15 and later, Android can also grey out the SMS permission for an app that did not come from an app store. The fix is the step that the README already has for accessibility: **App info**, the ⋮ menu, **Allow restricted settings**.

On the phone (`Sms.java`, in `src/auto/` if the test says so):

- Permissions: `READ_SMS`, `SEND_SMS` and `READ_CONTACTS` (for names). Ferry does not need `RECEIVE_SMS`, because it watches the SMS store for new rows while a session is open.
- A switch in **Settings**, off at first: "Let the laptop read and send SMS".
- The phone sends the list of conversations, the messages of one conversation, and each new message while the window is open.
- A reply goes out with `SmsManager` on the SIM that the conversation used last. The phone reports "sent" or "not sent" to the laptop.

On the laptop (`desktop/MessagesWindow.cs`): conversations on the left, messages on the right, a box to type a reply. Ferry sends an SMS only when you press Enter or click **Send**.

Limits that the README must state:

- Chat messages (RCS) in Google Messages are not SMS. Ferry cannot see them.
- Picture messages and group messages (MMS) do not show.
- An SMS that you send from the laptop costs what an SMS from the phone costs.

Done when: a conversation on the laptop matches the phone, a reply arrives on another phone, and the reply shows in the SMS app of the phone.

## Phase 4: the mirror, to look only

How it starts: you click **Mirror** on the laptop. The phone shows a notification. You tap it, and Android asks to share the screen. This tap is needed at each start (an Android rule).

On the phone:

- A new foreground service of the type `mediaProjection`, with the permission `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- The service records the screen at a smaller size, about 1280 pixels on the long side
- It sends each changed picture as a JPEG in one frame, at most 15 per second
- If the link is busy, it drops old pictures and sends only the newest

On the laptop (`desktop/MirrorWindow.cs`): a window in the shape of the phone that draws each picture when it arrives. Windows decodes JPEG with code that Ferry already uses.

Limits that the README must state:

- Apps that protect their screen (bank apps, some video apps) show black.
- The phone must be unlocked. The mirror stops when the phone locks.

Done when: you can read a chat on the mirror while it scrolls, and the delay is under a quarter of a second.

If the JPEG mirror is not smooth enough: the phone encodes H.264 with `MediaCodec`, and the laptop plays it with `MediaStreamSource` and `MediaPlayer` from Windows. Ferry can already call these Windows parts, so no new library is needed. I did not measure the delay of that path. We measure it before we commit to it.

## Phase 5: clicks on the mirror tap the phone

Ferry Auto only, because only Ferry Auto has the accessibility service.

- `auto_send.xml` gets `android:canPerformGestures="true"`.
- A click on the mirror becomes a tap at the same place on the phone (`dispatchGesture`). A drag becomes a swipe. A long press becomes a long press.
- Three buttons under the mirror do Back, Home and Recents (`performGlobalAction`).
- The service gets a new name and a new description on the phone, because it does more than automatic sending.
- The README says plainly that Ferry Auto can now tap the screen, and only when the mirror is open.

Done when: you can open an app, scroll a list and go back, all from the laptop.

Skipped at first: typing on the laptop keyboard into the phone.

## Security

Today a person who has the pairing code can read and send clipboard text. After this plan, the same code can do much more. The plan adds these protections, each in the phase that needs it.

| Risk | Protection | Phase |
|---|---|---|
| A person with the code posts a fake session message with an address on the internet, and the laptop connects to it | The laptop connects only to a private address on a network that the laptop is on | Done, before Helm |
| A person on the Wi-Fi records frames and sends them again, or sends them back | The counter and the direction in each frame. A frame out of order closes the session. | 0 |
| A leaked code controls the laptop | The laptop asks once before the first Helm session. A switch turns Helm off. The tray icon shows an open session. | 1 |
| A leaked code reads photos or SMS | Each has a switch on the phone that is off at first. The phone shows a notification while the laptop reads. | 2, 3 |
| A leaked code sees the screen | Android asks on the phone at each start | 4 |

A person needs two things for any of these: the code, and a place on your Wi-Fi. The QR code on the laptop screen holds the code. Do not show the Ferry window in a screen share.

The **Privacy and security** section of the README changes in each phase.

## Make sure of these on the Redmi before each phase

| Before phase | Make sure that |
|---|---|
| 1 | The ping time on your Wi-Fi is under 10 ms, and the phone hotspot gives the same result |
| 3 | Play Protect accepts or blocks a browser install with SMS permissions (this decides the edition) |
| 3 | HyperOS shows **Allow restricted settings** for the SMS permission |
| 4 | HyperOS lets the capture service run while Ferry is in the background |
| 5 | The accessibility service stays on after an update that adds the ability to tap |

## What this plan leaves out

- A start without the internet. For that, the laptop must find the phone by itself.
- UDP for the remote. Add it only if the pointer stutters on a weak Wi-Fi.
- Waking the laptop from the phone
- Videos in the photo grid, MMS and RCS, typing on the laptop into the phone
- H.264 for the mirror, until the JPEG mirror proves too slow

## Sources for the Android rules

- Consent before each screen capture: [Media projection, Android Developers](https://developer.android.com/media/grow/media-projection)
- Play Protect blocks browser installs that ask for SMS, notification or accessibility access, in some countries: [Google Security Blog](https://security.googleblog.com/2024/02/piloting-new-ways-to-protect-Android-users-from%20financial-fraud.html), [9to5Google](https://9to5google.com/2024/10/03/google-blocking-sideloaded-apps-india/)
- Android 15 restricts the SMS permission for apps that did not come from an app store: [9to5Google](https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/), [Android Authority](https://androidauthority.com/android-15-restricted-settings-sideloading-3481098)
