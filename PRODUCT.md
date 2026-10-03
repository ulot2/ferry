# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

Ferry also ships a Windows desktop app (C# WinForms tray app). Both apps share one visual identity.

## Users
The owner first: one person with a Windows laptop and an Android phone (Redmi Note 14) who copies text on one device and needs it on the other. Friends may install it later, so setup and pairing must explain themselves without the owner present.

## Product Purpose
Ferry moves clipboard text between a laptop and a phone. It replaces Microsoft Phone Link's clipboard sync, which the owner found unreliable. Success: a copy on the laptop is in the phone clipboard within a second or two, and a phone copy reaches the laptop with one tap.

## Positioning
No account and no server of its own. The two devices pair by scanning a QR code; the code is a 26-character secret that gives both the ntfy.sh topic and an AES-256-GCM key, so the public relay only carries encrypted text. Laptop-to-phone is fully automatic. Phone-to-laptop is automatic when the user opts in to the accessibility-based auto-send; otherwise it takes one tap, because Android blocks background clipboard reads.

## Operating Context
- The desktop app sits in the Windows tray and starts with Windows. Its window opens for pairing and status.
- The phone app runs a foreground service with a status notification. Sending happens from the notification button, a quick-settings tile, the share sheet, or the text-selection menu.
- Users glance at status to trust that the link works ("did it cross?").

## Capabilities and Constraints
- Text and images (no other files). Text over 4 KB travels as a text file. Images travel as encrypted files, shrunk to JPEG above 1.5 MB, refused above 10 MB, saved to Pictures/Ferry on arrival. Automatic screenshot sending is opt-in.
- Password-manager copies on Windows are never sent.
- All text is end-to-end encrypted. ntfy.sh keeps encrypted copies up to 12 hours so devices catch up after a disconnection; copies older than 10 minutes go to history, not the clipboard.
- Both apps keep the last 10 crossings and offer updates from GitHub releases (public repo ulot2/ferry).
- Anyone who learns the pairing code can read and send clipboard text, so it is treated as a secret and can be reset.
- Phone scanning uses Google's code scanner (needs Google Play services). Typing the 26-character pairing code is the fallback.
- Builds run on GitHub Actions; the laptop has little free disk space.

## Brand Commitments
- Name: Ferry. A "crossing" is one clipboard transfer.
- Visual direction "Nautical chart", chosen by the owner from four options: the two devices are harbors on a sea chart joined by a magenta course line, the history is a logbook, same look on phone and laptop. Light mode is a day chart; dark mode is soft charcoal (the owner rejected pure black as harsh). Details in DESIGN.md.

## Evidence on Hand
No users, reviews, or metrics. Do not invent any.

## Product Principles
1. Trust comes from visible state: always show whether the link is up and what crossed last.
2. Zero typing: pairing is a scan, sending is a tap.
3. Never surprise with data: say plainly what leaves the device and where it goes.
4. Recover without help: reconnect after sleep, restart, and network loss.

## Accessibility & Inclusion
Follow system font size (sp units on Android), meet WCAG AA contrast in light and dark, 48 dp touch targets, keyboard access on Windows.
