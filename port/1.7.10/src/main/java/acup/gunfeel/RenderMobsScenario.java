package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

// Dev (-Pscenario=rendermobs, ShootScenario's flat world): NTM 1.7.10's living mobs whose renderer is a plain Render
// (the hunter chopper, the plastic bag, the worm's head and a piece of it, the UFO, the FBI drone), one by one, on the
// server, held in front of the camera (pin: where they stand and how they are turned, every tick before the tracker
// sends them). The client draws them and sends their poses (ModelHitClient.beginRender). Then the server turns the mob,
// yRot +40 and the body -30, as main's MobSmoke checkTurn does, and later takes the client's fresh pose. The pose kept
// from before the turn, turned by the angle that fits it best onto the fresh one, says how far the drawn model turned;
// ModelHits.Turn's angle for the mob has to be that one, and the body's alone (the rule before) must not be, so the
// row proves the boxes tell the two apart. run/client/rendermobs.txt: a line a mob and "RENDERMOBS OK" or "FAIL (n)".
final class RenderMobsScenario {
    private RenderMobsScenario() {
    }

    // byMotion: the mob sets its yRot from its motion on both sides every tick (EntityBOTPrimeHead.onUpdate:
    // atan2(motionX, motionZ)), so it is turned by its motion, which the tracker sends; the others by yRot itself
    private record Case(String name, String type, boolean byMotion) {}
    private static final List<Case> CASES = List.of(
            new Case("вертолёт", "com.hbm.entity.mob.EntityHunterChopper", false),
            new Case("пакетик", "com.hbm.entity.mob.EntityPlasticBag", false),
            new Case("голова червя", "com.hbm.entity.mob.botprime.EntityBOTPrimeHead", true),
            new Case("тело червя", "com.hbm.entity.mob.botprime.EntityBOTPrimeBody", false),
            new Case("НЛО", "com.hbm.entity.mob.EntityUFO", false),
            new Case("дрон", "com.hbm.entity.mob.EntityFBIDrone", false));

    private static final int START = 40, KEEP = 50, CHECK = 95, NEXT = 110;
    private static final float YAW = 90F, TURN = 40F, BODY_TURN = -30F;

    private static final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    private static volatile int failed, verdicts;
    private static int index = -1, at;
    // the server's: the mob shown, where it is held and how it is turned
    private static volatile EntityLiving mob;
    private static volatile double px, py, pz;
    private static volatile float yaw, body;
    private static volatile boolean byMotion;
    private static Object kept;

    static void step(Minecraft mc, int t) {
        if (t < START) return;
        if (index >= CASES.size()) {
            if (t - at == 20) finish(mc);
            return;
        }
        if (index < 0 || t - at >= NEXT) {
            // one task a tick goes to the server (ShootScenario.onServer): the last mob's removal with the next one's spawn
            boolean first = index < 0;
            at = t;
            if (++index >= CASES.size()) {
                ShootScenario.onServer(RenderMobsScenario::remove);
                return;
            }
            Case c = CASES.get(index);
            ShootScenario.onServer(() -> {
                if (!first) remove();
                spawn(c);
            });
            return;
        }
        aim(mc);
        Case c = CASES.get(index);
        if (t - at == KEEP) ShootScenario.onServer(() -> keep(c));
        if (t - at > KEEP && t - at < CHECK && (t - at - KEEP) % 10 == 0) ShootScenario.onServer(RenderMobsScenario::resend);
        if (t - at == CHECK) ShootScenario.onServer(() -> check(c));
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
    }

    private static void expect(String what, boolean ok) {
        verdicts++;
        if (!ok) failed++;
        lines.add((ok ? "OK   " : "FAIL ") + what);
    }

    // server: the mob ahead of the player (+z), at its own size's distance, in the air, turned to YAW
    private static void spawn(Case c) {
        EntityPlayerMP p = player();
        World w = p.worldObj;
        w.getGameRules().setOrCreateGameRule("mobGriefing", "false");
        try {
            Entity e = (Entity) Class.forName(c.type()).getConstructor(World.class).newInstance(w);
            EntityLiving living = (EntityLiving) e;
            double d = 4 + Math.max(living.width, living.height) * 1.5;
            px = Math.floor(p.posX) + .5;
            py = Math.floor(p.boundingBox.minY) + 1.5;
            pz = Math.floor(p.posZ) + .5 + d;
            yaw = YAW;
            body = YAW;
            byMotion = c.byMotion();
            kept = null;
            living.setLocationAndAngles(px, py, pz, yaw, 0F);
            hold(living);
            w.spawnEntityInWorld(living);
            mob = living;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            mob = null;
            expect(c.name() + ": не создан: " + ex, false);
        }
    }

    // a world's tick on the server, before the tracker (ShootScenario.worldTick): the mob held
    static void pin(World w) {
        EntityLiving m = mob;
        if (m != null && m.worldObj == w && !m.isDead) hold(m);
    }

    private static void hold(EntityLiving m) {
        m.setPosition(px, py, pz);
        m.prevPosX = m.lastTickPosX = px;
        m.prevPosY = m.lastTickPosY = py;
        m.prevPosZ = m.lastTickPosZ = pz;
        double speed = byMotion ? .08 : 0;
        m.motionX = speed * Math.sin(Math.toRadians(yaw));
        m.motionY = 0;
        m.motionZ = speed * Math.cos(Math.toRadians(yaw));
        m.rotationYaw = m.prevRotationYaw = yaw;
        m.renderYawOffset = m.prevRenderYawOffset = body;
        m.rotationYawHead = m.prevRotationYawHead = body;
        m.rotationPitch = m.prevRotationPitch = 0F;
        m.fallDistance = 0;
        m.setAttackTarget(null);
        m.setHealth(m.getMaxHealth());
    }

    // server: every living thing but the player near the stand (a worm's head grows its pieces)
    private static void remove() {
        EntityPlayerMP p = player();
        List<Entity> all = new ArrayList<Entity>(p.worldObj.loadedEntityList);
        for (Entity e : all) if (e != p && e.getDistanceSqToEntity(p) < 64 * 64 && !(e instanceof EntityPlayerMP)) e.setDead();
        mob = null;
    }

    // server: the pose the client sent before the turn; then the turn
    private static void keep(Case c) {
        EntityLiving m = mob;
        if (m == null) return;
        kept = ModelHits.standPose(m);
        if (kept == null) {
            expect(c.name() + ": нет позы на сервере до поворота", false);
            return;
        }
        List<ModelBox> boxes = ModelHits.standBoxes(kept);
        lines.add("     " + c.name() + ": поза до поворота, коробок " + boxes.size() + ", части " + names(boxes)
                + ", правило " + ModelHits.turn(m.getClass()) + angles(m, kept));
        yaw = YAW + TURN;
        body = YAW + BODY_TURN;
        hold(m);
    }

    // server: the mob's place and turn sent again. 1.7.10's tracker sends a bare move every 60 ticks, and the client takes
    // its own yRot of the moment as the move's (NetHandlerPlayClient.handleEntityMovement): one that comes while the
    // client still eases into the turn stops it there, a third or two short (rm2, rm5: the chopper drawn at 116.3 with
    // the server at 130). That is vanilla's, not the rule's; a teleport later takes the client the rest of the way
    private static void resend() {
        EntityLiving m = mob;
        if (m != null && m.worldObj instanceof net.minecraft.world.WorldServer ws)
            ws.getEntityTracker().func_151247_a(m, new net.minecraft.network.play.server.S18PacketEntityTeleport(m));
    }

    // server: the fresh pose against the kept one turned
    private static void check(Case c) {
        EntityLiving m = mob;
        if (m == null || kept == null) return;
        Object fresh = ModelHits.standPose(m);
        if (fresh == null || ModelHits.standTick(fresh) <= ModelHits.standTick(kept) + 10) {
            expect(c.name() + ": нет свежей позы после поворота", false);
            return;
        }
        lines.add("     " + c.name() + ": свежая поза" + angles(m, fresh));
        List<ModelBox> was = ModelHits.standBoxes(kept), now = ModelHits.standBoxes(fresh);
        if (was.size() != now.size()) {
            expect(c.name() + ": число коробок разное: " + was.size() + " и " + now.size(), false);
            return;
        }
        // the fresh pose as it was drawn: the mob has been held since, only the UFO's spin goes on (its rule counts ticks,
        // the client's own); the rule's angle for it would carry the rule's error into the reference
        double freshAngle = ModelHits.turn(m.getClass()) == ModelHits.Turn.SPIN ? ModelHits.standAngle(m, fresh, false) : 0;
        List<ModelBox> target = turned(now, freshAngle);
        double rule = Math.toDegrees(ModelHits.standAngle(m, kept, false));
        double bodyRule = Math.toDegrees(ModelHits.standAngle(m, kept, true));
        int best = 0;
        double bestE = Double.MAX_VALUE, worstE = 0;
        for (int deg = -180; deg < 180; deg++) {
            double e = error(turned(was, Math.toRadians(deg)), target);
            if (e < bestE) { bestE = e; best = deg; }
            worstE = Math.max(worstE, e);
        }
        boolean fitted = Math.sqrt(worstE) - Math.sqrt(bestE) > .05;
        // the UFO spins 5 degrees a tick: the server takes the two poses in a tick sooner or later than the client drew them
        double tolerance = ModelHits.turn(m.getClass()) == ModelHits.Turn.SPIN ? 11 : 3;
        double ruleOff = Math.abs(MathHelper.wrapAngleTo180_double(rule - best));
        double bodyOff = Math.abs(MathHelper.wrapAngleTo180_double(bodyRule - best));
        double ruleE = Math.sqrt(error(turned(was, Math.toRadians(rule)), target));
        expect(String.format(Locale.ROOT, "%s: модель повернулась на %d° (остаток %.3f), по правилу %s %.1f° (остаток %.3f), по телу %.1f°%s",
                c.name(), best, Math.sqrt(bestE), ModelHits.turn(m.getClass()), rule, ruleE, bodyRule,
                fitted ? "" : ", коробки поворота не различают"), fitted && ruleOff <= tolerance && bodyOff > tolerance);
    }

    // the angles of a pose (the one it was taken at, the server's yRot when it came in) and of the client's mob now
    private static String angles(EntityLiving m, Object pose) {
        Entity drawn = Minecraft.getMinecraft().theWorld == null ? null : Minecraft.getMinecraft().theWorld.getEntityByID(m.getEntityId());
        return String.format(Locale.ROOT, "; снята при %.1f, yRot сервера при приёме %.1f; клиент сейчас yRot %.1f, тело %.1f",
                ModelHits.standYaw(pose), ModelHits.standYRot(pose), drawn == null ? Float.NaN : drawn.rotationYaw,
                drawn instanceof EntityLiving l ? l.renderYawOffset : Float.NaN);
    }

    // the boxes about the origin turned by angle as ModelHits.world turns them
    private static List<ModelBox> turned(List<ModelBox> boxes, double angle) {
        double cos = Math.cos(-angle), sin = Math.sin(-angle);
        List<ModelBox> out = new ArrayList<ModelBox>(boxes.size());
        for (ModelBox b : boxes) {
            double[] o = turn(b.x(), b.z(), cos, sin), u = turn(b.ux(), b.uz(), cos, sin), v = turn(b.vx(), b.vz(), cos, sin), w = turn(b.wx(), b.wz(), cos, sin);
            out.add(new ModelBox(b.part(), o[0], b.y(), o[1], u[0], b.uy(), u[1], v[0], b.vy(), v[1], w[0], b.wy(), w[1], b.mesh()));
        }
        return out;
    }

    private static double[] turn(double x, double z, double cos, double sin) {
        return new double[] {cos * x + sin * z, -sin * x + cos * z};
    }

    // the mean square distance of the boxes' corner and its three edge ends
    private static double error(List<ModelBox> a, List<ModelBox> b) {
        double e = 0;
        for (int i = 0; i < a.size(); i++) {
            ModelBox p = a.get(i), q = b.get(i);
            e += sq(p.x() - q.x(), p.y() - q.y(), p.z() - q.z());
            e += sq(p.x() + p.ux() - q.x() - q.ux(), p.y() + p.uy() - q.y() - q.uy(), p.z() + p.uz() - q.z() - q.uz());
            e += sq(p.x() + p.vx() - q.x() - q.vx(), p.y() + p.vy() - q.y() - q.vy(), p.z() + p.vz() - q.z() - q.vz());
            e += sq(p.x() + p.wx() - q.x() - q.wx(), p.y() + p.wy() - q.y() - q.wy(), p.z() + p.wz() - q.z() - q.wz());
        }
        return e / Math.max(1, 4 * a.size());
    }

    private static double sq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    private static String names(List<ModelBox> boxes) {
        List<String> out = new ArrayList<String>();
        for (ModelBox b : boxes) if (!out.contains(b.part()) && out.size() < 4) out.add(b.part());
        return String.join(", ", out);
    }

    // looks at the mob's middle, so the client draws it and sends its pose
    private static void aim(Minecraft mc) {
        if (mc.thePlayer == null || index < 0) return;
        double dz = pz - mc.thePlayer.posZ, dy = py + .5 - (mc.thePlayer.boundingBox.minY + mc.thePlayer.getEyeHeight());
        mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0F;
        mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = (float) -Math.toDegrees(Math.atan2(dy, dz));
    }

    private static void finish(Minecraft mc) {
        // a mob gone between its pose and its check writes nothing: every mob has to have its line
        if (verdicts < CASES.size()) {
            failed++;
            lines.add("FAIL строк с итогом " + verdicts + " из " + CASES.size());
        }
        String verdict = failed == 0 ? "RENDERMOBS OK" : "RENDERMOBS FAIL (" + failed + ")";
        lines.add(verdict);
        try (PrintWriter pw = new PrintWriter(new File(mc.mcDataDir, "rendermobs.txt"), StandardCharsets.UTF_8.name())) {
            for (String l : new ArrayList<String>(lines)) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario rendermobs", e);
        }
        GunFeel.LOGGER.info("Scenario rendermobs: {}", verdict);
        mc.shutdown();
    }
}
