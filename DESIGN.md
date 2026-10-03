# Design: Nautical chart

One world for the Android app and the Windows tray app, chosen by the owner from four directions
(Departure board, Nautical chart, Boarding pass, Native) on the "Ferry redesign options" canvas.
It replaces the earlier "Harbor" design. Light mode is the day chart; dark mode is a soft charcoal
night chart (the owner first chose pure black, then asked for something easier on the eyes). Each app follows the system light/dark setting.

## Idea
Ferry draws your two devices as two harbors on a sea chart, joined by a magenta course line: the
crossing. The history is the ship's logbook. Calm, light, precise; real chart conventions, not
decoration: land at the edges, shallows with depth contours, magenta for routes and the one action,
italic labels for places.

## Color
Strategy: Committed. The chart owns the top of every screen; magenta is rare and means "course" or "act".

| Role | Light (day chart) | Dark (soft charcoal night chart) |
|---|---|---|
| Water (chart field) | `#DCEBF2` | `#1C2127` |
| Contour lines | `#B9D6E4`, `#C8DFEA` | `#2C333B`, `#252B32` |
| Land | `#E9E1CC` | `#26251F` |
| Ground (page) | `#F2F6F8` | `#16191D` |
| Ink (text, harbors) | `#0F2A44` | `#DDE2E6` |
| Ink, muted | `#3D5A73` | `#A0A9B2` |
| Course (line, labels) | `#A3125F` | `#E05AA0` |
| Act (primary button fill) | `#A3125F`, text white | `#C2297A`, text white |
| Rule (section line) | `#C9D9E2` | `#2F363E` |
| Rule (row line) | `#DCE6EC` | `#232930` |
| Chip on (filled) | `#0F2A44`, text `#F2F6F8` | `#DDE2E6`, text `#16191D` |
| Chip off (outline) | `#9DB2C2` | `#47505A` |
| Pill on the chart (status) | `#F2F6F8` | `#16191D` |
| Steady (connected dot) | `#1E8E5A` | `#4CC38A` |
| Adrift (offline dot, errors) | `#B3261E` | `#F2B8B5` |

The QR code always sits on a white plate with near-black modules, in both themes, so any camera reads it.

## Type
Barlow (SIL OFL, bundled in `src/main/res/font`, embedded in the Windows app; license in `FONT-LICENSE-Barlow.txt`).
- Wordmark "FERRY": Barlow Condensed SemiBold, letter-spacing 0.2em, ink.
- Place labels on the chart ("Laptop", "Redmi Note 14", "course 14:02"): Barlow Italic. Italic only for chart labels and the "Logbook" heading.
- Kicker ("LAST CROSSING · LAPTOP TO PHONE"): Barlow Medium 12sp, tracked 0.14em, ink muted. The only tracked uppercase besides the wordmark.
- Last crossing text: Barlow Medium 20sp (phone), 17px (laptop).
- Body and logbook rows: Barlow Regular 15sp; times in Bold.
- Sizes in sp on Android so the system font size applies.

## Signature: the chart
A field of water with soft depth contours, land on the left and right edges, two harbor dots (ink)
where the land meets the water, and a dashed course arc between them with a small arrowhead at its
middle pointing the way the last crossing went. Italic labels name the harbors (this device, the
paired device) and the course time. Status sits on the chart as a pill (dot + "Steady link" /
"Connecting" / "Adrift" / "Not paired").

## Components
- Primary button: Act fill, white Barlow Bold 17sp, full pill, 52dp tall. One per screen.
- Chips (toggles): pill, 44dp tall; on = Chip on fill; off = Chip off outline with ink text.
- Logbook rows: time (Bold) · arrow (course color; → toward the phone, ← toward the laptop) · item, separated by row rules. Tap (phone) or double-click / Enter (laptop) copies again.
- Notices (update ready, automatic sending, battery, Xiaomi autostart, pairing): an outlined box (1dp section rule, 12dp corners) with a Medium title, body in ink muted, and one action. No colored side borders.
- Links ("Show pairing code", "Reset pairing", "Unpair", "Clear"): course color text, no fill.

## Layout
- Android: edge-to-edge; the chart (240dp plus the status-bar inset) runs under the status bar. 20dp side margins, 16dp between blocks.
- Windows: 420 px wide window (at 100% scaling). Title bar painted with the water color (Windows 11). Chart band 150 px, then content.

## Motion
One moment: when a new crossing lands, the content under the chart slides up 8dp and fades in (200ms, decelerate). Respect "remove animations" / reduced motion with an instant swap.
