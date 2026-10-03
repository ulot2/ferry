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
- Stub time ("14:02"): 28sp, medium, tabular figures.
- Body 16sp, secondary 14sp.

## Signature: the crossing ticket
A surface card with 16dp corners, split by a dashed perforation with two semicircle notches cut from top and bottom edges. Right of the perforation is the stub, filled signal yellow, holding the time. Left side: kicker, route line, two-line preview of the text. The ticket overlaps the bottom of the navy header by 40dp. Empty state keeps the ticket shape with a dash in the stub.

## Components
- Primary button: signal fill, on-signal text, pill shape, 56dp tall, full width. One per screen.
- Secondary button: outline 1dp, ink text, pill, 48dp.
- Text button: ink text, no fill, 48dp touch area.
- Status pill (in header): lamp dot 8dp + label, translucent white fill (12% on navy).
- Plain section cards on the ground: surface fill, 16dp corners, no border in light, 1dp outline in dark. Never nest cards.
- Notices (battery, Xiaomi autostart): same section card, title + one sentence + action. No colored side borders.

## Layout
- Android: edge-to-edge; navy header runs under the status bar. 24dp side margins, 16dp between cards, 32dp above section titles.
- Windows: fixed 380 × 640 px (at 100% scaling) window, same stack: header, ticket, then pairing or paired section.

## Motion
One moment: when a new crossing lands, the ticket slides up 8dp and fades in (200ms, decelerate). Respect the system "remove animations" setting with an instant swap.
