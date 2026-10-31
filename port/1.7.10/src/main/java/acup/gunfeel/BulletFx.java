// Ported from 26.2's src/main/java/acup/gunfeel/BulletFx.java (e284339): the block hit by its hit rule (BulletRules:
// particles, the block's own hit sound or one of the mod's, heard over the rule's range), sound families (".f1",
// ".f2"...: one shot's pellets sound alike) and the whizz and crack of a bullet passing the player's head.
// Left out: the crumbs of blocks drawn by their renderer (Crumbs: NTM's machines give the block's own crumbs, or none
// when their render type is not a plain block) and /gunfeel sounddebug. The in-flight sound (a "fly" line in the
// server's rules; the jar's rules have none) is a 1.7.10 MovingSound.
package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

// Client side of the bullet rules, from the bullets Tracers flies: what a block hit gives off, and the whizz.
public final class BulletFx {
    private BulletFx() {
    }

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

    // Hits of the same event within FAMILY_MS share one family, so one shot's pellets sound alike; the next shot rolls again
    private static final long FAMILY_MS = 80L;
    private static final Map<String, long[]> FAMILY = new HashMap<String, long[]>();

    static String familyOf(String id, Random r) {
        SoundHandler sh = Minecraft.getMinecraft().getSoundHandler();
        int n = 0;
        while (n < 16 && sh.getSound(new ResourceLocation(id + ".f" + (n + 1))) != null) n++;
        if (n == 0) return id;
        long now = GunClock.millis();
        long[] last = FAMILY.get(id);
        if (last == null || now - last[0] > FAMILY_MS || last[1] >= n) {
            last = new long[] {now, r.nextInt(n)};
            FAMILY.put(id, last);
        }
        return id + ".f" + (last[1] + 1);
    }

    // explosive: a round that blows up there; its own blast is loud enough, the hit sound stays at the rule's volume
    public static void blockHit(World world, int[] pos, int face, double[] at, double[] dir, boolean explosive) {
        if (!Config.bulletFx) return;
        Block block = world.getBlock(pos[0], pos[1], pos[2]);
        if (block.getMaterial() == Material.air) return;
        int meta = world.getBlockMetadata(pos[0], pos[1], pos[2]);
        BulletRules.Effect fx = BulletRules.hit(block, meta, world, pos[0], pos[1], pos[2]);
        if (TestLog.client) TestLog.c(TestLog.shot(), "fx", "block", BlockIds.state(Block.blockRegistry.getNameForObject(block), meta),
                "pos", pos[0] + "," + pos[1] + "," + pos[2], "sound", fx.sound() == null ? "none" : fx.sound().id(),
                "particles", fx.particles().size(), "explosive", explosive, "fxOn", Config.bulletFx);
        if (fx.empty()) return;
        double[] n = new double[3];
        n[HoleMath.axis(face)] = HoleMath.step(face);
        double[] o = out(dir, face);
        Random r = world.rand;
        double px = at[0] + n[0] * 0.02, py = at[1] + n[1] * 0.02, pz = at[2] + n[2] * 0.02;
        // only full blocks give off particles: on grates, slabs and the like they got stuck inside the model; a block
        // drawn by its renderer (NTM's machines) has no crumbs of its own (26.2 draws them from its model: Crumbs)
        if (new Tracers.WorldBoxes(world).fullCube(pos[0], pos[1], pos[2]) && block.getRenderType() == 0) {
            for (BulletRules.Particle part : fx.particles()) {
                String id = BulletServer.particle(part.id(), block, meta);
                if (id != null) spawn(world, id, Config.scaledCount(part.count(), Config.particleScale, r.nextDouble()), px, py, pz, o, r);
            }
        }
        BulletRules.Sound s = fx.sound();
        if (s == null) return;
        float volume = s.volume() * (explosive ? Math.min(1F, Config.hitVolume) : Config.hitVolume);
        if (volume <= 0F) return;
        float pitch = s.pitch() * (0.9F + r.nextFloat() * 0.2F);
        String id;
        if (s.id().equalsIgnoreCase("block")) {
            if (block.stepSound == null) return;
            // the block's hit sound, as 1.7.10 plays it while the block is mined (PlayerControllerMP)
            id = block.stepSound.getStepResourcePath();
            pitch *= block.stepSound.getPitch();
        } else {
            id = familyOf(s.id(), r);
        }
        play(at, id, volume, pitch, fx.range());
    }

    private static void spawn(World world, String particle, int count, double x, double y, double z, double[] o, Random r) {
        for (int i = 0; i < count; i++) {
            double sp = 0.05 + r.nextDouble() * 0.15;
            world.spawnParticle(particle, x, y, z, (o[0] + r.nextGaussian() * 0.35) * sp, (o[1] + r.nextGaussian() * 0.35) * sp + 0.03,
                    (o[2] + r.nextGaussian() * 0.35) * sp);
        }
    }

    // a sound heard over `range` blocks, fading out to it. 1.7.10 fades a sound out within 16 blocks (more only for a
    // volume over 1, which is no louder): set nearer the listener on the same line, so it comes from the same side but
    // fades as if over `range`; louder than 1, the same sound several times at once (each at most 1)
    static void play(double[] at, String id, float volume, float pitch, float range) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity ear = mc.renderViewEntity;
        double x = at[0], y = at[1], z = at[2];
        float fade = 16F;
        if (ear != null && range > fade) {
            double dx = x - ear.posX, dy = y - ear.posY, dz = z - ear.posZ;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d >= range) return;
            double k = fade / range;
            x = ear.posX + dx * k;
            y = ear.posY + dy * k;
            z = ear.posZ + dz * k;
        }
        int copies = Math.min(4, Math.max(1, (int) Math.ceil(volume - 1e-3F)));
        ResourceLocation sound = new ResourceLocation(id);
        for (int i = 0; i < copies; i++)
            mc.getSoundHandler().playSound(new PositionedSoundRecord(sound, volume / copies, pitch, (float) x, (float) y, (float) z));
    }

    // a stretch a bullet flew this frame, a..b: it whizzes when it passed within the rule's radius of the player's eyes
    // (not the player's own bullets); true when it did, so it whizzes once
    public static boolean whizz(World world, int owner, double[] a, double[] b, double speed) {
        if (!Config.bulletFx) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || owner == mc.thePlayer.getEntityId()) return false;
        BulletRules.Whizz w = BulletRules.whizz();
        if (w.sound() == null || w.radius() <= 0F) return false;
        // the client player's posY is its eyes (1.7.10)
        double ex = mc.thePlayer.posX, ey = mc.thePlayer.posY, ez = mc.thePlayer.posZ;
        double abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
        double l2 = abx * abx + aby * aby + abz * abz;
        double k = l2 < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((ex - a[0]) * abx + (ey - a[1]) * aby + (ez - a[2]) * abz) / l2));
        // the nearest point must be along the stretch, not its end: else it is still coming or already gone
        if (l2 >= 1e-9 && (k <= 0.0 || k >= 1.0)) return false;
        double nx = a[0] + abx * k, ny = a[1] + aby * k, nz = a[2] + abz * k;
        double d = Math.sqrt((nx - ex) * (nx - ex) + (ny - ey) * (ny - ey) + (nz - ez) * (nz - ez));
        if (d > w.radius()) return false;
        Random r = world.rand;
        // closer: louder and a little higher; faster bullets (NTM's usual is 10 blocks a tick) higher too
        float close = (float) (1.0 - d / w.radius());
        float vol = w.sound().volume() * (0.4F + 0.6F * close);
        float fast = (float) Math.max(0.9, Math.min(1.15, Math.pow(speed / 10.0, 0.25)));
        float pitch = w.sound().pitch() * fast * (0.9F + 0.15F * close) * (0.88F + r.nextFloat() * 0.24F);
        mc.getSoundHandler().playSound(new PositionedSoundRecord(new ResourceLocation(w.sound().id()), vol, pitch, (float) nx, (float) ny, (float) nz));
        // passing right by the head: the snap of the bullet's shock wave on top
        if (w.crack() != null && d <= w.crackRadius()) {
            float cv = w.crack().volume() * (float) (0.6 + 0.4 * (1.0 - d / Math.max(0.01, w.crackRadius())));
            mc.getSoundHandler().playSound(new PositionedSoundRecord(new ResourceLocation(w.crack().id()), cv,
                    w.crack().pitch() * (0.9F + r.nextFloat() * 0.2F), (float) nx, (float) ny, (float) nz));
        }
        return true;
    }

    // the in-flight sound of one bullet, following it until it stops
    public static void fly(java.util.function.Supplier<double[]> where, java.util.function.BooleanSupplier flying) {
        if (!Config.bulletFx) return;
        BulletRules.Sound s = BulletRules.fly();
        if (s == null) return;
        ResourceLocation id = new ResourceLocation(s.id());
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSoundHandler().getSound(id) == null) return;
        mc.getSoundHandler().playSound(new Flying(id, s, where, flying));
    }

    private static final class Flying extends MovingSound {
        private final java.util.function.Supplier<double[]> where;
        private final java.util.function.BooleanSupplier flying;

        Flying(ResourceLocation id, BulletRules.Sound s, java.util.function.Supplier<double[]> where, java.util.function.BooleanSupplier flying) {
            super(id);
            this.where = where;
            this.flying = flying;
            this.repeat = true;
            this.field_147665_h = 0; // repeat delay
            this.volume = s.volume();
            this.field_147663_c = s.pitch();
            move();
        }

        private void move() {
            double[] p = where.get();
            xPosF = (float) p[0];
            yPosF = (float) p[1];
            zPosF = (float) p[2];
        }

        @Override
        public void update() {
            if (!flying.getAsBoolean()) {
                donePlaying = true;
                return;
            }
            move();
        }
    }
}
