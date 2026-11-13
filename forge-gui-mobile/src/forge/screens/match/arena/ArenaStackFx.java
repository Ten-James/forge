package forge.screens.match.arena;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.IntIntMap;
import com.badlogic.gdx.utils.IntMap;
import com.badlogic.gdx.utils.IntSet;

import forge.Graphics;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.event.GameEventSpellResolved;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gui.FThreads;
import forge.screens.match.MatchController;
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
 * <li><b>Going off</b>: the engine says when an entry has resolved ({@link #resolvedEvent}, also to network clients,
 *     which receive the game events), fizzled or not; an entry that is gone without it, while its controller is still in
 *     the game, was countered, and only then does it shatter. A resolved instant or sorcery sparks and sends a bolt to
 *     what it aimed at; a fizzled one and an ability make a small neutral burst; a permanent spell makes none (its
 *     landing on the board is the accent).</li>
 * <li><b>Cause before effect</b>: what a resolving instant or sorcery aims at holds its damage flash and its life roll
 *     until the bolt has had time to arrive ({@link #holdMs}).</li>
 * </ul>
 *
 * <p>This is also <em>the</em> tracker of which entries the stack view showed and where: the view asks
 * {@link #stackSynced} whenever its stack changes, and is told through a {@link Left} which entries are gone, for the
 * spells that fly off to a pile.</p>
 */
public final class ArenaStackFx {
    private ArenaStackFx() { }

    /** An entry must be drawn within this long of joining the stack to fly in. */
    private static final int FRESH_MS = 600;
    /** How long after an entry is gone the engine's word that it resolved is waited for. */
    private static final int SETTLE_MS = 150;
    /** How long what a bolt aims at holds its hit: the wait for the word plus the bolt's flight. */
    private static final int HIT_HOLD_MS = SETTLE_MS + ArenaFx.BOLT_MS + 60;
    private static final long RESOLVED_KEEP_NANOS = 10_000_000_000L;

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

    private static final IntMap<Shown> shown = new IntMap<>();
    private static final IntSet known = new IntSet();
    private static final IntIntMap fresh = new IntIntMap();          // entry id -> when it joined (ms)
    private static final IntMap<Resolved> resolved = new IntMap<>();
    private static final IntIntMap aimedCards = new IntIntMap();     // card id -> until when an instant or sorcery aims at it (ms)
    private static final IntIntMap aimedPlayers = new IntIntMap();
    private static final List<ArenaMotion.Tween> pending = new ArrayList<>(); // the waits before an entry's fate is decided
    private static final IntArray scratch = new IntArray();
    private static boolean initialised;
    private static final Rectangle SCRATCH = new Rectangle();

    static void reset() {
        for (final ArenaMotion.Tween t : pending) {
            t.cancel(); // a stale check must not shatter anything in the next game, or after an undo
        }
        pending.clear();
        shown.clear();
        known.clear();
        fresh.clear();
        resolved.clear();
        aimedCards.clear();
        aimedPlayers.clear();
        initialised = false;
    }

    private static int nowMs() {
        return (int) (System.nanoTime() / 1_000_000L);
    }

    private static int keyOf(final CardView card, final boolean ability) {
        return card.getId() * 2 + (ability ? 1 : 0);
    }

    /** The engine finished resolving a spell or ability (fizzled or not). Any thread. */
    public static void resolvedEvent(final GameEventSpellResolved event) {
        if (!ArenaTheme.isEffectsOn()) {
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
            if (resolved.size > 64) { // words about abilities that never showed on the stack: only the old ones go
                scratch.clear();
                for (final IntMap.Entry<Resolved> en : resolved) {
                    if (r.at - en.value.at > RESOLVED_KEEP_NANOS) {
                        scratch.add(en.key);
                    }
                }
                for (int i = 0; i < scratch.size; i++) {
                    resolved.remove(scratch.get(i));
                }
            }
            resolved.put(key, r);
        });
    }

    // ---- going on ----------------------------------------------------------------------------------------------------

    /**
     * The stack view calls this when it draws an entry of the Arena stack, with {@code g} at the entry's own corner.
     * It keeps the entry's place for what happens when it is gone; and when the entry has just joined the stack, its
     * card flies in. While it flies this draws a {@link ArenaFx#ghost ghost} of the card in the entry's place, still
     * wearing the glow of a target that can be picked, and says true: the view then draws nothing of its own. In the
     * last part of the flight it says false and the flight fades out above the entry the view draws.
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
        final int joined = fresh.get(id, 0);
        if (joined != 0) {
            fresh.remove(id, 0);
            if (nowMs() - joined < FRESH_MS) {
                final Rectangle from = originOf(item, handStart);
                if (from != null) {
                    // it lands bigger than it starts: it grows into the large card of the stack
                    final float sh = Math.max(from.height, h * 0.5f), sw = ArenaFx.cardWidthFor(sh);
                    SCRATCH.set(from.x + from.width / 2f - sw / 2f, from.y + from.height / 2f - sh / 2f, sw, sh);
                    ArenaFx.fly(ArenaFx.CAT_STACK, id, s.card, null, SCRATCH, new Rectangle(screenPos.x, screenPos.y, w, h), false, ArenaFx.STACK_MS, 0);
                }
                if (s.card != null && !s.ability && !isPermanentSpell(s.card)) {
                    VCardDisplayArea.CardAreaPanel.takeHandStart(s.card); // it has left the hand for good: a flashback later starts from elsewhere
                }
            }
        }
        final ArenaFx.Fx e = ArenaFx.stackFlights.size == 0 ? null : ArenaFx.stackFlights.get(id);
        if (e == null) {
            return false;
        }
        e.to.set(screenPos.x, screenPos.y, w, h);
        if (!e.hidesTarget()) {
            return false;
        }
        ArenaFx.ghost(g, 0, 0, w, h, glow, 0.6f, s.card);
        return true;
    }

    /**
     * A touch on an entry whose card is still flying in only ends the flight and is used up: it does not also pick the
     * entry as a target, which the player could not see. Render thread; true when it did that.
     */
    public static boolean reveal(final StackItemView item) {
        if (ArenaFx.stackFlights.size == 0) {
            return false;
        }
        final ArenaFx.Fx e = ArenaFx.stackFlights.get(item.getId());
        if (e == null || !e.hidesTarget()) {
            return false;
        }
        ArenaFx.finish(e);
        return true;
    }

    private static boolean isPermanentSpell(final CardView card) {
        final CardStateView state = card.getCurrentState();
        return !state.isInstant() && !state.isSorcery();
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
        final IntSet still = new IntSet();
        for (final StackItemView item : stack) {
            still.add(item.getId());
        }
        if (!effects) { // what is on the stack now is not news when the effects come back
            known.clear();
            fresh.clear();
            aimedCards.clear();
            aimedPlayers.clear();
            initialised = false;
        } else {
            final int now = nowMs();
            final boolean news = initialised && !ArenaFx.quiet();
            for (final StackItemView item : stack) {
                if (known.add(item.getId()) && news) {
                    fresh.put(item.getId(), Math.max(1, now));
                }
                aim(item, now + 600_000);
            }
            scratch.clear();
            for (final IntSet.IntSetIterator it = known.iterator(); it.hasNext;) {
                final int id = it.next();
                if (!still.contains(id)) {
                    scratch.add(id);
                }
            }
            for (int i = 0; i < scratch.size; i++) {
                known.remove(scratch.get(i));
                fresh.remove(scratch.get(i), 0);
            }
            initialised = true;
        }
        if (shown.size == 0) {
            return;
        }
        final IntArray gone = new IntArray();
        for (final IntMap.Keys keys = shown.keys(); keys.hasNext;) {
            final int id = keys.next();
            if (!still.contains(id)) {
                gone.add(id);
            }
        }
        for (int i = 0; i < gone.size; i++) {
            final Shown s = shown.remove(gone.get(i));
            if (left != null && s.card != null) {
                left.left(s.item, s.card, s.rect);
            }
            if (effects) {
                ArenaFx.finish(ArenaFx.stackFlights.get(gone.get(i)));
                aim(s.item, nowMs() + HIT_HOLD_MS * 2); // the hit waits for the bolt a little longer, then it is no one's business
                final ArenaMotion.Tween[] wait = new ArenaMotion.Tween[1];
                wait[0] = ArenaMotion.play(SETTLE_MS, ArenaMotion.LINEAR, null, () -> {
                    pending.remove(wait[0]);
                    wentOff(s);
                });
                pending.add(wait[0]);
            }
        }
    }

    /** Marks what an instant or sorcery aims at (the only entries that send a bolt), until the given time. */
    private static void aim(final StackItemView item, final int until) {
        if (item == null || item.isAbility() || item.getSourceCard() == null || isPermanentSpell(item.getSourceCard())) {
            return;
        }
        if (item.getTargetCards() != null) {
            for (final CardView c : item.getTargetCards()) {
                aimedCards.put(c.getId(), until);
            }
        }
        if (item.getTargetPlayers() != null) {
            for (final PlayerView p : item.getTargetPlayers()) {
                aimedPlayers.put(p.getId(), until);
            }
        }
    }

    /** How long this card's damage feedback waits: while an instant or sorcery aims at it, for its bolt to arrive; else 0. */
    public static int holdMs(final CardView card) {
        return ArenaTheme.isEffectsOn() && aimedCards.size > 0 && aimedCards.get(card.getId(), 0) > nowMs() ? HIT_HOLD_MS : 0;
    }

    /** How long this player's life feedback waits, as {@link #holdMs(CardView)} for a card. */
    public static int holdMs(final PlayerView player) {
        return ArenaTheme.isEffectsOn() && aimedPlayers.size > 0 && aimedPlayers.get(player.getId(), 0) > nowMs() ? HIT_HOLD_MS : 0;
    }

    private static void wentOff(final Shown s) {
        if (!ArenaFx.on() || s.rect.width <= 0 || s.card == null) {
            return;
        }
        final GameView game = MatchController.instance == null ? null : MatchController.instance.getGameView();
        final PlayerView controller = s.item == null ? null : s.item.getActivatingPlayer();
        if (game == null || game.isGameOver() || controller != null && controller.getHasLost()) {
            return; // the game is over, or the player left and what they had on the stack went with them: nothing was countered
        }
        final Resolved r = resolved.remove(keyOf(s.card, s.ability));
        final boolean resolvedNow = r != null && System.nanoTime() - r.at < RESOLVED_KEEP_NANOS;
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
        if (r.fizzled || s.ability) {
            ArenaFx.burst(cx, cy, size * 0.45f, c); // it did nothing, or it is an ability: a small neutral burst, no hit
            return;
        }
        if (isPermanentSpell(s.card)) {
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
