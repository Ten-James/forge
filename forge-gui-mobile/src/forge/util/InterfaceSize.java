package forge.util;

/**
 * How large text, buttons and lists are drawn ({@code FPref.UI_INTERFACE_SIZE}, desktop only).
 * <p>
 * Forge's interface is designed for a phone: {@link Utils#scale} blows a 480 point tall layout up to the height of the
 * window. On a big monitor that makes every label and button several times the size a desktop program uses. The
 * setting scales that down: at 75% a 1440 px window is laid out like a 1080 px one (640 points tall instead of 480), so
 * the same screens show more and draw everything smaller. Layouts that size themselves from the window (the Arena match
 * board) follow an explicit percentage ({@link #explicitFactor()}) but not Auto: they already scale with the window.
 * <p>
 * Read once at start-up, before {@link Utils} is first used; changing it takes a restart.
 */
public final class InterfaceSize {
    public static final String AUTO = "Auto";
    public static final String[] CHOICES = { AUTO, "50%", "60%", "70%", "80%", "90%", "100%" };
    /** Auto: windows up to this tall get the full size; taller ones draw at the size this one would. */
    private static final float AUTO_FULL_HEIGHT = 1080f;

    private static String setting = "100%"; //phones and tablets, and anything that never sets it: the original size

    private InterfaceSize() {
    }

    /** Sets the size the interface is drawn at (desktop launcher, before the window opens). */
    public static void set(String value) {
        setting = value == null || value.isEmpty() ? AUTO : value;
        explicit = 1f;
        if (!AUTO.equalsIgnoreCase(setting)) {
            explicit = factor(startHeight);
        }
    }

    /** The setting as chosen ("Auto" or a percentage). */
    public static String get() {
        return setting;
    }

    private static float startHeight;

    /** The factor {@link Utils} applies to its height-based scale, for a window this tall at start-up. */
    public static float factor(float windowHeight) {
        startHeight = windowHeight;
        if (AUTO.equalsIgnoreCase(setting)) {
            return windowHeight > AUTO_FULL_HEIGHT ? AUTO_FULL_HEIGHT / windowHeight : 1f;
        }
        try {
            float percent = Float.parseFloat(setting.replace("%", "").trim());
            return Math.max(0.4f, Math.min(percent / 100f, 1.5f));
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    private static float explicit = 1f; // read all the time by the Arena board's sizes: worked out when the setting is set

    /**
     * The factor for layouts that size themselves from the window (the Arena board): the chosen percentage, or 1 for Auto
     * (they follow the window's height already, so Auto must not shrink them a second time).
     */
    public static float explicitFactor() {
        return explicit;
    }

    /** "Auto (75%)" for the Auto setting at the height the window had at start-up, the percentage otherwise. */
    public static String describe(String value) {
        return describe(value, startHeight);
    }

    /** "Auto (75%)" for the Auto setting at this window height, the percentage otherwise. */
    public static String describe(String value, float windowHeight) {
        if (AUTO.equalsIgnoreCase(value)) {
            float f = windowHeight > AUTO_FULL_HEIGHT ? AUTO_FULL_HEIGHT / windowHeight : 1f;
            return AUTO + " (" + Math.round(f * 100) + "%)";
        }
        return value;
    }
}
