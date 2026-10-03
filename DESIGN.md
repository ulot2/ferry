# Design: Harbor

One world for the Android app and the Windows tray app.

## Idea
A ferry terminal at night: a deep-navy field, a signal-yellow lamp, and a boarding pass for every crossing. The screens answer one question at a glance: is the link up, and what crossed last?

## Color
Committed strategy: a navy field owns the top of every screen in both themes. Yellow is rare and means "act" or "ticket".

| Role | Light | Dark |
|---|---|---|
| Harbor (header field) | `#0F2742` | `#0F2742` |
| On harbor (text on navy) | `#F4F7FB` | `#F4F7FB` |
| On harbor, muted | `#A9BAD0` | `#A9BAD0` |
| Signal (primary action, ticket stub) | `#F2C230` | `#F2C230` |
| On signal | `#0F2742` | `#0F2742` |
| Ground (page) | `#E9EEF4` | `#08121F` |
| Surface (cards, ticket) | `#FFFFFF` | `#13233A` |
| Ink (primary text) | `#0F2742` | `#E6EDF5` |
| Ink, muted | `#4A5B70` | `#9AAABD` |
| Outline | `#C9D3DE` | `#2A3D55` |
| Starboard (connected lamp) | `#1F9D61` | `#3DD68C` |
| Port (offline lamp, errors) | `#C9353A` | `#FF6B6F` |

Lamps follow ship navigation lights: green = connected, red = offline, on-harbor muted = connecting, paused or not paired. On the navy header both themes use the bright lamps: `#3DD68C` green and `#FF6B6F` red. Starboard and Port above are for text on the ground.

## Type
System faces only: Roboto on Android (sp units, Material type roles), Segoe UI on Windows.
- Wordmark: "FERRY", medium weight, 14sp, letter-spacing 0.24em, signal yellow on harbor. The only tracked uppercase text besides the ticket's "LAST CROSSING" kicker.
- Route line ("Laptop → Phone"): Title Large, 22sp, medium.
- Stub time ("5:36"): 26sp, medium, tabular figures, digits only. Day under it: 11sp, tracked; on a 12-hour clock it starts with AM/PM ("PM · TODAY"), so the time always fits the stub.
- Header line 15sp (on-harbor muted). Card titles 16sp medium. Body and preview 14sp, line height 1.25.

## Signature: the crossing ticket
A surface card with 16dp corners, split by a dashed perforation with two semicircle notches cut from top and bottom edges. Right of the perforation is the stub, filled signal yellow, holding the time. Left side: kicker, route line, two-line preview of the text. The ticket overlaps the bottom of the navy header by 48dp. In the dark theme it gets a 1dp outline edge, because surface and harbor are close in tone. Empty state keeps the ticket shape with a dash in the stub.

## Components
- Primary button: signal fill, on-signal text, pill shape, 56dp tall, full width. One per screen.
- Secondary button: outline 1dp, ink text, pill, 48dp.
- Text button: ink text, no fill, 48dp touch area.
- Status pill (in header): lamp dot 8dp + label, translucent white fill (12% on navy).
- Plain section cards on the ground: surface fill, 16dp corners, no border in light, 1dp outline in dark. Never nest cards.
- Notices (update ready, automatic sending, battery, Xiaomi autostart): same section card, title + one or two sentences + action. No colored side borders. Shown only when they apply.
- History (earlier crossings): a section card listing up to 9 rows under the ticket. Each row: muted meta line (direction · time, 12sp, tabular figures) over one ink line of preview text. Tap (phone) or double-click/Enter (laptop) copies it again. Not tracked uppercase.
- Arrival notices: phone uses a silent heads-up notification that clears after 8 s; laptop uses a tray pop-up. Both show the sender and a one-line preview.

## Layout
- Android: edge-to-edge; navy header runs under the status bar. 24dp side margins, 16dp between cards, 32dp above section titles.
- Windows: fixed 380 px wide window (at 100% scaling), 628 px tall while pairing and 480 px when paired. Same stack: header, ticket, then pairing or paired section. The title bar is painted harbor navy on Windows 11 so it joins the header. The QR code always sits on a white plate with navy modules, in both themes.

## Motion
One moment: when a new crossing lands, the ticket slides up 8dp and fades in (200ms, decelerate). Respect the system "remove animations" setting with an instant swap.
