package forge.screens.match.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Rectangle;

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
 * <li><b>Mills</b>: library cards that arrive in the graveyard fly there from the library pile.</li>
 * <li><b>Discards</b>: hand cards that arrive in the graveyard fly there from the hand.</li>
 * </ul>
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
                        sendCards(panel, cardsOf(player, ZoneType.Hand), Math.min(handNow, MOST_DEALT));
                    } else {
                        sendBacks(player, panel, Math.min(handNow, MOST_DEALT));
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
        if (libraryChanged && handChanged && gone > 0 && arrivedCount > 0) { // the library count means something only when the hand moved in the same update
            if (bottom) {
                // only the cards that really were in the library fly (when the library cannot be seen: the first ones that arrived)
                final List<CardView> drawn = new ArrayList<>();
                for (final CardView c : arrived) {
                    if (libraryBefore == null || libraryBefore.contains(c.getId())) {
                        drawn.add(c);
                    }
                }
                sendCards(panel, drawn, Math.min(Math.min(gone, drawn.size()), MOST_DRAWN_AT_ONCE));
            } else {
                sendBacks(player, panel, Math.min(Math.min(gone, arrivedCount), MOST_BACKS_AT_ONCE));
            }
        } else if (libraryChanged && graveyardChanged && !handChanged && gone > 0 && !buried.isEmpty()) { // a mill
            moveFaces(panel, buried, Math.min(Math.min(gone, buried.size()), MOST_MOVED_AT_ONCE), libraryOf(panel), panel.getArenaPileRect(ZoneType.Graveyard));
        } else if (handChanged && graveyardChanged && !libraryChanged && !buried.isEmpty()) { // a discard
            final List<CardView> discarded = new ArrayList<>();
            for (final CardView c : buried) {
                if (!bottom || handBeforeIds.contains(c.getId())) {
                    discarded.add(c);
                }
            }
            final int lost = Math.max(0, handBefore - handNow + arrivedCount);
            final Rectangle from = bottom ? handCenter() : panel.getArenaOpponentHandRect();
            moveFaces(panel, discarded, Math.min(Math.min(lost, discarded.size()), MOST_MOVED_AT_ONCE), from, panel.getArenaPileRect(ZoneType.Graveyard));
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
     */
    private static void sendCards(final VPlayerPanel panel, final List<CardView> cards, final int n) {
        final Rectangle lib = libraryOf(panel);
        if (lib == null) {
            return;
        }
        final Rectangle aim = handCenter();
        for (int i = 0; i < n; i++) {
            ArenaFx.fly(ArenaFx.CAT_DRAW, cards.get(i).getId(), cards.get(i), panel.getPlayer(), cardShaped(lib), aim, true, ArenaFx.DRAW_MS,
                    i * ArenaMotion.STAGGER_MS * 2);
        }
    }

    private static Rectangle cardShaped(final Rectangle pile) {
        final float w = ArenaFx.cardWidthFor(pile.height);
        return new Rectangle(pile.x + (pile.width - w) / 2f, pile.y, w, pile.height);
    }

    private static int moveIds = -1;

    private static void sendBacks(final PlayerView player, final VPlayerPanel panel, final int n) {
        final Rectangle to = panel.getArenaOpponentHandRect();
        final Rectangle from = libraryOf(panel);
        if (to == null || from == null) {
            return;
        }
        final float ch = Math.max(ArenaTheme.px(40), to.height * 0.9f), cw = ArenaFx.cardWidthFor(ch);
        final Rectangle target = new Rectangle(to.x + to.width / 2f - cw / 2f, to.y + to.height - ch, cw, ch);
        for (int i = 0; i < n; i++) {
            ArenaFx.fly(ArenaFx.CAT_BACK, moveIds--, null, player, cardShaped(from), target, false, ArenaFx.BACK_MS, i * ArenaMotion.STAGGER_MS);
        }
    }

    /** Cards (face up: the graveyard is open) flying from one place of the board to a pile; the pile draws them itself afterwards. */
    private static void moveFaces(final VPlayerPanel panel, final List<CardView> cards, final int n, final Rectangle from, final Rectangle pile) {
        if (from == null || pile == null || pile.width <= 0) {
            return;
        }
        final Rectangle to = cardShaped(pile);
        for (int i = 0; i < n; i++) {
            ArenaFx.fly(ArenaFx.CAT_MOVE, moveIds--, cards.get(i), panel.getPlayer(), cardShaped(from), to, false, ArenaFx.BACK_MS, i * ArenaMotion.STAGGER_MS);
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
     * the air this draws a {@link ArenaFx#ghost ghost} of the card in its slot (so the card is still there to point at)
     * and says true, and the flight follows the slot and lands in its lean. In the last part of the flight it says
     * false: the fan draws the real card again and the flight fades out above it. Render thread.
     *
     * @param slot the hand card's panel on the screen (the card sits at its top, {@code cardHeight} tall)
     * @param lean the fan's lean for the card (degrees, clockwise)
     */
    public static boolean handCardInFlight(final Graphics g, final CardView card, final Rectangle slot, final float cardHeight, final float lean) {
        if (ArenaFx.drawFlights.size == 0) {
            return false;
        }
        final ArenaFx.Fx e = ArenaFx.drawFlights.get(card.getId());
        if (e == null) {
            return false;
        }
        e.to.set(slot.x, slot.y, slot.width, cardHeight);
        e.lean = lean;
        if (!e.hidesTarget()) {
            return false;
        }
        ArenaFx.ghost(g, 0, 0, slot.width, cardHeight, null, 0.6f, card);
        return true;
    }

    /**
     * A touch on a card that is still flying in only ends the flight (so the card shows in its slot) and is used up:
     * it does not also play, cast or pick the card, which the player could not see. Render thread; true when it did that.
     */
    public static boolean reveal(final CardView card) {
        if (ArenaFx.drawFlights.size == 0) {
            return false;
        }
        final ArenaFx.Fx e = ArenaFx.drawFlights.get(card.getId());
        if (e == null || !e.hidesTarget()) {
            return false;
        }
        ArenaFx.finish(e);
        return true;
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
