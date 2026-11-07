package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

// Dev (-Pscenario=turn, ShootScenario's flat world): a kept pose of NTM CE's hunter chopper and plastic bag (drawn by a
// plain Render, taken by ModelHitClient.beginOther) turned as NTM's renderer turns the mob (ModelHits.Turn). One mob
// at a time, with no AI, held in the air in front of the camera, sends its pose; the server then turns its yRot by +40
// and its body by -30 at once, and turns the pose it keeps by the rule (kept). Some ticks later the client has drawn
// the mob at its new yRot and sent a fresh pose (fresh). Kept against fresh: the farthest corner of a cube apart. The
// control turns the same kept pose by the body rule (Turn.BODY), which is wrong for these two: it must be farther off.
// NTM's own update moves and turns them whatever their AI (the chopper flies and aims after EntityFlying's update, on
// both sides; the bag drifts), and the chopper fires at what is near (the bag dies of any hurt): one at a time, held on
// the server at the end of each world tick (before the tracker sends them) at their place with no motion and the yRot
// the stand wants, and on the client at the start of each frame. The chopper's rotors spin as it is drawn: they are
// left out of the comparison (written down apart). The client's body is not sent: a mob that stands keeps it there up
// to 50 degrees off its yRot (EntityLivingBase.updateDistance). The stand holds the client's body 45 off the yRot
// (LAG) to see that the pose is taken and turned by what the renderer turns the mob by, not by that body.
// run/forge/turn.txt; the game quits.
final class TurnScenario {
    private TurnScenario() {
    }

    // per mob, from its start: placed, turned, checked, removed
    private static final int START = 40, TURN = 100, CHECK = 200, NEXT = 220;
    // apart, as a share of the mob's size (the larger of its width and height): the chopper 8.25, the bag 0.45
    private static final double TOLERANCE = 0.03;
    private static final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    private static volatile int failed;

    private static final class Mob {
        final String name;
        final Function<World, EntityLiving> make;
        final double dx, dy, dz;
        volatile EntityLiving e;
        volatile List<ModelBox> before, kept, body;
        volatile long turnTick;

        Mob(String name, Function<World, EntityLiving> make, double dx, double dy, double dz) {
            this.name = name;
            this.make = make;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    private static final Mob[] MOBS = {
            new Mob("chopper", w -> new com.hbm.entity.mob.EntityHunterChopper(w), 0, 3, 16),
            new Mob("bag", w -> new com.hbm.entity.mob.EntityPlasticBag(w), 1, 1.2, 4)};
    private static volatile Mob current;
    private static volatile float wantYaw = -90F;
    // the client's body held off the yRot the pose is first taken at
    private static final float LAG = 45F;
    private static boolean registered;

    static void step(Minecraft mc, int t) {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new Hold());
        }
        if (t >= START) aim(mc);
        int k = (t - START) / NEXT, at = (t - START) % NEXT;
        if (t < START) return;
        if (k < MOBS.length) {
            Mob m = MOBS[k];
            if (at == 0) ShootScenario.onServer(() -> place(m));
            if (at == TURN) ShootScenario.onServer(() -> turn(m));
            if (at == CHECK) ShootScenario.onServer(() -> check(m));
            if (at == TURN - 1 || at == CHECK - 1) note(mc, m, "t+" + at);
            return;
        }
        if (k == MOBS.length && at == 0) {
            write(mc);
            GunFeel.LOGGER.info("Scenario turn: {}", failed == 0 ? "TURN OK" : "TURN FAIL (" + failed + ")");
            mc.shutdown();
        }
    }

    private static EntityPlayerMP player() {
        return net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
    }

    private static void expect(String what, boolean ok) {
        if (!ok) failed++;
        lines.add((ok ? "OK   " : "FAIL ") + what);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // the last mob removed; this one ahead (+z), facing +x (yRot -90: yaw 0 looks along +z)
    private static void place(Mob m) {
        try {
            Mob last = current;
            if (last != null && last.e != null) last.e.setDead();
            EntityPlayerMP p = player();
            World w = p.world;
            w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
            w.setWorldTime(6000);
            wantYaw = -90F;
            EntityLiving e = m.make.apply(w);
            e.getEntityData().setDouble("holdX", p.posX + m.dx);
            e.getEntityData().setDouble("holdY", p.posY + m.dy);
            e.getEntityData().setDouble("holdZ", p.posZ + m.dz);
            e.enablePersistence();
            e.setNoAI(true);
            e.setNoGravity(true);
            e.setEntityInvulnerable(true);
            e.setLocationAndAngles(p.posX + m.dx, p.posY + m.dy, p.posZ + m.dz, wantYaw, 0F);
            e.renderYawOffset = e.prevRenderYawOffset = wantYaw;
            e.rotationYawHead = e.prevRotationYawHead = wantYaw;
            w.spawnEntity(e);
            m.e = e;
            current = m;
            lines.add("#    " + m.name + ": placed " + e.getEntityId() + " at " + f(e.posX) + "," + f(e.posY) + "," + f(e.posZ));
        } catch (Throwable x) {
            expect(m.name + ": place crashed: " + x, false);
        }
    }

    public static final class Hold {
        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public void tick(net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent ev) {
            if (ev.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END || ev.world.isRemote) return;
            Mob m = current;
            EntityLiving e = m == null ? null : m.e;
            if (e == null || e.world != ev.world) return;
            e.motionX = e.motionY = e.motionZ = 0;
            e.setPosition(e.getEntityData().getDouble("holdX"), e.getEntityData().getDouble("holdY"), e.getEntityData().getDouble("holdZ"));
            e.rotationYaw = e.prevRotationYaw = wantYaw;
            e.rotationYawHead = e.prevRotationYawHead = wantYaw;
            e.setAir(300);
        }

        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public void frame(net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent ev) {
            if (ev.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.START) return;
            Entity c = client();
            if (c == null) return;
            c.rotationYaw = c.prevRotationYaw = wantYaw;
            if (c instanceof EntityLiving) ((EntityLiving) c).renderYawOffset = ((EntityLiving) c).prevRenderYawOffset = -90F + LAG;
        }
    }

    private static Entity client() {
        Mob m = current;
        Minecraft mc = Minecraft.getMinecraft();
        return m == null || m.e == null || mc.world == null ? null : mc.world.getEntityByID(m.e.getEntityId());
    }

    // what NTM's client tick turned the copy to since the last frame's hold
    private static void note(Minecraft mc, Mob m, String when) {
        Entity c = client();
        if (c instanceof EntityLiving)
            lines.add("#    " + m.name + " " + when + ": the client's copy yRot " + f(c.rotationYaw) + " body " + f(((EntityLiving) c).renderYawOffset));
    }

    private static void aim(Minecraft mc) {
        if (mc.player == null) return;
        mc.player.rotationYaw = mc.player.prevRotationYaw = 0F;
        mc.player.rotationPitch = mc.player.prevRotationPitch = -8F;
    }

    // the server turns the mob: yRot +40, body -30, and turns the pose it keeps by the rule, and by the body's
    private static void turn(Mob m) {
        try {
            EntityLiving e = m.e;
            m.turnTick = e.world.getTotalWorldTime();
            m.before = ModelHits.worldBoxes(e);
            expect(m.name + ": a pose came in: " + m.before.size() + " cubes, at " + ModelHits.poseTick(e) + " (now " + m.turnTick + ")", !m.before.isEmpty());
            String was = "yRot " + f(e.rotationYaw) + " body " + f(e.renderYawOffset);
            wantYaw = -50F;
            e.rotationYaw = e.prevRotationYaw = wantYaw;
            e.rotationYawHead = wantYaw;
            e.renderYawOffset -= 30F;
            lines.add("#    " + m.name + ": turned from " + was + " to yRot " + f(e.rotationYaw) + " body " + f(e.renderYawOffset));
            m.kept = ModelHits.worldBoxes(e);
            ModelHits.forcedTurn = ModelHits.Turn.BODY;
            try {
                m.body = ModelHits.worldBoxes(e);
            } finally {
                ModelHits.forcedTurn = null;
            }
        } catch (Throwable x) {
            expect(m.name + ": turn crashed: " + x, false);
        }
    }

    private static void check(Mob m) {
        try {
            EntityLiving e = m.e;
            lines.add("#    " + m.name + ": alive " + e.isEntityAlive() + " at " + f(e.posX) + "," + f(e.posY) + "," + f(e.posZ) + " yRot " + f(e.rotationYaw)
                    + " body " + f(e.renderYawOffset) + " size " + f(e.width) + "x" + f(e.height));
            long tick = ModelHits.poseTick(e);
            expect(m.name + ": a fresh pose after the turn: at " + tick + " (turned at " + m.turnTick + ")", tick > m.turnTick);
            compare(m, Math.max(e.width, e.height), ModelHits.worldBoxes(e));
        } catch (Throwable x) {
            expect(m.name + ": check crashed: " + x, false);
        }
    }

    private static boolean spins(ModelBox b) {
        return b.part().contains("Rotor");
    }

    private static void compare(Mob m, double size, List<ModelBox> fresh) {
        double tol = TOLERANCE * size;
        double still = apart(fresh, m.before, false), rule = apart(fresh, m.kept, false), control = apart(fresh, m.body, false);
        lines.add("#    " + m.name + ": fresh " + fresh.size() + " cubes; apart (rotors left out) from the pose before the turn " + f(still)
                + ", turned by the rule " + f(rule) + ", by the body " + f(control) + "; the rotors by the rule " + f(apart(fresh, m.kept, true)));
        lines.add("#    " + m.name + ": by the rule " + perPart(fresh, m.kept) + "; before " + perPart(fresh, m.before));
        expect(m.name + ": the model turned (the pose before the turn is off): " + f(still) + " > " + f(3 * tol), still > 3 * tol);
        expect(m.name + ": the kept pose turned by its rule is where the fresh one is: " + f(rule) + " <= " + f(tol), rule <= tol);
        expect(m.name + ": control, turned by the body it is farther off: " + f(control) + " > " + f(3 * tol), control > 3 * tol);
    }

    // the farthest corner of a cube from its match (rotors: only those, else all but those); infinite when the poses do
    // not match cube by cube; 0 when there is nothing to compare
    private static double apart(List<ModelBox> a, List<ModelBox> b, boolean rotors) {
        if (a == null || b == null || a.isEmpty() || a.size() != b.size()) return Double.POSITIVE_INFINITY;
        double worst = 0;
        for (int i = 0; i < a.size(); i++) {
            ModelBox p = a.get(i), q = b.get(i);
            if (!p.part().equals(q.part())) return Double.POSITIVE_INFINITY;
            if (spins(p) != rotors) continue;
            worst = Math.max(worst, corners(p, q));
        }
        return worst;
    }

    private static double corners(ModelBox p, ModelBox q) {
        double worst = 0;
        for (int c = 0; c < 8; c++) {
            int u = c & 1, v = (c >> 1) & 1, w = (c >> 2) & 1;
            double dx = (p.x() + u * p.ux() + v * p.vx() + w * p.wx()) - (q.x() + u * q.ux() + v * q.vx() + w * q.wx());
            double dy = (p.y() + u * p.uy() + v * p.vy() + w * p.wy()) - (q.y() + u * q.uy() + v * q.vy() + w * q.wy());
            double dz = (p.z() + u * p.uz() + v * p.vz() + w * p.wz()) - (q.z() + u * q.uz() + v * q.vz() + w * q.wz());
            worst = Math.max(worst, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return worst;
    }

    // per part: its farthest corner apart; the median and the worst parts
    private static String perPart(List<ModelBox> a, List<ModelBox> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) return "-";
        Map<String, Double> worst = new TreeMap<String, Double>();
        List<Double> all = new ArrayList<Double>();
        for (int i = 0; i < a.size(); i++) {
            double x = corners(a.get(i), b.get(i));
            all.add(x);
            worst.merge(a.get(i).part(), x, Math::max);
        }
        Collections.sort(all);
        List<Map.Entry<String, Double>> e = new ArrayList<Map.Entry<String, Double>>(worst.entrySet());
        e.sort((p, q) -> Double.compare(q.getValue(), p.getValue()));
        StringBuilder sb = new StringBuilder("median " + f(all.get(all.size() / 2)) + " worst");
        for (int i = 0; i < Math.min(4, e.size()); i++) sb.append(' ').append(e.get(i).getKey()).append('=').append(f(e.get(i).getValue()));
        return sb.toString();
    }

    private static void write(Minecraft mc) {
        lines.add("result " + (failed == 0 ? "OK" : "FAIL (" + failed + ")"));
        try (PrintWriter pw = new PrintWriter(new File(mc.gameDir, "turn.txt"), StandardCharsets.UTF_8.name())) {
            synchronized (lines) {
                for (String l : lines) pw.println(l);
            }
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario turn: turn.txt", e);
        }
    }
}
