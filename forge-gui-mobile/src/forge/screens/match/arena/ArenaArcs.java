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
                return ArenaMotion.clamp01((now - BORN[i]) / (DRAW_IN_MS * 1e6f));
            }
        }
        if (free < 0) {
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
        return 0f;
    }

    /** One arc from x1, y1 to x2, y2 (the coordinates of the Graphics in use). */
    public static void draw(final Graphics g, final float x1, final float y1, final float x2, final float y2, final int kind) {
        final float dx = x2 - x1, dy = y2 - y1;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 2f) {
            return;
        }
        final Color c = COLOR[Math.max(0, Math.min(COLOR.length - 1, kind))];
        final float grown = drawnIn(x1, y1, x2, y2, kind);
        final float reach = ArenaMotion.OUT.at(grown); // how much of the way the arc has got
        if (grown < 1f) {
            ArenaMotion.requestFrame();
        }
        // bow it: perpendicular to the line, upwards for a mostly level arc, to the left for a mostly upright one
        float nx = -dy / len, ny = dx / len;
        if (Math.abs(dx) >= Math.abs(dy) ? ny > 0 : nx > 0) {
            nx = -nx;
            ny = -ny;
        }
        final float bow = Math.min(len * 0.22f, ArenaTheme.px(220));
        final float cx = (x1 + x2) / 2f + nx * bow, cy = (y1 + y2) / 2f + ny * bow;
        final float glowR = Math.max(4f, ArenaTheme.px(13)), coreR = Math.max(2f, ArenaTheme.px(3.4f)); // a ribbon about px(6) across at its head
        // the discs overlap well, so the core reads as one line
        final int n = Math.max(24, Math.min(420, Math.round(len * 1.15f / (coreR * 1.1f))));
        // a pulse running towards the target, once every 0.9 s
        final boolean moving = ArenaTheme.isEffectsOn();
        final float run = moving ? (ArenaMotion.seconds() % 0.9f) / 0.9f : -1f; // reduce motion: no pulse running along the arc
        if (moving) {
            ArenaMotion.requestFrame();
        }
        final Color glow = ArenaTheme.alpha(c, Math.min(0.5f, 6.5f / n * 2.2f), S1);
        final int last = Math.min(n, (int) Math.ceil(n * reach));
        for (int i = 0; i <= last; i++) {
            final float t = i / (float) n;
            final float u = 1f - t;
            final float px = u * u * x1 + 2 * u * t * cx + t * t * x2, py = u * u * y1 + 2 * u * t * cy + t * t * y2;
            final float taper = 0.3f + 0.7f * (float) Math.pow(t, 0.8d); // a ribbon: thin where it leaves, widening all the way to its head
            g.drawSoftShadow(glow, px - glowR * 0.25f * taper, py - glowR * 0.25f * taper, glowR * 0.5f * taper, glowR * 0.5f * taper, glowR * 0.25f * taper, glowR * 0.75f * taper);
        }
        for (int i = 0; i <= last; i++) {
            final float t = i / (float) n;
            final float u = 1f - t;
            final float px = u * u * x1 + 2 * u * t * cx + t * t * x2, py = u * u * y1 + 2 * u * t * cy + t * t * y2;
            final float dist = Math.abs(t - run);
            final float lit = Math.max(0f, 1f - dist / 0.08f);
            final float taper = 0.3f + 0.7f * (float) Math.pow(t, 0.8d);
            final float r = coreR * taper * (1f + 0.6f * lit);
            S2.set(c.r + (1f - c.r) * (0.55f + 0.45f * lit), c.g + (1f - c.g) * (0.55f + 0.45f * lit), c.b + (1f - c.b) * (0.55f + 0.45f * lit), 0.9f);
            g.fillSmoothCircle(S2, px, py, r);
        }
        if (grown < 1f) { // the head still running: a bright bead at the front of the line
            final float t = Math.min(1f, last / (float) n), u = 1f - t;
            final float hx = u * u * x1 + 2 * u * t * cx + t * t * x2, hy = u * u * y1 + 2 * u * t * cy + t * t * y2;
            g.drawGlow(ArenaTheme.alpha(c, 0.8f, S3), hx - coreR * 2f, hy - coreR * 2f, coreR * 4f, coreR * 4f, coreR * 2f, glowR);
            g.fillSmoothCircle(ArenaTheme.alpha(ArenaTheme.WHITE, 0.95f, S3), hx, hy, coreR * 1.8f);
        }
        // the mark where it lands, opening as the line gets there
        final float arrive = ArenaMotion.OUT.at(ArenaMotion.unlerp(0.7f, 1f, grown));
        if (arrive <= 0f) {
            return;
        }
        final float ring = Math.max(4f, ArenaTheme.px(11)) * (0.5f + 0.5f * arrive);
        g.drawGlow(ArenaTheme.alpha(c, 0.7f * arrive, S3), x2 - ring, y2 - ring, 2 * ring, 2 * ring, ring, ArenaTheme.px(10));
        g.drawSmoothCircle(Math.max(1.5f, ArenaTheme.px(2.5f)), ArenaTheme.alpha(ArenaTheme.WHITE, 0.92f * arrive, S3), x2, y2, ring);
        g.fillSmoothCircle(ArenaTheme.alpha(c, 0.95f * arrive, S3), x2, y2, ring * 0.38f);
        // the arrow head: a big chevron just before the ring, pointing the way the line arrives, with a soft glow under it
        final float adx = x2 - cx, ady = y2 - cy, alen = (float) Math.sqrt(adx * adx + ady * ady);
        if (alen > 1f) {
            final float ux = adx / alen, uy = ady / alen, vx = -uy, vy = ux;
            final float tipX = x2 - ux * ring * 1.2f, tipY = y2 - uy * ring * 1.2f;
            final float arm = ring * 2.5f * arrive, spread = arm * 0.66f;
            final float bx = tipX - ux * arm, by = tipY - uy * arm;
            final float thick = Math.max(3.5f, ring * 0.62f);
            final Color head = ArenaTheme.mix(c, ArenaTheme.WHITE, 0.5f, S2);
            g.drawLine(thick * 2.4f, ArenaTheme.alpha(c, 0.28f * arrive, S3), bx + vx * spread, by + vy * spread, tipX, tipY);
            g.drawLine(thick * 2.4f, ArenaTheme.alpha(c, 0.28f * arrive, S3), bx - vx * spread, by - vy * spread, tipX, tipY);
            g.drawLine(thick, ArenaTheme.alpha(head, 0.98f * arrive, S3), bx + vx * spread, by + vy * spread, tipX + ux * thick * 0.4f, tipY + uy * thick * 0.4f);
            g.drawLine(thick, ArenaTheme.alpha(head, 0.98f * arrive, S3), bx - vx * spread, by - vy * spread, tipX + ux * thick * 0.4f, tipY + uy * thick * 0.4f);
            g.fillSmoothCircle(ArenaTheme.alpha(head, 0.98f * arrive, S3), tipX, tipY, thick * 0.5f);
        }
    }
}
