package forge.screens.match.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.IntMap;

import forge.Forge;
import forge.Graphics;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gui.FThreads;
import forge.player.PlayerZoneUpdate;
import forge.screens.match.MatchController;
import forge.screens.match.MatchScreen;
import forge.screens.match.views.VPlayerPanel;
import forge.screens.match.views.VCardDisplayArea;
import forge.util.collect.FCollectionView;

/**
 * Cards moving between the library, the hand and the graveyard, as flights ({@link ArenaFx} draws them). They are read
 * from the zone updates the match screen gets ({@link #zonesUpdated}):
 * <ul>
 * <li><b>Draws</b>: a player whose library gave cards up while their hand took new ones <em>in the same update</em>
 *     drew (or fetched) them. For the local player it is the very cards that were in the library that fly (the ids of
 *     the library are kept), so a card bounced to the hand in the same update stays where it is; an opponent's hand gets
 *     card backs. The opening hand is dealt the first time a player's zones are seen before the first turn; a mulligan
 *     is no draw.</li>
 * <li><b>Mills</b>: library cards that arrive in the graveyard fly there from the library pile, face down, turning over
 *     on the way.</li>
 * <li><b>Discards</b>: hand cards that arrive in the graveyard fly there from their slot in the hand. A card that
 *     arrives there off the stack (a spell that resolved) is no discard.</li>
 * </ul>
 * <p>A drawn card's slot in the hand stays an empty lit slot (leaning with the fan, wearing the castable glow) while
 * the card is in the air, and the card the flight lands in has the flight's lean already. A touch on a flying card
 * ends it; a touch during the opening deal ends the whole deal. A flight whose card goes elsewhere (played, discarded)
 * in the meantime carries on to where the card went, or fades out where it is.</p>
 */
public final class ArenaDrawFlights {
    private ArenaDrawFlights() { }

    /** Most cards flying at once for one update: your draws, their backs, a mill or a discard, an opening hand. */
    private static final int MOST_DRAWN_AT_ONCE = 6, MOST_BACKS_AT_ONCE = 4, MOST_MOVED_AT_ONCE = 4, MOST_DEALT = 7;

    /** What a player's zones were at the last update that touched them. */
    private static final class Seen {
        int libraryCount;
        Set<Integer> library;                 // who is in the library, or null when it cannot be seen
        final Set<Integer> hand = new HashSet<>();
        final Set<Integer> graveyard = new HashSet<>();
    }

    private static final Map<PlayerView, Seen> seen = new HashMap<>();

    static void reset() {
        seen.clear();
    }

    /**
     * Told of every zone update the match screen gets. Any thread. Does nothing while the effects are off (and forgets
     * what it knew, so that nothing counts as a draw when they come back).
     */
    public static void zonesUpdated(final Iterable<PlayerZoneUpdate> updates) {
        if (!ArenaTheme.isEffectsOn()) {
            FThreads.invokeInEdtNowOrLater(seen::clear);
            return;
        }
        final List<PlayerZoneUpdate> list = new ArrayList<>();
        for (final PlayerZoneUpdate u : updates) {
            list.add(u);
        }
        FThreads.invokeInEdtNowOrLater(() -> {
            for (final PlayerZoneUpdate u : list) {
                final Set<ZoneType> zones = u.getZones();
                zonesUpdated(u.getPlayer(), zones.contains(ZoneType.Library), zones.contains(ZoneType.Hand), zones.contains(ZoneType.Graveyard));
            }
        });
    }

    private static Set<Integer> idsOf(final PlayerView player, final ZoneType zone) {
        final FCollectionView<CardView> cards = player.getCards(zone);
        if (cards == null || cards.size() != player.getZoneSize(zone)) {
            return null;
        }
        final Set<Integer> ids = new HashSet<>();
        for (final CardView c : cards) {
            ids.add(c.getId());
        }
        return ids;
    }

    private static List<CardView> cardsOf(final PlayerView player, final ZoneType zone) {
        final List<CardView> list = new ArrayList<>();
        final FCollectionView<CardView> cards = player.getCards(zone);
        if (cards != null) {
            for (final CardView c : cards) {
                list.add(c);
            }
        }
        return list;
    }

    private static void zonesUpdated(final PlayerView player, final boolean libraryChanged, final boolean handChanged, final boolean graveyardChanged) {
        if (!libraryChanged && !handChanged && !graveyardChanged) {
            return;
        }
        final int libraryNow = player.getZoneSize(ZoneType.Library), handNow = player.getZoneSize(ZoneType.Hand);
        final boolean bottom = isBottom(player);
        Seen s = seen.get(player);
        if (s == null) { // the first look learns what is there: before the first turn that is the opening hand, dealt
            s = new Seen();
            seen.put(player, s);
            s.libraryCount = libraryNow;
            s.library = idsOf(player, ZoneType.Library);
            final Set<Integer> hand = idsOf(player, ZoneType.Hand), gy = idsOf(player, ZoneType.Graveyard);
            if (hand != null) {
                s.hand.addAll(hand);
            }
            if (gy != null) {
                s.graveyard.addAll(gy);
            }
            if (handNow > 0 && ArenaFx.on() && ArenaFx.beforeFirstTurn()) {
                final VPlayerPanel panel = MatchScreen.getPlayerPanel(player);
                if (panel != null) {
                    if (bottom) {
                        sendCards(panel, cardsOf(player, ZoneType.Hand), Math.min(handNow, MOST_DEALT), true);
                    } else {
                        sendBacks(player, panel, Math.min(handNow, MOST_DEALT), true);
                    }
                }
            }
            return;
        }
        // who is in the hand and the graveyard now, and who arrived (or left) since the last look
        final List<CardView> arrived = new ArrayList<>();
        final Set<Integer> hand = new HashSet<>();
        final int handBefore = s.hand.size();
        final Set<Integer> handBeforeIds = new HashSet<>(s.hand);
        if (handChanged) {
            for (final CardView c : cardsOf(player, ZoneType.Hand)) {
                hand.add(c.getId());
                if (!s.hand.contains(c.getId())) {
                    arrived.add(c);
                }
            }
        }
        final List<CardView> buried = new ArrayList<>();
        final Set<Integer> graveyard = new HashSet<>();
        if (graveyardChanged) {
            for (final CardView c : cardsOf(player, ZoneType.Graveyard)) {
                graveyard.add(c.getId());
                if (!s.graveyard.contains(c.getId())) {
                    buried.add(c);
                }
            }
        }
        final int arrivedCount = bottom ? arrived.size() : Math.max(0, handNow - handBefore);
        final int gone = s.libraryCount - libraryNow;
        final Set<Integer> libraryBefore = s.library;
        if (libraryChanged) {
            s.libraryCount = libraryNow;
            s.library = idsOf(player, ZoneType.Library);
        }
        if (handChanged) {
            s.hand.clear();
            s.hand.addAll(hand);
        }
        if (graveyardChanged) {
            s.graveyard.clear();
            s.graveyard.addAll(graveyard);
        }
        if (!ArenaFx.on() || ArenaFx.quiet()) {
            return;
        }
        final VPlayerPanel panel = MatchScreen.getPlayerPanel(player);
        if (panel == null) {
            return;
        }
        if (handChanged) {
            followCardsThatLeft(player, panel, hand, graveyard);
        }
        if (libraryChanged && handChanged && gone > 0 && arrivedCount > 0) { // the library count means something only when the hand moved in the same update
            if (bottom) {
                // only the cards that really were in the library fly (when the library cannot be seen: the first ones that arrived)
                final List<CardView> drawn = new ArrayList<>();
                for (final CardView c : arrived) {
                    if (libraryBefore == null || libraryBefore.contains(c.getId())) {
                        drawn.add(c);
                    }
                }
                sendCards(panel, drawn, Math.min(Math.min(gone, drawn.size()), MOST_DRAWN_AT_ONCE), false);
            } else {
                sendBacks(player, panel, Math.min(Math.min(gone, arrivedCount), MOST_BACKS_AT_ONCE), false);
            }
        } else if (libraryChanged && graveyardChanged && !handChanged && gone > 0 && !buried.isEmpty()) { // a mill
            moveFaces(panel, buried, Math.min(Math.min(gone, buried.size()), MOST_MOVED_AT_ONCE), libraryOf(panel), ZoneType.Graveyard, false, true);
        } else if (handChanged && graveyardChanged && !libraryChanged && !buried.isEmpty()) { // a discard
            final List<CardView> discarded = new ArrayList<>();
            for (final CardView c : buried) {
                if (ArenaStackFx.cameOffStack(c)) {
                    continue; // a spell that resolved or was countered goes to the graveyard off the stack, not out of the hand
                }
                if (!bottom || handBeforeIds.contains(c.getId())) {
                    discarded.add(c);
                }
            }
            final int lost = Math.max(0, handBefore - handNow + arrivedCount);
            final Rectangle from = bottom ? handCenter() : panel.getArenaOpponentHandRect();
            moveFaces(panel, discarded, Math.min(Math.min(lost, discarded.size()), MOST_MOVED_AT_ONCE), from, ZoneType.Graveyard, bottom, false);
        }
    }

    private static boolean isBottom(final PlayerView player) {
        return MatchController.getView() != null && MatchController.getView().getBottomPlayerPanel() != null
                && MatchController.getView().getBottomPlayerPanel().getPlayer() == player;
    }

    private static Rectangle handCenter() {
        final float ch = ArenaFx.guessHandCardHeight(), cw = ArenaFx.cardWidthFor(ch);
        return new Rectangle(Forge.getScreenWidth() / 2f - cw / 2f, Forge.getScreenHeight() - ch, cw, ch);
    }

    /**
     * The cards you drew fly in one after another, from a card-shaped start at the library pile's height to the size of
     * a card in the hand (the card size slider included); until the fan lays them out they aim at its middle.
     *
     * @param deal the opening deal: a touch on one of these ends them all
     */
    private static void sendCards(final VPlayerPanel panel, final List<CardView> cards, final int n, final boolean deal) {
        final Rectangle lib = libraryOf(panel);
        if (lib == null) {
            return;
        }
        final Rectangle aim = handCenter();
        for (int i = 0, count = Math.min(n, cards.size()); i < count; i++) { // never more flights than there are cards to show
            final ArenaFx.Fx e = ArenaFx.fly(ArenaFx.CAT_DRAW, cards.get(i).getId(), cards.get(i), panel.getPlayer(), cardShaped(lib), aim, true, ArenaFx.DRAW_MS,
                    i * ArenaMotion.STAGGER_MS * 2);
            if (e != null) {
                e.deal = deal;
            }
        }
    }

    private static Rectangle cardShaped(final Rectangle pile) {
        final float w = ArenaFx.cardWidthFor(pile.height);
        return new Rectangle(pile.x + (pile.width - w) / 2f, pile.y, w, pile.height);
    }

    private static int moveIds = -1;

    private static void sendBacks(final PlayerView player, final VPlayerPanel panel, final int n, final boolean deal) {
        final Rectangle to = panel.getArenaOpponentHandRect();
        final Rectangle from = libraryOf(panel);
        if (to == null || from == null) {
            return;
        }
        final float ch = Math.max(ArenaTheme.px(40), to.height * 0.9f), cw = ArenaFx.cardWidthFor(ch);
        final Rectangle target = new Rectangle(to.x + to.width / 2f - cw / 2f, to.y + to.height - ch, cw, ch);
        for (int i = 0; i < n; i++) {
            final ArenaFx.Fx e = ArenaFx.fly(ArenaFx.CAT_BACK, moveIds--, null, player, cardShaped(from), target, false, ArenaFx.BACK_MS, i * ArenaMotion.STAGGER_MS);
            if (e != null) {
                e.deal = deal;
                e.destOwner = player; // the hand's count and backs wait for it
                e.destZone = ZoneType.Hand;
            }
        }
    }

    /**
     * Cards (face up: the graveyard is open) flying from one place of the board to a pile; the pile draws them itself
     * afterwards, and holds them back until they land.
     *
     * @param fromSlots each card starts from its own slot in the hand (the one it was drawn in), the default start where it has none
     * @param flip      the cards fly face down and turn over on the way (a mill: they come off the library)
     */
    private static void moveFaces(final VPlayerPanel panel, final List<CardView> cards, final int n, final Rectangle from, final ZoneType zone,
            final boolean fromSlots, final boolean flip) {
        final Rectangle pile = panel.getArenaPileRect(zone);
        if (from == null || pile == null || pile.width <= 0) {
            return;
        }
        final Rectangle to = cardShaped(pile);
        for (int i = 0, count = Math.min(n, cards.size()); i < count; i++) {
            final CardView card = cards.get(i);
            final Rectangle start = fromSlots ? slotOf(card, from) : from;
            final ArenaFx.Fx e = ArenaFx.fly(ArenaFx.CAT_MOVE, moveIds--, card, panel.getPlayer(), cardShaped(start), to, flip, ArenaFx.BACK_MS, i * ArenaMotion.STAGGER_MS);
            if (e != null) {
                e.destOwner = panel.getPlayer();
                e.destZone = zone;
            }
        }
    }

    /** Where a card of the hand was: its slot in the fan as it was last drawn (card-sized), or {@code fallback} when it was never laid out. */
    private static Rectangle slotOf(final CardView card, final Rectangle fallback) {
        final VCardDisplayArea.CardAreaPanel panel = VCardDisplayArea.CardAreaPanel.peek(card);
        if (panel != null && panel.screenPos.width > 0) {
            final float shown = panel.getArenaState().cardHeight;
            return new Rectangle(panel.screenPos.x, panel.screenPos.y, panel.screenPos.width, shown > 0 ? shown : ArenaFx.guessHandCardHeight());
        }
        return fallback;
    }

    /**
     * A card that was flying into the hand is not in it any more (it was played, discarded, bounced): its flight carries
     * on to the graveyard when that is where the card went, and otherwise fades out where it is.
     */
    private static void followCardsThatLeft(final PlayerView player, final VPlayerPanel panel, final Set<Integer> hand, final Set<Integer> graveyard) {
        if (ArenaFx.drawFlights.size == 0) {
            return;
        }
        final IntArray ids = new IntArray();
        for (final IntMap.Keys keys = ArenaFx.drawFlights.keys(); keys.hasNext;) {
            ids.add(keys.next());
        }
        for (int i = 0; i < ids.size; i++) {
            final int id = ids.get(i);
            final ArenaFx.Fx e = ArenaFx.drawFlights.get(id);
            if (e == null || e.owner != player || hand.contains(id)) {
                continue;
            }
            final Rectangle pile = panel.getArenaPileRect(ZoneType.Graveyard);
            if (graveyard.contains(id) && pile != null && pile.width > 0) {
                ArenaFx.drawFlights.remove(id); // it is no hand card's flight now: its slot is gone
                e.cat = ArenaFx.CAT_MOVE;
                e.id = moveIds--;
                e.destOwner = player;
                e.destZone = ZoneType.Graveyard;
                e.lean = 0f;
                e.followFrame = 0L;
                e.flip = false;
                ArenaFx.retarget(e, cardShaped(pile));
            } else {
                ArenaFx.fadeOutInPlace(e);
            }
        }
    }

    private static Rectangle libraryOf(final VPlayerPanel panel) {
        final Rectangle pile = panel.getArenaPileRect(ZoneType.Library);
        if (pile != null && pile.width > 0) {
            return pile;
        }
        return panel.getAvatar() != null && panel.getAvatar().screenPos.width > 0 ? panel.getAvatar().screenPos : null;
    }

    /**
     * The local hand fan calls this for each card it draws. While the card's draw flight is waiting for its turn or in
     * the air this draws an empty lit slot in its place (leaning with the fan, wearing the glow the card would: a card
     * you could cast is still marked) and says true; the flight follows the slot and lands in its lean. In the last
     * part of the flight it says false: the fan draws the real card again, already leaning as the flight does, and the
     * flight fades out above it. Render thread.
     *
     * @param slot the hand card's panel on the screen (the card sits at its top, {@code cardHeight} tall)
     * @param lean the fan's lean for the card (degrees, clockwise)
     */
    public static boolean handCardInFlight(final Graphics g, final CardView card, final ArenaCards.State st, final Rectangle slot, final float cardHeight,
            final float lean) {
        if (ArenaFx.drawFlights.size == 0) {
            return false;
        }
        final ArenaFx.Fx e = ArenaFx.drawFlights.get(card.getId());
        if (e == null) {
            return false;
        }
        final float scx = slot.x + slot.width / 2f, scy = slot.y + cardHeight / 2f;
        final float jump = (float) Math.hypot(scx - (e.to.x + e.to.width / 2f), scy - (e.to.y + e.to.height / 2f));
        if (jump > cardHeight * 0.6f && e.hidesTarget()) {
            ArenaFx.retarget(e, new Rectangle(slot.x, slot.y, slot.width, cardHeight)); // the slot moved a long way (a new fan, a resize): carry on from where it is
        } else {
            e.to.set(slot.x, slot.y, slot.width, cardHeight);
        }
        e.lean = lean;
        ArenaFx.followed(e);
        if (!e.hidesTarget()) {
            if (!e.handed) {
                e.handed = true;
                st.lean.set(lean); // the card the fan draws now leans as the flight does: they must not be two cards a few degrees apart
            }
            return false;
        }
        ArenaCards.drawHandSlot(g, card, st, 0, 0, slot.width, cardHeight);
        return true;
    }

    private static long lastRevealNanos;

    /**
     * A touch on a card that is still flying in only ends the flight (so the card shows in its slot) and is used up:
     * it does not also play, cast or pick the card, which the player could not see. During the opening deal it ends the
     * whole deal. Render thread; true when it did that.
     */
    public static boolean reveal(final CardView card) {
        if (ArenaFx.drawFlights.size == 0) {
            return false;
        }
        final ArenaFx.Fx e = ArenaFx.drawFlights.get(card.getId());
        if (e == null || !e.hidesTarget()) {
            return false;
        }
        lastRevealNanos = System.nanoTime();
        if (e.deal) {
            for (int i = ArenaFx.fx.size() - 1; i >= 0; i--) {
                final ArenaFx.Fx other = ArenaFx.fx.get(i);
                if (other.kind == ArenaFx.FLIGHT && other.deal) {
                    ArenaFx.finish(other);
                }
            }
        } else {
            ArenaFx.finish(e);
        }
        return true;
    }

    /**
     * Whether a touch that ended a flight happened a moment ago. The next quick touch then must not count as the
     * second of a double tap (which the cards ignore): the first was used up showing the card.
     */
    public static boolean justRevealed() {
        return System.nanoTime() - lastRevealNanos < 700_000_000L;
    }

    /** Ends the card's flight, if any, from any thread (the effect list belongs to the render thread: this is posted to it). */
    public static void finish(final CardView card) {
        FThreads.invokeInEdtNowOrLater(() -> {
            if (ArenaFx.drawFlights.size > 0) {
                ArenaFx.finish(ArenaFx.drawFlights.get(card.getId()));
            }
        });
    }
}
