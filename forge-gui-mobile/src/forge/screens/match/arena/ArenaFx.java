package forge.screens.match.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;

import forge.Forge;
import forge.Graphics;
import forge.card.CardRenderer;
import forge.card.CardRenderer.CardStackPosition;
import forge.card.ColorSet;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gui.FThreads;
import forge.player.PlayerZoneUpdate;
import forge.screens.match.MatchController;
import forge.screens.match.MatchScreen;
import forge.screens.match.views.VCardDisplayArea;
import forge.screens.match.views.VPlayerPanel;
import forge.toolbox.FCardPanel;
import forge.util.collect.FCollectionView;

/**
 * The effect stack of the Arena board: everything that exists only for a moment and is drawn over the board, in one
 * pass after the cards and under the prompt bar (port of the idea of the Swing {@code ArenaFx}, grown for the GPU).
 * Effects are queued from anywhere in the match code, each runs off an {@link ArenaMotion} tween, and the stack costs
 * nothing while empty. Everything here is off with the Arena effects setting, which is the reduce-motion switch.
 *
 * <ul>
 * <li><b>Draw flights</b>: a card drawn into the hand flies from the library pile to its slot in the fan (turning from
 *     its back to its face, one after another when several are drawn); an opponent's draws send card backs from their
 *     library to their hand. Draws are read from the zone updates the match screen receives ({@link #zonesUpdated}):
 *     the library gave cards up and the hand took new ones in the same update. A mill or a bounce is no draw.</li>
 * <li><b>Stack flights</b>: a spell or ability flies onto the stack's large card from where it was cast or activated.
 *     When the entry resolves its colour goes off as a bolt that runs to what it aims at (sparks where it stood if
 *     it aims at nothing); a permanent spell that ends anywhere but the battlefield was countered and shatters.</li>
 * <li><b>Rings</b>: one landing accent per event, where a spell lands on the stack or a permanent on the board.</li>
 * </ul>
 *
 * <p>Draw flights and stack flights live in separate maps (a stack item's id and a card's id are different numbers
 * that can be equal). While something flies, the view that will show it draws {@link #ghost a ghost} in its slot, so a
 * card or a target never vanishes from under the pointer. The opening hand, the first seconds of a game and anything
 * past {@link #MAX_EFFECTS} just appear, so a wipe or a loaded game never floods the screen.</p>
 */
public final class ArenaFx {
    private ArenaFx() { }

    /** The most effects alive at once, and flights among them. A board wipe or a big draw stays readable. */
    public static final int MAX_EFFECTS = 40, MAX_FLIGHTS = 12;
    /** Flights' lengths: a drawn card (a clear arc), an opponent's card back, a spell onto the stack; rings, bursts and bolts. */
    private static final int DRAW_MS = 600, BACK_MS = 520, STACK_MS = 460, RING_MS = 520, BURST_MS = 640, BOLT_MS = 420, COUNTER_MS = 620;
    /** The opening hand and the first moments of a game are not animated. */
    private static final float QUIET_SECONDS = 2.5f;
    private static final int MOST_DRAWN_AT_ONCE = 6, MOST_BACKS_AT_ONCE = 4;
    private static final int SPARKS = 9;

    private static final Color S1 = new Color(), S2 = new Color(), S3 = new Color();
    private static final Color WARM = ArenaTheme.rgb(0xFFE3A8);
    private static final Color COUNTER = ArenaTheme.rgb(0xFF4D5E);

    private static final int RING = 0, BURST = 1, FLIGHT = 2, BOLT = 3, SHATTER = 4;
    private static final int CAT_DRAW = 0, CAT_BACK = 1, CAT_STACK = 2;

    private static final class Fx {
        int kind;
        float t;                // 0..1 plain progress (the tween is linear: drawing eases it once)
        float x, y, size;       // a ring or burst: where, how large it grows; a bolt: where it starts
        float x2, y2;           // a bolt: where it goes
        Color color = new Color();
        ArenaMotion.Tween tween;
        // flights
        int cat, id;            // which channel and what it stands for (a card's id, a stack entry's id)
        CardView card;          // the face it turns up (null: a back all the way)
        PlayerView owner;       // whose sleeve the back wears
        final Rectangle from = new Rectangle(), to = new Rectangle();
        boolean flip;           // back first, face second
        float lean;             // the lean of the slot it flies into (degrees), settled into as it lands
        float lift = 0.26f;     // how high the arc rises, as a share of the card's height
        float seed;
    }

    private static final List<Fx> fx = new ArrayList<>();
    private static final Map<Integer, Fx> drawFlights = new HashMap<>();
    private static final Map<Integer, Fx> stackFlights = new HashMap<>();
    private static float gameStart = ArenaMotion.seconds();

    /** What a player's hand was at the last zone update: who was in it. */
    private static final class Seen {
        int library;
        final Set<Integer> hand = new HashSet<>();
    }

    private static final Map<PlayerView, Seen> seen = new HashMap<>();

    /** An entry of the stack as it was last drawn: for what happens when it is gone. */
    private static final class Shown {
        StackItemView item;
        CardView card;
        final Rectangle rect = new Rectangle();
    }

    private static final Map<Integer, Shown> stackShown = new HashMap<>();
    private static final Set<Integer> stackSeen = new HashSet<>();
    private static final Rectangle SCRATCH = new Rectangle();
    private static int backIds = -1;

    // ---- state -------------------------------------------------------------------------------------------------------

    /** Forgets everything (a new game, an undo, leaving the match). Any thread. */
    public static void clear() {
        FThreads.invokeInEdtNowOrLater(ArenaFx::reset);
    }

    private static void reset() {
        for (final Fx e : fx) {
            if (e.tween != null) {
                e.tween.cancel();
            }
        }
        fx.clear();
        drawFlights.clear();
        stackFlights.clear();
        stackShown.clear();
        stackSeen.clear();
        seen.clear();
        gameStart = ArenaMotion.seconds();
    }

    /** Whether anything is on its way now. */
    public static boolean isShowing() {
        return !fx.isEmpty();
    }

    private static boolean on() {
        return ArenaTheme.isEffectsOn() && ArenaTheme.isMatchBoard();
    }

    private static boolean quiet() {
        return ArenaMotion.seconds() - gameStart < QUIET_SECONDS;
    }

    // ---- rings, bursts, bolts ----------------------------------------------------------------------------------------

    /** The ripple of light where something landed: a ring that spreads and thins, with a soft glow under it. */
    public static void ripple(final float x, final float y, final float size, final Color color) {
        timed(RING, x, y, size, color, RING_MS);
    }

    /** Sparks and streaks: something resolved or went off here, in this colour. */
    public static void burst(final float x, final float y, final float size, final Color color) {
        timed(BURST, x, y, size, color, BURST_MS);
    }

    private static Fx timed(final int kind, final float x, final float y, final float size, final Color color, final int millis) {
        if (!on() || fx.size() >= MAX_EFFECTS) {
            return null;
        }
        final Fx e = new Fx();
        e.kind = kind;
        e.x = x;
        e.y = y;
        e.size = size;
        e.seed = (x * 0.37f + y * 0.71f) % 6.2831f;
        e.color.set(color);
        fx.add(e);
        e.tween = ArenaMotion.play(millis, ArenaMotion.LINEAR, t -> e.t = t, () -> fx.remove(e));
        return e;
    }

    /** A bolt of this colour from where something resolved to what it aims at; it bursts there when it arrives. */
    private static void bolt(final float x, final float y, final float tx, final float ty, final Color color, final float size) {
        if (!on() || fx.size() >= MAX_EFFECTS) {
            return;
        }
        final Fx e = new Fx();
        e.kind = BOLT;
        e.x = x;
        e.y = y;
        e.x2 = tx;
        e.y2 = ty;
        e.size = size;
        e.color.set(color);
        fx.add(e);
        e.tween = ArenaMotion.play(BOLT_MS, ArenaMotion.LINEAR, t -> e.t = t, () -> {
            fx.remove(e);
            burst(tx, ty, size, e.color);
        });
    }

    /** The colour a card's effects take: its frame colour (gold for colourless and artifacts). */
    public static Color colorOf(final CardView card, final Color out) {
        if (card == null) {
            return out.set(WARM);
        }
        final CardStateView state = card.getCurrentState();
        final ColorSet colors = state.getColors();
        if (colors == null || colors.isColorless()) {
            return out.set(state.isLand() ? ArenaTheme.GOLD_BRIGHT : WARM);
        }
        return out.set(ArenaTheme.mix(ArenaTheme.frameColor(colors, state.isLand(), state.isArtifact()), ArenaTheme.WHITE, 0.35f, S3));
    }

    // ---- flights -----------------------------------------------------------------------------------------------------

    private static Fx fly(final int cat, final int id, final CardView card, final PlayerView owner, final Rectangle from, final Rectangle to,
            final boolean flip, final int millis, final int delayMs) {
        if (!on() || fx.size() >= MAX_EFFECTS || drawFlights.size() + stackFlights.size() >= MAX_FLIGHTS || from == null || to == null || to.width <= 0) {
            return null;
        }
        final Fx e = new Fx();
        e.kind = FLIGHT;
        e.cat = cat;
        e.id = id;
        e.card = card;
        e.owner = owner;
        e.from.set(from);
        e.to.set(to);
        e.flip = flip;
        colorOf(card, e.color);
        fx.add(e);
        if (cat == CAT_DRAW) {
            drawFlights.put(id, e);
        } else if (cat == CAT_STACK) {
            stackFlights.put(id, e);
        }
        e.tween = ArenaMotion.play(millis, delayMs, ArenaMotion.LINEAR, t -> e.t = t, () -> land(e));
        return e;
    }

    private static void forget(final Fx e) {
        fx.remove(e);
        if (e.cat == CAT_DRAW && drawFlights.get(e.id) == e) {
            drawFlights.remove(e.id);
        } else if (e.cat == CAT_STACK && stackFlights.get(e.id) == e) {
            stackFlights.remove(e.id);
        }
    }

    private static void land(final Fx e) {
        forget(e);
        if (e.cat == CAT_STACK) { // the one landing accent of a spell going onto the stack; hand draws land quietly
            ripple(e.to.x + e.to.width / 2f, e.to.y + e.to.height * 0.7f, Math.max(e.to.width, ArenaTheme.px(60)) * 0.9f, e.color);
        }
    }

    /** Ends a flight now, with no accent (what it stood for is already there or gone). */
    private static void finish(final Fx e) {
        if (e.tween != null) {
            e.tween.cancel();
        }
        forget(e);
    }

    // ---- draws -------------------------------------------------------------------------------------------------------

    /**
     * Told of every zone update the match screen gets. A player whose library gave cards up while their hand took new
     * ones in the same update drew them (or fetched them): the local player's new cards fly in from the library one
     * after another, an opponent's hand gets card backs. Anything else (a mill, a card bounced to the hand, a
     * mulligan that puts the hand back before it draws) is no draw. Any thread.
     */
    public static void zonesUpdated(final Iterable<PlayerZoneUpdate> updates) {
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

    private static void zonesUpdated(final PlayerView player, final boolean libraryChanged, final boolean handChanged) {
        if (!libraryChanged && !handChanged) {
            return;
        }
        Seen s = seen.get(player);
        final boolean first = s == null;
        if (first) {
            s = new Seen();
            seen.put(player, s);
            s.library = player.getZoneSize(ZoneType.Library);
        }
        final int library = libraryChanged ? player.getZoneSize(ZoneType.Library) : s.library;
        final int gone = s.library - library;
        s.library = library;
        if (!handChanged) {
            return;
        }
        final boolean bottom = isBottom(player);
        // who is in the hand now (everyone, so that cards that arrive later are told from cards that were there)
        final List<CardView> arrived = new ArrayList<>();
        final FCollectionView<CardView> cards = player.getCards(ZoneType.Hand);
        final Set<Integer> now = new HashSet<>();
        if (cards != null) {
            for (final CardView c : cards) {
                now.add(c.getId());
                if (!s.hand.contains(c.getId())) {
                    arrived.add(c);
                }
            }
        }
        final int handNow = player.getZoneSize(ZoneType.Hand);
        final int arrivedCount = bottom ? arrived.size() : Math.max(0, handNow - s.hand.size());
        s.hand.clear();
        s.hand.addAll(now);
        if (first || gone <= 0 || arrivedCount <= 0 || !on() || quiet()) {
            return;
        }
        final int n = Math.min(gone, arrivedCount);
        final VPlayerPanel panel = MatchScreen.getPlayerPanel(player);
        if (panel == null) {
            return;
        }
        if (bottom) {
            sendCards(panel, arrived, Math.min(n, MOST_DRAWN_AT_ONCE));
        } else {
            sendBacks(player, panel, Math.min(n, MOST_BACKS_AT_ONCE));
        }
    }

    private static boolean isBottom(final PlayerView player) {
        return MatchController.getView() != null && MatchController.getView().getBottomPlayerPanel() != null
                && MatchController.getView().getBottomPlayerPanel().getPlayer() == player;
    }

    /** The cards you drew fly in one after another from the library; until the fan lays them out they aim at its middle. */
    private static void sendCards(final VPlayerPanel panel, final List<CardView> cards, final int n) {
        final Rectangle lib = libraryOf(panel);
        if (lib == null) {
            return;
        }
        final float ch = Math.max(ArenaTheme.px(60), Forge.getScreenHeight() * 0.17f), cw = ch / FCardPanel.ASPECT_RATIO;
        for (int i = 0; i < n; i++) {
            final Rectangle start = new Rectangle(lib.x + lib.width / 2f - cw * 0.35f, lib.y + lib.height / 2f - ch * 0.35f, cw * 0.7f, ch * 0.7f);
            final Rectangle aim = new Rectangle(Forge.getScreenWidth() / 2f - cw / 2f, Forge.getScreenHeight() - ch, cw, ch);
            fly(CAT_DRAW, cards.get(i).getId(), cards.get(i), panel.getPlayer(), start, aim, true, DRAW_MS, i * ArenaMotion.STAGGER_MS * 2);
        }
    }

    private static void sendBacks(final PlayerView player, final VPlayerPanel panel, final int n) {
        final Rectangle to = panel.getArenaOpponentHandRect();
        final Rectangle from = libraryOf(panel);
        if (to == null || from == null) {
            return;
        }
        final float ch = Math.max(ArenaTheme.px(40), to.height * 0.9f), cw = ch / FCardPanel.ASPECT_RATIO;
        final Rectangle target = new Rectangle(to.x + to.width / 2f - cw / 2f, to.y + to.height - ch, cw, ch);
        for (int i = 0; i < n; i++) {
            final Rectangle start = new Rectangle(from.x + from.width / 2f - cw / 2f, from.y + from.height / 2f - ch / 2f, cw, ch);
            fly(CAT_BACK, backIds--, null, player, start, target, false, BACK_MS, i * ArenaMotion.STAGGER_MS);
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
     * The local hand fan calls this for each card it draws. While the card's draw flight is on its way it draws a
     * {@link #ghost} in the card's slot (so the card is still there to point at) and says true; the flight follows the
     * slot and lands in its lean.
     *
     * @param slot   the hand card's panel on the screen (the card sits at its top, {@code cardHeight} tall)
     * @param lean   the fan's lean for the card (degrees, clockwise)
     */
    public static boolean handCardInFlight(final Graphics g, final CardView card, final Rectangle slot, final float cardHeight, final float lean) {
        if (drawFlights.isEmpty()) {
            return false;
        }
        final Fx e = drawFlights.get(card.getId());
        if (e == null) {
            return false;
        }
        e.to.set(slot.x, slot.y, slot.width, cardHeight);
        e.lean = lean;
        ghost(g, 0, 0, slot.width, cardHeight, null, 0.5f);
        return true;
    }

    // ---- the stack ----------------------------------------------------------------------------------------------------

    /**
     * The stack view calls this when it draws an entry of the Arena stack, with {@code g} at the entry's own corner.
     * The first time an entry shows, its card flies in from where it was cast or activated (the caster's hand, their
     * portrait, the permanent's tile); while it flies this draws a {@link #ghost} in its place, still wearing the glow
     * of a target that can be picked, and says true: the view then draws nothing of its own. Every call also keeps the
     * entry's place for what happens when it is gone ({@link #stackSynced}).
     */
    public static boolean stackEntry(final Graphics g, final StackItemView item, final Rectangle screenPos, final float w, final float h,
            final Color glow, final Rectangle handStart) {
        if (!ArenaTheme.isEffectsOn()) {
            return false;
        }
        final int id = item.getId();
        Shown shown = stackShown.get(id);
        if (shown == null) {
            shown = new Shown();
            stackShown.put(id, shown);
        }
        shown.item = item;
        shown.card = item.getSourceCard();
        shown.rect.set(screenPos.x, screenPos.y, w, h);
        if (stackSeen.add(id) && on() && !quiet()) {
            final Rectangle from = originOf(item, handStart);
            if (from != null) {
                // it lands bigger than it starts: it grows into the large card of the stack
                final float sh = Math.max(from.height, h * 0.5f), sw = sh / FCardPanel.ASPECT_RATIO;
                SCRATCH.set(from.x + from.width / 2f - sw / 2f, from.y + from.height / 2f - sh / 2f, sw, sh);
                fly(CAT_STACK, id, shown.card, null, SCRATCH, new Rectangle(screenPos.x, screenPos.y, w, h), false, STACK_MS, 0);
            }
        }
        final Fx e = stackFlights.isEmpty() ? null : stackFlights.get(id);
        if (e == null) {
            return false;
        }
        e.to.set(screenPos.x, screenPos.y, w, h);
        ghost(g, 0, 0, w, h, glow, 0.5f);
        return true;
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

    /**
     * The stack view tells which entries are on the stack now (when it updates or lays out). Entries it showed that are
     * not any more have resolved or been countered: a flight still on its way is finished (no second copy is left on
     * screen), and the colour of the entry goes off, as a bolt to what it aimed at, or shattering if it was countered.
     */
    public static void stackSynced(final FCollectionView<StackItemView> stack) {
        if (stackShown.isEmpty() && stackSeen.isEmpty()) {
            return;
        }
        final Set<Integer> still = new HashSet<>();
        for (final StackItemView item : stack) {
            still.add(item.getId());
        }
        for (final Iterator<Map.Entry<Integer, Shown>> it = stackShown.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<Integer, Shown> en = it.next();
            if (still.contains(en.getKey())) {
                continue;
            }
            it.remove();
            stackSeen.remove(en.getKey());
            final Fx flying = stackFlights.get(en.getKey());
            if (flying != null) {
                finish(flying);
            }
            resolved(en.getValue());
        }
        stackSeen.retainAll(still);
    }

    private static void resolved(final Shown s) {
        if (!on() || s.rect.width <= 0) {
            return;
        }
        final float cx = s.rect.x + s.rect.width / 2f, cy = s.rect.y + s.rect.height / 2f, size = Math.max(s.rect.width, ArenaTheme.px(80));
        if (wasCountered(s)) {
            timed(SHATTER, cx, cy, size, COUNTER, COUNTER_MS);
            return;
        }
        final Color c = colorOf(s.card, new Color());
        final float[] aim = targetOf(s.item);
        if (aim != null) {
            burst(cx, cy, size * 0.6f, c);
            bolt(cx, cy, aim[0], aim[1], c, size * 0.8f);
        } else {
            burst(cx, cy, size, c);
        }
    }

    /** A permanent spell that ended up anywhere but the battlefield did not resolve. (Instants and sorceries always end in a pile.) */
    private static boolean wasCountered(final Shown s) {
        final CardView card = s.card;
        if (s.item == null || s.item.isAbility() || card == null) {
            return false;
        }
        final CardStateView state = card.getCurrentState();
        if (state.isInstant() || state.isSorcery()) {
            return false;
        }
        final ZoneType zone = card.getZone();
        return zone == ZoneType.Graveyard || zone == ZoneType.Exile || zone == ZoneType.Hand || zone == ZoneType.Library;
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

    // ---- ghosts ------------------------------------------------------------------------------------------------------

    /**
     * What stays in the place of a card that is flying there: a dim card-shaped slot with a thin rim, in {@code rim}'s
     * colour when it has one (a target that can be picked keeps its glow). It can still be clicked.
     */
    public static void ghost(final Graphics g, final float x, final float y, final float w, final float h, final Color rim, final float alpha) {
        final float r = w * 0.045f;
        g.fillSmoothRoundRect(ArenaTheme.alpha(ArenaTheme.BLACK, 0.22f * alpha, S1), x, y, w, h, r);
        if (rim != null) {
            g.drawGlow(ArenaTheme.alpha(rim, 0.7f * alpha, S2), x, y, w, h, r, h * 0.045f);
        }
        g.drawSmoothRoundRect(Math.max(1f, h * 0.006f), ArenaTheme.alpha(rim != null ? rim : ArenaTheme.WHITE, 0.4f * alpha, S3), x, y, w, h, r);
    }

    // ---- drawing -----------------------------------------------------------------------------------------------------

    /** Draws every effect over the board (the match screen's overlay calls this each frame after the cards). Render thread. */
    public static void draw(final Graphics g) {
        if (!on()) {
            if (!fx.isEmpty()) {
                clear();
            }
            return;
        }
        if (fx.isEmpty()) {
            return;
        }
        // flights under rings and sparks, so a landing's light is on top of the card
        for (int i = 0, n = fx.size(); i < n; i++) {
            final Fx e = fx.get(i);
            if (e.kind == FLIGHT && e.t > 0f) {
                drawFlight(g, e);
            }
        }
        for (int i = 0, n = fx.size(); i < n; i++) {
            final Fx e = fx.get(i);
            switch (e.kind) {
            case RING: drawRing(g, e); break;
            case BURST: drawBurst(g, e); break;
            case BOLT: drawBolt(g, e); break;
            case SHATTER: drawShatter(g, e); break;
            default: break;
            }
        }
    }

    private static void drawRing(final Graphics g, final Fx e) {
        final float out = ArenaMotion.OUT.at(e.t);
        final float fade = 1f - e.t;
        final float r = e.size * (0.25f + 0.75f * out);
        ArenaArt.halo(g, e.x, e.y, r * 1.1f, e.color, 0.35f * fade * fade);
        g.drawSmoothCircle(Math.max(1.5f, ArenaTheme.px(3f) * fade), ArenaTheme.alpha(e.color, 0.85f * fade, S1), e.x, e.y, r);
        g.drawSmoothCircle(Math.max(1f, ArenaTheme.px(1.5f)), ArenaTheme.alpha(ArenaTheme.WHITE, 0.55f * fade, S2), e.x, e.y, r * 0.72f);
    }

    /** A flash, a ring, and streaks flying outwards that thin as they go. */
    private static void drawBurst(final Graphics g, final Fx e) {
        final float out = ArenaMotion.OUT.at(e.t);
        final float fade = 1f - e.t;
        final float flash = ArenaMotion.pop(ArenaMotion.clamp01(e.t * 2.2f));
        ArenaArt.halo(g, e.x, e.y, e.size * (0.5f + 0.5f * out), ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.4f, S2), 0.5f * flash);
        g.drawSmoothCircle(Math.max(1.5f, ArenaTheme.px(2.5f) * fade), ArenaTheme.alpha(e.color, 0.8f * fade, S1), e.x, e.y, e.size * 0.6f * out);
        final Color streak = ArenaTheme.alpha(ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.5f, S2), 0.9f * fade, S1);
        for (int i = 0; i < SPARKS; i++) {
            final float a = e.seed + i * (6.2831f / SPARKS) + (i % 2) * 0.28f;
            final float far = e.size * (0.45f + 0.35f * ((i * 37) % 10) / 10f) * out, near = far * (0.35f + 0.4f * e.t);
            final float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            g.drawLine(Math.max(1.5f, e.size * 0.035f * fade), streak, e.x + cos * near, e.y + sin * near, e.x + cos * far, e.y + sin * far);
        }
    }

    /** A comet running from where an entry resolved to what it aims at: a bright head with a tapering trail. */
    private static void drawBolt(final Graphics g, final Fx e) {
        final float m = ArenaMotion.IN_OUT.at(e.t);
        final int trail = 14;
        final float tail = Math.max(0f, m - 0.32f);
        for (int i = 0; i <= trail; i++) {
            final float p = ArenaMotion.lerp(tail, m, i / (float) trail), k = i / (float) trail;
            final float px = ArenaMotion.lerp(e.x, e.x2, p), py = ArenaMotion.lerp(e.y, e.y2, p);
            final float r = Math.max(1.5f, e.size * 0.07f * (0.25f + 0.75f * k));
            g.drawSoftShadow(ArenaTheme.alpha(e.color, 0.5f * k, S1), px - r, py - r, 2 * r, 2 * r, r, r * 1.2f);
            g.fillSmoothCircle(ArenaTheme.alpha(ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.4f + 0.5f * k, S2), 0.9f * k, S1), px, py, r * 0.7f);
        }
    }

    /** A countered spell: a ring that collapses inwards, a red cross over it, and the cross breaking up. */
    private static void drawShatter(final Graphics g, final Fx e) {
        final float fade = 1f - ArenaMotion.IN.at(e.t);
        final float collapse = 1f - ArenaMotion.OUT.at(ArenaMotion.clamp01(e.t * 1.6f));
        ArenaArt.halo(g, e.x, e.y, e.size * 0.8f, e.color, 0.4f * fade);
        g.drawSmoothCircle(Math.max(2f, ArenaTheme.px(3f)), ArenaTheme.alpha(e.color, 0.9f * fade, S1), e.x, e.y, e.size * (0.25f + 0.45f * collapse));
        final float arm = e.size * 0.3f * (1f + 0.5f * e.t), th = Math.max(2f, e.size * 0.05f * fade);
        final Color line = ArenaTheme.alpha(ArenaTheme.mix(e.color, ArenaTheme.WHITE, 0.3f, S2), fade, S1);
        g.drawLine(th, line, e.x - arm, e.y - arm, e.x + arm, e.y + arm);
        g.drawLine(th, line, e.x - arm, e.y + arm, e.x + arm, e.y - arm);
        for (int i = 0; i < 8; i++) {
            final float a = e.seed + i * 0.785f, d = e.size * (0.2f + 0.5f * ArenaMotion.OUT.at(e.t));
            g.fillSmoothCircle(ArenaTheme.alpha(e.color, 0.8f * fade, S3), e.x + (float) Math.cos(a) * d, e.y + (float) Math.sin(a) * d + e.size * 0.2f * e.t * e.t,
                    Math.max(1.5f, e.size * 0.025f));
        }
    }

    private static void drawFlight(final Graphics g, final Fx e) {
        final float t = e.t, m = ArenaMotion.SMOOTH.at(t); // eased once, here
        final float h = ArenaMotion.lerp(e.from.height, e.to.height, m), w = ArenaMotion.lerp(e.from.width, e.to.width, m);
        final float fcx = e.from.x + e.from.width / 2f, fcy = e.from.y + e.from.height / 2f;
        final float tcx = e.to.x + e.to.width / 2f, tcy = e.to.y + e.to.height / 2f;
        final float dist = (float) Math.hypot(tcx - fcx, tcy - fcy);
        final float arc = (float) Math.sin(Math.PI * m);
        final float rise = Math.min(h * e.lift * 1.6f, dist * 0.3f) * arc;
        final float gx = ArenaMotion.lerp(fcx, tcx, m), gy = ArenaMotion.lerp(fcy, tcy, m); // the point on the table below the card
        final float cx = gx, cy = gy - rise;
        // nearer the viewer in the air, turning over (back first) in the first two thirds
        final float air = 1f + 0.14f * arc;
        final float flipT = e.flip ? ArenaMotion.clamp01(t / 0.66f) : 1f;
        final float squash = Math.max(0.04f, (float) Math.abs(Math.cos(Math.PI * flipT)));
        final boolean showBack = e.card == null || e.flip && flipT < 0.5f;
        final float cw = w * air, ch = h * air;
        final float drawW = e.flip ? cw * squash : cw;
        final float x = cx - drawW / 2f, y = cy - ch / 2f;
        final float old = g.getfloatAlphaComposite();
        g.setAlphaComposite(old * ArenaMotion.clamp01(t / 0.1f));
        // a shadow on the table: it stays under the card's place, and softens, spreads and fades as the card rises
        final float spread = ArenaTheme.px(6) + rise * 0.5f;
        g.drawSoftShadow(ArenaTheme.alpha(ArenaTheme.BLACK, 0.62f - 0.3f * arc, S1), gx - w * 0.48f - ch * 0.01f, gy - h * 0.46f + ch * 0.03f + rise * 0.18f,
                w * 0.96f, h * 0.96f, w * 0.045f, spread);
        // it settles into the lean of the slot it lands in
        final float lean = e.lean * m;
        final boolean turned = Math.abs(lean) > 0.05f;
        if (turned) {
            g.startRotateTransform(x + drawW / 2f, y + ch, -lean);
        }
        if (showBack) {
            ArenaCards.drawCardBack(g, e.owner, x, y, drawW, ch, Math.max(ArenaTheme.px(3), drawW / 18f), false);
        } else {
            CardRenderer.drawCard(g, e.card, x, y, drawW, ch, CardStackPosition.Top);
            g.drawSmoothRoundRect(Math.max(1f, ch * 0.006f), ArenaTheme.alpha(e.color, 0.7f * (1f - m), S1), x, y, drawW, ch, drawW * 0.045f);
        }
        if (turned) {
            g.endTransform();
        }
        g.setAlphaComposite(old);
    }
}
