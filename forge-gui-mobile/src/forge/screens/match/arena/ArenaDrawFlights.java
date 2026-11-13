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
 * Cards drawn from the library, as flights ({@link ArenaFx} draws them). Draws are read from the zone updates the match
 * screen gets ({@link #zonesUpdated}): a player whose library gave cards up while their hand took new ones <em>in the
 * same update</em> drew them (or fetched them). For the local player it is the very cards that were in the library
 * that fly (the ids of the library are kept), so a card bounced to the hand in the same update stays where it is; an
 * opponent's hand gets card backs. A mill, a bounce on its own, and a mulligan (the first turn has not begun) are no
 * draws.
 */
public final class ArenaDrawFlights {
    private ArenaDrawFlights() { }

    private static final int MOST_DRAWN_AT_ONCE = 6, MOST_BACKS_AT_ONCE = 4;

    /** What a player's zones were at the last update that touched them. */
    private static final class Seen {
        int libraryCount;
        Set<Integer> library;                 // who is in the library, or null when it cannot be seen
        final Set<Integer> hand = new HashSet<>();
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
            if (!seen.isEmpty()) {
                FThreads.invokeInEdtNowOrLater(seen::clear);
            }
            return;
        }
        final List<PlayerZoneUpdate> list = new ArrayList<>();
        for (final PlayerZoneUpdate u : updates) {
            list.add(u);
        }
        FThreads.invokeInEdtNowOrLater(() -> {
            for (final PlayerZoneUpdate u : list) {
                zonesUpdated(u.getPlayer(), u.getZones().contains(ZoneType.Library), u.getZones().contains(ZoneType.Hand));
            }
        });
    }

    private static Set<Integer> idsOf(final PlayerView player, final ZoneType zone, final int expected) {
        final FCollectionView<CardView> cards = player.getCards(zone);
        if (cards == null || cards.size() != expected) {
            return null;
        }
        final Set<Integer> ids = new HashSet<>();
        for (final CardView c : cards) {
            ids.add(c.getId());
        }
        return ids;
    }

    private static void zonesUpdated(final PlayerView player, final boolean libraryChanged, final boolean handChanged) {
        if (!libraryChanged && !handChanged) {
            return;
        }
        final int libraryNow = player.getZoneSize(ZoneType.Library), handNow = player.getZoneSize(ZoneType.Hand);
        Seen s = seen.get(player);
        if (s == null) { // the first look only learns what is there
            s = new Seen();
            seen.put(player, s);
            s.libraryCount = libraryNow;
            s.library = idsOf(player, ZoneType.Library, libraryNow);
            final Set<Integer> hand = idsOf(player, ZoneType.Hand, handNow);
            if (hand != null) {
                s.hand.addAll(hand);
            }
            return;
        }
        final boolean bottom = isBottom(player);
        // who is in the hand now, and who arrived since the last look
        final List<CardView> arrived = new ArrayList<>();
        final Set<Integer> hand = new HashSet<>();
        if (handChanged) {
            final FCollectionView<CardView> cards = player.getCards(ZoneType.Hand);
            if (cards != null) {
                for (final CardView c : cards) {
                    hand.add(c.getId());
                    if (!s.hand.contains(c.getId())) {
                        arrived.add(c);
                    }
                }
            }
        }
        final int arrivedCount = bottom ? arrived.size() : Math.max(0, handNow - s.hand.size());
        final int gone = s.libraryCount - libraryNow;
        final Set<Integer> libraryBefore = s.library;
        if (libraryChanged) {
            s.libraryCount = libraryNow;
            s.library = idsOf(player, ZoneType.Library, libraryNow);
        }
        if (handChanged) {
            s.hand.clear();
            s.hand.addAll(hand);
        }
        // the library count means something only when the hand moved in the same update
        if (!libraryChanged || !handChanged || gone <= 0 || arrivedCount <= 0 || !ArenaFx.on() || ArenaFx.quiet()) {
            return;
        }
        final VPlayerPanel panel = MatchScreen.getPlayerPanel(player);
        if (panel == null) {
            return;
        }
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
    }

    private static boolean isBottom(final PlayerView player) {
        return MatchController.getView() != null && MatchController.getView().getBottomPlayerPanel() != null
                && MatchController.getView().getBottomPlayerPanel().getPlayer() == player;
    }

    /**
     * The cards you drew fly in one after another, from the library pile's own size to the size of a card in the hand
     * (the card size slider included); until the fan lays them out they aim at its middle.
     */
    private static void sendCards(final VPlayerPanel panel, final List<CardView> cards, final int n) {
        final Rectangle lib = libraryOf(panel);
        if (lib == null) {
            return;
        }
        final float ch = ArenaFx.guessHandCardHeight(), cw = ArenaFx.cardWidthFor(ch);
        for (int i = 0; i < n; i++) {
            final Rectangle start = new Rectangle(lib.x, lib.y, lib.width, lib.height);
            final Rectangle aim = new Rectangle(Forge.getScreenWidth() / 2f - cw / 2f, Forge.getScreenHeight() - ch, cw, ch);
            ArenaFx.fly(ArenaFx.CAT_DRAW, cards.get(i).getId(), cards.get(i), panel.getPlayer(), start, aim, true, ArenaFx.DRAW_MS, i * ArenaMotion.STAGGER_MS * 2);
        }
    }

    private static int backIds = -1;

    private static void sendBacks(final PlayerView player, final VPlayerPanel panel, final int n) {
        final Rectangle to = panel.getArenaOpponentHandRect();
        final Rectangle from = libraryOf(panel);
        if (to == null || from == null) {
            return;
        }
        final float ch = Math.max(ArenaTheme.px(40), to.height * 0.9f), cw = ArenaFx.cardWidthFor(ch);
        final Rectangle target = new Rectangle(to.x + to.width / 2f - cw / 2f, to.y + to.height - ch, cw, ch);
        for (int i = 0; i < n; i++) {
            final Rectangle start = new Rectangle(from.x, from.y, from.width, from.height);
            ArenaFx.fly(ArenaFx.CAT_BACK, backIds--, null, player, start, target, false, ArenaFx.BACK_MS, i * ArenaMotion.STAGGER_MS);
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
     * the air this draws a {@link ArenaFx#ghost ghost} in the card's slot (so the card is still there to point at) and
     * says true, and the flight follows the slot and lands in its lean. In the last part of the flight it says false:
     * the fan draws the real card again and the flight fades out above it.
     *
     * @param slot the hand card's panel on the screen (the card sits at its top, {@code cardHeight} tall)
     * @param lean the fan's lean for the card (degrees, clockwise)
     */
    public static boolean handCardInFlight(final Graphics g, final CardView card, final Rectangle slot, final float cardHeight, final float lean) {
        if (ArenaFx.drawFlights.isEmpty()) {
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
        ArenaFx.ghost(g, 0, 0, slot.width, cardHeight, null, 0.6f);
        return true;
    }

    /** The player touched the card: if it is still on its way, it is in its slot now, so a touch never acts on a card nobody can see. */
    public static void finish(final CardView card) {
        if (!ArenaFx.drawFlights.isEmpty()) {
            ArenaFx.finish(ArenaFx.drawFlights.get(card.getId()));
        }
    }
}
