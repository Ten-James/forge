# Arena board and effects (libGDX client)

The libGDX Arena look (`forge-gui-mobile/src/forge/screens/match/arena/`) has a calm 3D table for a background and a layer
of effects for what moves. Both are on by default, and both have a setting under **Settings > Graphic Options** (Arena Clean 3D
Board, Arena Effects). The effects switch is also in the match: the **Arena Effects** checkbox in the gear menu (the round button
in the top corner).

## The clean 3D board

- A dark felt table seen from above, receding gently to a horizon above the window (shallow on purpose, so the flat cards
  do not fight it), with a fine grid in perspective and one soft key light.
- The two halves are marked: the opponent's half a little cooler, yours a little warmer, with a lit seam where the armies
  meet (at the board's field split); the carved ring is centred on that seam.
- Haze and vignette are light so piles and plates at the edges stay readable on the 14-inch touch laptop. On wide windows
  (21:9, 32:9) the key light's pool widens with the aspect ratio, so the middle of the table stays lit, and a soft band of
  light runs down each flank (strongest a little in from the edge, not at it) instead of dark edges.
- The grid's cells are wider than tall and the carved ring is a flat ellipse. A plain gradient of about the same tone stands in
  until the first picture is ready.
- It is baked from the real back-buffer size (sharp on HiDPI and ultrawide) into one texture of up to 2560 px wide (1280 on
  Android) on a worker thread, uploaded in strips over a few frames on the desktop (no dropped frames on 144/240 Hz monitors;
  each strip asks for the next frame, so even a still screen finishes it), and faded in over the old picture when the size or
  the day/night mood changes. A size is baked only once the window has held still. On Android the texture is a managed one
  whose pixmap is kept; on the desktop it is an ordinary texture and the pixmap is let go. It is kept from match to match and let
  go when the Arena look or **Arena Clean 3D Board** is turned off, which brings back the old flagstone floor with banners.

## Effects (reduce motion)

**Arena Effects** is the reduce-motion switch (Settings > Graphic Options, or the **Arena Effects** checkbox in the match's gear
menu). Off means: no flights of drawn, cast, milled or discarded cards, no rings, sparks, bolts or shards, cards and tokens
just appear and leave (no enter/leave flights, no token pop), no damage jolt, red hit flash, floating damage or life
numbers or life-ring flash, the life total changes at once (the portrait's life roll is gated too), the turn banner only fades
(no slide), the board's new picture replaces the old at once, arrows show whole with no pulse running along them and do not
fade out, and nothing breathes (glows and the waiting ring stay still and ask for no frames). Cards on the board and in the
hand do not glide to a new place, swing over past the tilt when tapped, pop their stats, lift on hover (they are simply up or
down), tilt towards the pointer or change their light by fading; screens and dialogs cut instead of fading over the old
picture; the end screen is simply there, with no beat of waiting; the zoom view opens at once, steps with no slide and
shows a card's other face with no turning over. Kept on purpose even with effects off: the small hover fades of buttons and
menu items (feedback for the pointer, not scenery) and the strip of the zoom view following the card (a short spring).

| Effect | What it does |
| --- | --- |
| Deal and draw | The opening hand is dealt from the library when a game begins. Cards you draw fly from your library pile to their slot in the hand fan, turning from back to face, one after another. An opponent's draws send card backs to their hand. Read from the zone updates (library and hand in the same update, and only the cards that really were in the library), so a bounce or a mulligan is no draw. |
| Mill and discard | Cards that arrive in a graveyard from the library fly there from the library pile, face down, turning over on the way; discards fly from the card's own slot in the hand. A card that arrives in the graveyard off the stack (a spell that resolved) is no discard. |
| Cast | A spell or ability that joins the stack flies onto the stack's large card from your hand, the caster's portrait or the permanent's tile, and lands with a ring. What was already on the stack, or was hidden deep in it, just appears. |
| Resolve | The engine says when an entry resolved (network clients receive the same event). An instant or sorcery that resolved sparks and sends a bolt along the arrow's bow to what it aimed at, and what it aimed at starts its damage flash, jolt, number and life roll from the bolt's own finish (no fixed wait; a hit that no bolt ever comes for shows after about a second at the most, and undo or a new game drops hits that are waiting). A fizzled spell and an ability make a small neutral burst; a permanent spell makes none (its landing is the accent). A spell that is gone without the engine saying it resolved, while its controller is still in the game, was countered and breaks into shards where it stood: its card does not also fly to the graveyard (the pile holds the card back until the shards have fallen). The flight of a resolved spell's card to its pile waits for that verdict. |
| Land | A permanent entering the battlefield lands with a ring of light when it touches down; tokens and lands get a small one, and landings within 90 ms of each other share one. |
| Arrows | Targeting arcs draw themselves in from the source as one solid, tapered ribbon (soft glow ribbons under a bright core, filled and antialiased, no dots) that ends where a solid triangular head begins, with a ring where it lands. A pulse of light runs along a new arrow for its first three cycles, then it rests and asks for no frames. Arrows fade out when removed, are drawn once even when the stack view and the board both show them, and do not draw in again when the layout moves. Sparks and shards are filled antialiased shapes too. |

One motion language: every card that flies (draws, casts, mills, discards, permanents entering the battlefield and cards
leaving it for a pile) lifts off, leaves slowly and quickens, travels along an arc driven by its progress (rolling a little the
way it goes), casts a shadow that softens as it rises, eases out with a small overshoot, is at rest in its slot when the real
card is drawn again, and then fades out above it (`ArenaFx.drawPose`). Flight and bolt times grow with the distance against the
window's height, within 0.8x to 1.35x of their base. Piles and an opponent's hand hold back cards still flying to them (their
count and top card show them only once they land). A flight whose card moves on in the air carries on from where it is to
the new place; one whose card is gone fades out where it is.

While a card you drew is in the air, its slot in the hand is an empty lit hollow (no card face) that leans with the fan and
wears the glow the card will have, so a card you could cast is marked at once; the card the fan draws on landing already has the
flight's lean. A stack entry that waits to fly or flies is held by a dimmed ghost of it with a plain rim (a pickable target keeps
its glow). A touch on a flying card only ends the flight, so the real card is seen before anything is cast, played or picked, and
the next quick touch counts as a first tap; during the opening deal one touch ends the whole deal; a right click still zooms.
Effects are drawn under the prompt bar and its buttons in every prompt mode.

Limits: 40 effects at once, and among them at most 12 draw and stack flights (opponents' card backs and the moves to piles count
only towards the 40; whatever is over a limit just appears); at most 7 cards dealt, 6 drawn, 4 backs, 4 milled or discarded cards
per update; arrows are remembered in 48 slots; nothing but the opening deal animates before the first turn begins; effects only
run where the Arena layout is in use (not in a hotseat game with two prompts).

## Sizes, touch and mouse

- Sizes come from `ArenaTheme.px()` (1:1 at 1440 logical px high, kept between 0.75 and 2.5 of that) times the **Interface
  Size** setting when it is a percentage (not Auto: the board already follows the window); fonts go through
  `ArenaTheme.fontPx`, the one place that limits a size.
- **Pass until the stack resolves** (F2) is also an entry of the gear menu (enabled while the stack is not empty), so it can be
  done with a click or a tap.
- The hover hints (what a pile's light means, a plate's tooltip, the card size slider's label) show under a finger too: a touch
  acts as the pointer for a few seconds.
- A right click on the empty board no longer cancels a decision quietly: the first asks (the Cancel button lights up and says
  "Right-click again to cancel"), the second within a moment cancels. On a touch screen, press Cancel.

## Frames

The client renders on demand. While tweens, springs, flights or holds run, frames come at the display rate; a view that only
breathes (`ArenaMotion.pulse`) gets about 30 frames a second, so a 144 or 240 Hz monitor and a laptop battery are not spent on a
slow glow. Arrows' travelling pulse stops after three cycles.

## For developers

- `ArenaFx` is the engine (effect list, motion, drawing); `ArenaDrawFlights` starts draw flights from zone updates;
  `ArenaStackFx` starts stack flights, is the one tracker of what the stack showed, and decides how an entry went off
  (`IGuiGame.notifyStackResolved` carries the engine's "resolved" word). Draw and stack flights live in separate maps.
- `ArenaTableBoard` is the board; `ArenaBoardPainter` is the flagstone mat.
- Anything that breathes must use `ArenaMotion.pulse` (constant when effects are off); new motion should check
  `ArenaTheme.isEffectsOn()`.
- Shapes that are not axis-aligned (arrow ribbons, heads, sparks, shards) go through `Graphics.fillSmoothRibbon`,
  `fillSmoothWedge`, `fillSmoothCapsule` and `fillSmoothRotatedRect`: filled and antialiased by the Arena shader, never `drawLine`
  or `fillTriangle`.
- A hit that waits for a bolt goes through `ArenaStackFx.whenHit` (it runs now when nothing aims at the target).
