package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.mojang.blaze3d.platform.NativeImage;

import acup.gunfeel.ai.AiPose;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * -PbotHandStand: the layer armour at the hands, an FBI agent against the player in the same set. Each in its own lit
 * room, each pose, each hand seen close from five sides. While a shot is taken the set's outer texture is swapped for
 * one that writes its own texel into the colour (u in red, v in green, against a full blue: the shading scales all
 * three alike), the leggings' for a clear one: an armour pixel on the frame names the texel it came from. Round the
 * hand (the limb's far end, at the frame's middle) the stand counts the armour pixels by the part of the texture they
 * come from, and the pixels the armour changes against the same shot with no armour at all. Lines go to
 * {@code <label>-hands.tsv}, the shots with the real textures, cut round the hand, to {@code <label>-hands/}.
 */
final class BotHandStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.botHandStand");
    private static final BlockPos O = new BlockPos(0, 100, 0);
    /** the agent's and the player's places, each in a room of its own */
    private static final double BOT_Z = 4.5, PLAYER_Z = 20.5;
    /** the camera's distance from the hand, blocks; the circle round the hand counted, px of the frame */
    private static final double DIST = 1.2;
    private static final int RADIUS = 180, CROP = 400;

    private record Set_(String name, BotStandHits.Loadout loadout, Identifier asset) {
    }

    private static final Set_[] SETS = { new Set_("NONE", BotStandHits.LOADOUTS[0], null),
            new Set_("STEEL", BotStandHits.STEEL, Identifier.fromNamespaceAndPath("hbm", "steel")),
            new Set_("CMB", BotStandHits.CMB_LAYER, Identifier.fromNamespaceAndPath("hbm", "cmb")),
            new Set_("DIAMOND", BotStandHits.DIAMOND, Identifier.withDefaultNamespace("diamond")) };

    /** a subject's pose: the agent's arms (AiPose) or the player's, the gun in its hand or not */
    private record PoseCase(String name, boolean bot, byte stance, byte arms, boolean gun) {
    }

    private static final PoseCase[] POSES = { new PoseCase("bot_hold", true, AiPose.STAND, AiPose.HOLD, true),
            new PoseCase("bot_aim", true, AiPose.STAND, AiPose.AIM, true), new PoseCase("bot_stack", true, AiPose.STAND, AiPose.STACK, true),
            new PoseCase("bot_kneel_aim", true, AiPose.CROUCH, AiPose.AIM, true),
            new PoseCase("bot_down", true, AiPose.STAND, AiPose.NO_GUN, false),
            new PoseCase("player_gun", false, AiPose.STAND, (byte) 0, true), new PoseCase("player_down", false, AiPose.STAND, (byte) 0, false) };

    /** the views: name, turn from the subject's front (0: in front of it, 90: on the hand's side), height, degrees */
    private static final String[] VIEW_NAMES = { "front", "back", "outside", "front_outside_high", "front_low" };
    private static final float[][] VIEWS = { { 0F, 15F }, { 180F, 15F }, { 90F, 15F }, { 45F, 35F }, { 0F, -30F } };

    private static Mob bot;
    private static ArmorStand eye;
    private static int setIndex, poseIndex, handIndex, viewIndex, phase, wait, misses;
    private static long until;
    private static final List<String> ROWS = new ArrayList<>();
    /** per set and pose: the armour pixels round the hand, summed over the hands and views */
    private static final Map<String, int[]> SUM = new TreeMap<>();
    private static boolean anyCoded;
    private static int pending;

    private BotHandStand() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static long now() {
        return mc().level.getGameTime();
    }

    static void start(Mob agent) {
        bot = agent;
        Minecraft mc = mc();
        mc.options.fov().set(30);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        StringBuilder head = new StringBuilder("набор\tпоза\tрука\tвид\tброни_в_кадре");
        for (int r : RINGS) {
            for (String g : REGION) head.append("\tброня_").append(g).append('_').append(r);
            for (String g : REGION) head.append("\tкожа_").append(g).append('_').append(r);
            head.append("\tпрочее_").append(r).append("\tкрая_").append(r);
        }
        ROWS.add(head + "\tкисть\tкамера\tброня_тексели_" + RINGS[0] + "\tкожа_тексели_" + RINGS[0]);
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            room(level, BOT_Z);
            room(level, PLAYER_Z);
            var p = server.getPlayerList().getPlayers().getFirst();
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.teleportTo(level, 0.5, O.getY(), PLAYER_Z, Set.of(), 180F, 0F, false);
            Mob m = (Mob) level.getEntity(agent.getId());
            m.snapTo(0.5, O.getY(), BOT_Z, 180F, 0F);
        }).join();
        eye = new ArmorStand(mc.level, 0.5, O.getY(), BOT_Z);
        eye.setInvisible(true);
        setIndex = poseIndex = handIndex = viewIndex = phase = 0;
        setPose();
    }

    /** a room of glowstone round a subject at z: lit through, nothing blue behind it */
    private static void room(ServerLevel level, double z) {
        int cz = (int) Math.floor(z);
        for (int x = -3; x <= 4; x++) for (int dz = -4; dz <= 4; dz++) for (int y = -1; y <= 4; y++) {
            boolean wall = x == -3 || x == 4 || dz == -4 || dz == 4 || y == -1 || y == 4;
            level.setBlock(O.offset(x, y, cz + dz), wall ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static Entity subject() {
        return POSES[poseIndex].bot() ? mc().level.getEntity(bot.getId()) : mc().player;
    }

    /** the set on both and the pose on the subject; the textures as the set has them */
    private static void setPose() {
        Set_ s = SETS[setIndex];
        PoseCase c = POSES[poseIndex];
        var server = mc().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            Mob m = (Mob) level.getEntity(bot.getId());
            var p = server.getPlayerList().getPlayers().getFirst();
            BotStandHits.wear(m, s.loadout());
            for (var slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET })
                p.setItemSlot(slot, m.getItemBySlot(slot).copy());
            ItemStack gun = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_carbine")));
            if (c.bot()) {
                m.setItemSlot(EquipmentSlot.MAINHAND, c.gun() ? gun : ItemStack.EMPTY);
                m.setYRot(180F);
                m.setYBodyRot(180F);
                m.setYHeadRot(180F);
                int at = (int) level.getGameTime();
                m.setData(AiPose.Sync.TYPE, new AiPose(c.stance(), (byte) 0, (byte) 0, at, c.arms(), AiPose.NONE, 0, (short) 0, AiPose.FIGHT));
                m.setPose(c.stance() == AiPose.CROUCH ? Pose.CROUCHING : Pose.STANDING);
            } else {
                p.setItemSlot(EquipmentSlot.MAINHAND, c.gun() ? gun : ItemStack.EMPTY);
                p.teleportTo(level, 0.5, O.getY(), PLAYER_Z, Set.of(), 180F, 0F, false);
            }
        }).join();
        until = now() + 30;
        phase = 0;
    }

    static void frame() {
        if (now() < until) return;
        Entity who = subject();
        if (who == null) {
            ClientSmoke.check(false, "кисти: клиент не видит " + POSES[poseIndex].name());
            next();
            return;
        }
        switch (phase) {
            case 0 -> {
                // the camera on the hand from this view, the coded texture; first in front of the subject, so it is
                // drawn and its pose caught
                if (mc().getCameraEntity() != eye) mc().setCameraEntity(eye);
                if (!aim(who)) {
                    if (++misses < 20) {
                        place(new Vec3(who.getX(), who.getY() + 1.2, who.getZ() - 2.0), 0F, 0F);
                        return;
                    }
                    java.util.TreeSet<String> names = new java.util.TreeSet<>();
                    for (ModelBox box : ModelHits.worldBoxes(who)) names.add(box.part());
                    ClientSmoke.check(false, "кисти: нет коробок руки " + key() + " " + names);
                    misses = 0;
                    next();
                    return;
                }
                misses = 0;
                wait = 3;
                textures(WHITE);
                phase = 1;
            }
            case 1 -> {
                if (--wait > 0) return;
                String k = key();
                pending++;
                net.minecraft.client.Screenshot.takeScreenshot(mc().gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        WHITE_SHOT.put(k, image.getPixels());
                    } finally {
                        pending--;
                    }
                });
                textures(CODE);
                wait = 3;
                phase = 2;
            }
            case 2 -> {
                if (--wait > 0) return;
                String k = key();
                Set_ s = SETS[setIndex];
                pending++;
                net.minecraft.client.Screenshot.takeScreenshot(mc().gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        coded(k, s, image);
                    } finally {
                        pending--;
                    }
                });
                textures(REAL);
                wait = 3;
                phase = 3;
            }
            case 3 -> {
                if (--wait > 0) return;
                String k = key();
                pending++;
                net.minecraft.client.Screenshot.takeScreenshot(mc().gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        real(k, image);
                    } finally {
                        pending--;
                    }
                });
                phase = 4;
                wait = 2;
            }
            default -> {
                if (--wait > 0) return;
                next();
            }
        }
    }

    private static String key() {
        return POSES[poseIndex].name() + "\t" + (handIndex == 0 ? "right" : "left") + "\t" + VIEW_NAMES[viewIndex];
    }

    private static void next() {
        phase = 0;
        if (++viewIndex < VIEWS.length) return;
        viewIndex = 0;
        if (++handIndex < 2) return;
        handIndex = 0;
        if (++poseIndex < POSES.length) {
            setPose();
            return;
        }
        poseIndex = 0;
        if (++setIndex < SETS.length) {
            setPose();
            return;
        }
        finish();
    }

    // ---------------------------------------------------------------- the camera

    /** the eye put DIST from the subject's hand on this view, looking at it */
    private static boolean aim(Entity who) {
        var boxes = ModelHits.worldBoxes(who);
        String arm = handIndex == 0 ? "root/right_arm" : "root/left_arm";
        boolean isBot = POSES[poseIndex].bot();
        ModelBox limb = largest(boxes, isBot ? arm + "/forearm" : arm), upper = largest(boxes, isBot ? arm : "root/body");
        if (limb == null || upper == null) return false;
        Vec3 ref = isBot ? ModelContours.point(upper, .5, .5, .5) : top(upper);
        Vec3[] ends = ends(limb);
        Vec3 hand = ends[0].distanceTo(ref) > ends[1].distanceTo(ref) ? ends[0] : ends[1];
        // the subject faces yaw 180 (north); the hand's side: right is east
        double front = Math.toRadians(180F + VIEWS[viewIndex][0] * (handIndex == 0 ? -1 : 1));
        double elev = Math.toRadians(VIEWS[viewIndex][1]);
        // a direction at yaw a points (-sin a, cos a)
        Vec3 dir = new Vec3(-Math.sin(front) * Math.cos(elev), Math.sin(elev), Math.cos(front) * Math.cos(elev));
        Vec3 cam = hand.add(dir.scale(DIST));
        if (cam.y < O.getY() + 0.1) cam = new Vec3(cam.x, O.getY() + 0.1, cam.z);
        Vec3 look = hand.subtract(cam);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-look.y, Math.hypot(look.x, look.z)));
        place(cam, yaw, pitch);
        AT.put(key(), String.format(Locale.ROOT, "%.2f,%.2f,%.2f\t%.2f,%.2f,%.2f", hand.x, hand.y - O.getY(), hand.z, cam.x, cam.y - O.getY(), cam.z));
        return true;
    }

    /** the eye at this place (its eye's), turned so */
    private static void place(Vec3 cam, float yaw, float pitch) {
        eye.setPos(cam.x, cam.y - eye.getEyeHeight(), cam.z);
        eye.setYRot(yaw);
        eye.setXRot(pitch);
        eye.setYHeadRot(yaw);
        eye.setOldPosAndRot();
        eye.yHeadRotO = yaw;
    }

    private static ModelBox largest(List<ModelBox> boxes, String part) {
        ModelBox best = null;
        double size = -1;
        for (ModelBox b : boxes) {
            if (!b.part().equals(part)) continue;
            double s = len(b.ux(), b.uy(), b.uz()) * len(b.vx(), b.vy(), b.vz()) * len(b.wx(), b.wy(), b.wz());
            if (s > size) {
                size = s;
                best = b;
            }
        }
        return best;
    }

    private static double len(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** the box's two end centres along its longest edge */
    private static Vec3[] ends(ModelBox b) {
        double u = len(b.ux(), b.uy(), b.uz()), v = len(b.vx(), b.vy(), b.vz()), w = len(b.wx(), b.wy(), b.wz());
        if (u >= v && u >= w) return new Vec3[] { ModelContours.point(b, 0, .5, .5), ModelContours.point(b, 1, .5, .5) };
        if (v >= w) return new Vec3[] { ModelContours.point(b, .5, 0, .5), ModelContours.point(b, .5, 1, .5) };
        return new Vec3[] { ModelContours.point(b, .5, .5, 0), ModelContours.point(b, .5, .5, 1) };
    }

    /** the body's top: the end of its longest edge standing higher */
    private static Vec3 top(ModelBox body) {
        Vec3[] e = ends(body);
        return e[0].y > e[1].y ? e[0] : e[1];
    }

    // ---------------------------------------------------------------- the textures

    private static final Map<Identifier, NativeImage> ORIGINAL = new HashMap<>();

    private static Identifier outer(Identifier asset) {
        return Identifier.fromNamespaceAndPath(asset.getNamespace(), "textures/entity/equipment/humanoid/" + asset.getPath() + ".png");
    }

    private static Identifier inner(Identifier asset) {
        return Identifier.fromNamespaceAndPath(asset.getNamespace(), "textures/entity/equipment/humanoid_leggings/" + asset.getPath() + ".png");
    }

    private static NativeImage original(Identifier id) {
        return ORIGINAL.computeIfAbsent(id, t -> {
            try (var in = mc().getResourceManager().open(t)) {
                return NativeImage.read(in);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("нет текстуры " + t, e);
            }
        });
    }

    private static final int REAL = 0, WHITE = 1, CODE = 2;
    /** the white shot's pixels, by the view's key, till its coded shot comes */
    private static final Map<String, int[]> WHITE_SHOT = new java.util.concurrent.ConcurrentHashMap<>();
    /** the skins coded: the agent's and the player's */
    private static Identifier playerSkin;

    /**
     * The set's textures and the skins as they are, white or coded (each texel's place in the colour: the armour's
     * with a full blue, the skin's with half); the leggings clear but as they are. The white shot gives each pixel's
     * light: the coded one over it is the texel's code alone, whatever the light's colour (the glowstone's is warm).
     */
    private static void textures(int mode) {
        Identifier asset = SETS[setIndex].asset();
        if (asset != null) {
            replace(outer(asset), mode, 255, 32);
            NativeImage in = original(inner(asset));
            NativeImage b = new NativeImage(in.getWidth(), in.getHeight(), false);
            for (int y = 0; y < in.getHeight(); y++) for (int x = 0; x < in.getWidth(); x++) b.setPixel(x, y, mode != REAL ? 0 : in.getPixel(x, y));
            mc().getTextureManager().register(inner(asset), new DynamicTexture(() -> "hand stand inner", b));
        }
        // the agent's skin (NTM's ResourceManager.fbi_tex), by its path: the stand adds nothing to the NTM the mod calls
        replace(Identifier.fromNamespaceAndPath("hbm", "textures/entity/fbi.png"), mode, 128, 64);
        if (playerSkin == null) playerSkin = mc().player.getSkin().body().texturePath();
        replace(playerSkin, mode, 128, 64);
    }

    /** a texture as it is, white, or coded: u in red, v in green ({@code rows} rows coded), this blue */
    private static void replace(Identifier id, int mode, int blue, int rows) {
        NativeImage was = original(id);
        int w = was.getWidth(), h = was.getHeight();
        NativeImage a = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int px = was.getPixel(x, y), alpha = px >>> 24;
            int u = x * 64 / w, v = y * 64 / w;
            int g = rows == 32 ? v * 6 + 2 : v * 3 + 1;
            a.setPixel(x, y, mode == WHITE ? alpha << 24 | 0xFFFFFF : mode == CODE ? alpha << 24 | (u * 3 + 1) << 16 | g << 8 | blue : px);
        }
        mc().getTextureManager().register(id, new DynamicTexture(() -> "hand stand " + id, a));
    }

    // ---------------------------------------------------------------- the counts

    /** what a pixel shows: an armour texel, a skin texel, neither (the gun, the world), or a mixed edge */
    static final int ARMOUR = 1, SKIN = 2, OTHER = 3, MIXED = 4;

    /**
     * a pixel's kind and texel ({@code kind << 16 | u << 8 | v}): the coded shot against the white one channel by
     * channel (the light cancels); blue as white is the armour's, half of it the skin's, all three as white neither
     */
    static int texel(int coded, int white) {
        int r = coded >> 16 & 255, g = coded >> 8 & 255, b = coded & 255;
        int wr = white >> 16 & 255, wg = white >> 8 & 255, wb = white & 255;
        if (Math.abs(r - wr) + Math.abs(g - wg) + Math.abs(b - wb) <= 24) return OTHER << 16;
        if (wr < 24 || wg < 24 || wb < 24) return MIXED << 16;
        float rb = b / (float) wb;
        int u = Math.round((r * 255F / wr - 1F) / 3F);
        if (Math.abs(rb - 1F) < 0.1F && r < 0.85F * wr) {
            int v = Math.round((g * 255F / wg - 2F) / 6F);
            return u >= 0 && u < 64 && v >= 0 && v < 32 ? ARMOUR << 16 | u << 8 | v : MIXED << 16;
        }
        if (Math.abs(rb - 0.502F) < 0.08F) {
            int v = Math.round((g * 255F / wg - 1F) / 3F);
            return u >= 0 && u < 64 && v >= 0 && v < 64 ? SKIN << 16 | u << 8 | v : MIXED << 16;
        }
        return MIXED << 16;
    }

    /**
     * the part of a 64 x 32 texture (armour, the agent's skin) or a 64 x 64 one (the player's) a texel is on: 0 the
     * arm's top, 1 the hand's end, 2 the arm above the elbow, 3 below it, 4 the body, 5 the head, 6 a sleeve or a
     * jacket of the player's skin, 7 the rest
     */
    static int region(int u, int v) {
        // the right arm (and the agent's left, mirrored); the player's left arm
        if (u >= 40 && u < 56 && v >= 16 && v < 32) return v < 20 ? (u >= 44 && u < 48 ? 0 : u >= 48 && u < 52 ? 1 : 7) : v < 26 ? 2 : 3;
        if (u >= 32 && u < 48 && v >= 48) return v < 52 ? (u >= 36 && u < 40 ? 0 : u >= 40 && u < 44 ? 1 : 7) : v < 58 ? 2 : 3;
        if (u >= 16 && u < 40 && v >= 16 && v < 32) return 4;
        if (v < 16) return 5;
        if (v >= 32) return 6;
        return 7;
    }

    private static final String[] REGION = { "верх_плеча", "торец_кисти", "выше_локтя", "ниже_локтя", "тело", "голова", "рукав/куртка_кожи", "прочее" };
    /** the circles counted round the hand, px of the frame */
    private static final int[] RINGS = { 90, RADIUS };

    private static void coded(String key, Set_ s, NativeImage image) {
        int w = image.getWidth(), h = image.getHeight(), cx = w / 2, cy = h / 2;
        int[] white = WHITE_SHOT.remove(key);
        if (white == null || white.length != w * h) {
            ClientSmoke.check(false, "кисти: нет белого снимка " + s.name() + " " + key);
            return;
        }
        int[] px = image.getPixels();
        // per ring: armour by region, skin by region, other, mixed
        int[][] counts = new int[RINGS.length][18];
        Map<Integer, Integer> armour = new TreeMap<>(), skin = new TreeMap<>();
        int armourFrame = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int t = texel(px[y * w + x], white[y * w + x]), kind = t >> 16, u = t >> 8 & 255, v = t & 255;
            if (kind == ARMOUR) armourFrame++;
            int d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
            for (int r = 0; r < RINGS.length; r++) {
                if (d2 > RINGS[r] * RINGS[r]) continue;
                int slot = kind == ARMOUR ? region(u, v) : kind == SKIN ? 8 + region(u, v) : kind == OTHER ? 16 : 17;
                counts[r][slot]++;
            }
            if (d2 <= RINGS[0] * RINGS[0]) {
                if (kind == ARMOUR) armour.merge(u << 8 | v, 1, Integer::sum);
                if (kind == SKIN) skin.merge(u << 8 | v, 1, Integer::sum);
            }
        }
        if (armourFrame > 0) anyCoded = true;
        StringBuilder row = new StringBuilder(s.name() + "\t" + key + "\t" + armourFrame);
        for (int[] c : counts) for (int v : c) row.append('\t').append(v);
        row.append('\t').append(AT.getOrDefault(key, "-\t-")).append('\t').append(top(armour)).append('\t').append(top(skin));
        ROWS.add(row.toString());
        String pose = key.substring(0, key.indexOf('\t'));
        int[] sum = SUM.computeIfAbsent(s.name() + "\t" + pose, k -> new int[18]);
        for (int i = 0; i < 18; i++) sum[i] += counts[0][i];
    }

    private static String top(Map<Integer, Integer> texels) {
        StringBuilder out = new StringBuilder();
        texels.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
                .forEach(e -> out.append(e.getKey() >> 8).append(',').append(e.getKey() & 255).append(':').append(e.getValue()).append(' '));
        return out.length() == 0 ? "-" : out.toString().trim();
    }

    /** the hand and the camera, blocks (height over the floor), by the view's key */
    private static final Map<String, String> AT = new java.util.concurrent.ConcurrentHashMap<>();

    /** the shot with the real textures, cut round the hand, for the eye */
    private static void real(String key, NativeImage image) {
        int cx = image.getWidth() / 2, cy = image.getHeight() / 2;
        String set = SETS[setIndex].name();
        try {
            Path dir = Path.of(System.getProperty("ntmgunfeel.botStandFile")).resolveSibling(label() + "-hands");
            Files.createDirectories(dir);
            try (NativeImage crop = new NativeImage(CROP, CROP, false)) {
                image.copyRect(crop, cx - CROP / 2, cy - CROP / 2, 0, 0, CROP, CROP, false, false);
                crop.writeToFile(dir.resolve((set + "-" + key.replace('\t', '-')).toLowerCase(Locale.ROOT) + ".png"));
            }
        } catch (java.io.IOException | RuntimeException e) {
            ClientSmoke.check(false, "кисти: снимок " + key + ": " + e);
        }
    }

    private static String label() {
        String f = Path.of(System.getProperty("ntmgunfeel.botStandFile")).getFileName().toString();
        return f.endsWith(".tsv") ? f.substring(0, f.length() - 4) : f;
    }

    private static void finish() {
        if (pending > 0) {
            // the last shots' read-back still to come
            until = now() + 2;
            setIndex = SETS.length - 1;
            poseIndex = POSES.length - 1;
            handIndex = 1;
            viewIndex = VIEWS.length - 1;
            phase = 4;
            wait = 1;
            return;
        }
        mc().setCameraEntity(mc().player);
        ClientSmoke.check(anyCoded, "кисти: закодированная текстура брони видна на кадрах (подмена сработала)");
        for (var e : SUM.entrySet()) {
            int[] c = e.getValue();
            StringBuilder armour = new StringBuilder(), skin = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (c[i] > 0) armour.append(REGION[i]).append(' ').append(c[i]).append(", ");
                if (c[8 + i] > 0) skin.append(REGION[i]).append(' ').append(c[8 + i]).append(", ");
            }
            ROWS.add("итог\t" + e.getKey() + "\tброня: " + armour + "\tкожа: " + skin + "\tпрочее " + c[16] + "\tкрая " + c[17]);
            ClientSmoke.check(true, String.format(Locale.ROOT, "кисти %s, в круге %d px у кисти: броня {%s} кожа {%s} прочее %d, края %d",
                    e.getKey().replace('\t', ' '), RINGS[0], armour, skin, c[16], c[17]));
        }
        try {
            Path f = Path.of(System.getProperty("ntmgunfeel.botStandFile"));
            Files.createDirectories(f.getParent());
            Files.write(f.resolveSibling(label() + "-hands.tsv"), ROWS);
        } catch (java.io.IOException e) {
            ClientSmoke.check(false, "кисти: запись " + e);
        }
        ClientSmoke.finish();
    }
}
