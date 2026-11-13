package forge.screens.match.arena;

import java.nio.ByteBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.TimeUtils;

import forge.Graphics;

/**
 * The clean 3D board: one calm table seen from above and a little in front, instead of the busy flagstone mat. The
 * surface is a dark felt that recedes gently towards a horizon above the window (kept shallow, so the flat cards stand
 * on it without fighting its perspective), a fine grid lies on it in true perspective, one soft key light falls on the
 * middle of the table, and the two halves are marked: the opponent's a little cooler, yours a little warmer, with a
 * lit seam between them where the two armies meet. On wide windows the extra width is lit from the sides instead of
 * sinking into dark edges. Haze and vignette are kept light so the piles and plates at the edges stay readable on a
 * small screen.
 *
 * <p>The picture is worked out into a texture of up to 2560 pixels wide (three quarters of the window on a normal
 * monitor, so grid lines stay sharp at 21:9 and 32:9) and drawn as one image per frame. It is baked a few rows per
 * frame, so there is never a hitch: while a new size or mood is being worked out the last picture stays on screen,
 * stretched, and until the first one exists a plain gradient stands in. The texture is a managed one (a lost GL context
 * brings it back) and is let go when the match is left ({@link #release}). Nothing is allocated per frame.</p>
 */
public final class ArenaTableBoard {
    private ArenaTableBoard() { }

    /** The longest side of the baked picture, the shortest, and how large a share of the window it is at most. */
    private static final int MAX_SIDE = 2560, MIN_SIDE = 320;
    private static final float MAX_SHARE = 0.75f;
    /** How long a size has to hold still before it is baked, and how much time a frame may spend baking (a first picture may take more). */
    private static final int SETTLE_MS = 160;
    private static final long BUDGET_NANOS = 3_500_000L, FIRST_BUDGET_NANOS = 12_000_000L;

    // ---- the table in perspective: the camera looks down at the table; the horizon lies well above the window -------
    /** Where the horizon is, in window heights from the top (negative: above the window), and the grid's cell size. */
    private static final float HORIZON = -1.0f, CELL = 0.06f;
    /** Where the key light lands on the screen (shares of the window). */
    private static final float LIGHT_X = 0.46f, LIGHT_Y = 0.42f;
    /** The carved ring in the middle of the table, as a distance on the table plane, and where its middle lies in depth. */
    private static final float RING_R = 0.2f, RING_Z = 0.704f;
    /** The seam between the halves (the board's field split), and how wide the aspect has to be for the side lights to be at full strength. */
    private static final float SEAM = ArenaTheme.FIELD_SPLIT;

    /** One mood: the felt in the dark and under the light, the haze, the grid lines, the light, the cool and warm halves. */
    private static final class Mood {
        final int[] dark, lit, haze, line, light, cool;
        final float lightGain;

        Mood(final int dark, final int lit, final int haze, final int line, final int light, final int cool, final float lightGain) {
            this.dark = split(dark);
            this.lit = split(lit);
            this.haze = split(haze);
            this.line = split(line);
            this.light = split(light);
            this.cool = split(cool);
            this.lightGain = lightGain;
        }

        private static int[] split(final int hex) {
            return new int[] {(hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF};
        }
    }

    // day, default and night, like the flagstone board's moods
    private static final Mood[] MOODS = {
            new Mood(0x243651, 0x5A7898, 0x1B2A40, 0xBBD3EE, 0xFFF3DF, 0x6E92C4, 0.34f),
            new Mood(0x1B2840, 0x40587E, 0x121C30, 0x9DBBE6, 0xFFE8C8, 0x5F86C0, 0.30f),
            new Mood(0x0E1528, 0x25355A, 0x080D1B, 0x6F8CC4, 0xC6D4FF, 0x4A6AB0, 0.30f),
    };

    /** A picture that is being baked, row by row. */
    private static final class Job {
        final Pixmap pixmap;
        final int mood;
        final float aspect;
        int row;

        Job(final int w, final int h, final int mood, final float aspect) {
            this.pixmap = new Pixmap(w, h, Pixmap.Format.RGBA8888);
            this.mood = mood;
            this.aspect = aspect;
            pixmap.getPixels().clear();
        }
    }

    private static Pixmap pixmap;   // what the texture shows (kept: the texture is managed)
    private static Texture texture;
    private static int shownMood = -1, shownW, shownH;
    private static Job job;
    private static float seenW = -1, seenH = -1;
    private static long seenSince;
    private static final int[] SIZE = new int[2];

    /** Draws the table over {@code x, y, w, h} (the match screen's area). Render thread. */
    public static void draw(final Graphics g, final float x, final float y, final float w, final float h, final ArenaTheme.BoardVariant variant) {
        if (w <= 0 || h <= 0) {
            return;
        }
        final int mood = variant.ordinal();
        if (w != seenW || h != seenH) {
            seenW = w;
            seenH = h;
            seenSince = TimeUtils.millis();
        }
        final boolean settled = TimeUtils.timeSinceMillis(seenSince) >= SETTLE_MS;
        sizeFor(w, h);
        final boolean stale = texture == null || mood != shownMood || SIZE[0] != shownW || SIZE[1] != shownH;
        if (job != null && (job.mood != mood || job.pixmap.getWidth() != SIZE[0] || job.pixmap.getHeight() != SIZE[1])) {
            job.pixmap.dispose(); // what it was making is not what is wanted now
            job = null;
        }
        if (stale && job == null && (settled || texture == null)) {
            job = new Job(SIZE[0], SIZE[1], mood, w / h);
        }
        if (job != null) {
            advance(texture == null ? FIRST_BUDGET_NANOS : BUDGET_NANOS);
            ArenaMotion.requestFrame();
        } else if (stale && !settled) {
            ArenaMotion.requestFrame(); // the picture is stale: come back once the size has held still
        }
        if (texture != null) {
            g.drawImage(texture, x, y, w, h);
        } else { // the first picture is still being made
            final Mood m = MOODS[mood];
            g.fillGradientRect(ArenaTheme.rgb((m.haze[0] << 16) | (m.haze[1] << 8) | m.haze[2]), ArenaTheme.rgb((m.lit[0] << 16) | (m.lit[1] << 8) | m.lit[2]), true,
                    x, y, w, h);
        }
    }

    /** Lets go of the picture (the match is left, the theme went off): the next draw bakes it again. Render thread. */
    public static void release() {
        if (job != null) {
            job.pixmap.dispose();
            job = null;
        }
        if (texture != null) {
            texture.dispose();
            texture = null;
        }
        if (pixmap != null) {
            pixmap.dispose();
            pixmap = null;
        }
        shownMood = -1;
        shownW = shownH = 0;
        seenW = seenH = -1;
    }

    private static void sizeFor(final float w, final float h) {
        final float scale = Math.min(MAX_SHARE, MAX_SIDE / Math.max(w, h));
        SIZE[0] = Math.max(MIN_SIDE / 2, Math.round(w * scale));
        SIZE[1] = Math.max(MIN_SIDE / 2, Math.round(h * scale));
    }

    /** Bakes rows of the current job for up to {@code budget} nanoseconds; when it is done, it becomes the picture. */
    private static void advance(final long budget) {
        final Job j = job;
        final long end = System.nanoTime() + budget;
        final Mood m = MOODS[j.mood];
        final int ph = j.pixmap.getHeight();
        final ByteBuffer out = j.pixmap.getPixels();
        while (j.row < ph && System.nanoTime() < end) {
            renderRow(out, j.row, j.pixmap.getWidth(), ph, j.aspect, m);
            j.row++;
        }
        if (j.row < ph) {
            return;
        }
        out.position(0);
        // done: swap it in (the old picture goes only now, so the screen never shows a gap)
        final Texture old = texture;
        final Pixmap oldPixmap = pixmap;
        pixmap = j.pixmap;
        texture = new Texture(new PixmapTextureData(pixmap, null, false, false, true)); // managed: kept pixmap, rebuilt after a lost GL context
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        shownMood = j.mood;
        shownW = pixmap.getWidth();
        shownH = ph;
        job = null;
        if (old != null) {
            old.dispose();
        }
        if (oldPixmap != null) {
            oldPixmap.dispose();
        }
        if (Gdx.graphics != null) {
            Gdx.graphics.requestRendering();
        }
    }

    // ---- the picture ----------------------------------------------------------------------------------------------------

    private static float smooth(final float a, final float b, final float v) {
        final float t = Math.max(0f, Math.min(1f, (v - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    /** A cheap hash noise in -1..1, to break up banding in the dark gradients. */
    private static float noise(final int x, final int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >>> 13)) * 1274126177;
        n ^= n >>> 16;
        return ((n & 0xFFFF) / 32767.5f) - 1f;
    }

    /** How much of a pixel a line {@code dist} away from the pixel's middle covers, for lines about {@code width} wide. */
    private static float coverage(final float dist, final float width) {
        return Math.max(0f, Math.min(1f, width * 0.5f + 0.5f - dist));
    }

    private static float mixTo(final float v, final int target, final float k) {
        return v + (target - v) * k;
    }

    /** One row of the picture, written at the buffer's current position (rows come in order). */
    private static void renderRow(final ByteBuffer out, final int py, final int pw, final int ph, final float aspect, final Mood m) {
        final float invH = 1f / ph;
        final float v = (py + 0.5f) * invH;           // 0 at the top, 1 at the bottom (window heights)
        final float d = v - HORIZON;                   // below the horizon
        final float z = 1f / d;                        // how far away the table is here
        // depth haze: only the very far side sinks a little; the opponent's piles and plates stay readable
        final float haze = 1f - smooth(0f, 0.3f, v);
        final float zPerPx = z * z * invH;             // depth covered by one pixel step down
        final float xPerPx = z * invH;                 // distance across the table covered by one pixel step across
        final float zCell = z / CELL;
        final float rowLine = coverage(Math.abs(zCell - Math.round(zCell)) * CELL / zPerPx, 1.15f);
        // the halves: the opponent's cooler, yours warmer, and a lit seam where they meet
        final float cool = v < SEAM ? 0.07f : 0f, warm = v < SEAM ? 0f : 0.05f;
        final float seamD = (v - SEAM) / 0.0045f;
        final float seam = (float) Math.exp(-seamD * seamD), seamGlow = (float) Math.exp(-Math.abs(v - SEAM) / 0.05f);
        final float sideShare = smooth(1.6f, 3.2f, aspect); // how wide the window is: 0 on 16:10, 1 on 32:9
        final float hazeK = haze * haze * 0.32f;
        for (int px = 0; px < pw; px++) {
            final float fx = (px + 0.5f) / pw;
            final float u = (fx - 0.5f) * aspect;                  // across, in window heights
            final float tx = u * z;                                // across, on the table
            // light: a broad pool from the key light, tighter in depth than across
            final float lx = (fx - LIGHT_X) * aspect, ly = v - LIGHT_Y;
            final float pool = Math.max(0f, 1f - (lx * lx * 0.45f + ly * ly * 1.7f) * 1.1f);
            final float lit = pool * pool * (3f - 2f * pool);
            // wide windows: the extra width is lit from the sides, in pools down each flank, instead of dark edges
            final float edgeDist = Math.min(fx, 1f - fx) * aspect;  // distance from the nearest side, in window heights
            final float flank = sideShare * (float) Math.exp(-edgeDist * 1.4f) * Math.max(0f, 1f - Math.abs(v - 0.46f) * 1.5f);
            // a light vignette; sideways it fades as the window widens (the flanks are lit instead)
            final float ex = Math.abs(fx - 0.5f) * 2f, ey = Math.abs(v - 0.5f) * 2f;
            final float edge = 1f - 0.14f * smooth(0.7f, 1.2f, (float) Math.sqrt(ex * ex * 0.6f * (1f - 0.8f * sideShare) + ey * ey * 0.8f));
            final float cx = fx - 0.97f, cy = v - 0.93f;
            final float corner = 1f - 0.22f * smooth(0.5f, 0f, (float) Math.sqrt(cx * cx * aspect * aspect + cy * cy)); // the action button's corner
            // the felt, from its dark to its lit tone
            final float tone = Math.min(1f, 0.30f + lit * 0.8f + flank * 0.45f);
            float r = m.dark[0] + (m.lit[0] - m.dark[0]) * tone;
            float gr = m.dark[1] + (m.lit[1] - m.dark[1]) * tone;
            float b = m.dark[2] + (m.lit[2] - m.dark[2]) * tone;
            // the halves' tints
            r = mixTo(r, m.cool[0], cool);
            gr = mixTo(gr, m.cool[1], cool);
            b = mixTo(b, m.cool[2], cool);
            r = mixTo(r, m.light[0], warm);
            gr = mixTo(gr, m.light[1], warm);
            b = mixTo(b, m.light[2], warm);
            // the grid: columns converge on the horizon, rows crowd together; fine, fading with distance
            final float xCell = tx / CELL;
            final float colDist = Math.abs(xCell - Math.round(xCell)) * CELL / xPerPx;
            final float cover = Math.max(rowLine, coverage(colDist, 1.0f));
            final float line = cover * (0.05f + 0.08f * lit) * (0.45f + 0.55f * (1f - haze));
            r = mixTo(r, m.line[0], line);
            gr = mixTo(gr, m.line[1], line);
            b = mixTo(b, m.line[2], line);
            // the carved ring in the middle of the table: a ring on the table plane is an ellipse on the screen
            final float rdz = z - RING_Z;
            final float ringDist = Math.max(1e-4f, (float) Math.sqrt(tx * tx + rdz * rdz));
            final float gx = tx / ringDist * xPerPx, gz = rdz / ringDist * zPerPx; // one pixel's step along the ring's normal
            final float ringPx = Math.abs(ringDist - RING_R) / Math.max(1e-5f, (float) Math.sqrt(gx * gx + gz * gz));
            final float ring = coverage(ringPx, 2.2f) * 0.10f * (0.3f + lit);
            // the light itself warms the lit part, the flanks and the seam a little
            final float glow = Math.min(0.6f, ring + lit * m.lightGain * 0.35f + flank * 0.22f + seam * 0.30f + seamGlow * 0.05f);
            r = mixTo(r, m.light[0], glow);
            gr = mixTo(gr, m.light[1], glow);
            b = mixTo(b, m.light[2], glow);
            // haze at the far edge, then the vignette
            r = mixTo(r, m.haze[0], hazeK);
            gr = mixTo(gr, m.haze[1], hazeK);
            b = mixTo(b, m.haze[2], hazeK);
            final float k = edge * corner;
            final float grain = noise(px, py) * 1.4f;
            out.put((byte) Math.max(0, Math.min(255, Math.round(r * k + grain))));
            out.put((byte) Math.max(0, Math.min(255, Math.round(gr * k + grain))));
            out.put((byte) Math.max(0, Math.min(255, Math.round(b * k + grain))));
            out.put((byte) 255);
        }
    }
}
