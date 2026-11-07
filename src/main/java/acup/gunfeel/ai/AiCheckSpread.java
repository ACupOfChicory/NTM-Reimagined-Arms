package acup.gunfeel.ai;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.mojang.authlib.GameProfile;

import acup.gunfeel.Spread;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's spread: an AI mob's shot is spread as a player's. The same Spread.apply NTM's calcSpread goes through
 * (MixinLegoSpread) on an AI dummy and on a fake player with the same gun, in the same states: standing, walking,
 * sprinting, in the air, crouching, crouching and walking, aiming, and a burst's bloom shot by shot (a shot a tick). How
 * fast it moved is what each tells the game: a mob its own move last tick (Entity.computeSpeed), a player what its
 * client sent (setKnownMovement here). Then the dummy aims with NTM's aim key, as a player: the gun's aim flag.
 */
final class AiCheckSpread {
    private enum Phase {
        SETUP, STATES, BLOOM, AIM, DONE
    }

    /** a player's walking and sprinting pace, blocks a tick (4.317 and 5.612 blocks a second) */
    static final double WALK = 0.21585, SPRINT = 0.2806, SNEAK_WALK = 0.0648;
    static final int BLOOM_SHOTS = 6;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private Phase phase = Phase.SETUP;
    private int ticks;
    private Mob mob;
    private ServerPlayer player;
    private final ItemStack mobGun, playerGun;
    private final StringBuilder bloomBot = new StringBuilder(), bloomPlayer = new StringBuilder();
    private boolean bloomSame = true;

    AiCheckSpread(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
        var gun = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(AiSpawn.DEFAULT_GUN);
        mobGun = new ItemStack(gun);
        playerGun = new ItemStack(gun);
    }

    boolean tick() {
        ticks++;
        switch (phase) {
            case SETUP -> setup();
            case STATES -> states();
            case BLOOM -> bloom();
            case AIM -> aim();
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    private void setup() {
        Vec3 at = new Vec3(AiCheckCrowd.X0 + 4.5, y, AiCheckCrowd.Z0 + 4.5);
        List<Mob> made = AiSpawn.spawn(level, at, AiSpawn.Kind.DUMMY, 1, null);
        if (made.isEmpty()) {
            check.check("the spread check: a dummy spawns", false);
            phase = Phase.DONE;
            return;
        }
        mob = made.get(0);
        mob.setPos(at.x, at.y, at.z);
        AiBot bot = AiBots.get(mob);
        if (bot != null) bot.brain = null;
        player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-a1a1a1a1a1a1"), "armsai_spread"));
        player.setPos(at.x + 2, at.y, at.z);
        phase = Phase.STATES;
    }

    /** both standing on the ground, in a pose, having moved pace blocks last tick */
    private void put(LivingEntity e, double pace, boolean ground, Pose pose) {
        e.setOnGround(ground);
        e.setPose(pose);
        Vec3 mv = new Vec3(pace, 0, 0);
        if (e instanceof ServerPlayer p) p.setKnownMovement(mv);
        else known(e, mv);
    }

    // a mob's move last tick, as Entity.computeSpeed leaves it (Mojang's names at runtime)
    private static void known(Entity e, Vec3 mv) {
        try {
            var f = Entity.class.getDeclaredField("lastKnownSpeed");
            f.setAccessible(true);
            f.set(e, mv);
        } catch (ReflectiveOperationException x) {
            throw new IllegalStateException(x);
        }
    }

    private float spread(LivingEntity e, boolean aim) {
        return Spread.apply(e, e == mob ? mobGun : playerGun, aim, 1F);
    }

    private void states() {
        record State(String name, double pace, boolean ground, Pose pose, boolean aim) {
        }
        List<State> all = List.of(new State("standing", 0, true, Pose.STANDING, false), new State("walking", WALK, true, Pose.STANDING, false),
                new State("sprinting", SPRINT, true, Pose.STANDING, false), new State("in the air", 0, false, Pose.STANDING, false),
                new State("crouching", 0, true, Pose.CROUCHING, false), new State("crouching, walking", SNEAK_WALK, true, Pose.CROUCHING, false),
                new State("aiming", 0, true, Pose.STANDING, true), new State("aiming, walking", WALK, true, Pose.STANDING, true));
        StringBuilder line = new StringBuilder("     spread, NTM's x1, bot / player:");
        boolean same = true;
        for (State s : all) {
            put(mob, s.pace, s.ground, s.pose);
            put(player, s.pace, s.ground, s.pose);
            float b = spread(mob, s.aim), p = spread(player, s.aim);
            same &= Math.abs(b - p) <= 1e-6F;
            line.append(String.format(Locale.ROOT, " %s %.3f/%.3f;", s.name, b, p));
        }
        put(mob, 0, true, Pose.STANDING);
        put(player, 0, true, Pose.STANDING);
        check.out(line.toString());
        check.check("an AI mob's shot is spread as a player's, in every state", same);
        phase = Phase.BLOOM;
        ticks = 0;
    }

    /** a shot a tick from both: the bloom each shot meets */
    private void bloom() {
        put(mob, 0, true, Pose.STANDING);
        put(player, 0, true, Pose.STANDING);
        float b = spread(mob, false), p = spread(player, false);
        bloomSame &= Math.abs(b - p) <= 1e-6F;
        bloomBot.append(String.format(Locale.ROOT, " %.3f", b));
        bloomPlayer.append(String.format(Locale.ROOT, " %.3f", p));
        if (ticks < BLOOM_SHOTS) return;
        check.out("     a burst, a shot a tick: bot" + bloomBot + "; player" + bloomPlayer);
        check.check("and the same bloom in a burst", bloomSame);
        phase = Phase.AIM;
        ticks = 0;
    }

    private void aim() {
        ItemStack stack = mob.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) {
            check.check("the dummy holds an NTM gun to aim", false);
            phase = Phase.DONE;
            return;
        }
        gun.handleKeybind(mob, null, stack, EnumKeybind.GUN_TERTIARY, true);
        boolean on = ItemGunBaseNT.getIsAiming(stack);
        gun.handleKeybind(mob, null, stack, EnumKeybind.GUN_TERTIARY, false);
        boolean off = !ItemGunBaseNT.getIsAiming(stack);
        check.check("NTM's aim key aims an AI mob's gun as a player's (and lets go)", on && off);
        mob.discard();
        phase = Phase.DONE;
    }
}
