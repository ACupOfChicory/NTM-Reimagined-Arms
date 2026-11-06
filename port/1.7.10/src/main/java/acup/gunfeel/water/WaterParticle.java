package acup.gunfeel.water;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import acup.gunfeel.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

// The particles of the water effects (WaterFx), on vanilla sprites: drops, water dust (generic, low alpha), bubbles
// that rise and pop at the surface, foam that rides the flow, the muzzle's and a blast's gas bubble (one bubble
// sprite, only its scale changes), a blast's flash. Speeds are in blocks per second, worked out per client tick
// (0.05 s), as the sandbox (notes/water/water-splash.tpl.html) has them.
// port: 26.2's SingleQuadParticle as 1.7.10's EntityFX on particles.png (layer 0): the drop is the rain splash
// (19-22), water dust and foam the smoke's generic (0-7), the bubble 32, the flash the firework's overlay. 1.7.10 has
// no bubble_pop: the pop is the bubble swelling to 1.6 times and fading. Drawn here (renderParticle) by the frame's
// time as 26.2's extract.
// port: 1.7.10 draws the particles before the water (EntityRenderer.renderWorld: "particles", then "water" with no
// depth written), so the water came over a drop or foam above its surface and tinted it blue. Those on the camera's
// side of the surface (both in the air or both in the water) are drawn at the frame's end instead (renderLate, after
// the rings), as 26.2 has particles after the water; the others stay before it, under the water's colour.
public final class WaterParticle extends EntityFX {
    public enum Kind {
        DROP, MIST, BUBBLE, RISE, POP, FOAM, GAS, FLASH, DEBRIS
    }

    private static final double DT = 0.05;
    // the flow's speed for foam and drifting rings, blocks per second per unit of the flow
    static final double FLOW_SPEED = 1.6;
    static final double FALL_SPEED = 8;

    // ours alive now (WaterFx's limit). port: the engine drops the oldest of a full layer (4000) without setDead, so
    // the living are counted by those still ticked (sweep), not by a counter
    public static int live;
    private static final List<WaterParticle> ALL = new ArrayList<>();
    private long seen = WaterFx.ticks;

    static void forget() {
        ALL.clear();
        LATE.clear();
        live = 0;
    }

    private static final ResourceLocation SHEET = new ResourceLocation("textures/particle/particles.png");
    private static final List<WaterParticle> LATE = new ArrayList<>();
    // the frame (counted by newFrame), the one LATE and the camera's side were taken for, the view's axes then
    private static long frame, lateFrame = -1;
    private static boolean camWet;
    private static float lateA, lateRx, lateRxz, lateRz, lateRyz, lateRxy;
    // for the stand: drawn at the frame's end so far; lateOff draws every one before the water, as the engine does
    public static int lateDrawn;
    public static volatile boolean lateOff;
    // in the water (under its surface) as of the tick wetTick
    private boolean wet;
    private long wetTick = -1;

    private static boolean wetAt(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        return WaterSurface.wet(w, bx, by, bz) && y < WaterSurface.topOf(w, bx, by, bz);
    }

    // the frame's start (GunFeelClient.renderTick): a frame whose end did not come (no RenderWorldLastEvent, the
    // tracers off) leaves nothing behind
    public static void newFrame() {
        frame++;
        LATE.clear();
    }

    // the frame's end (RenderWorldLastEvent, after the rings): the ones on the camera's side, as EffectRenderer
    // draws them (its blend, alpha test and depth; the lightmap and the fog, both of which the engine turned off
    // after the water: the fog's settings are still the world's, setupFog(0) before the water)
    public static void renderLate(float partial) {
        if (LATE.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        mc.entityRenderer.enableLightmap(partial);
        mc.getTextureManager().bindTexture(SHEET);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.003921569F);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_FOG);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        try {
            for (WaterParticle p : LATE) {
                t.setBrightness(p.getBrightnessForRender(lateA));
                p.draw(t, lateA, lateRx, lateRxz, lateRz, lateRyz, lateRxy);
                lateDrawn++;
            }
        } finally {
            t.draw();
            LATE.clear();
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDepthMask(true);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glDisable(GL11.GL_BLEND);
            mc.entityRenderer.disableLightmap(partial);
        }
    }

    // the client tick: the ones dead or no longer ticked (dropped by the engine) are not counted
    static void sweep(long now) {
        int k = 0;
        for (int i = 0; i < ALL.size(); i++) {
            WaterParticle p = ALL.get(i);
            if (!p.isDead && p.seen >= now - 2) ALL.set(k++, p);
        }
        for (int i = ALL.size() - 1; i >= k; i--) ALL.remove(i);
        live = ALL.size();
    }

    final Kind kind;
    // velocity, blocks per second
    double vx, vy, vz;
    float size0, alpha0, grav, drag;
    // foam: 0 rides the surface, 1 falls off an edge or along a stream; a drop: 1 makes a little ring where it lands
    int mode;
    double phase;
    // the gas bubble: its full radius, the bubbles it breaks into, where it started, forward
    double gasR;
    int gasN;
    boolean still;
    Vec3 origin = Vec3.ZERO, forward = Vec3.ZERO;
    // the sprite: its index on particles.png (16 a row); FLASH has its own corner of the sheet
    private int sprite;
    private float alphaNow;
    private final int[] frames;

    // the stand's frame log (WaterFxStand): the first particle of this kind spawned, each frame drawn: the time in
    // ticks, its size, alpha, the drawn centre and the gas bubble's origin
    public static volatile Kind watchKind;
    public static WaterParticle watched;
    public static final List<double[]> WATCH = new ArrayList<>();

    WaterParticle(World level, Kind kind, double x, double y, double z, double vx, double vy, double vz, double life, double size,
            float alpha, int... frames) {
        super(level, x, y, z);
        this.kind = kind;
        this.frames = frames;
        this.sprite = frames[0];
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.motionX = this.motionY = this.motionZ = 0;
        this.particleMaxAge = Math.max(1, (int) Math.round(life / DT));
        this.size0 = (float) size;
        this.alpha0 = alpha;
        this.alphaNow = alpha;
        this.particleAlpha = alpha;
        this.particleRed = this.particleGreen = this.particleBlue = 1F;
        this.particleGravity = 0;
        this.noClip = true;
        this.phase = rand.nextDouble() * 6.283;
        ALL.add(this);
        live++;
    }

    World level() {
        return worldObj;
    }

    int age() {
        return particleAge;
    }

    double x() {
        return posX;
    }

    double y() {
        return posY;
    }

    double z() {
        return posZ;
    }

    void frame(int i) {
        sprite = frames[Math.max(0, Math.min(frames.length - 1, i))];
    }

    void color(float r, float g, float b) {
        particleRed = r;
        particleGreen = g;
        particleBlue = b;
    }

    void remove() {
        setDead();
    }

    boolean removed() {
        return isDead;
    }

    @Override
    public int getFXLayer() {
        return 0;
    }

    @Override
    public int getBrightnessForRender(float a) {
        return kind == Kind.FLASH ? 0xF000F0 : super.getBrightnessForRender(a);
    }

    // half the quad's side at the frame's time
    float quadSize(float a) {
        if (kind == Kind.GAS) return (float) Math.max(0, gasRadius((particleAge + a) * DT, gasR));
        // water dust swells as it goes
        if (kind == Kind.MIST) return size0 * 0.5F + size0 * 0.35F * (float) DT * 0.5F * (particleAge + a);
        // port: the pop swells as it fades, in place of 26.2's bubble_pop frames
        if (kind == Kind.POP) return size0 * 0.5F * (1F + 0.6F * Math.min(1F, (particleAge + a) / particleMaxAge));
        return size0 * 0.5F;
    }

    // fading in and out by the frame's time, not once a tick: set on a tick, the water dust's alpha stood at its full
    // value until the first tick and then fell to a fifth of it (the owner's blinking at a shot, 2026-11-06)
    private float fade(float a) {
        float t = particleAge + a, f = 1F - t / particleMaxAge;
        switch (kind) {
            case DROP:
                return Math.max(0F, alpha0 * Math.min(1F, f * 5F));
            case MIST:
                return Math.max(0F, alpha0 * Math.min(1F, (1 - f) * 6F) * f);
            case FOAM:
                return Math.max(0F, alpha0 * Math.min(1F, f * 3F));
            case DEBRIS:
                return Math.max(0F, Math.min(1F, (particleMaxAge - t) * (float) DT * 2F) * alpha0);
            case FLASH:
                return Math.max(0F, (float) (alpha0 * (1 - t * DT / 0.05)));
            case POP:
                return Math.max(0F, alpha0 * Math.min(1F, f * 2F));
            default:
                return alpha0;
        }
    }

    // the gas bubble's texture half a texel inside its cell: at a big scale the sheet's neighbours would show
    private static final float HALF_TEXEL = 1F / 256F;

    @Override
    public void renderParticle(Tessellator t, float a, float rx, float rxz, float rz, float ryz, float rxy) {
        if (!lateOff) {
            if (lateFrame != frame) {
                // the frame's first: the camera's side (interpPos is the view entity, ActiveRenderInfo the camera
                // from it), the view's axes for the frame's end
                lateFrame = frame;
                LATE.clear();
                camWet = wetAt(worldObj, interpPosX + ActiveRenderInfo.objectX, interpPosY + ActiveRenderInfo.objectY,
                        interpPosZ + ActiveRenderInfo.objectZ);
                lateA = a;
                lateRx = rx;
                lateRxz = rxz;
                lateRz = rz;
                lateRyz = ryz;
                lateRxy = rxy;
            }
            if (wetTick != WaterFx.ticks) {
                wetTick = WaterFx.ticks;
                // foam and a pop ride the surface (its corners' height, which on a slope is under the cell's own):
                // over it, always
                wet = kind != Kind.FOAM && kind != Kind.POP && wetAt(worldObj, posX, posY, posZ);
            }
            // a frame with no frame's end (no RenderWorldLastEvent) leaves no more than LIVE_MAX behind
            if (wet == camWet && LATE.size() < WaterFx.LIVE_MAX) {
                LATE.add(this);
                return;
            }
        }
        draw(t, a, rx, rxz, rz, ryz, rxy);
    }

    private void draw(Tessellator t, float a, float rx, float rxz, float rz, float ryz, float rxy) {
        alphaNow = fade(a);
        particleAlpha = alphaNow;
        if (kind == Kind.GAS && gasRadius((particleAge + a) * DT, gasR) <= 0) return;
        if (alphaNow <= 0F) return;
        float s = quadSize(a);
        double px = prevPosX + (posX - prevPosX) * a, py = prevPosY + (posY - prevPosY) * a, pz = prevPosZ + (posZ - prevPosZ) * a;
        if (this == watched) WATCH.add(new double[] {particleAge + a, s, alphaNow, px, py, pz, origin.x, origin.y, origin.z});
        float u0, u1, v0, v1;
        if (kind == Kind.FLASH) {
            // EntityFireworkOverlayFX's corner of the sheet
            u0 = 0.25F;
            u1 = 0.5F;
            v0 = 0.125F;
            v1 = 0.375F;
        } else {
            u0 = (sprite % 16) / 16F;
            u1 = u0 + 0.0624375F;
            v0 = (sprite / 16) / 16F;
            v1 = v0 + 0.0624375F;
            if (kind == Kind.GAS) {
                u0 += HALF_TEXEL;
                u1 = u0 + 1F / 16F - 2 * HALF_TEXEL;
                v0 += HALF_TEXEL;
                v1 = v0 + 1F / 16F - 2 * HALF_TEXEL;
            }
        }
        float x = (float) (px - interpPosX), y = (float) (py - interpPosY), z = (float) (pz - interpPosZ);
        t.setColorRGBA_F(particleRed, particleGreen, particleBlue, alphaNow);
        t.addVertexWithUV(x - rx * s - ryz * s, y - rxz * s, z - rz * s - rxy * s, u1, v1);
        t.addVertexWithUV(x - rx * s + ryz * s, y + rxz * s, z - rz * s + rxy * s, u1, v0);
        t.addVertexWithUV(x + rx * s + ryz * s, y + rxz * s, z + rz * s + rxy * s, u0, v0);
        t.addVertexWithUV(x + rx * s - ryz * s, y - rxz * s, z + rz * s - rxy * s, u0, v1);
    }

    // the gas bubble's radius at t seconds (ticks 0-2 it grows to R, tick 3 shrinks to 0.4R, tick 4 bounces to
    // 0.7R; after that it breaks up: -1)
    static double gasRadius(double t, double r) {
        double tk = t * 20;
        if (tk < 2) return r * (1 - Math.pow(1 - tk / 2, 2));
        if (tk < 3) return r * (1 - 0.6 * (tk - 2));
        if (tk < 4) return r * (0.4 + 0.3 * (tk - 3));
        return -1;
    }

    private void moveBy(double dx, double dy, double dz) {
        posX += dx;
        posY += dy;
        posZ += dz;
    }

    private boolean solidAt(double px, double py, double pz) {
        return worldObj.getBlock((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz)).getMaterial().blocksMovement();
    }

    @Override
    public void onUpdate() {
        seen = WaterFx.ticks;
        prevPosX = posX;
        prevPosY = posY;
        prevPosZ = posZ;
        if (particleAge++ >= particleMaxAge) {
            remove();
            return;
        }
        float f = 1F - (float) particleAge / particleMaxAge;
        switch (kind) {
            case DROP:
                drop();
                break;
            case MIST:
                mist(f);
                break;
            case BUBBLE:
            case RISE:
                bubble();
                break;
            case POP:
                // its water gone (drained, flowed away): gone with it
                if (WaterSurface.top(worldObj, (int) Math.floor(posX), (int) Math.floor(posZ), posY + 0.4, 3) == null) {
                    remove();
                    return;
                }
                break;
            case FOAM:
                foam();
                break;
            case GAS:
                gas();
                break;
            case FLASH:
                break;
            case DEBRIS:
                vy -= 1.2 * DT;
                vx *= 1 - DT;
                vz *= 1 - DT;
                moveBy(vx * DT, vy * DT, vz * DT);
                if (solidAt(posX, posY, posZ)) remove();
                break;
        }
        if (WaterFx.audit && !isDead) WaterFx.audit(this);
    }

    private void drop() {
        double df = Math.max(0, 1 - drag * DT);
        vx *= df;
        vy *= df;
        vz *= df;
        vy -= grav * DT;
        moveBy(vx * DT, vy * DT, vz * DT);
        if (posY < -1 || solidAt(posX, posY, posZ)) {
            remove();
            return;
        }
        if (vy < 0 && nearWater()) {
            WaterSurface.Info s = WaterSurface.at(worldObj, posX, posY + 0.3, posZ, false);
            if (s != null && posY <= s.y()) {
                if (mode == 1 && rand.nextFloat() < 0.25F) WaterRings.add(worldObj, posX, s.y(), posZ, 0, 0.9, 0.1, 0.5, 0.5F, !s.still(), false);
                remove();
            }
        }
    }

    // the cell or the one under it has water: only then a surface can be near
    private boolean nearWater() {
        int bx = (int) Math.floor(posX), by = (int) Math.floor(posY), bz = (int) Math.floor(posZ);
        return WaterSurface.wet(worldObj, bx, by, bz) || WaterSurface.wet(worldObj, bx, by - 1, bz);
    }

    private void mist(float f) {
        double df = Math.max(0, 1 - drag * DT);
        vx *= df;
        vy *= df;
        vz *= df;
        vy += 0.25 * DT;
        moveBy(vx * DT, vy * DT, vz * DT);
        frame(Math.max(0, Math.min(7, 7 - (int) Math.floor(8 * (1 - f)))));
    }

    // bubbles under water: RISE (the muzzle's and a blast's, the trail) eases to 1.6 blocks per second and wobbles;
    // BUBBLE (a splash's) rises at its own speed and drifts with the flow. Both pop at the surface.
    private void bubble() {
        if (kind == Kind.RISE) {
            vx *= Math.max(0, 1 - 2.5 * DT);
            vz *= Math.max(0, 1 - 2.5 * DT);
            vy += (1.6 - vy) * Math.min(1, DT * 2.2);
            phase += DT * 9;
            moveBy(vx * DT + Math.sin(phase) * 0.18 * DT, vy * DT, vz * DT + Math.cos(phase * 0.8) * 0.12 * DT);
        } else {
            phase += DT * 9;
            moveBy(vx * DT + Math.sin(phase) * 0.004, vy * DT, vz * DT);
        }
        int bx = (int) Math.floor(posX), by = (int) Math.floor(posY), bz = (int) Math.floor(posZ);
        if (!WaterSurface.wet(worldObj, bx, by, bz)) {
            // came up through the top of its cell: pops where the water ends
            if (WaterSurface.wet(worldObj, bx, by - 1, bz) && !WaterSurface.falling(worldObj, bx, by - 1, bz))
                pop(WaterSurface.topOf(worldObj, bx, by - 1, bz));
            else remove();
            return;
        }
        if (WaterSurface.falling(worldObj, bx, by, bz)) return;
        if (WaterSurface.wet(worldObj, bx, by + 1, bz)) return;
        double top = WaterSurface.topOf(worldObj, bx, by, bz);
        if (posY >= top - 0.05) pop(top);
    }

    private void pop(double top) {
        WaterFx.spawn(worldObj, Kind.POP, posX, top + 0.03, posZ, 0, 0, 0, 0.25, kind == Kind.RISE ? size0 * 1.6 : 0.22, 0.95F);
        remove();
    }

    private void foam() {
        if (mode == 0) {
            WaterSurface.Info s = WaterSurface.at(worldObj, posX, posY + 0.4, posZ, false);
            if (s == null) {
                remove();
                return;
            }
            double tx = s.fx() * FLOW_SPEED, tz = s.fz() * FLOW_SPEED, kk = Math.min(1, DT * (s.still() ? 0.8 : 3));
            vx += (tx - vx) * kk;
            vz += (tz - vz) * kk;
            double nx = posX + vx * DT, nz = posZ + vz * DT;
            WaterSurface.Info s2 = WaterSurface.at(worldObj, nx, posY + 0.4, nz, false);
            if (s2 != null && s2.y() > posY - 0.35) {
                posX = nx;
                posZ = nz;
                posY = s2.y() + 0.02;
            } else {
                // off the edge: down along the stream, onto the water below
                double ox = Math.floor(posX), oz = Math.floor(posZ);
                posX = Math.max(ox - 0.04, Math.min(ox + 1.04, nx));
                posZ = Math.max(oz - 0.04, Math.min(oz + 1.04, nz));
                vx *= 0.15;
                vz *= 0.15;
                vy = 0;
                mode = 1;
            }
        } else {
            vy = Math.max(vy - 22 * DT, -FALL_SPEED);
            moveBy(vx * DT, vy * DT, vz * DT);
            if (posY < 0 || solidAt(posX, posY, posZ)) {
                remove();
                return;
            }
            WaterSurface.Info s = WaterSurface.at(worldObj, posX, posY + 0.3, posZ, false);
            if (s != null && posY <= s.y() + 0.02) {
                mode = 0;
                posY = s.y() + 0.02;
                double a = rand.nextDouble() * 6.283;
                vx = Math.cos(a) * 0.4;
                vz = Math.sin(a) * 0.4;
            } else if (s == null && !WaterSurface.wet(worldObj, (int) Math.floor(posX), (int) Math.floor(posY), (int) Math.floor(posZ))
                    && !WaterSurface.wet(worldObj, (int) Math.floor(posX), (int) Math.floor(posY) - 1, (int) Math.floor(posZ))
                    && !nearStream()) {
                // fell off into the air away from any stream: foam does not hang there
                remove();
            }
        }
    }

    // a falling stream next to it (foam sticks to the stream and falls with it)
    private boolean nearStream() {
        int bx = (int) Math.floor(posX), by = (int) Math.floor(posY), bz = (int) Math.floor(posZ);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (WaterSurface.wet(worldObj, bx + dx, by, bz + dz)) return true;
        return false;
    }

    // the gas bubble's centre at tk ticks: ahead of its origin by 0.85 of its radius (over it by 0.6 for a blast),
    // rising slowly
    private Vec3 gasCentre(double tk) {
        double r = Math.max(0, gasRadius(Math.min(tk, 3.999) * DT, gasR));
        return origin.add(still ? Vec3.ZERO : forward.scale(r * 0.85)).add(0, tk * DT * 0.4 + (still ? r * 0.6 : 0), 0);
    }

    // drawn between this tick's centre and the next one's, as its size is drawn at the frame's time: set to this
    // tick's centre, it lagged a tick behind the size and stood still at its origin while it swelled to full
    void placeGas() {
        Vec3 c0 = gasCentre(particleAge), c1 = gasCentre(particleAge + 1);
        prevPosX = c0.x;
        prevPosY = c0.y;
        prevPosZ = c0.z;
        posX = c1.x;
        posY = c1.y;
        posZ = c1.z;
    }

    private void gas() {
        if (gasRadius(particleAge * DT, gasR) > 0) {
            placeGas();
            return;
        }
        // breaks up into bubbles that rise and pop
        double big = gasR * 0.7;
        int n = WaterFx.scaled(gasN);
        for (int j = 0; j < n; j++) {
            double dx = rand.nextDouble() * 2 - 1, dy = rand.nextDouble() * 2 - 1, dz = rand.nextDouble() * 2 - 1;
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l < 1e-6) continue;
            dx /= l;
            dy /= l;
            dz /= l;
            double rr = rand.nextDouble() * big;
            WaterFx.spawn(worldObj, Kind.RISE, posX + dx * rr, posY + dy * rr, posZ + dz * rr, dx * WaterFx.rnd(0.3, 1.2), dy * WaterFx.rnd(0.3, 1.2) + 0.3,
                    dz * WaterFx.rnd(0.3, 1.2), 12, WaterFx.rnd(0.07, 0.16) * (0.7 + gasR), 1F);
        }
        remove();
    }
}
