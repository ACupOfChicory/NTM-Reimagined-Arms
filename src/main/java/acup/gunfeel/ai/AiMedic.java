package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.EntityBody;
import acup.gunfeel.LevelEntities;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 26.2's medic operations for core. */
public final class AiMedic extends AiMedicBase {
    private AiMedic() {
    }

    private static final Staff<AiBot, AiSoldier> STAFF = new Staff<>() {
        @Override public @Nullable Integer keptKits(AiBot b) { return b.mob.getPersistentData().getInt(KITS_TAG).orElse(null); }
        @Override public void keepKits(AiBot b, int kits) { b.mob.getPersistentData().putInt(KITS_TAG, kits); }
        @Override public void heal(Body patient, float amount) { ((LivingEntity) EntityBody.entity(patient)).heal(amount); }
        @Override public boolean free(AiBot b, long tick) { return AiSupply.free(b, tick); }
        @Override public boolean exposed(AiBot b) { return AiSupply.exposed(b); }
        @Override public PathSearch.Cost fireLines(Object level, AiBot b) { return FireLines.of((ServerLevel) level, b.mob); }
        @Override public void fire(AiBot b, boolean fire) { b.gunner.fire(fire); }
        @Override public void aim(AiBot b, @Nullable Vec3d at) { b.gunner.aim(at == null ? null : new Vec3(at.x(), at.y(), at.z())); }
        @Override public void crouch(AiSoldier s, boolean crouch) { s.crouch(crouch); }
        @Override public void note(AiSoldier s, AiBot b, long tick, String text) { s.note(b, tick, text); }
        @Override public double run() { return AiSoldier.RUN; }
        @Override public EntityView entities(Object level) { return new LevelEntities((ServerLevel) level); }
    };

    public static boolean on() { return enabled(STAFF); }
    static boolean cover() { return careUnderFire(STAFF); }
    static boolean waits(AiBot bot) { return awaitsCare(STAFF, bot); }
    static float share() { return medicShare(STAFF); }
    static int kits(AiBot bot) { return kitCount(STAFF, bot); }
    private static State state(AiBot bot) { return stateOf(STAFF, bot); }
    private static void keep(AiBot bot) { keepStock(STAFF, bot); }
    static boolean medic(AiBot bot) { return isMedic(STAFF, bot); }
    static boolean hurt(LivingEntity e) { return wounded(STAFF, EntityBody.of(e)); }
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, long tick) { return tickMedic(STAFF, s, bot, level, tick); }
    private static void treat(AiSoldier s, AiBot bot, LivingEntity to, long tick) { useKit(STAFF, s, bot, EntityBody.of(to), tick); }
    private static long cell(Entity e) { return patientCell(STAFF, EntityBody.of(e)); }
    private static @Nullable LivingEntity pick(AiBot bot, ServerLevel level) { return (LivingEntity) EntityBody.entity(choosePatient(STAFF, bot, level)); }
    private static TargetMemory.@Nullable Contact fight(AiBot bot) { return visibleFoe(STAFF, bot); }
    private static boolean served(ServerLevel level, AiBot bot, int id) { return patientTaken(STAFF, level, bot, id); }
    static void fell(AiBot bot) { medicFell(STAFF, bot); }
    static String shown(AiBot bot) { return kitLabel(STAFF, bot); }
    public static String info() { return medicInfo(STAFF); }
}
