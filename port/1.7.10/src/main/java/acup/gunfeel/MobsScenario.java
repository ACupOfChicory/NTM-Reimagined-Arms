package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;

// Dev (-Pscenario=mobs, ShootScenario's flat world): every living entity type of the registry, one by one, put in the
// client's world only (no AI: the server does not know it) in front of the camera, drawn for a few frames, and what
// the model hitboxes took from it written down: the parts and their names, the head zones, or, when nothing was
// taken, the renderer and its model. run/client/mobs.tsv has a line a type; the game quits.
// -Dntmgunfeel.mobs=a,b limits it to those names.
final class MobsScenario {
    private MobsScenario() {
    }

    private static final int START = 40, DRAW = 12;
    private static final int ID_BASE = -7_710_000;

    private static List<String> names;
    private static int next, since;
    private static EntityLiving shown;
    private static final Map<String, String> ROWS = new TreeMap<>();
    // name -> the pose as JSON (run/client/mobs-poses.jsonl): HeadsPortTest resolves heads on these offline
    private static final Map<String, String> DUMP = new TreeMap<>();
    private static int none, noHead;

    static void step(Minecraft mc, int t) {
        if (t < START) return;
        if (names == null) names = names();
        if (shown != null) {
            if (++since < DRAW) {
                aim(mc, shown);
                return;
            }
            record(shown);
            mc.theWorld.removeEntityFromWorld(shown.getEntityId());
            shown = null;
        }
        if (next >= names.size()) {
            if (next == names.size()) finish(mc);
            next++;
            return;
        }
        String name = names.get(next++);
        try {
            Entity e = EntityList.createEntityByName(name, mc.theWorld);
            if (!(e instanceof EntityLiving)) {
                ROWS.put(name, name + "\t-\tnot made\t\t\t\t");
                return;
            }
            EntityLiving living = (EntityLiving) e;
            double d = 3 + Math.max(living.width, living.height) * 1.5;
            living.setLocationAndAngles(Math.floor(mc.thePlayer.posX) + 0.5, Math.floor(mc.thePlayer.boundingBox.minY),
                    Math.floor(mc.thePlayer.posZ) + 0.5 + d, 180F, 0F);
            living.rotationYawHead = living.prevRotationYawHead = living.renderYawOffset = living.prevRenderYawOffset = 180F;
            // Et Futurum's shulker draws its head only open (ShulkerRenderer.renderModel): its peek, data watcher 13
            if (living.getClass().getName().equals("ganymedes01.etfuturum.entities.EntityShulker"))
                living.getDataWatcher().updateObject(13, (byte) 30);
            mc.theWorld.addEntityToWorld(ID_BASE - next, living);
            shown = living;
            since = 0;
            aim(mc, living);
        } catch (Throwable ex) {
            ROWS.put(name, name + "\t-\tfailed: " + ex.toString().replace('\t', ' ') + "\t\t\t\t");
            GunFeel.LOGGER.warn("Scenario mobs: {} failed", name, ex);
        }
    }

    // looks at the middle of the mob, straight ahead (+z)
    private static void aim(Minecraft mc, EntityLiving e) {
        double dz = e.posZ - mc.thePlayer.posZ, dy = e.posY + e.height / 2 - (mc.thePlayer.posY);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dz));
        mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0F;
        mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
        e.motionX = e.motionY = e.motionZ = 0;
    }

    @SuppressWarnings("unchecked")
    private static List<String> names() {
        String only = System.getProperty("ntmgunfeel.mobs", "").trim();
        List<String> out = new ArrayList<>();
        if (!only.isEmpty()) {
            for (String s : only.split(",")) out.add(s.trim());
            return out;
        }
        for (Map.Entry<String, Class<? extends Entity>> en : ((Map<String, Class<? extends Entity>>) EntityList.stringToClassMapping).entrySet()) {
            Class<? extends Entity> c = en.getValue();
            if (c != null && EntityLiving.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers())) out.add(en.getKey());
        }
        java.util.Collections.sort(out);
        GunFeel.LOGGER.info("Scenario mobs: {} living types", out.size());
        return out;
    }

    private static void record(EntityLiving e) {
        String name = EntityList.getEntityString(e);
        Render r = RenderManager.instance.getEntityRenderObject(e);
        String renderer = r == null ? "none" : r.getClass().getName();
        String model = "";
        if (r instanceof RendererLivingEntity) {
            ModelBase m = mainModel((RendererLivingEntity) r);
            model = m == null ? "null" : m.getClass().getName();
        } else if (r != null) {
            model = "not RendererLivingEntity";
        }
        List<ModelBox> boxes = ModelHits.worldBoxes(e);
        TreeSet<String> parts = new TreeSet<>();
        for (ModelBox b : boxes) parts.add(b.part());
        int heads = ModelHits.headZones(e).size();
        String why = ModelHits.headResolution(e).toString();
        if (boxes.isEmpty()) none++;
        else if (heads == 0) noHead++;
        String row = name + "\t" + e.getClass().getName() + "\t" + boxes.size() + "\t" + heads + "\t" + renderer + "\t" + model + "\t"
                + String.join(" ", parts) + "\t" + why.replace('\t', ' ');
        ROWS.put(name, row);
        List<ModelBox> pose = ModelHits.poseBoxes(e);
        if (pose != null) DUMP.put(name, dump(name, ModelHeads.key(e), ModelHits.poseYaw(e), pose));
        GunFeel.LOGGER.info("Scenario mobs: {} {} cubes, {} head zones, {} ({})", name, boxes.size(), heads, renderer, model);
    }

    private static String dump(String name, String key, float yaw, List<ModelBox> boxes) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("name", name);
        o.addProperty("key", key);
        o.addProperty("yaw", yaw);
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        for (ModelBox b : boxes) {
            com.google.gson.JsonObject j = new com.google.gson.JsonObject();
            j.addProperty("part", b.part());
            com.google.gson.JsonArray n = new com.google.gson.JsonArray();
            for (double d : new double[] {b.x(), b.y(), b.z(), b.ux(), b.uy(), b.uz(), b.vx(), b.vy(), b.vz(), b.wx(), b.wy(), b.wz()})
                n.add(new com.google.gson.JsonPrimitive(d));
            j.add("n", n);
            ModelMesh mesh = b.mesh() == 0 ? null : ModelMesh.get(b.mesh());
            if (mesh != null) {
                com.google.gson.JsonArray t = new com.google.gson.JsonArray();
                for (float f : mesh.triangles()) t.add(new com.google.gson.JsonPrimitive(f));
                j.add("mesh", t);
            }
            list.add(j);
        }
        o.add("boxes", list);
        return o.toString();
    }

    private static Field mainModelField;

    private static ModelBase mainModel(RendererLivingEntity r) {
        try {
            if (mainModelField == null) {
                for (Field f : RendererLivingEntity.class.getDeclaredFields())
                    if (f.getType() == ModelBase.class) {
                        f.setAccessible(true);
                        mainModelField = f;
                        break;
                    }
            }
            return (ModelBase) mainModelField.get(r);
        } catch (Exception ex) {
            return null;
        }
    }

    private static void finish(Minecraft mc) {
        File out = new File(mc.mcDataDir, "mobs.tsv");
        try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8.name())) {
            w.println("# name\tclass\tcubes\thead zones\trenderer\tmain model\tparts\thead resolution");
            for (String row : ROWS.values()) w.println(row);
        } catch (Exception ex) {
            GunFeel.LOGGER.warn("Scenario mobs: {} not written", out, ex);
        }
        File poses = new File(mc.mcDataDir, "mobs-poses.jsonl");
        try (PrintWriter w = new PrintWriter(poses, StandardCharsets.UTF_8.name())) {
            for (String line : DUMP.values()) w.println(line);
        } catch (Exception ex) {
            GunFeel.LOGGER.warn("Scenario mobs: {} not written", poses, ex);
        }
        GunFeel.LOGGER.info(String.format(Locale.ROOT, "Scenario mobs: %d types, %d without a model, %d without a head zone; %s", ROWS.size(), none, noHead, out));
        mc.shutdown();
    }
}
