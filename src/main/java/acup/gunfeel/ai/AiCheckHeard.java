package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's foe heard where it was lost (TargetMemory.echo): a squad of SQUAD knows of a foe shut in a box of
 * plain stone with no way in (no glimpse of it through a doorway keeps it known: hearing alone does). It makes a noise
 * every NOISE_EVERY ticks: a rifle's shot, its sound alone, heard the way any is (AiBots.sound: each one's own ear, its
 * error, its getting used to a sound from one place). Rounds in turn with a sound from where it was lost keeping it
 * known and without. Counted at the end of each: how many of the squad still know of it, how near the nearest of them
 * is to the box. Kept: it is known to the end, while heard; not, it is forgotten as before. (With a way in, a niche
 * whose way in is round the far side: the squad goes round to it searching and takes the one in it with sound kept or
 * not, between ticks 800 and 1400 or not by 1500; it tells nothing of hearing.)
 */
final class AiCheckHeard {
    static final int X0 = 7000, Z0 = 0, SX = 40, SZ = 48, ROUNDS = 6, RUN = 1500, NOISE_EVERY = 150, SQUAD = 4;
    /** the box: a 3 by 3 floor walled round three high with a roof, no way in */
    static final int NICHE_X = X0 + 20, NICHE_Z = Z0 + 10;
    static final double SQUAD_Z = NICHE_Z + 22.5;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    static final String SIDE = "heard", FOE = "heard_foe";

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, echoesBefore;
    private Mob foe;
    private final List<Mob> squad = new ArrayList<>();
    /** each round: kept (1) or not, known of by so many of the squad at the end, the nearest to the niche (tenths of a block), echoes, the foe down (1) */
    final List<int[]> results = new ArrayList<>();

    AiCheckHeard(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private boolean keeps() {
        return round % 2 == 0;
    }

    boolean tick() {
        if (!started) {
            started = true;
            AiBots.awake = true;
            for (int cx = X0 >> 4; cx <= (X0 + SX) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SZ) >> 4; cz++) level.setChunkForced(cx, cz, true);
            start(0);
            return false;
        }
        if (round >= ROUNDS) return true;
        ticks++;
        if (ticks % NOISE_EVERY == 0 && foe.isAlive()) noise();
        if (ticks < RUN && foe.isAlive()) return false;
        end();
        if (round + 1 < ROUNDS) start(round + 1);
        else {
            round = ROUNDS;
            report();
        }
        return round >= ROUNDS;
    }

    /** a rifle's shot from in the niche: its sound alone, as AiCheckFolly's */
    private void noise() {
        var item = BuiltInRegistries.ITEM.getValue(RIFLE);
        if (!(item instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)) return;
        ItemStack stack = new ItemStack(item);
        var rec = gun.getConfig(stack, 0).getReceivers(stack)[0];
        SoundEvent sound = rec.getFireSound(stack).get();
        level.playSound(null, NICHE_X + 0.5, y + 1.5, NICHE_Z + 0.5, sound, SoundSource.PLAYERS, rec.getFireVolume(stack), 1F);
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SX; x++)
            for (int z = Z0; z < Z0 + SZ; z++) {
                set(x, y - 1, z, stone);
                boolean edge = x == X0 || x == X0 + SX - 1 || z == Z0 || z == Z0 + SZ - 1;
                for (int h = 0; h < 5; h++) set(x, y + h, z, edge && h < 3 ? stone : air);
            }
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                boolean w = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int h = 0; h < 3; h++) set(NICHE_X + dx, y + h, NICHE_Z + dz, w ? stone : air);
                set(NICHE_X + dx, y + 3, NICHE_Z + dz, stone);
            }
    }

    private Mob spawn(double x, double z, String team, float yaw, boolean armed) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(RIFLE));
        if (made.isEmpty()) return null;
        Mob m = made.get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        if (!armed) m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        AiTeams.join(level, m, team);
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        AiBots.heardKeeps = keeps();
        AiSquads.on = true;
        AiSquads.forget(level);
        build();
        // the foe: unarmed, still, in the niche (it fires at no one: the noise is the stand's, from where it is)
        foe = spawn(NICHE_X + 0.5, NICHE_Z + 0.5, FOE, 0F, false);
        var speed = foe.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(NICHE_X - 4.5 + 3 * i, SQUAD_Z, SIDE, 180F, true));
        AiBots.refreshTargets(level);
        // known of: seen where it is, at the start (it went in there); its next look sees it no more
        long now = level.getGameTime();
        for (Mob m : squad) if (AiBots.get(m) instanceof AiBot b) b.memory.seen(foe.getId(), foe.getX(), foe.getY(), foe.getZ(), now, 0);
        echoesBefore = TargetMemory.echoes;
    }

    private void end() {
        int known = 0;
        double nearest = Double.MAX_VALUE;
        for (Mob m : squad) {
            if (!m.isAlive()) continue;
            if (AiBots.get(m) instanceof AiBot b && b.memory.contact(foe.getId()) != null) known++;
            nearest = Math.min(nearest, Math.hypot(m.getX() - (NICHE_X + 0.5), m.getZ() - (NICHE_Z + 0.5)));
        }
        int echoes = TargetMemory.echoes - echoesBefore;
        boolean down = !foe.isAlive();
        check.out(String.format(Locale.ROOT, "     heard, round %d, %s: known of by %d of %d at the end (tick %d), the nearest %.1f blocks from the box, echoes %d, the foe %s",
                round + 1, keeps() ? "a sound from where it was lost keeps it" : "not kept by a sound", known, SQUAD, ticks, nearest, echoes, down ? "down" : "up"));
        results.add(new int[] {keeps() ? 1 : 0, known, (int) Math.round(nearest * 10), echoes, down ? 1 : 0});
        for (Mob m : squad) m.discard();
        squad.clear();
        foe.discard();
    }

    private void report() {
        AiBots.heardKeeps = true;
        int keptRounds = 0, keptKnown = 0, offRounds = 0, offKnown = 0, offEchoes = 0;
        for (int[] r : results) {
            if (r[0] == 1) {
                keptRounds++;
                // the foe taken counts as known to the end: it was gone after
                if (r[1] >= SQUAD / 2 || r[4] == 1) keptKnown++;
            } else {
                offRounds++;
                if (r[1] > 0) offKnown++;
                offEchoes += r[3];
            }
        }
        check.check(String.format(Locale.ROOT, "heard: a foe heard where it was lost is known of to the end by half the squad at least, or taken (%d of %d rounds)",
                keptKnown, keptRounds), keptRounds > 0 && keptKnown == keptRounds);
        check.check(String.format(Locale.ROOT, "heard: with no sound kept as its own it is forgotten as before (known at the end in %d of %d rounds, echoes %d)",
                offKnown, offRounds, offEchoes), offRounds > 0 && offKnown == 0 && offEchoes == 0);
    }
}
