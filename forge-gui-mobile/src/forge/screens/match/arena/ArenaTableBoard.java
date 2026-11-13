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
 * surface is a dark felt that recedes towards a horizon above the top of the window, a fine grid lies on it in true
 * perspective (so rows and cards read as standing on it and the far side feels deeper), one soft key light falls on
 * the middle of the table and the far edge sinks into haze. There are no banners and no clutter behind the cards.
 *
 * <p>The picture is worked out once per board size and mood into a small texture (about half the window's size, kept
 * as a managed texture so a lost GL context brings it back) and drawn as one image per frame, so it costs nothing to
 * run. While the window is being dragged to a new size the last picture stays stretched until the size has settled.
 * Nothing is allocated per frame.</p>
 */
public final class ArenaTableBoard {
    private ArenaTableBoard() { }

    /** The longest side of the baked picture, in pixels, and how long a size has to hold still before it is baked again. */
    private static final int MAX_SIDE = 1600, MIN_SIDE = 320;
    private static final int SETTLE_MS = 160;

    // ---- the table in perspective: the camera looks down at the table; the horizon lies above the window --------------
    /** Where the horizon is, in window heights from the top (negative: above the window), and the grid's cell size. */
    private static final float HORIZON = -0.42f, CELL = 0.085f;
    /** Where the key light lands on the screen (shares of the window) and how far it reaches. */
    private static final float LIGHT_X = 0.46f, LIGHT_Y = 0.42f;
    /** The carved ring in the middle of the table, as a distance on the table plane, and where its middle lies in depth. */
    private static final float RING_R = 0.3f, RING_Z = 1.19f;

    /** One mood: the felt in the dark and under the light, the haze, the grid lines, the light. */
    private static final class Mood {
        final int[] dark, lit, haze, line, light;
        final float lightGain;

        Mood(final int dark, final int lit, final int haze, final int line, final int light, final float lightGain) {
            this.dark = split(dark);
            this.lit = split(lit);
            this.haze = split(haze);
            this.line = split(line);
            this.light = split(light);
            this.lightGain = lightGain;
        }

        private static int[] split(final int hex) {
            return new int[] {(hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF};
        }
    }

    // day, default and night, like the flagstone board's moods
    private static final Mood[] MOODS = {
            new Mood(0x1C2B3E, 0x4C6683, 0x0F1826, 0xBBD3EE, 0xFFF3DF, 0.34f),
            new Mood(0x141E31, 0x34496B, 0x080D19, 0x9DBBE6, 0xFFE8C8, 0.30f),
            new Mood(0x0A0F1E, 0x1D2A48, 0x04070F, 0x6F8CC4, 0xC6D4FF, 0.30f),
    };

    private static Pixmap pixmap;
    private static Texture texture;
    private static int bakedW, bakedH, bakedMood = -1;
    private static float seenW = -1, seenH = -1;
    private static long seenSince;

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
        if (texture == null || mood != bakedMood || settled && !fits(w, h)) {
            bake(w, h, mood);
        } else if (!settled && !fits(w, h)) {
            ArenaMotion.requestFrame(); // the picture is stale: come back once the size has held still
        }
        g.drawImage(texture, x, y, w, h);
    }

    private static boolean fits(final float w, final float h) {
        final int[] size = sizeFor(w, h, null);
        return size[0] == bakedW && size[1] == bakedH;
    }

    private static final int[] SIZE = new int[2];

    private static int[] sizeFor(final float w, final float h, final int[] out) {
        final int[] r = out == null ? SIZE : out;
        final float scale = Math.min(0.5f, MAX_SIDE / Math.max(w, h));
        r[0] = Math.max(MIN_SIDE / 2, Math.round(w * scale));
        r[1] = Math.max(MIN_SIDE / 2, Math.round(h * scale));
        return r;
    }

    private static void bake(final float w, final float h, final int moodIndex) {
        final int[] size = sizeFor(w, h, null);
        final int pw = size[0], ph = size[1];
        if (pixmap == null || pixmap.getWidth() != pw || pixmap.getHeight() != ph) {
            if (texture != null) {
                texture.dispose();
                texture = null;
            }
            if (pixmap != null) {
                pixmap.dispose();
            }
            pixmap = new Pixmap(pw, ph, Pixmap.Format.RGBA8888);
        }
        render(pixmap, w / h, MOODS[moodIndex]);
        if (texture == null) {
            // managed: the pixmap is kept, so the texture is rebuilt by libGDX if the GL context is lost
            texture = new Texture(new PixmapTextureData(pixmap, null, false, false, true));
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        } else {
            texture.load(texture.getTextureData());
        }
        bakedW = pw;
        bakedH = ph;
        bakedMood = moodIndex;
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

    private static void render(final Pixmap pm, final float aspect, final Mood m) {
        final int pw = pm.getWidth(), ph = pm.getHeight();
        final ByteBuffer out = pm.getPixels();
        out.clear();
        final float invH = 1f / ph;
        for (int py = 0; py < ph; py++) {
            final float v = (py + 0.5f) * invH;           // 0 at the top, 1 at the bottom (window heights)
            final float d = v - HORIZON;                   // below the horizon
            final float z = 1f / d;                        // how far away the table is here
            // depth haze: the far side sinks into the dark, the near side stays clear
            final float haze = 1f - smooth(0.08f, 0.62f, v);
            // the rows of the grid cross the table at multiples of CELL in depth; pixels per unit of depth here
            final float zPerPx = z * z * invH;             // depth covered by one pixel step down
            final float xPerPx = z * invH;                 // distance across the table covered by one pixel step across
            final float zCell = z / CELL;
            final float zDist = Math.abs(zCell - Math.round(zCell)) * CELL / zPerPx;
            final float rowLine = coverage(zDist, 1.15f);
            for (int px = 0; px < pw; px++) {
                final float u = ((px + 0.5f) / pw - 0.5f) * aspect;   // across, in window heights
                final float tx = u * z;                               // across, on the table
                // light: a broad pool from the key light, tighter in depth than across
                final float lx = ((px + 0.5f) / pw - LIGHT_X) * aspect, ly = v - LIGHT_Y;
                final float pool = Math.max(0f, 1f - (lx * lx * 0.55f + ly * ly * 2.1f) * 1.25f);
                final float lit = pool * pool * (3f - 2f * pool);
                // vignette towards the edges and the bottom right corner the action button sits in
                final float ex = Math.abs((px + 0.5f) / pw - 0.5f) * 2f, ey = Math.abs(v - 0.5f) * 2f;
                final float edge = 1f - 0.34f * smooth(0.55f, 1.15f, (float) Math.sqrt(ex * ex * 0.7f + ey * ey * 0.9f));
                final float corner = 1f - 0.35f * smooth(0.6f, 0.0f, (float) Math.sqrt(((px + 0.5f) / pw - 0.97f) * ((px + 0.5f) / pw - 0.97f) * aspect * aspect + (v - 0.93f) * (v - 0.93f)));
                // the felt, from its dark to its lit tone, a touch of fine grain
                final float tone = Math.min(1f, 0.18f + lit * 0.95f);
                float r = m.dark[0] + (m.lit[0] - m.dark[0]) * tone;
                float gr = m.dark[1] + (m.lit[1] - m.dark[1]) * tone;
                float b = m.dark[2] + (m.lit[2] - m.dark[2]) * tone;
                // the grid: columns converge on the horizon, rows crowd together; fine, fading with distance
                final float xCell = tx / CELL;
                final float colDist = Math.abs(xCell - Math.round(xCell)) * CELL / xPerPx;
                final float cover = Math.max(rowLine, coverage(colDist, 1.0f));
                final float line = cover * (0.045f + 0.075f * lit) * (0.25f + 0.75f * (1f - haze * 0.9f));
                r += (m.line[0] - r) * line;
                gr += (m.line[1] - gr) * line;
                b += (m.line[2] - b) * line;
                // the carved ring in the middle of the table: a ring on the table plane is an ellipse on the screen
                final float rdz = z - RING_Z;
                final float ringDist = Math.max(1e-4f, (float) Math.sqrt(tx * tx + rdz * rdz));
                final float gx = tx / ringDist * xPerPx, gz = rdz / ringDist * zPerPx; // one pixel's step along the ring's normal
                final float ringPx = Math.abs(ringDist - RING_R) / Math.max(1e-5f, (float) Math.sqrt(gx * gx + gz * gz));
                final float ring = coverage(ringPx, 2.2f) * 0.10f * (0.3f + lit);
                r += (m.light[0] - r) * ring;
                gr += (m.light[1] - gr) * ring;
                b += (m.light[2] - b) * ring;
                // the light itself warms the lit part a little
                final float warm = lit * m.lightGain * 0.35f;
                r += (m.light[0] - r) * warm;
                gr += (m.light[1] - gr) * warm;
                b += (m.light[2] - b) * warm;
                // haze at the far edge, then the vignette
                final float h0 = haze * haze * 0.85f;
                r += (m.haze[0] - r) * h0;
                gr += (m.haze[1] - gr) * h0;
                b += (m.haze[2] - b) * h0;
                final float k = edge * corner;
                final float grain = noise(px, py) * 1.4f;
                out.put((byte) Math.max(0, Math.min(255, Math.round(r * k + grain))));
                out.put((byte) Math.max(0, Math.min(255, Math.round(gr * k + grain))));
                out.put((byte) Math.max(0, Math.min(255, Math.round(b * k + grain))));
                out.put((byte) 255);
            }
        }
        out.position(0);
    }
}
