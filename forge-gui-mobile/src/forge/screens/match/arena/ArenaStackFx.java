package forge.screens.match.arena;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;

import forge.Graphics;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.event.GameEventSpellResolved;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gui.FThreads;
import forge.screens.match.MatchScreen;
import forge.screens.match.views.VCardDisplayArea;
import forge.screens.match.views.VPlayerPanel;
import forge.util.collect.FCollectionView;

/**
 * Spells and abilities on the Arena stack, as effects ({@link ArenaFx} runs them).
 *
 * <ul>
 * <li><b>Going on</b>: an entry that joins the stack flies in from where it was cast or activated (the caster's hand,
 *     their portrait, the permanent's tile) and lands with a ring. Only entries that joined while effects were on and
 *     the game was under way, and were drawn within a moment of joining, fly: what was already there, or was hidden
 *     deep in the stack, simply appears.</li>
 * <li><b>Going off</b>: the engine says when an entry has resolved ({@link #resolvedEvent}), fizzled or not; an entry
 *     that is gone without it was countered, and only then does it shatter in red. A resolved instant or sorcery
 *     sparks and sends a bolt to what it aimed at; a fizzled one and an ability make a small neutral burst; a
 *     permanent spell makes none (its landing on the board is the accent).</li>
 * </ul>
 *
 * <p>This is also <em>the</em> tracker of which entries the stack view showed and where: the view asks
 * {@link #stackSynced} whenever its stack changes, and is told through a {@link Left} which entries are gone, for the
 * spells that fly off to a pile.</p>
 */
public final class ArenaStackFx {
    private ArenaStackFx() { }

    /** An entry must be drawn within this long of joining the stack to fly in. */
    private static final long FRESH_NANOS = 600_000_000L;
    /** How long after an entry is gone the engine's word that it resolved is waited for. */
    private static final int SETTLE_MS = 150;

    /** Told which entries have left the stack, with the card and the place where each was last shown. */
    public interface Left {
        void left(StackItemView item, CardView card, Rectangle where);
    }

    /** An entry of the stack as it was last drawn. */
    private static final class Shown {
        StackItemView item;
        CardView card;
        boolean ability;
        final Rectangle rect = new Rectangle();
    }

    /** What the engine said about a resolved entry (by its card and whether it is a spell). */
    private static final class Resolved {
        boolean fizzled;
        long at;
    }

    private static final Map<Integer, Shown> shown = new HashMap<>();
    private static final Set<Integer> known = new HashSet<>();
    private static final Map<Integer, Long> fresh = new HashMap<>();
    private static final Map<Integer, Resolved> resolved = new HashMap<>();
    private static boolean initialised;
    private static final Rectangle SCRATCH = new Rectangle();

    static void reset() {
        shown.clear();
        known.clear();
        fresh.clear();
        resolved.clear();
        initialised = false;
    }

    private static int keyOf(final CardView card, final boolean ability) {
        return card.getId() * 2 + (ability ? 1 : 0);
    }

    /** The engine finished resolving a spell or ability (fizzled or not). Any thread. */
    public static void resolvedEvent(final GameEventSpellResolved event) {
        if (!ArenaFx.on()) {
            return;
        }
        final CardView host = event.spell() == null ? null : event.spell().getHostCard();
        if (host == null) {
            return;
        }
        final int key = keyOf(host, !event.spell().isSpell());
        final boolean fizzled = event.hasFizzled();
        FThreads.invokeInEdtNowOrLater(() -> {
            final Resolved r = new Resolved();
            r.fizzled = fizzled;
            r.at = System.nanoTime();
            if (resolved.size() > 64) { // words about abilities that never showed on the stack
                resolved.clear();
            }
            resolved.put(key, r);
        });
    }

    // ---- going on ----------------------------------------------------------------------------------------------------

    /**
     * The stack view calls this when it draws an entry of the Arena stack, with {@code g} at the entry's own corner.
     * It keeps the entry's place for what happens when it is gone; and when the entry has just joined the stack, its
     * card flies in. While it flies this draws a {@link ArenaFx#ghost ghost} in the entry's place, still wearing the
     * glow of a target that can be picked, and says true: the view then draws nothing of its own. In the last part of
     * the flight it says false and the flight fades out above the entry the view draws.
     */
    public static boolean entry(final Graphics g, final StackItemView item, final Rectangle screenPos, final float w, final float h,
            final Color glow, final Rectangle handStart) {
        final int id = item.getId();
        Shown s = shown.get(id);
        if (s == null) {
            s = new Shown();
            shown.put(id, s);
        }
        s.item = item;
        s.card = item.getSourceCard();
        s.ability = item.isAbility();
        s.rect.set(screenPos.x, screenPos.y, w, h);
        if (!ArenaFx.on()) {
            return false;
        }
        final Long joined = fresh.remove(id);
        if (joined != null && System.nanoTime() - joined < FRESH_NANOS) {
            final Rectangle from = originOf(item, handStart);
            if (from != null) {
                // it lands bigger than it starts: it grows into the large card of the stack
                final float sh = Math.max(from.height, h * 0.5f), sw = ArenaFx.cardWidthFor(sh);
                SCRATCH.set(from.x + from.width / 2f - sw / 2f, from.y + from.height / 2f - sh / 2f, sw, sh);
                ArenaFx.fly(ArenaFx.CAT_STACK, id, s.card, null, SCRATCH, new Rectangle(screenPos.x, screenPos.y, w, h), false, ArenaFx.STACK_MS, 0);
            }
        }
        final ArenaFx.Fx e = ArenaFx.stackFlights.isEmpty() ? null : ArenaFx.stackFlights.get(id);
        if (e == null) {
            return false;
        }
        e.to.set(screenPos.x, screenPos.y, w, h);
        if (!e.hidesTarget()) {
            return false;
        }
        ArenaFx.ghost(g, 0, 0, w, h, glow, 0.6f);
        return true;
    }

    /** The player touched the entry: if its flight is still on its way, it is in its place now. */
    public static void finish(final StackItemView item) {
        if (!ArenaFx.stackFlights.isEmpty()) {
            ArenaFx.finish(ArenaFx.stackFlights.get(item.getId()));
        }
    }

    private static Rectangle originOf(final StackItemView item, final Rectangle handStart) {
        final CardView source = item.getSourceCard();
        if (!item.isAbility() && handStart != null && handStart.width > 0) {
            return handStart;
        }
        if (item.isAbility() && source != null && source.getZone() == ZoneType.Battlefield) {
            final VCardDisplayArea.CardAreaPanel tile = VCardDisplayArea.CardAreaPanel.peek(source);
            if (tile != null && tile.screenPos.width > 0) {
                return tile.screenPos;
            }
        }
        final PlayerView activator = item.getActivatingPlayer();
        final VPlayerPanel panel = activator == null ? null : MatchScreen.getPlayerPanel(activator);
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

    // ---- going off ---------------------------------------------------------------------------------------------------

    /**
     * The stack view tells which entries are on the stack now (whenever it updates or lays out). Entries that joined
     * since the last time are marked fresh (so they may fly in once, if drawn soon); entries it had shown that are gone
     * are reported to {@code left}, any flight of theirs still on its way is finished (no second copy is left on
     * screen), and what happened to them goes off: a short moment later, once the engine's word on it has surely come.
     */
    public static void stackSynced(final FCollectionView<StackItemView> stack, final Left left) {
        final boolean effects = ArenaFx.on();
        if (!effects) { // what is on the stack now is not news when the effects come back
            known.clear();
            fresh.clear();
            initialised = false;
        } else {
            final long now = System.nanoTime();
            final boolean news = initialised && !ArenaFx.quiet();
            final Set<Integer> still = new HashSet<>();
            for (final StackItemView item : stack) {
                still.add(item.getId());
                if (known.add(item.getId()) && news) {
                    fresh.put(item.getId(), now);
                }
            }
            known.retainAll(still);
            fresh.keySet().retainAll(still);
            initialised = true;
        }
        if (shown.isEmpty()) {
            return;
        }
        final Set<Integer> still = new HashSet<>();
        for (final StackItemView item : stack) {
            still.add(item.getId());
        }
        for (final Iterator<Map.Entry<Integer, Shown>> it = shown.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<Integer, Shown> en = it.next();
            if (still.contains(en.getKey())) {
                continue;
            }
            it.remove();
            final Shown s = en.getValue();
            if (left != null && s.card != null) {
                left.left(s.item, s.card, s.rect);
            }
            if (effects) {
                ArenaFx.finish(ArenaFx.stackFlights.get(en.getKey()));
                final Shown copy = s;
                ArenaMotion.play(SETTLE_MS, ArenaMotion.LINEAR, null, () -> wentOff(copy));
            }
        }
    }

    private static void wentOff(final Shown s) {
        if (!ArenaFx.on() || s.rect.width <= 0 || s.card == null) {
            return;
        }
        final Resolved r = resolved.remove(keyOf(s.card, s.ability));
        final boolean resolvedNow = r != null && System.nanoTime() - r.at < 5_000_000_000L;
        final float cx = s.rect.x + s.rect.width / 2f, cy = s.rect.y + s.rect.height / 2f, size = Math.max(s.rect.width, ArenaTheme.px(80));
        final Color c = ArenaFx.colorOf(s.card, new Color());
        if (!resolvedNow) { // gone with no word that it resolved
            if (s.ability) {
                ArenaFx.burst(cx, cy, size * 0.45f, c); // not a spell: a small neutral burst, it is not known what became of it
            } else {
                ArenaFx.shatter(cx, cy, size); // a spell that never resolved was countered
            }
            return;
        }
        if (r.fizzled) {
            ArenaFx.burst(cx, cy, size * 0.45f, c); // it did nothing: a small neutral burst, no hit
            return;
        }
        if (s.ability) {
            ArenaFx.burst(cx, cy, size * 0.45f, c);
            return;
        }
        final CardStateView state = s.card.getCurrentState();
        if (!state.isInstant() && !state.isSorcery()) {
            return; // a permanent spell: where it lands on the board is the one accent
        }
        final float[] aim = targetOf(s.item);
        if (aim != null) {
            ArenaFx.burst(cx, cy, size * 0.5f, c);
            ArenaFx.bolt(cx, cy, aim[0], aim[1], c, size * 0.8f);
        } else {
            ArenaFx.burst(cx, cy, size, c);
        }
    }

    /** Where the entry's first target is on the screen (a card's tile or a player's portrait), or null. */
    private static float[] targetOf(final StackItemView item) {
        if (item == null) {
            return null;
        }
        if (item.getTargetCards() != null) {
            for (final CardView c : item.getTargetCards()) {
                final VCardDisplayArea.CardAreaPanel tile = VCardDisplayArea.CardAreaPanel.peek(c);
                if (tile != null && tile.screenPos.width > 0) {
                    return new float[] {tile.screenPos.x + tile.screenPos.width / 2f, tile.screenPos.y + tile.screenPos.height / 2f};
                }
            }
        }
        if (item.getTargetPlayers() != null) {
            for (final PlayerView p : item.getTargetPlayers()) {
                final VPlayerPanel panel = MatchScreen.getPlayerPanel(p);
                if (panel != null && panel.getAvatar() != null && panel.getAvatar().screenPos.width > 0) {
                    final Rectangle r = panel.getAvatar().screenPos;
                    return new float[] {r.x + r.width / 2f, r.y + r.height / 2f};
                }
            }
        }
        return null;
    }
}
