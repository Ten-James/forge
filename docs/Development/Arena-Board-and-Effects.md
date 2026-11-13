# Arena board and effects (libGDX client)

The libGDX Arena look (`forge-gui-mobile/src/forge/screens/match/arena/`) has a calm 3D table for a background and a layer
of effects for what moves. Both are on by default, and both have a setting under **Settings > Graphic Options** (Arena Clean 3D
Board, Arena Effects). The effects switch is also in the match: gear menu > "Effects: on / off".

## The clean 3D board

- A dark felt table seen from above, receding gently to a horizon above the window (shallow on purpose, so the flat cards
  do not fight it), with a fine grid in perspective and one soft key light.
- The two halves are marked: the opponent's half a little cooler, yours a little warmer, with a lit seam where the armies
  meet (at the board's field split).
- Haze and vignette are light so piles and plates at the edges stay readable on the 14-inch touch laptop. On wide windows
  (21:9, 32:9) the extra width is lit from the sides instead of sinking into dark edges.
- The grid's cells are wider than tall and the carved ring is a flat ellipse. A plain gradient of about the same tone stands in
  until the first picture is ready.
- It is baked from the real back-buffer size (sharp on HiDPI and ultrawide) into one texture of up to 2560 px wide (1280 on
  Android) on a worker thread, uploaded in strips over a few frames on the desktop (no dropped frames on 144/240 Hz monitors),
  and faded in over the old picture when the size or the day/night mood changes. A size is baked only once the window has held
  still. It is kept from match to match and let go when the Arena look or **Arena Clean 3D Board** is turned off, which brings
  back the old flagstone floor with banners.

## Effects (reduce motion)

**Arena Effects** is the reduce-motion switch (Settings > Graphic Options, or the **Arena Effects** checkbox in the match's gear
menu). Off means: no flights of drawn, cast, milled or discarded cards, no rings, sparks, bolts or shards, cards and tokens
just appear and leave (no enter/leave flights, no token pop), no damage jolt, red hit flash, floating damage or life
numbers or life-ring flash, the life total changes at once, the turn banner only fades (no slide), the board's new picture
replaces the old at once, arrows show whole with no pulse running along them and do not fade out, and nothing breathes
(glows and the waiting ring stay still and ask for no frames).

| Effect | What it does |
| --- | --- |
| Deal and draw | The opening hand is dealt from the library when a game begins. Cards you draw fly from your library pile to their slot in the hand fan, turning from back to face, one after another. An opponent's draws send card backs to their hand. Read from the zone updates (library and hand in the same update, and only the cards that really were in the library), so a bounce or a mulligan is no draw. |
| Mill and discard | Cards that arrive in a graveyard from the library or the hand fly there, face up, from the library pile or the hand. |
| Cast | A spell or ability that joins the stack flies onto the stack's large card from your hand, the caster's portrait or the permanent's tile, and lands with a ring. What was already on the stack, or was hidden deep in it, just appears. |
| Resolve | The engine says when an entry resolved (network clients receive the same event). An instant or sorcery that resolved sparks and sends a bolt along the arrow's bow to what it aimed at, and what it aimed at shows its damage flash, jolt, number and life roll only when the bolt arrives. A fizzled spell and an ability make a small neutral burst; a permanent spell makes none (its landing is the accent). A spell that is gone without the engine saying it resolved, while its controller is still in the game, was countered and breaks into shards. |
| Land | A permanent entering the battlefield lands with a ring of light when it touches down; tokens and lands get a small one, and landings within 90 ms of each other share one. |
| Arrows | Targeting arcs draw themselves in from the source as a tapered ribbon that ends where a chevron head begins, fade out when removed, and do not draw in again when the layout moves. |

A flight lifts off, travels along an arc driven by its progress (rolling a little the way it goes), eases out with a small
overshoot, is at rest in its slot when the real card is drawn again, and then fades out above it. Flight and bolt times grow
with the distance against the window's height, within 0.8x to 1.35x of their base. While a card or stack entry waits to fly or
flies, a dimmed ghost of it with a plain rim holds its slot (a pickable target keeps its glow); a touch on it only ends the
flight, so the real card is seen before anything is cast, played or picked. Effects are drawn under the prompt bar and its
buttons in every prompt mode.

Limits: 40 effects at once and 12 flights among them (the rest just appear); at most 7 cards dealt, 6 drawn, 4 backs, 4 milled
or discarded cards per update; arrows are remembered in 48 slots; nothing but the opening deal animates before the first turn
begins; effects only run where the Arena layout is in use (not in a hotseat game with two prompts).

## For developers

- `ArenaFx` is the engine (effect list, motion, drawing); `ArenaDrawFlights` starts draw flights from zone updates;
  `ArenaStackFx` starts stack flights, is the one tracker of what the stack showed, and decides how an entry went off
  (`IGuiGame.notifyStackResolved` carries the engine's "resolved" word). Draw and stack flights live in separate maps.
- `ArenaTableBoard` is the board; `ArenaBoardPainter` is the flagstone mat.
- Anything that breathes must use `ArenaMotion.pulse` (constant when effects are off); new motion should check
  `ArenaTheme.isEffectsOn()`.
