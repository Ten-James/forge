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
- It is baked into one texture of up to 2560 px wide on a worker thread (no dropped frames on 144/240 Hz monitors), uploaded
  on the render thread, and faded in over the old picture when the size or the day/night mood changes. It is kept from
  match to match and let go when the Arena look or **Arena Clean 3D Board** is turned off, which brings back the old
  flagstone floor with banners.

## Effects (reduce motion)

**Arena Effects** is the reduce-motion switch. Off means: no flights of drawn or cast cards, no rings, sparks or bolts,
cards and tokens just appear and leave (no enter/leave flights, no token pop), no damage jolt, red hit flash or life-ring
flash, the life total changes at once, the turn banner only fades (no slide), arrows show whole with no pulse running
along them, and nothing breathes (glows stay lit and ask for no frames). Damage numbers still show.

| Effect | What it does |
| --- | --- |
| Draw | Cards you draw fly from your library pile to their slot in the hand fan, turning from back to face, one after another. An opponent's draws send card backs to their hand. Read from the zone updates (library and hand in the same update, and only the cards that really were in the library), so a mill, a bounce or a mulligan is no draw. |
| Cast | A spell or ability that joins the stack flies onto the stack's large card from your hand, the caster's portrait or the permanent's tile, and lands with a ring. What was already on the stack, or was hidden deep in it, just appears. |
| Resolve | The engine says when an entry resolved. An instant or sorcery that resolved sparks and sends a bolt to what it aimed at; a fizzled one and an ability make a small neutral burst; a permanent spell makes none (its landing is the accent). A spell that is gone without the engine saying it resolved was countered and shatters in red. |
| Land | A permanent entering the battlefield lands with a ring of light. |
| Arrows | Targeting arcs draw themselves in from the source as a tapered ribbon and end in a chevron; they do not draw in again when the layout moves. |

While a card or stack entry waits to fly or flies, a ghost with a clear rim holds its slot (a target keeps its glow), and
touching it ends the flight at once. In the last part of a flight the real card is drawn again and the flight fades out
above it. Effects are drawn under the prompt bar and its buttons in every prompt mode. Nothing animates before the first
turn begins, and anything past 40 effects at once just appears.

## For developers

- `ArenaFx` is the engine (effect list, motion, drawing); `ArenaDrawFlights` starts draw flights from zone updates;
  `ArenaStackFx` starts stack flights, is the one tracker of what the stack showed, and decides how an entry went off
  (`IGuiGame.notifyStackResolved` carries the engine's "resolved" word). Draw and stack flights live in separate maps.
- `ArenaTableBoard` is the board; `ArenaBoardPainter` is the flagstone mat.
- Anything that breathes must use `ArenaMotion.pulse` (constant when effects are off); new motion should check
  `ArenaTheme.isEffectsOn()`.
