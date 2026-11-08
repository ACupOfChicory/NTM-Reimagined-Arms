package acup.gunfeel.gas.client;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import acup.gunfeel.gas.GasKind;
import acup.gunfeel.gas.GasPoison;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;

/**
 * Client: the oily stains the mustard gas leaves on the floor it lay on. Each client makes them from its own copy of the
 * cloud (the same cells as the server's), so they cost no packets: a floor block under the gas gathers its density
 * times the ticks; past {@link #SHOWS} its stain shows and darkens up to {@link #FULL}. It stays {@link #LIFE} ticks after
 * the gas last lay there (5 minutes) and fades out over the last {@link #FADE}; a broken floor block takes its stain with
 * it. At most {@link #MAX}, the oldest go first. A player who comes later sees the stains of a cloud still there, not of
 * one that is gone.
 */
public final class GasStains {
    public static final float SHOWS = 20F, FULL = 160F, LEAST = 0.1F;
    public static final int EVERY = 5, LIFE = 6000, FADE = 600, MAX = 2048;
    /** The stain's colour, a dark oily yellow-brown, and its strongest opacity. */
    public static final int RGB = 0x4A3C14;
    public static final float OPACITY = 0.85F;
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("ntmgunfeel", "textures/effect/mustard_stain.png");

    private static final class Stain {
        final BlockPos pos;
        float sum;
        long last;
        // this frame's, for the drawing
        float alpha;
        int light;

        Stain(BlockPos pos) {
            this.pos = pos;
        }
    }

    // by the floor block, the first stained first
    private static final Map<Long, Stain> STAINS = new LinkedHashMap<>();
    private static ClientLevel level;

    private GasStains() {
    }

    /** Every client tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel now = mc.level;
        if (now != level) {
            STAINS.clear();
            level = now;
        }
        if (now == null) return;
        long time = now.getGameTime();
        if (time % EVERY != 0) return;
        SmokeClouds all = SmokeClouds.get(now);
        if (all != null) for (SmokeClouds.Cloud c : all.clouds()) {
            GasKind g = GasKind.of(c.kind());
            if (g == null || !GasPoison.soaks(g) || c.catchingUp(time)) continue;
            for (int i : c.grid.floorCells(LEAST)) {
                int x = c.baseX() + i % SmokeGrid.SX, z = c.baseZ() + i / SmokeGrid.SX % SmokeGrid.SZ, y = c.baseY() + i / (SmokeGrid.SX * SmokeGrid.SZ) - 1;
                long k = BlockPos.asLong(x, y, z);
                Stain s = STAINS.get(k);
                if (s == null) {
                    // gas on water leaves nothing
                    if (!now.getFluidState(new BlockPos(x, y, z)).isEmpty()) continue;
                    s = new Stain(new BlockPos(x, y, z));
                    STAINS.put(k, s);
                }
                s.sum = Math.min(FULL, s.sum + Math.min(1F, c.grid.density(i)) * EVERY);
                s.last = time;
            }
        }
        boolean blocks = time % 20 == 0;
        for (Iterator<Stain> it = STAINS.values().iterator(); it.hasNext();) {
            Stain s = it.next();
            if (time - s.last > LIFE || blocks && now.isLoaded(s.pos) && !solidTop(now, s.pos)) it.remove();
        }
        for (Iterator<Stain> it = STAINS.values().iterator(); STAINS.size() > MAX && it.hasNext();) {
            it.next();
            it.remove();
        }
    }

    private static boolean solidTop(ClientLevel lvl, BlockPos pos) {
        return lvl.getBlockState(pos).isFaceSturdy(lvl, pos, net.minecraft.core.Direction.UP) && lvl.getBlockState(pos.above()).getCollisionShape(lvl, pos.above()).isEmpty();
    }

    public static void reset() {
        STAINS.clear();
        level = null;
    }

    /** How strong a stain is now, 0 to 1: how much gas it took and how near its end it is. Common to the drawing and the stand. */
    static float strength(float sum, long sinceLast) {
        if (sum < SHOWS) return 0F;
        float s = Math.min(1F, (sum - SHOWS) / (FULL - SHOWS) * 0.7F + 0.3F);
        if (sinceLast > LIFE - FADE) s *= Math.max(0F, (LIFE - sinceLast) / (float) FADE);
        return s;
    }

    // dev (GasStand): the stains now, as {x, y, z, strength}
    public static float[][] snapshot() {
        long time = level == null ? 0 : level.getGameTime();
        return STAINS.values().stream().map(s -> new float[] {s.pos.getX(), s.pos.getY(), s.pos.getZ(), strength(s.sum, time - s.last)}).toArray(float[][]::new);
    }

    public static void submit(SubmitCustomGeometryEvent event) {
        if (STAINS.isEmpty() || level == null) return;
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        long time = level.getGameTime();
        java.util.List<Stain> shown = new java.util.ArrayList<>();
        for (Stain s : STAINS.values()) {
            s.alpha = strength(s.sum, time - s.last);
            if (s.alpha <= 0F) continue;
            double dx = s.pos.getX() + 0.5 - cam.x, dz = s.pos.getZ() + 0.5 - cam.z;
            if (dx * dx + dz * dz > 96 * 96) continue;
            s.light = LightCoordsUtil.getLightCoords(level, s.pos.above());
            shown.add(s);
        }
        if (shown.isEmpty()) return;
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), acup.gunfeel.HoleRender.type(TEXTURE, false, false), (p, vc) -> {
            for (Stain s : shown) quad(p, vc, s, cam);
        });
    }

    // one stain on the block's top face: a variant of the 2 by 2 and a quarter turn picked by the block
    private static void quad(PoseStack.Pose p, VertexConsumer vc, Stain s, Vec3 cam) {
        int x = s.pos.getX(), y = s.pos.getY(), z = s.pos.getZ();
        int h = Math.floorMod(x * 73856093 ^ y * 19349663 ^ z * 83492791, 16);
        int variant = h & 3, turn = h >> 2 & 3;
        float u0 = (variant & 1) * 0.5F + 1F / 128F, v0 = (variant >> 1) * 0.5F + 1F / 128F, u1 = u0 + 0.5F - 2F / 128F, v1 = v0 + 0.5F - 2F / 128F;
        float[][] uv = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
        float fx = (float) (x - cam.x), fy = (float) (y + 1.002 - cam.y), fz = (float) (z - cam.z);
        float[][] corner = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        int color = (Math.round(s.alpha * OPACITY * 255) << 24) | RGB;
        int light = s.light;
        for (int i = 0; i < 4; i++) {
            float[] c = corner[i], t = uv[(i + turn) & 3];
            vc.addVertex(p, fx + c[0], fy, fz + c[1]).setColor(color).setUv(t[0], t[1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0F, 1F, 0F);
        }
    }
}
