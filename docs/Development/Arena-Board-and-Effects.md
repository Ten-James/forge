# Arena board and effects (libGDX client)

The libGDX Arena look (`forge-gui-mobile/src/forge/screens/match/arena/`) has a calm 3D table for a background and a layer
of effects for what moves. Both are on by default, and both have a setting under **Settings > Match** (Arena Clean 3D
Board, Arena Effects). The effects switch is also in the match: gear menu > "Effects: on / off".

## The clean 3D board

- A dark felt table seen from above, receding gently to a horizon above the window (shallow on purpose, so the flat cards
  do not fight it), with a fine grid in perspective and one soft key light.
- The two halves are marked: the opponent's half a little cooler, yours a little warmer, with a lit seam where the armies
  meet (at the board's field split).
- Haze and vignette are light so piles and plates at the edges stay readable on the 14-inch touch laptop. On wide windows
  (21:9, 32:9) the extra width is lit from the sides instead of sinking into dark edges.
- It is baked into one texture of up to 2560 px wide, a few rows per frame (no hitch on resize or day/night change; the last
  picture stays stretched meanwhile) and let go when the match is left. Turn **Arena Clean 3D Board** off for the old
  flagstone floor with banners.

## Effects (reduce motion)

**Arena Effects** is the reduce-motion switch. Off means: no flights, no rings or sparks, arrows show whole with no pulse
running along them, and nothing breathes (glows stay lit and ask for no frames).

| Effect | What it does |
| --- | --- |
| Draw | Cards you draw fly from your library to their slot in the hand fan, turning from back to face, one after another. An opponent's draws send card backs to their hand. Read from the zone updates, so a mill or a bounce is no draw. |
| Cast | A spell or ability flies onto the stack's large card from your hand, the caster's portrait or the permanent's tile, and lands with a ring. |
| Resolve | A resolved entry sparks in its colour and a bolt runs to what it aimed at. A permanent spell that ended outside the battlefield was countered and shatters in red. |
| Land | A permanent entering the battlefield lands with a ring of light. |
| Arrows | Targeting arcs draw themselves in from the source and end in a chevron. |

While a card or stack entry flies, a ghost holds its slot (and a target keeps its glow), so it can still be pointed at.
Effects are drawn under the prompt bar and its buttons in every prompt mode. The opening hand and the first 2.5 seconds
of a game, and anything past 40 effects at once, just appear.

## For developers

- `ArenaFx` is the effect stack; flights for draws and for the stack live in separate maps (their ids can be equal).
- `ArenaTableBoard` is the board; `ArenaBoardPainter` is the flagstone mat.
- Anything that breathes must use `ArenaMotion.pulse` (constant when effects are off); new motion should check
  `ArenaTheme.isEffectsOn()`.
