// Ported from 26.2's src/main/java/acup/gunfeel/BulletFx.java at 0762086 (port/1.7.10-forge de8bdf7): only the block
// hit, with what 26.2's catch-all hit rule gives ("hit * particles=block*6,minecraft:smoke*1 sound=block/0.6").
// Left out until the bullet rules (BulletRules, ntmgunfeel-bullets.txt) and the mod's sounds have a 1.7.10 reader:
// per-block hit rules and the mod's hit sounds, the whizz past the player, the in-flight sound, the crumbs of blocks
// drawn by their renderer (Crumbs), the sound families and /gunfeel sounddebug.
package acup.gunfeel;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;

// Client side of the bullet rules, from the bullets Tracers flies: what a block hit gives off.
public final class BulletFx {
    private BulletFx() {
    }

    // 26.2's catch-all hit rule: block crumbs, a puff of smoke, the block's own hit sound at 0.6
    static final int CRUMBS = 6, SMOKE = 1;
    static final float VOLUME = 0.6F;

    // the bits of a hit fly back towards the shooter: the bullet's direction bounced off the face, normalized
    static double[] out(double[] dir, int face) {
        double[] n = new double[3];
        n[HoleMath.axis(face)] = HoleMath.step(face);
        double d = 2 * (dir[0] * n[0] + dir[1] * n[1] + dir[2] * n[2]);
        double[] o = {dir[0] - n[0] * d, dir[1] - n[1] * d, dir[2] - n[2] * d};
        double l = Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]);
        if (l > 1e-12) for (int i = 0; i < 3; i++) o[i] /= l;
        return o;
    }

    // explosive: a round that blows up there; its own blast is loud enough, the hit sound stays at the rule's volume
    public static void blockHit(World world, int[] pos, int face, double[] at, double[] dir, boolean explosive) {
        if (!Config.bulletFx) return;
        Block block = world.getBlock(pos[0], pos[1], pos[2]);
        if (block.getMaterial() == Material.air) return;
        int meta = world.getBlockMetadata(pos[0], pos[1], pos[2]);
        if (TestLog.client) TestLog.c(-1, "fx", "block", Block.blockRegistry.getNameForObject(block) + "[" + meta + "]",
                "pos", pos[0] + "," + pos[1] + "," + pos[2], "explosive", explosive, "fxOn", Config.bulletFx);
        double[] n = new double[3];
        n[HoleMath.axis(face)] = HoleMath.step(face);
        double[] o = out(dir, face);
        Random r = world.rand;
        double px = at[0] + n[0] * 0.02, py = at[1] + n[1] * 0.02, pz = at[2] + n[2] * 0.02;
        // only full blocks give off particles: on grates, slabs and the like they got stuck inside the model
        if (new Tracers.WorldBoxes(world).fullCube(pos[0], pos[1], pos[2]) && block.getRenderType() == 0) {
            String crumb = "blockcrack_" + Block.getIdFromBlock(block) + "_" + meta;
            spawn(world, crumb, Config.scaledCount(CRUMBS, Config.particleScale, r.nextDouble()), px, py, pz, o, r);
            spawn(world, "smoke", Config.scaledCount(SMOKE, Config.particleScale, r.nextDouble()), px, py, pz, o, r);
        }
        float volume = VOLUME * (explosive ? Math.min(1F, Config.hitVolume) : Config.hitVolume);
        if (volume <= 0F || block.stepSound == null) return;
        // the block's hit sound, as 1.7.10 plays it while the block is mined (PlayerControllerMP)
        world.playSound(at[0], at[1], at[2], block.stepSound.getStepResourcePath(), volume, block.stepSound.getPitch() * (0.9F + r.nextFloat() * 0.2F), false);
    }

    private static void spawn(World world, String particle, int count, double x, double y, double z, double[] o, Random r) {
        for (int i = 0; i < count; i++) {
            double sp = 0.05 + r.nextDouble() * 0.15;
            world.spawnParticle(particle, x, y, z, (o[0] + r.nextGaussian() * 0.35) * sp, (o[1] + r.nextGaussian() * 0.35) * sp + 0.03,
                    (o[2] + r.nextGaussian() * 0.35) * sp);
        }
    }
}
