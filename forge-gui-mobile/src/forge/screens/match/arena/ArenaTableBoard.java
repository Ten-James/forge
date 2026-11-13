package forge.screens.match.arena;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.TimeUtils;

import forge.Graphics;
import forge.gui.GuiBase;

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
 * monitor, so grid lines stay sharp at 21:9 and 32:9) and drawn as one image per frame. The pixels are filled on a
 * worker thread (nothing of GL is touched there) and uploaded on the render thread when they are ready, so frames are
 * never spent on it, whatever the refresh rate. When a new size or mood is ready it fades in over the old picture; a
 * plain gradient stands in until the first one exists. A size is only baked once the window has held still for a
 * moment. The texture is a managed one (a lost GL context brings it back), is kept from one match to the next, and is
 * let go when the Arena look or the clean board is turned off ({@link #release}). Nothing is allocated per frame.</p>
 */
public final class ArenaTableBoard {
    private ArenaTableBoard() { }

    /** The longest side of the baked picture, the shortest, and how large a share of the window it is at most. */
    private static final int MAX_SIDE = 2560, MAX_SIDE_ANDROID = 1280, MIN_SIDE = 320;
    private static final float MAX_SHARE = 0.75f;
    /** How long a size has to hold still before it is baked, and how long a new picture takes to fade in. */
    private static final int SETTLE_MS = 160;
    private static final int FADE_MS = 320;

    // ---- the table in perspective: the camera looks down at the table; the horizon lies well above the window -------
    /** Where the horizon is, in window heights from the top (negative: above the window), and the grid's cell size. */
    private static final float HORIZON = -1.0f, CELL_X = 0.07f, CELL_Z = 0.03f; // cells wider than tall, as a floor seen at a slant
    /** Where the key light lands on the screen (shares of the window). */
    private static final float LIGHT_X = 0.46f, LIGHT_Y = 0.42f;
    /** The carved ring in the middle of the table: a flat ellipse on the screen, in window heights, around the board's medallion. */
    private static final float RING_X = 0.2f, RING_Y = 0.085f, RING_AT = 0.42f;
    /** The seam between the halves (the board's field split), and how wide the aspect has to be for the side lights to be at full strength. */
    private static final float SEAM = ArenaTheme.FIELD_SPLIT;

    /** One mood: the felt in the dark and under the light, the haze, the grid lines, the light, the cool and warm halves. */
    private static final class Mood {
        final int[] dark, lit, haze, line, light, cool;
        final Color fallbackTop, fallbackBottom;
        final float lightGain;

        Mood(final int dark, final int lit, final int haze, final int line, final int light, final int cool, final float lightGain) {
            this.dark = split(dark);
            this.lit = split(lit);
            this.haze = split(haze);
            this.line = split(line);
            this.light = split(light);
            this.cool = split(cool);
            this.lightGain = lightGain;
            // what stands in until the picture exists: about the tone the picture has at its top and at its bottom
            this.fallbackTop = ArenaTheme.rgb(blend(dark, haze, 0.3f));
            this.fallbackBottom = ArenaTheme.rgb(blend(dark, lit, 0.35f));
        }

        private static int blend(final int a, final int b, final float k) {
            int out = 0;
            for (int shift = 16; shift >= 0; shift -= 8) {
                final int ca = (a >> shift) & 0xFF, cb = (b >> shift) & 0xFF;
                out |= Math.round(ca + (cb - ca) * k) << shift;
            }
            return out;
        }

        private static int[] split(final int hex) {
            return new int[] {(hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF};
        }
    }

    // day, default and night, like the flagstone board's moods
    private static final Map<ArenaTheme.BoardVariant, Mood> MOODS = new EnumMap<>(ArenaTheme.BoardVariant.class);
    static {
        MOODS.put(ArenaTheme.BoardVariant.DAY, new Mood(0x243651, 0x5A7898, 0x1B2A40, 0xBBD3EE, 0xFFF3DF, 0x6E92C4, 0.34f));
        MOODS.put(ArenaTheme.BoardVariant.DEFAULT, new Mood(0x1B2840, 0x40587E, 0x121C30, 0x9DBBE6, 0xFFE8C8, 0x5F86C0, 0.30f));
        MOODS.put(ArenaTheme.BoardVariant.NIGHT, new Mood(0x0E1528, 0x25355A, 0x080D1B, 0x6F8CC4, 0xC6D4FF, 0x4A6AB0, 0.30f));
    }

    private static final int RUNNING = 0, DONE = 1, CANCELLED = 2;
    private static ExecutorService worker;

    /** A picture being baked on the worker thread. Whoever comes second (the worker or a cancel) disposes the pixmap. */
    private static final class Job implements Runnable {
        final Pixmap pixmap;
        final ArenaTheme.BoardVariant mood;
        final float aspect;
        final int w, h;
        final AtomicInteger state = new AtomicInteger(RUNNING);

        Job(final int w, final int h, final ArenaTheme.BoardVariant mood, final float aspect) {
            this.pixmap = new Pixmap(w, h, Pixmap.Format.RGBA8888);
            this.w = w;
            this.h = h;
            this.mood = mood;
            this.aspect = aspect;
        }

        @Override
        public void run() {
            final ByteBuffer out = pixmap.getPixels().duplicate(); // its own position, away from the render thread's
            out.clear();
            final Mood m = MOODS.get(mood);
            for (int row = 0; row < h && state.get() == RUNNING; row++) {
                renderRow(out, row, w, h, aspect, m);
            }
            if (state.compareAndSet(RUNNING, DONE)) {
                if (Gdx.graphics != null) {
                    Gdx.graphics.requestRendering(); // ready: the render thread uploads it on its next frame
                }
            } else {
                pixmap.dispose(); // cancelled while it ran
            }
        }

        /** Drops it: the pixmap goes now if it is ready, or when the worker notices. */
        void cancel() {
            if (!state.compareAndSet(RUNNING, CANCELLED)) {
                pixmap.dispose(); // it had finished, and nobody else will use it
            }
        }
    }

    private static Pixmap pixmap;   // what the texture shows (kept: the texture is managed)
    private static Texture texture;
    private static ArenaTheme.BoardVariant shownMood;
    private static int shownW, shownH;
    private static Job job;
    // the picture that was shown before the new one, fading out
    private static Texture fadeFrom;
    private static Pixmap fadePixmap;
    private static float fade = 1f;
    private static ArenaMotion.Tween fadeTween;
    private static float seenW = -1, seenH = -1;
    private static long seenSince;
    private static final int[] SIZE = new int[2];

    /** Whether a picture (or the making of one) is held, so that the match screen can let go of it when it is not wanted. */
    public static boolean isLoaded() {
        return texture != null || job != null || upload != null;
    }

    /** Draws the table over {@code x, y, w, h} (the match screen's area). Render thread. */
    public static void draw(final Graphics g, final float x, final float y, final float w, final float h, final ArenaTheme.BoardVariant variant) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (w != seenW || h != seenH) {
            seenW = w;
            seenH = h;
            seenSince = TimeUtils.millis();
        }
        final boolean settled = TimeUtils.timeSinceMillis(seenSince) >= SETTLE_MS;
        sizeFor(w, h);
        if (job != null && (job.mood != variant || job.w != SIZE[0] || job.h != SIZE[1])) {
            job.cancel(); // what it is making is not what is wanted now
            job = null;
        }
        if (upload != null) {
            stepUpload();
        }
        if (job != null && job.state.get() == DONE) {
            beginUpload(job);
            job = null;
        }
        final boolean stale = texture == null || variant != shownMood || SIZE[0] != shownW || SIZE[1] != shownH;
        if (stale && job == null && upload == null && settled) {
            job = new Job(SIZE[0], SIZE[1], variant, w / h);
            if (worker == null) {
                worker = Executors.newSingleThreadExecutor(r -> {
                    final Thread t = new Thread(r, "arena-board");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
            }
            worker.execute(job);
        } else if (stale && !settled) {
            ArenaMotion.requestFrame(); // come back once the size has held still
        }
        if (fade < 1f && fadeFrom != null) {
            g.drawImage(fadeFrom, x, y, w, h);
        } else if (texture == null || fade < 1f) {
            drawFallback(g, x, y, w, h, variant);
        }
        if (texture != null) {
            if (fade < 1f) {
                final float old = g.getfloatAlphaComposite();
                g.setAlphaComposite(old * fade);
                g.drawImage(texture, x, y, w, h);
                g.setAlphaComposite(old);
            } else {
                g.drawImage(texture, x, y, w, h);
            }
        }
    }

    /** A plain gradient of the mood, for while the first picture is being made. */
    private static void drawFallback(final Graphics g, final float x, final float y, final float w, final float h, final ArenaTheme.BoardVariant variant) {
        final Mood m = MOODS.get(variant);
        g.fillGradientRect(m.fallbackTop, m.fallbackBottom, true, x, y, w, h);
    }

    /** A picture being sent to the GPU in strips, a little each frame (desktop). */
    private static final class Upload {
        final Job job;
        final Texture tex;
        int row;

        Upload(final Job job, final Texture tex) {
            this.job = job;
            this.tex = tex;
        }
    }

    private static Upload upload;
    /** About how much of a picture is sent per frame, in bytes. */
    private static final int STRIP_BYTES = 1_500_000;

    /**
     * A finished picture is sent to the GPU. On Android in one go, as a managed texture whose pixmap is kept (a lost GL
     * context brings it back); on the desktop an empty texture is made and filled strip by strip over a few frames, and
     * the pixmap is let go afterwards, so a picture costs no frame much and no memory beyond the texture.
     */
    private static void beginUpload(final Job j) {
        if (GuiBase.isAndroid()) {
            final Texture t = new Texture(new PixmapTextureData(j.pixmap, null, false, false, true));
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            swapIn(t, j.pixmap, j);
            return;
        }
        final Texture t = new Texture(new GLOnlyTextureData(j.w, j.h, 0, GL20.GL_RGBA, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE));
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        upload = new Upload(j, t);
    }

    private static void stepUpload() {
        final Upload u = upload;
        final int rows = Math.max(16, STRIP_BYTES / (u.job.w * 4));
        final int n = Math.min(rows, u.job.h - u.row);
        final ByteBuffer pixels = u.job.pixmap.getPixels();
        pixels.position(u.row * u.job.w * 4);
        u.tex.bind();
        Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1);
        Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, u.row, u.job.w, n, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
        u.row += n;
        if (u.row >= u.job.h) {
            upload = null;
            u.job.pixmap.dispose();
            swapIn(u.tex, null, u.job);
        } else {
            ArenaMotion.requestFrame();
        }
    }

    /** A picture that is ready becomes the one on the screen, fading in over the one that was there (at once with reduce motion). */
    private static void swapIn(final Texture tex, final Pixmap keep, final Job j) {
        endFade(); // a fade still running is cut short: its old picture goes
        fadeFrom = texture;
        fadePixmap = pixmap;
        pixmap = keep;
        texture = tex;
        shownMood = j.mood;
        shownW = j.w;
        shownH = j.h;
        if (!ArenaTheme.isEffectsOn()) {
            endFade();
            return;
        }
        fade = 0f;
        fadeTween = ArenaMotion.play(FADE_MS, ArenaMotion.LINEAR, t -> fade = t, ArenaTableBoard::endFade);
    }

    private static void endFade() {
        if (fadeTween != null) {
            fadeTween.cancel();
            fadeTween = null;
        }
        fade = 1f;
        if (fadeFrom != null) {
            fadeFrom.dispose();
            fadeFrom = null;
        }
        if (fadePixmap != null) {
            fadePixmap.dispose();
            fadePixmap = null;
        }
    }

    /** Lets go of the picture (the Arena look or the clean board went off): the next draw bakes it again. Render thread. */
    public static void release() {
        if (job != null) {
            job.cancel();
            job = null;
        }
        if (upload != null) {
            upload.tex.dispose();
            upload.job.pixmap.dispose();
            upload = null;
        }
        endFade();
        if (texture != null) {
            texture.dispose();
            texture = null;
        }
        if (pixmap != null) {
            pixmap.dispose();
            pixmap = null;
        }
        shownMood = null;
        shownW = shownH = 0;
        seenW = seenH = -1;
    }

    /** The size to bake at: three quarters of the window in real pixels (the back buffer's, so HiDPI stays sharp), within the limit. */
    private static void sizeFor(final float w, final float h) {
        final float density = Gdx.graphics == null ? 1f : Math.max(1f, Gdx.graphics.getBackBufferWidth() / (float) Math.max(1, Gdx.graphics.getWidth()));
        final float limit = GuiBase.isAndroid() ? MAX_SIDE_ANDROID : MAX_SIDE;
        final float scale = Math.min(MAX_SHARE * density, limit / Math.max(w, h));
        SIZE[0] = Math.max(MIN_SIDE / 2, Math.round(w * scale));
        SIZE[1] = Math.max(MIN_SIDE / 2, Math.round(h * scale));
    }

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
        final float zCell = z / CELL_Z;
        final float rowLine = coverage(Math.abs(zCell - Math.round(zCell)) * CELL_Z / zPerPx, 1.15f);
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
            final float xCell = tx / CELL_X;
            final float colDist = Math.abs(xCell - Math.round(xCell)) * CELL_X / xPerPx;
            final float cover = Math.max(rowLine, coverage(colDist, 1.0f));
            final float line = cover * (0.05f + 0.08f * lit) * (0.45f + 0.55f * (1f - haze));
            r = mixTo(r, m.line[0], line);
            gr = mixTo(gr, m.line[1], line);
            b = mixTo(b, m.line[2], line);
            // the carved ring in the middle of the table: a ring on the table plane is an ellipse on the screen
            final float rex = u / RING_X, rey = (v - RING_AT) / RING_Y;
            final float ringPx = Math.abs((float) Math.sqrt(rex * rex + rey * rey) - 1f) * RING_Y / invH * 0.9f;
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
