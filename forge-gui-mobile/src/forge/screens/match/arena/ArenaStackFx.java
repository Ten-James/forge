package forge.screens.match.arena;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.IntArray;
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
import forge.screens.match.CardFlightOverlay;
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
 * <li><b>Cause before effect</b>: what a resolving instant or sorcery aims at holds its damage flash, its number and its life
 *     roll until the bolt arrives ({@link #whenHit}): they start from the bolt's own finish, not after a fixed wait.</li>
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
    /** How long what is aimed at stays marked after its entry left the stack (the engine's word, the longest bolt, a margin), and how long a held hit waits at the most if no bolt ever comes. */
    private static final int LINGER_MS = SETTLE_MS + (int) (ArenaFx.BOLT_MS * 1.4f) + 200, HOLD_MOST_MS = LINGER_MS + 400;
    /** How long a card that was on the stack is remembered as having come off it. */
    private static final int CAME_OFF_MS = 5000;
    private static final long RESOLVED_KEEP_NANOS = 10_000_000_000L;

    /** Told which entries have left the stack, with the card and the place where each was last shown; answers the flight it started for the card, if any. */
    public interface Left {
        CardFlightOverlay.Flight left(StackItemView item, CardView card, Rectangle where);
    }

    /** A hit (a damage flash, a life roll) waiting for the bolt that causes it. */
    public static final class Hold {
        private final Runnable action;
        private ArenaMotion.Tween fallback;
        private boolean done;

        private Hold(final Runnable action) {
            this.action = action;
        }

        /** Drops the hit: it will not happen (the game was undone, a newer change came). */
        public void cancel() {
            done = true;
            if (fallback != null) {
                fallback.cancel();
            }
        }
    }

    /** An entry of the stack as it was last drawn. */
    private static final class Shown {
        StackItemView item;
        CardView card;
        boolean ability;
        CardFlightOverlay.Flight leaver; // the flight of its card to a pile, held until it is known the entry was not countered
        final Rectangle rect = new Rectangle();
    }

    /** What the engine said about a resolved entry (by its card and whether it is a spell). */
    private static final class Resolved {
        boolean fizzled;
        long at;
    }

    private static final IntMap<Shown> shown = new IntMap<>();
    private static final IntSet known = new IntSet();
    private static final IntMap<Long> fresh = new IntMap<>();         // entry id -> when it joined (ms)
    private static final IntMap<Resolved> resolved = new IntMap<>();
    private static final IntMap<Long> aimedCards = new IntMap<>();    // card id -> until when an instant or sorcery aims at it (ms)
    private static final IntMap<Long> aimedPlayers = new IntMap<>();
    private static final IntMap<Long> offStack = new IntMap<>();      // card id -> until when it counts as having been on the stack (ms)
    private static final IntMap<List<Hold>> cardHolds = new IntMap<>(), playerHolds = new IntMap<>();
    private static final List<ArenaMotion.Tween> pending = new ArrayList<>(); // the waits before an entry's fate is decided
    private static final IntArray scratch = new IntArray();
    private static boolean initialised;
    private static final Rectangle SCRATCH = new Rectangle();

    static void reset() {
        for (final ArenaMotion.Tween t : pending) {
            t.cancel(); // a stale check must not shatter anything in the next game, or after an undo
        }
        pending.clear();
        dropHolds(cardHolds);
        dropHolds(playerHolds);
        for (final Shown s : shown.values()) {
            CardFlightOverlay.release(s.leaver);
        }
        shown.clear();
        known.clear();
        fresh.clear();
        resolved.clear();
        aimedCards.clear();
        aimedPlayers.clear();
        offStack.clear();
        initialised = false;
    }

    private static void dropHolds(final IntMap<List<Hold>> holds) {
        for (final List<Hold> list : holds.values()) {
            for (final Hold h : list) {
                h.cancel();
            }
        }
        holds.clear();
    }

    /** Milliseconds on the monotonic clock: a long, so a long session never wraps it. */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
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
        final Long joined = fresh.remove(id);
        if (joined != null) {
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
        final float jump = (float) Math.hypot(screenPos.x + w / 2f - (e.to.x + e.to.width / 2f), screenPos.y + h / 2f - (e.to.y + e.to.height / 2f));
        if (jump > h * 0.6f && e.hidesTarget()) {
            ArenaFx.retarget(e, new Rectangle(screenPos.x, screenPos.y, w, h)); // the stack was laid out somewhere else: carry on from where it is
        } else {
            e.to.set(screenPos.x, screenPos.y, w, h);
        }
        ArenaFx.followed(e);
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
     * since the last time are marked fresh (so they may fly in once, if drawn soon) and forget any old word that they
     * resolved (a card that comes back to the stack, a flashback, is a new story); entries it had shown that are gone
     * are reported to {@code left}, any flight of theirs still on its way is finished (no second copy is left on
     * screen), and what happened to them goes off: a short moment later, once the engine's word on it has surely come.
     * The flight {@code left} starts for the spell's card waits for that verdict ({@link #wentOff}): a countered spell
     * only breaks up where it stood.
     */
    public static void stackSynced(final FCollectionView<StackItemView> stack, final Left left) {
        final boolean effects = ArenaFx.on();
        final IntSet still = new IntSet();
        for (final StackItemView item : stack) {
            still.add(item.getId());
        }
        final long now = nowMs();
        prune(aimedCards, now);
        prune(aimedPlayers, now);
        prune(offStack, now);
        if (!effects) { // what is on the stack now is not news when the effects come back
            known.clear();
            fresh.clear();
            aimedCards.clear();
            aimedPlayers.clear();
            initialised = false;
        } else {
            final boolean news = initialised && !ArenaFx.quiet();
            for (final StackItemView item : stack) {
                if (known.add(item.getId())) {
                    if (item.getSourceCard() != null) {
                        resolved.remove(keyOf(item.getSourceCard(), item.isAbility())); // an old word about this card is not about this entry
                    }
                    if (news) {
                        fresh.put(item.getId(), now);
                    }
                }
                aim(item, now + 600_000L);
                if (!item.isAbility() && item.getSourceCard() != null) {
                    offStack.put(item.getSourceCard().getId(), now + 600_000L);
                }
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
                fresh.remove(scratch.get(i));
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
                s.leaver = left.left(s.item, s.card, s.rect);
            }
            if (s.card != null && !s.ability) {
                offStack.put(s.card.getId(), now + CAME_OFF_MS); // it arrives in a pile soon: that is no discard
            }
            if (effects) {
                ArenaFx.finish(ArenaFx.stackFlights.get(gone.get(i)));
                aim(s.item, now + LINGER_MS); // its hit waits for the bolt (or for the verdict that there is none)
                CardFlightOverlay.hold(s.leaver); // its card does not leave for a pile before it is known it was not countered
                final ArenaMotion.Tween[] wait = new ArenaMotion.Tween[1];
                wait[0] = ArenaMotion.play(SETTLE_MS, ArenaMotion.LINEAR, null, () -> {
                    pending.remove(wait[0]);
                    wentOff(s);
                });
                pending.add(wait[0]);
            } else {
                releaseHits(s.item);
            }
        }
    }

    /** Forgets what has run out: the entries of the map whose time has passed. */
    private static void prune(final IntMap<Long> map, final long now) {
        if (map.size == 0) {
            return;
        }
        scratch.clear();
        for (final IntMap.Entry<Long> en : map) {
            if (en.value <= now) {
                scratch.add(en.key);
            }
        }
        for (int i = 0; i < scratch.size; i++) {
            map.remove(scratch.get(i));
        }
    }

    /** Marks what an instant or sorcery aims at (the only entries that send a bolt), until the given time. */
    private static void aim(final StackItemView item, final long until) {
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

    /** Whether this card came off the stack a moment ago (a spell that resolved or was countered): its arrival in a pile is no discard. */
    public static boolean cameOffStack(final CardView card) {
        final Long until = offStack.size == 0 ? null : offStack.get(card.getId());
        return until != null && until > nowMs();
    }

    // ---- hits that wait for their bolt -------------------------------------------------------------------------------

    /**
     * Runs {@code action} (the damage flash, the number, the jolt) when what aims at this card has hit it: while an
     * instant or sorcery is on its way to it the action waits and starts from the bolt's own finish, otherwise it runs
     * now. Render thread. Returns the wait, to be cancelled if a newer change replaces it, or null when it ran at once.
     */
    public static Hold whenHit(final CardView card, final Runnable action) {
        if (!isAimed(aimedCards, card.getId())) {
            action.run();
            return null;
        }
        return hold(cardHolds, card.getId(), action);
    }

    /** {@link #whenHit(CardView, Runnable)} for a player's life. */
    public static Hold whenHit(final PlayerView player, final Runnable action) {
        if (!isAimed(aimedPlayers, player.getId())) {
            action.run();
            return null;
        }
        return hold(playerHolds, player.getId(), action);
    }

    private static boolean isAimed(final IntMap<Long> map, final int id) {
        if (!ArenaTheme.isEffectsOn() || map.size == 0) {
            return false;
        }
        final Long until = map.get(id);
        return until != null && until > nowMs();
    }

    private static Hold hold(final IntMap<List<Hold>> holds, final int id, final Runnable action) {
        final Hold h = new Hold(action);
        List<Hold> list = holds.get(id);
        if (list == null) {
            list = new ArrayList<>();
            holds.put(id, list);
        }
        list.add(h);
        // no bolt will ever come (the entry went another way): the hit is not kept waiting for ever
        h.fallback = ArenaMotion.play(HOLD_MOST_MS, ArenaMotion.LINEAR, null, () -> fire(holds, id, h));
        return h;
    }

    private static void fire(final IntMap<List<Hold>> holds, final int id, final Hold h) {
        final List<Hold> list = holds.get(id);
        if (list != null) {
            list.remove(h);
            if (list.isEmpty()) {
                holds.remove(id);
            }
        }
        if (!h.done) {
            h.done = true;
            h.action.run();
        }
    }

    private static void release(final IntMap<List<Hold>> holds, final int id) {
        final List<Hold> list = holds.remove(id);
        if (list == null) {
            return;
        }
        for (final Hold h : list) {
            if (!h.done) {
                h.done = true;
                h.fallback.cancel();
                h.action.run();
            }
        }
    }

    /** The bolt arrived, or there was none: what the entry aimed at takes its hit now, and is no longer marked. */
    private static void releaseHits(final StackItemView item) {
        if (item == null) {
            return;
        }
        if (item.getTargetCards() != null) {
            for (final CardView c : item.getTargetCards()) {
                release(cardHolds, c.getId());
                if (!stillAimedByStack(c.getId(), true)) {
                    aimedCards.remove(c.getId());
                }
            }
        }
        if (item.getTargetPlayers() != null) {
            for (final PlayerView p : item.getTargetPlayers()) {
                release(playerHolds, p.getId());
                if (!stillAimedByStack(p.getId(), false)) {
                    aimedPlayers.remove(p.getId());
                }
            }
        }
    }

    /** Whether an entry still on the stack aims at this card or player (then the mark stays). */
    private static boolean stillAimedByStack(final int id, final boolean card) {
        for (final Shown s : shown.values()) {
            final StackItemView item = s.item;
            if (item == null || item.isAbility() || s.card == null || isPermanentSpell(s.card)) {
                continue;
            }
            if (card && item.getTargetCards() != null) {
                for (final CardView c : item.getTargetCards()) {
                    if (c.getId() == id) {
                        return true;
                    }
                }
            } else if (!card && item.getTargetPlayers() != null) {
                for (final PlayerView p : item.getTargetPlayers()) {
                    if (p.getId() == id) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The verdict on an entry that has left the stack (a moment after it did, once the engine's word has come): it
     * resolved, fizzled or was countered, and the effects and its card's flight follow from that. A countered spell
     * only shatters where it stood; its card does not also fly to the pile.
     */
    private static void wentOff(final Shown s) {
        final boolean countered = effectsOf(s);
        if (countered) {
            CardFlightOverlay.cancel(s.leaver);
        } else {
            CardFlightOverlay.release(s.leaver);
        }
        s.leaver = null;
    }

    /** Starts what goes off for the entry; true when it was countered (and shattered). Its targets' hits are released here or when the bolt lands. */
    private static boolean effectsOf(final Shown s) {
        if (!ArenaFx.on() || s.rect.width <= 0 || s.card == null) {
            releaseHits(s.item);
            return false;
        }
        final GameView game = MatchController.instance == null ? null : MatchController.instance.getGameView();
        final PlayerView controller = s.item == null ? null : s.item.getActivatingPlayer();
        if (game == null || game.isGameOver() || controller != null && controller.getHasLost()) {
            releaseHits(s.item);
            return false; // the game is over, or the player left and what they had on the stack went with them: nothing was countered
        }
        final Resolved r = resolved.remove(keyOf(s.card, s.ability));
        final boolean resolvedNow = r != null && System.nanoTime() - r.at < RESOLVED_KEEP_NANOS;
        final float cx = s.rect.x + s.rect.width / 2f, cy = s.rect.y + s.rect.height / 2f, size = Math.max(s.rect.width, ArenaTheme.px(80));
        final Color c = ArenaFx.colorOf(s.card, new Color());
        if (!resolvedNow) { // gone with no word that it resolved
            releaseHits(s.item);
            if (s.ability) {
                ArenaFx.burst(cx, cy, size * 0.45f, c); // not a spell: a small neutral burst, it is not known what became of it
                return false;
            }
            ArenaFx.shatter(cx, cy, size, s.card.getOwner()); // a spell that never resolved was countered
            return true;
        }
        if (r.fizzled || s.ability) {
            releaseHits(s.item);
            ArenaFx.burst(cx, cy, size * 0.45f, c); // it did nothing, or it is an ability: a small neutral burst, no hit
            return false;
        }
        if (isPermanentSpell(s.card)) {
            releaseHits(s.item);
            return false; // a permanent spell: where it lands on the board is the one accent
        }
        final float[] aim = targetOf(s.item);
        if (aim != null) {
            ArenaFx.burst(cx, cy, size * 0.5f, c);
            final StackItemView item = s.item;
            ArenaFx.bolt(cx, cy, aim[0], aim[1], c, size * 0.8f, () -> releaseHits(item)); // the hit starts when the bolt lands
        } else {
            releaseHits(s.item);
            ArenaFx.burst(cx, cy, size, c);
        }
        return false;
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
