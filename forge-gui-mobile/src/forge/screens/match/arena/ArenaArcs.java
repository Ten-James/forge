package forge.screens.match.arena;

import com.badlogic.gdx.graphics.Color;

import forge.Graphics;

/**
 * The Arena's targeting arcs (port of the Swing TargetingOverlay's Arena arcs): a glowing curve from what aims to what
 * it aims at, bowed away from the straight line, with a bright core, a soft glow and a pulse of light running along it
 * towards the target, which wears a small ring. Drawn as a trail of batched soft discs, so a board full of arcs costs
 * a few hundred quads and no transforms or extra draw calls.
 *
 * <p>An arc that has just appeared draws itself in from its source to its target (a bright head runs ahead of it and
 * the mark and arrow head open at the end), and wears an arrow head pointing the way it goes. Arcs are told apart by
 * what they connect (kind and rounded end points, so a card gliding to its place does not start a new one), and an arc
 * that was not drawn for a few frames is forgotten, so the next time it shows it draws in again. With the Arena
 * effects off they simply show whole.</p>
 */
public final class ArenaArcs {
    private ArenaArcs() { }

    /** What the arc means: a friendly aim, an attack, a block, or a spell or ability aimed at an opponent's things. */
    public static final int FRIEND = 0, ATTACK = 1, BLOCK = 2, FOE = 3;
    private static final Color[] COLOR = {ArenaTheme.rgb(0x6ED2FF), ArenaTheme.rgb(0xFF7A2E), ArenaTheme.rgb(0x5C9BFF), ArenaTheme.rgb(0xFF5A4A)};
    private static final Color S1 = new Color(), S2 = new Color(), S3 = new Color();

    /** How long an arc takes to draw itself in, how many arcs are remembered at once (more just show whole), and how long one may be missing. */
    private static final int DRAW_IN_MS = 300, MEMORY = 48, FORGET_MS = 120;
    /** The light running along an arc goes round this many times, once every PULSE_MS, then the arc rests (and asks for no more frames). */
    private static final int PULSE_CYCLES = 3, PULSE_MS = 900;
    private static float lastAgeMs; // how old the arc {@link #drawnIn} last looked at is
    private static final int[] KIND = new int[MEMORY];
    private static final float[] EX1 = new float[MEMORY], EY1 = new float[MEMORY], EX2 = new float[MEMORY], EY2 = new float[MEMORY];
    private static final long[] BORN = new long[MEMORY], LAST = new long[MEMORY], STAMP = new long[MEMORY];

    /**
     * How far this arc has drawn itself in, 0..1: 1 once it is old, or when the effects are off. An arc is the same
     * arc as one drawn a moment ago when it is of the same kind and both its ends are still near where they were (a
     * card gliding to its place drags its arc with it and does not start it again).
     */
    private static float drawnIn(final float x1, final float y1, final float x2, final float y2, final int kind) {
        if (!ArenaTheme.isEffectsOn()) {
            lastAgeMs = 1e9f;
            return 1f;
        }
        final float tol = ArenaTheme.px(160), near = ArenaTheme.px(30), far = ArenaTheme.px(480);
        final long frame = ArenaMotion.frame(), now = System.nanoTime();
        int free = -1;
        for (int i = 0; i < MEMORY; i++) {
            final boolean alive = LAST[i] != 0L && now - LAST[i] < FORGET_MS * 1_000_000L;
            if (!alive) {
                if (free < 0) {
                    free = i;
                }
                continue;
            }
            if (KIND[i] != kind || STAMP[i] == frame) {
                continue;
            }
            final float d1 = Math.max(Math.abs(EX1[i] - x1), Math.abs(EY1[i] - y1)), d2 = Math.max(Math.abs(EX2[i] - x2), Math.abs(EY2[i] - y2));
            // the same arc when both ends stayed about where they were, or one end stayed put while a layout moved the other
            if (d1 < tol && d2 < tol || d1 < near && d2 < far || d2 < near && d1 < far) {
                EX1[i] = x1;
                EY1[i] = y1;
                EX2[i] = x2;
                EY2[i] = y2;
                LAST[i] = now;
                STAMP[i] = frame;
                lastAgeMs = (now - BORN[i]) / 1e6f;
                return ArenaMotion.clamp01((now - BORN[i]) / (DRAW_IN_MS * 1e6f));
            }
        }
        if (free < 0) {
            lastAgeMs = 1e9f;
            return 1f;
        }
        KIND[free] = kind;
        EX1[free] = x1;
        EY1[free] = y1;
        EX2[free] = x2;
        EY2[free] = y2;
        BORN[free] = now;
        LAST[free] = now;
        STAMP[free] = frame;
        lastAgeMs = 0f;
        return 0f;
    }

    /**
     * The bow of an arc from x1, y1 to x2, y2: where its middle control point is, written to {@code out[0], out[1]}.
     * Perpendicular to the line, upwards for a mostly level arc, to the left for a mostly upright one. Bolts follow it too.
     */
    static void bow(final float x1, final float y1, final float x2, final float y2, final float[] out) {
        final float dx = x2 - x1, dy = y2 - y1;
        final float len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        float nx = -dy / len, ny = dx / len;
        if (Math.abs(dx) >= Math.abs(dy) ? ny > 0 : nx > 0) {
            nx = -nx;
            ny = -ny;
        }
        final float bow = Math.min(len * 0.22f, ArenaTheme.px(220));
        out[0] = (x1 + x2) / 2f + nx * bow;
        out[1] = (y1 + y2) / 2f + ny * bow;
    }

    private static final float[] CTRL = new float[2];

    /** One arc from x1, y1 to x2, y2 (the coordinates of the Graphics in use). */
    public static void draw(final Graphics g, final float x1, final float y1, final float x2, final float y2, final int kind) {
        final float grown = drawnIn(x1, y1, x2, y2, kind);
        render(g, x1, y1, x2, y2, kind, grown, 1f);
    }

    /**
     * Draws the arcs that were drawn a moment ago and are not any more, fading out (so a removed arrow does not just
     * vanish). Call once per frame after every arc has been drawn.
     */
    public static void drawFading(final Graphics g) {
        if (!ArenaTheme.isEffectsOn()) {
            return;
        }
        final long frame = ArenaMotion.frame(), now = System.nanoTime();
        for (int i = 0; i < MEMORY; i++) {
            if (LAST[i] == 0L || STAMP[i] >= frame - 1) { // drawn this frame or last (a later view may still draw it this frame): not faded
                continue;
            }
            final float age = (now - LAST[i]) / (FORGET_MS * 1e6f);
            if (age < 1f) {
                render(g, EX1[i], EY1[i], EX2[i], EY2[i], KIND[i], 1f, (1f - age) * (1f - age));
                ArenaMotion.requestFrame();
            }
        }
    }

    private static final int MAX_POINTS = 72;
    private static final float[] XY = new float[MAX_POINTS * 2], HW = new float[MAX_POINTS], RGBA = new float[MAX_POINTS * 4];

    /** One point of the path along a quadratic curve: {@code t} 0..1 from the source to the target. */
    private static void pointAt(final float t, final float x1, final float y1, final float cx, final float cy, final float x2, final float y2, final float[] out,
            final int at) {
        final float u = 1f - t;
        out[at] = u * u * x1 + 2 * u * t * cx + t * t * x2;
        out[at + 1] = u * u * y1 + 2 * u * t * cy + t * t * y2;
    }

    /** Strokes the path from its start to {@code upTo} (0..1 of the ribbon) as one solid ribbon of the given width profile and colour. */
    private static void ribbon(final Graphics g, final int steps, final int count, final float width, final Color base, final float lighten, final float alpha,
            final float run, final float runWidth) {
        for (int i = 0; i < count; i++) {
            final float k = i / (float) steps;
            final float lit = run < 0f ? 0f : Math.max(0f, 1f - Math.abs(k - run) / 0.08f);
            final float taper = 0.3f + 0.7f * (float) Math.pow(k, 0.8d); // thin where it leaves, widening all the way to its head
            HW[i] = width * taper * (1f + runWidth * lit);
            final float mixed = lighten + (1f - lighten) * 0.8f * lit;
            RGBA[i * 4] = base.r + (1f - base.r) * mixed;
            RGBA[i * 4 + 1] = base.g + (1f - base.g) * mixed;
            RGBA[i * 4 + 2] = base.b + (1f - base.b) * mixed;
            RGBA[i * 4 + 3] = alpha;
        }
        g.fillSmoothRibbon(XY, HW, RGBA, count);
    }

    private static void render(final Graphics g, final float x1, final float y1, final float x2, final float y2, final int kind, final float grown,
            final float fadeAlpha) {
        final float dx = x2 - x1, dy = y2 - y1;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 2f) {
            return;
        }
        final Color c = COLOR[Math.max(0, Math.min(COLOR.length - 1, kind))];
        final float reach = ArenaMotion.OUT.at(grown); // how much of the way the arc has got
        if (grown < 1f) {
            ArenaMotion.requestFrame();
        }
        final float old = g.getfloatAlphaComposite();
        if (fadeAlpha < 1f) {
            g.setAlphaComposite(old * fadeAlpha);
        }
        try {
            bow(x1, y1, x2, y2, CTRL);
            final float cx = CTRL[0], cy = CTRL[1];
            final float coreW = Math.max(2.5f, ArenaTheme.px(4.6f)); // half the width at the head: a ribbon about px(9) across
            final float ring = Math.max(4f, ArenaTheme.px(11));
            // the ribbon ends where the arrow head's base is
            final float end = Math.max(0.3f, 1f - ring * 1.4f / len);
            final int steps = Math.max(14, Math.min(MAX_POINTS - 2, Math.round(len * end / Math.max(3f, ArenaTheme.px(7)))));
            final int count = Math.min(steps, (int) Math.ceil(steps * reach)) + 1;
            for (int i = 0; i < count; i++) {
                pointAt(end * i / steps, x1, y1, cx, cy, x2, y2, XY, i * 2);
            }
            // a pulse of light running towards the target, once every 0.9 s, for the first few cycles only
            final boolean pulsing = ArenaTheme.isEffectsOn() && fadeAlpha >= 1f && lastAgeMs < PULSE_CYCLES * PULSE_MS;
            final float run = pulsing ? (lastAgeMs % PULSE_MS) / PULSE_MS : -1f;
            if (pulsing) {
                ArenaMotion.requestFrame();
            }
            // soft glow ribbons under a bright solid core: all filled, all antialiased, no overlap inside one ribbon
            ribbon(g, steps, count, coreW * 2.6f, c, 0f, 0.14f, -1f, 0f);
            ribbon(g, steps, count, coreW * 1.6f, c, 0.1f, 0.26f, -1f, 0f);
            ribbon(g, steps, count, coreW, c, 0.55f, 0.95f, run, 0.5f);
            if (grown < 1f) { // the head still running: a bright bead at the front of the line
                final int at = (count - 1) * 2;
                final float hx = XY[at], hy = XY[at + 1];
                g.drawGlow(ArenaTheme.alpha(c, 0.8f, S3), hx - coreW * 1.2f, hy - coreW * 1.2f, coreW * 2.4f, coreW * 2.4f, coreW * 1.2f, ArenaTheme.px(10));
                g.fillSmoothCircle(ArenaTheme.alpha(ArenaTheme.WHITE, 0.95f, S3), hx, hy, coreW);
            }
            // the mark where it lands, opening as the line gets there
            final float arrive = ArenaMotion.OUT.at(ArenaMotion.unlerp(0.7f, 1f, grown));
            if (arrive <= 0f) {
                return;
            }
            final float markR = ring * (0.5f + 0.5f * arrive);
            g.drawGlow(ArenaTheme.alpha(c, 0.7f * arrive, S3), x2 - markR, y2 - markR, 2 * markR, 2 * markR, markR, ArenaTheme.px(10));
            g.drawSmoothCircle(Math.max(1.5f, ArenaTheme.px(2.5f)), ArenaTheme.alpha(ArenaTheme.WHITE, 0.92f * arrive, S3), x2, y2, markR);
            g.fillSmoothCircle(ArenaTheme.alpha(c, 0.95f * arrive, S3), x2, y2, markR * 0.38f);
            // the arrow head: a solid triangle just before the ring, pointing the way the line arrives, with a soft glow under it
            final float adx = x2 - cx, ady = y2 - cy, alen = (float) Math.sqrt(adx * adx + ady * ady);
            if (alen > 1f) {
                final float ux = adx / alen, uy = ady / alen;
                final float tipX = x2 - ux * ring * 1.2f, tipY = y2 - uy * ring * 1.2f;
                final float arm = ring * 2.4f * arrive, spread = arm * 0.62f;
                final Color head = ArenaTheme.mix(c, ArenaTheme.WHITE, 0.5f, S2);
                g.fillSmoothWedge(ArenaTheme.alpha(c, 0.28f * arrive, S3), tipX - ux * arm * 1.25f, tipY - uy * arm * 1.25f, tipX, tipY, spread * 1.6f);
                g.fillSmoothWedge(ArenaTheme.alpha(head, 0.98f * arrive, S3), tipX - ux * arm, tipY - uy * arm, tipX + ux * coreW * 0.3f, tipY + uy * coreW * 0.3f, spread);
            }
        } finally {
            g.setAlphaComposite(old);
        }
    }

}
