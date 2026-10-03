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
No account and no server of its own. The two devices pair by scanning a QR code; the code holds a secret topic on the public ntfy.sh relay. Laptop-to-phone is fully automatic. Phone-to-laptop takes one tap because Android blocks background clipboard reads.

## Operating Context
- The desktop app sits in the Windows tray and starts with Windows. Its window opens for pairing and status.
- The phone app runs a foreground service with a status notification. Sending happens from the notification button, a quick-settings tile, the share sheet, or the text-selection menu.
- Users glance at status to trust that the link works ("did it cross?").

## Capabilities and Constraints
- Text only. Images are skipped. Text over 4 KB travels as a text file.
- Password-manager copies on Windows are never sent.
- Laptop copies are not stored on ntfy.sh. Phone copies stay there up to 12 hours.
- Anyone who learns the topic can read and send clipboard text, so the topic is treated as a secret and can be reset.
- Phone scanning uses Google's code scanner (needs Google Play services). Manual topic entry is the fallback.
- Builds run on GitHub Actions; the laptop has little free disk space.

## Brand Commitments
- Name: Ferry. A "crossing" is one clipboard transfer.
- Visual direction "Harbor", chosen by the owner: deep navy with a signal-yellow accent, last transfer shown as a boarding-pass style crossing card, same look on phone and laptop.

## Evidence on Hand
No users, reviews, or metrics. Do not invent any.

## Product Principles
1. Trust comes from visible state: always show whether the link is up and what crossed last.
2. Zero typing: pairing is a scan, sending is a tap.
3. Never surprise with data: say plainly what leaves the device and where it goes.
4. Recover without help: reconnect after sleep, restart, and network loss.

## Accessibility & Inclusion
Follow system font size (sp units on Android), meet WCAG AA contrast in light and dark, 48 dp touch targets, keyboard access on Windows.
