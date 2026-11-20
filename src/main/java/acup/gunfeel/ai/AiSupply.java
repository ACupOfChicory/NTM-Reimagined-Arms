package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.LevelEntities;
import acup.gunfeel.host.EntityView;
import net.minecraft.server.level.ServerLevel;

/** 26.2's supplier: the mob's fields and game calls for core. */
public final class AiSupply extends AiSupplyBase {
    static @Nullable AiSupplyAlike alike;

    private AiSupply() {
    }

    private static final Staff<AiBot, AiSoldier> STAFF = new Staff<>() {
        @Override public int @Nullable [] readStock(AiBot b) { return b.mob.getPersistentData().getIntArray(STOCK_KEY).orElse(null); }
        @Override public void writeStock(AiBot b, int[] stock) { b.mob.getPersistentData().putIntArray(STOCK_KEY, stock); }
        @Override public @Nullable AiBot target(AiBot b) { return b.supplyTo; }
        @Override public void target(AiBot b, @Nullable AiBot to) { b.supplyTo = to; }
        @Override public @Nullable Left fetching(AiBot b) { return b.supplyFetch; }
        @Override public void fetching(AiBot b, @Nullable Left left) { b.supplyFetch = left; }
        @Override public EntityView entities(Object level) { return new LevelEntities((ServerLevel) level); }
        @Override public boolean hasSpot(AiBot b) { return b.brain instanceof AiSoldier s && s.spot() != null; }
        @Override public void assignSupplier(AiBot b) { AiRoles.assign(b, AiRole.SUPPLIER); }
        @Override public int grenadeStock() { return AiGrenades.stock(); }
        @Override public int smokeStock() { return AiSmoke.stock(); }
        @Override public void fire(AiBot b, boolean firing) { b.gunner.fire(firing); }
        @Override public PathSearch.Cost fireLines(Object level, AiBot b) { return FireLines.of((ServerLevel) level, b.mob); }
        @Override public double runSpeed() { return AiSoldier.RUN; }
        @Override public void keepAmmo(AiBot b) { AiAdapter.keep(b); }
        @Override public void keepGrenades(AiBot b) { AiAdapter.keepGrenades(b); }
        @Override public void keepSmokes(AiBot b) { AiAdapter.keepSmokes(b); }
        @Override public void note(AiSoldier s, AiBot b, long tick, String text) { s.note(b, tick, text); }
    };

    public static void forget(ServerLevel level) { forgetLevel(level); }
    static void squad(java.util.List<AiBot> bots, long tick) { checkSquad(STAFF, bots, tick); }
    static void fell(ServerLevel level, AiBot bot, long tick) { leaveStock(STAFF, level, bot, tick); }
    private static boolean fetch(AiSoldier s, AiBot bot, ServerLevel level, long tick) { return fetchStock(STAFF, s, bot, level, tick); }
    private static @Nullable AiBot bot(ServerLevel level, int id) { return findBot(STAFF, level, id); }
    private static boolean squadLives(ServerLevel level, Left l) { return squadAlive(STAFF, level, l); }
    private static int key(String key, int def) { return stockKey(key, def); }
    static int[] stock(AiBot bot) { return supplies(STAFF, bot); }
    private static void keep(AiBot bot) { saveStock(STAFF, bot); }
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, long tick) { return supplyStep(STAFF, s, bot, level, tick); }
    private static @Nullable AiBot pick(AiBot bot, int[] stock, long tick) { return pickRecipient(STAFF, bot, stock, tick); }
    private static void hand(AiSoldier s, AiBot bot, AiBot to, int[] stock, long tick) { handStock(STAFF, s, bot, to, stock, tick); }
    static String shown(AiBot bot) { return shownStock(STAFF, bot); }

    public static boolean on() { return alike == null ? enabled() : alike.answer("on", enabled(), () -> AiSupplyOld.on()); }
    static boolean outOn() { return alike == null ? outEnabled() : alike.answer("outOn", outEnabled(), () -> AiSupplyOld.outOn && acup.gunfeel.ReaimTimes.flag(OUT_KEY, true)); }
    static float share() { return alike == null ? supplierShare() : alike.answer("share", supplierShare(), () -> AiSupplyOld.share()); }
    static double need(AiBot b, int[] stock) { return alike == null ? supplyNeed(STAFF, b, stock) : alike.answer("need", supplyNeed(STAFF, b, stock), () -> AiSupplyOld.need(b, stock)); }
    private static long cell(AiBot b) { return alike == null ? botCell(STAFF, b) : alike.answer("cell", botCell(STAFF, b), () -> AiSupplyOld.cell(b)); }
    static boolean free(AiBot b, long tick) { return alike == null ? supplierFree(STAFF, b, tick) : alike.focus("free", b, () -> supplierFree(STAFF, b, tick), () -> AiSupplyOld.free(b, tick)); }
    static boolean exposed(AiBot b) { return alike == null ? recipientExposed(STAFF, b) : alike.focus("exposed", b, () -> recipientExposed(STAFF, b), () -> AiSupplyOld.exposed(b)); }
    private static boolean seesFoe(AiBot b) { return alike == null ? foeVisible(STAFF, b) : alike.focus("seesFoe", b, () -> foeVisible(STAFF, b), () -> AiSupplyOld.seesFoe(b)); }

    /** The stand also asks queries used only inside core's writes. */
    static void compare(AiBot b, long tick) {
        int focus = b.memory.focusId;
        try {
            on(); outOn(); share(); cell(b); free(b, tick); exposed(b); seesFoe(b);
            need(b, new int[] {1, 1, 1});
        } finally {
            b.memory.focusId = focus;
        }
    }
}
