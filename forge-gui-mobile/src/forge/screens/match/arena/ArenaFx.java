package forge.screens.match.arena;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.IntMap;

import forge.Graphics;
import forge.card.CardRenderer;
import forge.card.CardRenderer.CardStackPosition;
import forge.card.ColorSet;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.player.PlayerView;
import forge.gui.FThreads;
import forge.screens.match.MatchController;
import forge.toolbox.FCardPanel;

/**
 * The engine of the Arena board's effects: everything that exists only for a moment and is drawn over the board, in one
 * pass after the cards and under the prompt bar (port of the idea of the Swing {@code ArenaFx}, grown for the GPU).
 * Effects are queued from anywhere in the match code, each runs off an {@link ArenaMotion} tween, and the stack costs
 * nothing while empty. Everything here is off with the Arena effects setting, which is the reduce-motion switch.
 *
 * <p>This class owns the effect list, the flights' motion and the drawing (card flights, rings, bursts, bolts, the
 * shatter of a countered spell, ghosts). What starts the effects lives next to it: {@link ArenaDrawFlights} (cards
 * drawn from the library, read from the zone updates) and {@link ArenaStackFx} (spells and abilities going onto the
 * stack and what happens when they are gone). Draw flights and stack flights are kept in separate maps: a stack
 * item's id and a card's id are different numbers that can be equal.</p>
 *
 * <p>Flights follow a live target and settle into it with a small overshoot. In the last part of a flight the card it
 * stands for is drawn by its own view again and the flight fades out above it, so nothing pops on landing. While a
 * flight is still waiting for its turn or in the air, the view draws a {@link #ghost} in the slot, and finishing the
 * flight (a tap on the card) puts the real card there at once. Anything past {@link #MAX_EFFECTS} just appears.</p>
 */
public final class ArenaFx {
    private ArenaFx() { }

    /** The most effects alive at once, and flights among them. A board wipe or a big draw stays readable. */
    public static final int MAX_EFFECTS = 40, MAX_FLIGHTS = 12;
    static final int DRAW_MS = 600, BACK_MS = 520, STACK_MS = 460;
    static final int BOLT_MS = 420;
    private static final int RING_MS = 520, BURST_MS = 640, COUNTER_MS = 700;
    /** A flight's first part lifts the card off; its travel ends where the crossfade begins, at rest in its slot. */
    private static final float LIFT_END = 0.10f;
    /** From here on a flight is crossfading into the real thing (the view draws it again, the flight fades out above it). */
    private static final float CROSSFADE_FROM = 0.84f;
    /** Flight and bolt times grow and shrink with the way they go, against the window's height, and stay within these bounds. */
    private static final float SLOWEST = 1.35f, FASTEST = 0.8f;
    private static final int SPARKS = 9, SHARDS = 10;

    private static final Color S1 = new Color(), S2 = new Color(), S3 = new Color();
    private static final Color WARM = ArenaTheme.rgb(0xFFE3A8);
    static final Color COUNTER = ArenaTheme.rgb(0xFF4D5E);

    static final int RING = 0, BURST = 1, FLIGHT = 2, BOLT = 3, SHATTER = 4;
    static final int CAT_DRAW = 0, CAT_BACK = 1, CAT_STACK = 2, CAT_MOVE = 3;

    static final class Fx {
        int kind;
        float t;                // 0..1 plain progress (the tween is linear: drawing eases it)
        float x, y, size;       // a ring or burst: where, how large it grows; a bolt: where it starts
        float x2, y2, cx, cy;   // a bolt: where it goes and the bow it follows
        final Color color = new Color();
        ArenaMotion.Tween tween;
        // flights
        int cat, id;            // which channel and what it stands for (a card's id, a stack entry's id)
        CardView card;          // the face it turns up (null: a back all the way)
        PlayerView owner;       // whose sleeve the back wears
        final Rectangle from = new Rectangle(), to = new Rectangle();
        boolean flip;           // back first, face second
        float lean;             // the lean of the slot it flies into (degrees), settled into as it lands
        float lift = 0.26f;     // how high the arc rises, as a share of the card's height
        float seed;
        boolean accent;         // a stack flight: its ring of light has been set off

        /** Whether the card it stands for must stay hidden (its view draws a ghost): waiting or in the air, not yet crossfading. */
        boolean hidesTarget() {
            return t < CROSSFADE_FROM;
        }
    }

    static final List<Fx> fx = new ArrayList<>();
    static final IntMap<Fx> drawFlights = new IntMap<>();
    static final IntMap<Fx> stackFlights = new IntMap<>();
    private static long lastLanding;
    private static final float[] BOW = new float[2];

    // ---- state -------------------------------------------------------------------------------------------------------

    /** Forgets everything (a new game, an undo, leaving the match). Any thread. */
    public static void clear() {
        FThreads.invokeInEdtNowOrLater(() -> {
            for (final Fx e : fx) {
                if (e.tween != null) {
                    e.tween.cancel();
                }
            }
            fx.clear();
            drawFlights.clear();
            stackFlights.clear();
            ArenaDrawFlights.reset();
            ArenaStackFx.reset();
        });
    }

    /** Whether effects play: the Arena layout is in use (not a hotseat game), in a match, and reduce motion is off. */
    static boolean on() {
        return ArenaTheme.isEffectsOn() && ArenaMatchLayout.applies() && ArenaTheme.isMatchBoard();
    }

    /** Before the first turn begins (the opening hand is dealt there, once; mulligans are not animated). */
    static boolean beforeFirstTurn() {
        final GameView game = MatchController.instance == null ? null : MatchController.instance.getGameView();
        return game != null && game.getTurn() < 1;
    }

    /** Before the first turn begins nothing is animated: mulligans just appear. */
    static boolean quiet() {
        final GameView game = MatchController.instance == null ? null : MatchController.instance.getGameView();
        return game == null || game.getTurn() < 1;
    }

    // ---- rings, bursts, bolts ----------------------------------------------------------------------------------------

    /** The ripple of light where something landed: a ring that spreads and thins, with a soft glow under it. */
    public static void ripple(final float x, final float y, final float size, final Color color) {
        timed(RING, x, y, size, color, RING_MS);
    }

    /** Sparks and streaks: something resolved or went off here, in this colour. */
    public static void burst(final float x, final float y, final float size, final Color color) {
        timed(BURST, x, y, size, color, BURST_MS);
    }

    /**
     * The ring of a permanent that touched down on the board. Landings that come together (a token swarm, a land and its
     * trigger) share one: a ring that comes within a moment of the last one is dropped, and a small card's ring is small.
     */
    public static void landingRing(final float x, final float y, final float size, final Color color) {
        final long now = System.nanoTime();
        if (now - lastLanding < 90_000_000L) {
            return;
        }
        lastLanding = now;
        timed(RING, x, y, size, color, RING_MS);
    }

    /** A countered spell breaking up into shards where it stood. */
    static void shatter(final float x, final float y, final float size) {
        timed(SHATTER, x, y, size, COUNTER, COUNTER_MS);
    }

    /** How long a flight or bolt over {@code dist} takes against a base time: longer for a longer way, within bounds. */
    static int scaled(final int base, final float dist) {
        return Math.round(base * Math.max(FASTEST, Math.min(SLOWEST, 0.75f + 0.6f * dist / ArenaBoard.windowH())));
    }

    private static Fx timed(final int kind, final float x, final float y, final float size, final Color color, final int millis) {
        if (!on() || fx.size() >= MAX_EFFECTS) {
            return null;
        }
        final Fx e = new Fx();
        e.kind = kind;
        e.x = x;
        e.y = y;
        e.size = size;
        e.seed = (x * 0.37f + y * 0.71f) % 6.2831f;
        e.color.set(color);
        fx.add(e);
        e.tween = ArenaMotion.play(millis, ArenaMotion.LINEAR, t -> e.t = t, () -> fx.remove(e));
        return e;
    }

    /** A bolt of this colour from where something resolved to what it aims at; it bursts there when it arrives. */
    static void bolt(final float x, final float y, final float tx, final float ty, final Color color, final float size) {
        if (!on() || fx.size() >= MAX_EFFECTS) {
            return;
        }
        final Fx e = new Fx();
        e.kind = BOLT;
        e.x = x;
        e.y = y;
        e.x2 = tx;
        e.y2 = ty;
        e.size = size;
        e.color.set(color);
        ArenaArcs.bow(x, y, tx, ty, BOW);
        e.cx = BOW[0];
        e.cy = BOW[1];
        fx.add(e);
        e.tween = ArenaMotion.play(scaled(BOLT_MS, (float) Math.hypot(tx - x, ty - y)), ArenaMotion.LINEAR, t -> e.t = t, () -> {
            fx.remove(e);
            burst(tx, ty, size * 0.8f, e.color);
        });
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

    // ---- flights -----------------------------------------------------------------------------------------------------

    static Fx fly(final int cat, final int id, final CardView card, final PlayerView owner, final Rectangle from, final Rectangle to,
            final boolean flip, final int millis, final int delayMs) {
        if (!on() || fx.size() >= MAX_EFFECTS || drawFlights.size + stackFlights.size >= MAX_FLIGHTS || from == null || to == null || to.width <= 0) {
            return null;
        }
        final Fx e = new Fx();
        e.kind = FLIGHT;
        e.cat = cat;
        e.id = id;
        e.card = card;
        e.owner = owner;
        e.from.set(from);
        e.to.set(to);
        e.flip = flip;
        colorOf(card, e.color);
        fx.add(e);
        if (cat == CAT_DRAW) {
            drawFlights.put(id, e);
        } else if (cat == CAT_STACK) {
            stackFlights.put(id, e);
        }
        final float dist = (float) Math.hypot(to.x + to.width / 2f - from.x - from.width / 2f, to.y + to.height / 2f - from.y - from.height / 2f);
        e.tween = ArenaMotion.play(scaled(millis, dist), delayMs, ArenaMotion.LINEAR, t -> {
            e.t = t;
            if (e.cat == CAT_STACK && !e.accent && t >= CROSSFADE_FROM) { // it touched down on the stack: its one ring of light
                e.accent = true;
                ripple(e.to.x + e.to.width / 2f, e.to.y + e.to.height * 0.7f, Math.max(e.to.width, ArenaTheme.px(60)) * 0.9f, e.color);
            }
        }, () -> forget(e));
        return e;
    }

    private static void forget(final Fx e) {
        fx.remove(e);
        if (e.cat == CAT_DRAW && drawFlights.get(e.id) == e) {
            drawFlights.remove(e.id);
        } else if (e.cat == CAT_STACK && stackFlights.get(e.id) == e) {
            stackFlights.remove(e.id);
        }
    }

    /** Ends a flight now, with no accent (what it stood for is there already, or gone, or was tapped). */
    static void finish(final Fx e) {
        if (e == null) {
            return;
        }
        if (e.tween != null) {
            e.tween.cancel();
        }
        forget(e);
    }

    // ---- ghosts ------------------------------------------------------------------------------------------------------

    /**
     * What stays in the place of a card that is flying there: the card itself, dimmed, in a plain light rim that is no
     * selection (a target that can be picked keeps its own glow in {@code rim}'s colour). Touching it ends the flight.
     */
    public static void ghost(final Graphics g, final float x, final float y, final float w, final float h, final Color rim, final float alpha,
            final CardView card) {
        final float r = w * 0.045f;
        g.fillSmoothRoundRect(ArenaTheme.alpha(ArenaTheme.NAVY, 0.55f * alpha, S1), x, y, w, h, r);
        if (card != null) {
            final float old = g.getfloatAlphaComposite();
            g.setAlphaComposite(old * 0.42f * alpha);
            CardRenderer.drawCard(g, card, x, y, w, h, CardStackPosition.Top);
            g.setAlphaComposite(old);
        }
        if (rim != null) {
            g.drawGlow(ArenaTheme.alpha(rim, 0.7f * alpha, S2), x, y, w, h, r, h * 0.045f);
        }
        g.drawSmoothRoundRect(Math.max(1.5f, ArenaTheme.px(1.5f)), ArenaTheme.alpha(rim != null ? rim : ArenaTheme.TEXT_DIM, 0.8f * alpha, S3), x, y, w, h, r);
    }

    // ---- drawing -----------------------------------------------------------------------------------------------------

    /** Draws every effect over the board (the match screen's overlay calls this each frame after the cards). Render thread. */
    public static void draw(final Graphics g) {
        if (!on()) {
            if (!fx.isEmpty()) {
                clear();
            }
            return;
        }
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
            switch (e.kind) {
            case RING: drawRing(g, e); break;
            case BURST: drawBurst(g, e); break;
            case BOLT: drawBolt(g, e); break;
            case SHATTER: drawShatter(g, e); break;
            default: break;
            }
        }
    }

    private static void drawRing(final Graphics g, final Fx e) {
        final float out = ArenaMotion.OUT.at(e.t);
        final float fade = 1f - e.t;
        final float r = e.size * (0.25f + 0.75f * out);
        ArenaArt.halo(g, e.x, e.y, r * 1.1f, e.color, 0.35f * fade * fade);
        g.drawSmoothCircle(Math.max(1.5f, ArenaTheme.px(3f) * fade), ArenaTheme.alpha(e.color, 0.85f * fade, S1), e.x, e.y, r);
        g.drawSmoothCircle(Math.max(1f, ArenaTheme.px(1.5f)), ArenaTheme.alpha(ArenaTheme.WHITE, 0.55f * fade, S2), e.x, e.y, r * 0.72f);
    }

    /** A flash, a ring, and streaks flying outwards that thin as they go. */
    private static void drawBurst(final Graphics g, final Fx e) {
        final float out = ArenaMotion.OUT.at(e.t);
        final float fade = 1f - e.t;
        final float flash = ArenaMotion.pop(ArenaMotion.clamp01(e.t * 2.2f));
        ArenaArt.halo(g, e.x, e.y, e.size * (0.5f + 0.5f * out), ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.4f, S2), 0.5f * flash);
        g.drawSmoothCircle(Math.max(1.5f, ArenaTheme.px(2.5f) * fade), ArenaTheme.alpha(e.color, 0.8f * fade, S1), e.x, e.y, e.size * 0.6f * out);
        final Color streak = ArenaTheme.alpha(ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.5f, S2), 0.9f * fade, S1);
        for (int i = 0; i < SPARKS; i++) {
            final float a = e.seed + i * (6.2831f / SPARKS) + (i % 2) * 0.28f;
            final float far = e.size * (0.45f + 0.35f * ((i * 37) % 10) / 10f) * out, near = far * (0.35f + 0.4f * e.t);
            final float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            g.drawLine(Math.max(1.5f, e.size * 0.035f * fade), streak, e.x + cos * near, e.y + sin * near, e.x + cos * far, e.y + sin * far);
        }
    }

    /** A comet running from where an entry resolved to what it aims at: a bright head with a tapering trail. */
    private static void drawBolt(final Graphics g, final Fx e) {
        final float m = ArenaMotion.IN_OUT.at(e.t);
        final int trail = 14;
        final float tail = Math.max(0f, m - 0.32f);
        for (int i = 0; i <= trail; i++) {
            final float p = ArenaMotion.lerp(tail, m, i / (float) trail), k = i / (float) trail;
            final float u = 1f - p; // along the bow the arrow of the same entry would take
            final float px = u * u * e.x + 2 * u * p * e.cx + p * p * e.x2, py = u * u * e.y + 2 * u * p * e.cy + p * p * e.y2;
            final float r = Math.max(1.5f, e.size * 0.07f * (0.25f + 0.75f * k));
            g.drawSoftShadow(ArenaTheme.alpha(e.color, 0.5f * k, S1), px - r, py - r, 2 * r, 2 * r, r, r * 1.2f);
            g.fillSmoothCircle(ArenaTheme.alpha(ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.4f + 0.5f * k, S2), 0.9f * k, S1), px, py, r * 0.7f);
        }
    }

    /** A countered spell: a ring that collapses inwards, then the card breaking into shards that spin outwards and fall. */
    private static void drawShatter(final Graphics g, final Fx e) {
        final float fade = 1f - ArenaMotion.IN.at(e.t);
        final float collapse = 1f - ArenaMotion.OUT.at(ArenaMotion.clamp01(e.t * 1.6f));
        ArenaArt.halo(g, e.x, e.y, e.size * 0.8f, e.color, 0.35f * fade);
        g.drawSmoothCircle(Math.max(2f, ArenaTheme.px(3f)), ArenaTheme.alpha(e.color, 0.9f * fade, S1), e.x, e.y, e.size * (0.25f + 0.45f * collapse));
        final float out = ArenaMotion.OUT.at(e.t), fall = e.size * 0.5f * e.t * e.t;
        for (int i = 0; i < SHARDS; i++) {
            final float a = e.seed + i * (6.2831f / SHARDS), dist = e.size * (0.12f + 0.4f * (0.6f + 0.4f * ((i * 53) % 10) / 10f)) * out;
            final float sx = e.x + (float) Math.cos(a) * dist, sy = e.y + (float) Math.sin(a) * dist * 0.8f + fall;
            final float r = e.size * (0.05f + 0.035f * ((i * 31) % 7) / 7f) * (1f - 0.4f * e.t), spin = a + e.t * (3f + i % 3);
            final float c1 = (float) Math.cos(spin), s1 = (float) Math.sin(spin);
            g.fillTriangle(ArenaTheme.alpha(ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.25f * (i % 3), S2), 0.9f * fade, S3), sx + c1 * r * 1.6f, sy + s1 * r * 1.6f,
                    sx - s1 * r, sy + c1 * r, sx + s1 * r * 0.6f, sy - c1 * r * 0.6f);
        }
    }

    /** Eases out and settles with a small overshoot (about 2%) before it rests. */
    private static float settle(final float t) {
        final float c = 0.8f, u = t - 1f;
        return 1f + (c + 1f) * u * u * u + c * u * u;
    }

    /**
     * One flight. Its first tenth lifts the card off the table; then it travels, rising and coming down again along an
     * arc driven by how far along it has got (zero at the end), rolling a little with its speed, and is at rest in its
     * slot when the crossfade begins, so the two images never differ in place.
     */
    private static void drawFlight(final Graphics g, final Fx e) {
        final float t = e.t;
        final float ts = ArenaMotion.clamp01((t - LIFT_END) / (CROSSFADE_FROM - LIFT_END));
        final float p = settle(ts), travel = ArenaMotion.clamp01(p);
        final float h = ArenaMotion.lerp(e.from.height, e.to.height, p), w = ArenaMotion.lerp(e.from.width, e.to.width, p);
        final float fcx = e.from.x + e.from.width / 2f, fcy = e.from.y + e.from.height / 2f;
        final float tcx = e.to.x + e.to.width / 2f, tcy = e.to.y + e.to.height / 2f;
        final float dist = (float) Math.hypot(tcx - fcx, tcy - fcy);
        final float arc = (float) Math.sin(Math.PI * travel);
        final float rise = Math.min(h * e.lift * 1.6f, dist * 0.3f) * arc + h * 0.06f * (float) Math.sin(Math.PI * ArenaMotion.clamp01(t / (LIFT_END * 2.2f)));
        final float gx = ArenaMotion.lerp(fcx, tcx, p), gy = ArenaMotion.lerp(fcy, tcy, p); // the point on the table below the card
        final float cx = gx, cy = gy - rise;
        // nearer the viewer in the air, turning over (back first) in the first two thirds of its travel
        final float air = 1f + 0.14f * arc;
        final float flipT = e.flip ? ArenaMotion.clamp01(ts / 0.66f) : 1f;
        final float squash = Math.max(0.04f, (float) Math.abs(Math.cos(Math.PI * flipT)));
        final boolean showBack = e.card == null || e.flip && flipT < 0.5f;
        final float cw = w * air, ch = h * air;
        final float drawW = e.flip ? cw * squash : cw;
        final float x = cx - drawW / 2f, y = cy - ch / 2f;
        // it fades in at the start and, over the real card drawn again by its own view, out at the end
        final float cross = ArenaMotion.SMOOTH.at(ArenaMotion.unlerp(CROSSFADE_FROM, 1f, t));
        final float old = g.getfloatAlphaComposite();
        boolean turned = false;
        try {
            g.setAlphaComposite(old * ArenaMotion.clamp01(t / 0.06f) * (1f - cross));
            // a shadow on the table: it stays under the card's place, and softens, spreads and fades as the card rises
            final float spread = ArenaTheme.px(6) + rise * 0.5f;
            g.drawSoftShadow(ArenaTheme.alpha(ArenaTheme.BLACK, 0.62f - 0.3f * arc, S1), gx - w * 0.48f - ch * 0.01f, gy - h * 0.46f + ch * 0.03f + rise * 0.18f,
                    w * 0.96f, h * 0.96f, w * 0.045f, spread);
            // it settles into the lean of the slot it lands in, and rolls a little the way it goes, most when it is fastest
            final float roll = Math.signum(tcx - fcx) * 7f * arc * Math.min(1f, Math.abs(tcx - fcx) / (ArenaBoard.windowH() * 0.3f));
            final float lean = e.lean * p + roll;
            turned = Math.abs(lean) > 0.05f;
            if (turned) {
                g.startRotateTransform(x + drawW / 2f, y + ch, -lean);
            }
            if (showBack) {
                ArenaCards.drawCardBack(g, e.owner, x, y, drawW, ch, Math.max(ArenaTheme.px(3), drawW / 18f), false);
            } else {
                CardRenderer.drawCard(g, e.card, x, y, drawW, ch, CardStackPosition.Top);
                g.drawSmoothRoundRect(Math.max(1f, ch * 0.006f), ArenaTheme.alpha(e.color, 0.7f * (1f - travel), S1), x, y, drawW, ch, drawW * 0.045f);
            }
        } finally {
            if (turned) {
                g.endTransform();
            }
            g.setAlphaComposite(old);
        }
    }

    /** A card's height as the hand fan draws it, for flights that have to guess it before the fan has laid the card out. */
    static float guessHandCardHeight() {
        return Math.max(ArenaTheme.px(60), ArenaBoard.windowH() * 0.17f * ArenaBoard.cardScale());
    }

    static float cardWidthFor(final float height) {
        return height / FCardPanel.ASPECT_RATIO;
    }
}
