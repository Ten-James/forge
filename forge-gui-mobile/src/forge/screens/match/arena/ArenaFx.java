package forge.screens.match.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;

import forge.Graphics;
import forge.gui.FThreads;
import forge.game.card.CardView.CardStateView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.card.ColorSet;
import forge.screens.match.MatchController;
import forge.screens.match.MatchScreen;
import forge.screens.match.views.VPlayerPanel;
import forge.toolbox.FCardPanel;
import forge.card.CardRenderer;
import forge.card.CardRenderer.CardStackPosition;

/**
 * The effect stack of the Arena board: everything that exists only for a moment and is drawn over the board, in one
 * pass after the cards (port of the idea of the Swing {@code ArenaFx}, grown for the GPU). Effects are queued from
 * anywhere in the match code, each runs off an {@link ArenaMotion} tween, and the stack costs nothing while empty.
 *
 * <ul>
 * <li><b>Flights</b>: a card (or a card back) that travels in an arc between two places of the board, turning over on
 *     the way when it goes from a back to a face. Used for a card drawn from the library into the hand, a card
 *     fetched from the library, and a spell or ability going onto the stack. A flight's target is read again every
 *     frame, so the card lands exactly where the layout ends up putting it, however that moves meanwhile.</li>
 * <li><b>Rings</b> and <b>bursts</b>: the ripple of light where something lands, and the sparks of something
 *     resolving, in the colour of what caused them.</li>
 * </ul>
 *
 * <p>Draws of the library are noticed here by watching the zone sizes (a drop of the library followed by a growing
 * hand is a draw), so no part of the rules code needs to know about the screen. A match's first seconds (the opening
 * hand) and anything past {@link #MAX_EFFECTS} just appear, so a wipe or a loaded game never floods the screen. All
 * of it is off with the Arena effects setting.</p>
 */
public final class ArenaFx {
    private ArenaFx() { }

    /** The most effects alive at once, and flights among them. A board wipe or a big draw stays readable. */
    public static final int MAX_EFFECTS = 40, MAX_FLIGHTS = 10;
    /** A flight's length, a draw's, and the gap between cards that go together. */
    private static final int DRAW_MS = 420, STACK_MS = 380, RING_MS = 520, BURST_MS = 640;
    /** The opening hand and the first moments of a game are not animated. */
    private static final float QUIET_SECONDS = 2.5f;
    /** A drop of the library counts for a draw this long. */
    private static final int DRAW_WINDOW_MS = 1600;
    private static final int MOST_BACKS_AT_ONCE = 4;
    private static final int SPARKS = 9;

    private static final Color S1 = new Color(), S2 = new Color(), S3 = new Color();
    private static final Color WARM = ArenaTheme.rgb(0xFFE3A8);

    private static final int RING = 0, BURST = 1, FLIGHT = 2;

    private static final class Fx {
        int kind;
        float t;                // 0..1 plain progress
        float x, y, size;       // a ring or burst: where, how large it grows
        Color color = new Color();
        ArenaMotion.Tween tween;
        // flights
        int id;                 // what the flight is for (a card's id, a stack entry's id); 0 for none
        CardView card;          // the face it turns up (null: a back all the way)
        PlayerView owner;       // whose sleeve the back wears
        final Rectangle from = new Rectangle(), to = new Rectangle();
        boolean live;           // the target is read from the layout again every frame
        boolean flip;           // back first, face second
        boolean landed;
        float lift;             // how high the arc rises, as a share of the card's height
        float seed;
    }

    private static final List<Fx> fx = new ArrayList<>();
    private static final Map<Integer, Fx> flightsById = new HashMap<>();
    private static final Set<Integer> seenOnStack = new HashSet<>();
    private static float gameStart = ArenaMotion.seconds();

    // what the zone sizes said last time (per player id), and how many draws are waiting for their cards
    private static final Map<Integer, int[]> zones = new HashMap<>();
    private static long polledFrame = Long.MIN_VALUE;
    private static final Rectangle SCRATCH = new Rectangle();
    /** Ids of flights that stand for no card or entry (an opponent's card back): below zero, counting down. */
    private static int backIds = -1;

    // ---- state -------------------------------------------------------------------------------------------------------

    /** Forgets everything (a new game, an undo, leaving the match). Any thread. */
    public static void clear() {
        FThreads.invokeInEdtNowOrLater(ArenaFx::reset);
    }

    private static void reset() {
        for (final Fx e : fx) {
            if (e.tween != null) {
                e.tween.cancel();
            }
        }
        fx.clear();
        flightsById.clear();
        seenOnStack.clear();
        zones.clear();
        gameStart = ArenaMotion.seconds();
    }

    /** Whether anything is on its way now. */
    public static boolean isShowing() {
        return !fx.isEmpty();
    }

    private static boolean on() {
        return ArenaTheme.isEffectsOn() && ArenaTheme.isMatchBoard();
    }

    private static boolean quiet() {
        return ArenaMotion.seconds() - gameStart < QUIET_SECONDS;
    }

    // ---- rings and bursts ----------------------------------------------------------------------------------------------

    /** The ripple of light where something landed: a ring that spreads and thins, with a soft glow under it. */
    public static void ripple(final float x, final float y, final float size, final Color color) {
        if (!on() || fx.size() >= MAX_EFFECTS) {
            return;
        }
        final Fx e = add(RING, x, y, size, color);
        e.tween = ArenaMotion.play(RING_MS, ArenaMotion.LINEAR, t -> e.t = t, () -> fx.remove(e));
    }

    /** Sparks and a ring: something resolved or went off here, in this colour. */
    public static void burst(final float x, final float y, final float size, final Color color) {
        if (!on() || fx.size() >= MAX_EFFECTS) {
            return;
        }
        final Fx e = add(BURST, x, y, size, color);
        e.seed = (x * 0.37f + y * 0.71f) % 6.2831f;
        e.tween = ArenaMotion.play(BURST_MS, ArenaMotion.LINEAR, t -> e.t = t, () -> fx.remove(e));
    }

    private static Fx add(final int kind, final float x, final float y, final float size, final Color color) {
        final Fx e = new Fx();
        e.kind = kind;
        e.x = x;
        e.y = y;
        e.size = size;
        e.color.set(color);
        fx.add(e);
        return e;
    }

    /** The colour a card's effects take: its frame colour (gold for colourless and artifacts). */
    public static Color colorOf(final CardView card, final Color out) {
        if (card == null) {
            return out.set(WARM);
        }
        final CardStateView state = card.getCurrentState();
        final ColorSet colors = state.getColors();
        if (colors == null || colors.isColorless()) {
            return out.set(state.isLand() ? ArenaTheme.GOLD_BRIGHT : WARM);
        }
        return out.set(ArenaTheme.mix(ArenaTheme.frameColor(colors, state.isLand(), state.isArtifact()), ArenaTheme.WHITE, 0.35f, S3));
    }

    // ---- flights ---------------------------------------------------------------------------------------------------------

    /**
     * A card-shaped thing flying from one place to another, for effects the match code starts itself (a spell going
     * onto the stack). {@code to} is read again on every {@link #retarget} call; without them it lands where it said.
     * Does nothing (and says so) when the effects are off or too many are running: show the card straight away then.
     *
     * @param id    what it stands for (a stack entry's id); {@link #isFlying(int)} and {@link #retarget} find it by that
     * @param card  the face it turns up, or null for a back all the way
     * @param flip  start as a back and turn over to the face on the way
     * @return whether the flight started
     */
    public static boolean fly(final int id, final CardView card, final PlayerView owner, final Rectangle from, final Rectangle to, final boolean flip,
            final int millis, final int delayMs) {
        if (!on() || fx.size() >= MAX_EFFECTS || flightsById.size() >= MAX_FLIGHTS || from == null || to == null || to.width <= 0 || flightsById.containsKey(id)) {
            return false;
        }
        final Fx e = new Fx();
        e.kind = FLIGHT;
        e.id = id;
        e.card = card;
        e.owner = owner;
        e.from.set(from);
        e.to.set(to);
        e.live = true;
        e.flip = flip;
        e.lift = 0.22f;
        colorOf(card, e.color);
        fx.add(e);
        if (id != 0) {
            flightsById.put(id, e);
        }
        e.tween = ArenaMotion.play(millis, delayMs, ArenaMotion.SMOOTH, t -> e.t = t, () -> land(e));
        return true;
    }

    private static void land(final Fx e) {
        fx.remove(e);
        if (e.id != 0 && flightsById.get(e.id) == e) {
            flightsById.remove(e.id);
        }
        if (!e.landed) {
            e.landed = true;
            final float cx = e.to.x + e.to.width / 2f, cy = e.to.y + e.to.height / 2f;
            ripple(cx, cy + e.to.height * 0.2f, Math.max(e.to.width, ArenaTheme.px(60)) * 0.9f, e.color);
        }
    }

    /** Whether the flight for {@code id} is still in the air: what it stands for stays hidden until it lands. */
    public static boolean isFlying(final int id) {
        return flightsById.containsKey(id);
    }

    /** Tells a flight where its target is now (screen coordinates of the thing it is flying to). */
    public static void retarget(final int id, final float x, final float y, final float w, final float h) {
        final Fx e = flightsById.get(id);
        if (e != null) {
            e.to.set(x, y, w, h);
        }
    }

    // ---- the stack: a spell or ability going on it ---------------------------------------------------------------------

    /**
     * An entry of the stack, on screen for the first time (called from its draw): the card flies to its place from where
     * it was cast or activated, the caster's hand, or the permanent's tile. Entries seen before, and the effects being
     * off, do nothing. {@code to} is the entry's place on the screen.
     */
    public static void stackEntryShown(final int itemId, final CardView source, final PlayerView activator, final boolean ability,
            final Rectangle to, final Rectangle handStart) {
        if (!seenOnStack.add(itemId) || !on() || quiet()) {
            return;
        }
        final Rectangle from = originOf(source, activator, ability, handStart);
        if (from == null) {
            return;
        }
        // a flight lands bigger than it starts: it grows into the large card of the stack
        final float h = Math.max(from.height, to.height * 0.5f);
        final Rectangle start = SCRATCH;
        start.set(from.x + from.width / 2f - h / FCardPanel.ASPECT_RATIO / 2f, from.y + from.height / 2f - h / 2f, h / FCardPanel.ASPECT_RATIO, h);
        fly(itemId, source, null, start, to, false, STACK_MS, 0);
    }

    private static Rectangle originOf(final CardView source, final PlayerView activator, final boolean ability, final Rectangle handStart) {
        if (handStart != null && handStart.width > 0) {
            return handStart;
        }
        final VPlayerPanel panel = activator == null ? null : MatchScreen.getPlayerPanel(activator);
        if (ability && source != null) {
            final forge.screens.match.views.VCardDisplayArea.CardAreaPanel tile = forge.screens.match.views.VCardDisplayArea.CardAreaPanel.peek(source);
            if (tile != null && tile.screenPos.width > 0 && source.getZone() == ZoneType.Battlefield) {
                return tile.screenPos;
            }
        }
        if (panel != null) {
            final Rectangle hand = panel.getArenaOpponentHandRect();
            if (hand != null) {
                return hand;
            }
            if (panel.getAvatar() != null && panel.getAvatar().screenPos.width > 0) {
                return panel.getAvatar().screenPos;
            }
        }
        return null;
    }

    /** A stack entry that is gone (resolved, countered): sparks where it stood, in its colour. */
    public static void stackEntryGone(final int itemId, final CardView source, final Rectangle where) {
        seenOnStack.remove(itemId);
        if (where == null || where.width <= 0) {
            return;
        }
        final Color c = colorOf(source, S1);
        burst(where.x + where.width / 2f, where.y + where.height / 2f, Math.max(where.width, ArenaTheme.px(80)), c);
    }

    // ---- the library: draws -------------------------------------------------------------------------------------------

    /**
     * Watches the zone sizes of every player: a drop of the library is a draw (or a fetch) in waiting; an opponent's
     * hand growing then sends backs from their library to their hand, and the local player's cards claim their
     * waiting draws in {@link #claimDraw}. Once per frame, on the render thread, from here or from {@link #claimDraw}.
     */
    private static void poll() {
        final long frame = ArenaMotion.frame();
        if (polledFrame == frame) {
            return;
        }
        polledFrame = frame;
        final GameView game = MatchController.instance == null ? null : MatchController.instance.getGameView();
        if (game == null || game.getPlayers() == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final PlayerView player : game.getPlayers()) {
            final int lib = player.getZoneSize(ZoneType.Library), hand = player.getZoneSize(ZoneType.Hand);
            int[] z = zones.get(player.getId());
            if (z == null) {
                z = new int[] {lib, hand, 0, 0};
                zones.put(player.getId(), z);
                continue;
            }
            // z: library size, hand size, draws waiting, when the last one began waiting (as an int of ms: a rolling stamp)
            if (lib < z[0]) {
                z[2] += z[0] - lib;
                z[3] = (int) now;
            }
            if (z[2] > 0 && (int) now - z[3] > DRAW_WINDOW_MS) {
                z[2] = 0;
            }
            if (hand > z[1] && z[2] > 0 && !ArenaBoard.isMe(player) && on() && !quiet()) {
                final int n = Math.min(Math.min(z[2], hand - z[1]), MOST_BACKS_AT_ONCE);
                sendBacks(player, n);
                z[2] -= Math.min(z[2], hand - z[1]);
            }
            z[0] = lib;
            z[1] = hand;
        }
    }

    private static void sendBacks(final PlayerView player, final int n) {
        final VPlayerPanel panel = MatchScreen.getPlayerPanel(player);
        if (panel == null) {
            return;
        }
        final Rectangle to = panel.getArenaOpponentHandRect();
        final Rectangle from = libraryOf(panel);
        if (to == null || from == null) {
            return;
        }
        final float ch = Math.max(ArenaTheme.px(40), to.height * 0.9f), cw = ch / FCardPanel.ASPECT_RATIO;
        final Rectangle target = new Rectangle(to.x + to.width / 2f - cw / 2f, to.y + to.height - ch, cw, ch);
        for (int i = 0; i < n; i++) {
            final Rectangle start = new Rectangle(from.x + from.width / 2f - cw / 2f, from.y + from.height / 2f - ch / 2f, cw, ch);
            fly(backIds--, null, player, start, target, false, DRAW_MS, i * ArenaMotion.STAGGER_MS);
        }
    }

    private static Rectangle libraryOf(final VPlayerPanel panel) {
        final Rectangle pile = panel.getArenaPileRect(ZoneType.Library);
        if (pile != null && pile.width > 0) {
            return pile;
        }
        return panel.getAvatar() != null && panel.getAvatar().screenPos.width > 0 ? panel.getAvatar().screenPos : null;
    }

    /**
     * The local hand fan asks this for each card it draws: a card seen in the hand for the first time right after the
     * library gave one up flies in from the library pile, turning from its back to its face. While it flies this
     * returns true and the fan draws nothing for it; the flight's target follows the slot the fan gives it.
     *
     * @param st   the card's Arena state (remembers that it has been looked at)
     * @param slot the hand card's panel on the screen (the card sits at its top, {@code cardHeight} tall)
     */
    public static boolean claimDraw(final CardView card, final Rectangle slot, final float cardHeight, final ArenaCards.State st) {
        if (!flightsById.isEmpty() && flightsById.containsKey(card.getId())) { // already on its way: its target follows the slot
            retarget(card.getId(), slot.x, slot.y, slot.width, cardHeight);
            return true;
        }
        if (st.drawChecked || !on()) {
            return false;
        }
        poll();
        st.drawChecked = true; // a card is looked at once: the one that arrives first is the one a draw is meant for
        if (quiet()) {
            return false;
        }
        final PlayerView owner = card.getController() != null ? card.getController() : card.getOwner();
        final int[] z = owner == null ? null : zones.get(owner.getId());
        if (z == null || z[2] <= 0) {
            return false;
        }
        final VPlayerPanel panel = MatchScreen.getPlayerPanel(owner);
        final Rectangle lib = panel == null ? null : libraryOf(panel);
        if (lib == null) {
            return false;
        }
        z[2]--;
        final float w = slot.width, h = cardHeight;
        final Rectangle from = new Rectangle(lib.x + lib.width / 2f - w * 0.35f, lib.y + lib.height / 2f - h * 0.35f, w * 0.7f, h * 0.7f);
        final Rectangle to = new Rectangle(slot.x, slot.y, w, h);
        return fly(card.getId(), card, owner, from, to, true, DRAW_MS, 0);
    }

    // ---- drawing -----------------------------------------------------------------------------------------------------------

    /** Draws every effect over the board (the match screen's overlay calls this each frame after the cards). Render thread. */
    public static void draw(final Graphics g) {
        if (!on()) {
            if (!fx.isEmpty()) {
                clear();
            }
            return;
        }
        poll();
        if (fx.isEmpty()) {
            return;
        }
        // flights under rings and sparks, so a landing's light is on top of the card
        for (int i = 0, n = fx.size(); i < n; i++) {
            final Fx e = fx.get(i);
            if (e.kind == FLIGHT && e.t > 0f) {
                drawFlight(g, e);
            }
        }
        for (int i = 0, n = fx.size(); i < n; i++) {
            final Fx e = fx.get(i);
            if (e.kind == RING) {
                drawRing(g, e);
            } else if (e.kind == BURST) {
                drawBurst(g, e);
            }
        }
    }

    private static void drawRing(final Graphics g, final Fx e) {
        final float out = ArenaMotion.OUT.at(e.t);
        final float fade = 1f - e.t;
        final float r = e.size * (0.25f + 0.75f * out);
        ArenaArt.halo(g, e.x, e.y, r * 1.1f, e.color, 0.35f * fade * fade);
        g.drawSmoothCircle(Math.max(1.5f, ArenaTheme.px(3f) * fade), ArenaTheme.alpha(e.color, 0.85f * fade, S1), e.x, e.y, r);
        final float r2 = r * 0.72f;
        g.drawSmoothCircle(Math.max(1f, ArenaTheme.px(1.5f)), ArenaTheme.alpha(ArenaTheme.WHITE, 0.55f * fade, S2), e.x, e.y, r2);
    }

    private static void drawBurst(final Graphics g, final Fx e) {
        final float out = ArenaMotion.OUT.at(e.t);
        final float fade = 1f - e.t;
        final float flash = ArenaMotion.pop(ArenaMotion.clamp01(e.t * 2.2f));
        ArenaArt.halo(g, e.x, e.y, e.size * (0.5f + 0.5f * out), ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.4f, S2), 0.5f * flash);
        g.drawSmoothCircle(Math.max(1.5f, ArenaTheme.px(2.5f) * fade), ArenaTheme.alpha(e.color, 0.8f * fade, S1), e.x, e.y, e.size * 0.6f * out);
        for (int i = 0; i < SPARKS; i++) {
            final float a = e.seed + i * (6.2831f / SPARKS) + (i % 2) * 0.28f;
            final float reach = e.size * (0.45f + 0.35f * ((i * 37) % 10) / 10f) * out;
            final float sx = e.x + (float) Math.cos(a) * reach, sy = e.y + (float) Math.sin(a) * reach - e.size * 0.12f * out * out;
            final float r = Math.max(1.5f, e.size * 0.045f * (1f - e.t * 0.6f));
            g.fillSmoothCircle(ArenaTheme.alpha(ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.5f, S2), 0.9f * fade, S1), sx, sy, r);
        }
    }

    private static void drawFlight(final Graphics g, final Fx e) {
        final float t = e.t, m = ArenaMotion.SMOOTH.at(t);
        final float h = ArenaMotion.lerp(e.from.height, e.to.height, m), w = ArenaMotion.lerp(e.from.width, e.to.width, m);
        final float fcx = e.from.x + e.from.width / 2f, fcy = e.from.y + e.from.height / 2f;
        final float tcx = e.to.x + e.to.width / 2f, tcy = e.to.y + e.to.height / 2f;
        // the straight way, plus an arc that rises in the middle (more for a longer way) and settles at both ends
        final float dist = (float) Math.hypot(tcx - fcx, tcy - fcy);
        final float rise = Math.min(h * e.lift, dist * 0.2f) * (float) Math.sin(Math.PI * t);
        final float cx = ArenaMotion.lerp(fcx, tcx, m), cy = ArenaMotion.lerp(fcy, tcy, m) - rise;
        // the card is bigger in the air, nearer the viewer, and turns over (back first) in the first two thirds
        final float air = 1f + 0.12f * (float) Math.sin(Math.PI * t);
        final float flipT = e.flip ? ArenaMotion.clamp01(t / 0.66f) : 1f;
        final float squash = Math.max(0.04f, (float) Math.abs(Math.cos(Math.PI * flipT)));
        final boolean showBack = e.card == null || e.flip && flipT < 0.5f;
        final float cw = w * air, ch = h * air;
        final float drawW = e.flip ? cw * squash : cw;
        final float x = cx - drawW / 2f, y = cy - ch / 2f;
        final float fadeIn = ArenaMotion.clamp01(t / 0.12f);
        final float old = g.getfloatAlphaComposite();
        g.setAlphaComposite(old * fadeIn);
        // a shadow on the table that stays under the card's place and shrinks as the card rises
        final float shadowRise = (float) Math.sin(Math.PI * t);
        ArenaCards.cardShadow(g, x + ch * 0.02f * shadowRise, y + ch * 0.03f * shadowRise, drawW, ch, drawW * 0.045f);
        final float tilt = (float) Math.sin(Math.PI * t) * 9f; // leans back as it rises, flat again as it lands
        if (showBack) {
            ArenaCards.drawCardBack(g, e.owner, x, y, drawW, ch, Math.max(ArenaTheme.px(3), drawW / 18f), false);
        } else {
            final TextureRegion face = ArenaCards.faceOf(e.card);
            if (face != null && tilt > 0.5f) {
                g.drawTiltedImage(face, x, y, drawW, ch, tilt, 0f, 0f, 1f, drawW * 0.045f, 0.6f, 0f, 1f);
            } else {
                CardRenderer.drawCard(g, e.card, x, y, drawW, ch, CardStackPosition.Top);
            }
        }
        if (!showBack) {
            g.drawSmoothRoundRect(Math.max(1f, ch * 0.006f), ArenaTheme.alpha(e.color, 0.7f * (1f - m), S1), x, y, drawW, ch, drawW * 0.045f);
        }
        g.setAlphaComposite(old);
    }
}
