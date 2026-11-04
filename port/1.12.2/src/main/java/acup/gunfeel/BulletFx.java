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
        while (n < 16 && sh.getAccessor(new ResourceLocation(id + ".f" + (n + 1))) != null) n++;
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
        blockHit(world, pos, face, at, dir, explosive, false);
    }

    // window: a machine's window (MachineGlass) met, with the sound and crumbs of glass
    public static void blockHit(World world, int[] pos, int face, double[] at, double[] dir, boolean explosive, boolean window) {
        if (!Config.bulletFx) return;
        Block block = window ? net.minecraft.init.Blocks.GLASS : Mc.block(world, pos[0], pos[1], pos[2]);
        if (Mc.material(block) == Material.AIR) return;
        int meta = window ? 0 : Mc.meta(world, pos[0], pos[1], pos[2]);
        BulletRules.Effect fx = BulletRules.hit(block, meta, world, pos[0], pos[1], pos[2]);
        if (TestLog.client) TestLog.c(TestLog.shot(), "fx", "block", BlockIds.state(Mc.name(block), meta),
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
        if (window || new Tracers.WorldBoxes(world).fullCube(pos[0], pos[1], pos[2]) && Mc.renderType(block) == 0) {
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
            net.minecraft.block.SoundType st = block.getSoundType(Mc.state(world, pos[0], pos[1], pos[2]), world, Mc.pos(pos[0], pos[1], pos[2]), null);
            if (st == null) return;
            // the block's hit sound, as 1.7.10 plays it while the block is mined (PlayerControllerMP; 1.12.2: its hit sound)
            id = st.getHitSound().getSoundName().toString();
            pitch *= st.getPitch();
        } else {
            id = familyOf(s.id(), r);
        }
        play(at, id, volume, pitch, fx.range());
    }

    private static void spawn(World world, String particle, int count, double x, double y, double z, double[] o, Random r) {
        for (int i = 0; i < count; i++) {
            double sp = 0.05 + r.nextDouble() * 0.15;
            Mc.spawn(world, particle, x, y, z, (o[0] + r.nextGaussian() * 0.35) * sp, (o[1] + r.nextGaussian() * 0.35) * sp + 0.03,
                    (o[2] + r.nextGaussian() * 0.35) * sp);
        }
    }

    // a sound heard over `range` blocks, fading out to it (over its own fade when that is longer, as 26.2's). 1.7.10
    // fades a sound out within 16 blocks (more only for a volume over 1, which is no louder): set nearer the listener on
    // the same line, so it comes from the same side but fades as if over `range`; louder than 1, the same sound several
    // times at once (each at most 1)
    static void play(double[] at, String id, float volume, float pitch, float range) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity ear = mc.getRenderViewEntity();
        double x = at[0], y = at[1], z = at[2];
        float fade = fade26(id);
        if (ear != null) {
            // port: from the ears, as 26.2 from its camera (1.12.2's posY is the feet: from there a hit came out lower and nearer)
            double ey = ear.posY + ear.getEyeHeight();
            double dx = x - ear.posX, dy = y - ey, dz = z - ear.posZ;
            if (range > fade && dx * dx + dy * dy + dz * dz >= range * range) return;
            double[] p = faded(new double[] {ear.posX, ey, ear.posZ}, at, Math.max(fade, range));
            x = p[0];
            y = p[1];
            z = p[2];
        }
        int copies = Math.min(4, Math.max(1, (int) Math.ceil(volume - 1e-3F)));
        ResourceLocation sound = new ResourceLocation(id);
        for (int i = 0; i < copies; i++)
            mc.getSoundHandler().playSound(record(sound, volume / copies, pitch, x, y, z));
    }

    // port: how far 26.2 fades a sound out, its sounds.json's attenuation_distance (16 without one; SoundsJsonTest
    // checks the table against it). 1.12.2's sounds.json has no such field: every sound fades out over 16 blocks
    static final Map<String, Float> FADE_26 = new HashMap<String, Float>();
    static {
        FADE_26.put("ntmgunfeel:bullet.whizz", 8F);
        FADE_26.put("ntmgunfeel:bullet.crack", 12F);
        FADE_26.put("ntmgunfeel:bullet.ricochet.steel", 32F);
        for (String hit : new String[] {"wood", "dirt", "gravel", "stone", "concrete", "metal", "steel",
                "concrete.f1", "concrete.f2", "dirt.f1", "dirt.f2", "gravel.f1", "gravel.f2", "gravel.f3", "metal.f1", "metal.f2",
                "steel.f1", "steel.f2", "steel.f3", "steel.f4", "steel.f5", "stone.f1", "stone.f2", "wood.f1", "wood.f2"})
            FADE_26.put("ntmgunfeel:bullet.hit." + hit, 24F);
    }

    static float fade26(String id) {
        Float f = FADE_26.get(id);
        return f == null ? 16F : f;
    }

    // where a sound at `at` goes so that 1.12.2, fading it over 16 blocks, fades it as over `over` blocks: on the line
    // from the ears, 16/over as far (so from the same side)
    static double[] faded(double[] ear, double[] at, double over) {
        double k = 16.0 / over;
        return new double[] {ear[0] + (at[0] - ear[0]) * k, ear[1] + (at[1] - ear[1]) * k, ear[2] + (at[2] - ear[2]) * k};
    }

    // port: 1.12.2's positioned sound by its name (sounds.json), heard over 16 blocks as 1.7.10's
    private static PositionedSoundRecord record(ResourceLocation id, float volume, float pitch, double x, double y, double z) {
        return new PositionedSoundRecord(id, net.minecraft.util.SoundCategory.PLAYERS, volume, pitch, false, 0,
                net.minecraft.client.audio.ISound.AttenuationType.LINEAR, (float) x, (float) y, (float) z);
    }

    // a stretch a bullet flew this frame, a..b: it whizzes when it passed within the rule's radius of the player's eyes
    // (not the player's own bullets); true when it did, so it whizzes once
    public static boolean whizz(World world, int owner, double[] a, double[] b, double speed) {
        if (!Config.bulletFx) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || owner == mc.player.getEntityId()) return false;
        BulletRules.Whizz w = BulletRules.whizz();
        if (w.sound() == null || w.radius() <= 0F) return false;
        // port: 1.7.10's client player's posY is its eyes; 1.12.2's is its feet
        double ex = mc.player.posX, ey = mc.player.posY + mc.player.getEyeHeight(), ez = mc.player.posZ;
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
        // port: 26.2 fades the whizz over 8 blocks and the crack over 12 (sounds.json); 1.12.2 over 16, so set farther out
        double[] eye = {ex, ey, ez}, near = {nx, ny, nz};
        double[] wp = faded(eye, near, fade26(w.sound().id()));
        mc.getSoundHandler().playSound(record(new ResourceLocation(w.sound().id()), vol, pitch, wp[0], wp[1], wp[2]));
        // passing right by the head: the snap of the bullet's shock wave on top
        if (w.crack() != null && d <= w.crackRadius()) {
            float cv = w.crack().volume() * (float) (0.6 + 0.4 * (1.0 - d / Math.max(0.01, w.crackRadius())));
            double[] cp = faded(eye, near, fade26(w.crack().id()));
            mc.getSoundHandler().playSound(record(new ResourceLocation(w.crack().id()), cv,
                    w.crack().pitch() * (0.9F + r.nextFloat() * 0.2F), cp[0], cp[1], cp[2]));
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
        if (mc.getSoundHandler().getAccessor(id) == null) return;
        mc.getSoundHandler().playSound(new Flying(id, s, where, flying));
    }

    private static final class Flying extends MovingSound {
        private final java.util.function.Supplier<double[]> where;
        private final java.util.function.BooleanSupplier flying;

        Flying(ResourceLocation id, BulletRules.Sound s, java.util.function.Supplier<double[]> where, java.util.function.BooleanSupplier flying) {
            super(new net.minecraft.util.SoundEvent(id), net.minecraft.util.SoundCategory.PLAYERS);
            this.where = where;
            this.flying = flying;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = s.volume();
            this.pitch = s.pitch();
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
