package acup.gunfeel.crosshair;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.Crosshair;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// Client: the dynamic crosshair in place of NTM's while an NTM gun is in the main hand (MixinClientRegistry). Look and
// behaviour after the dynamic crosshair of TACZ Refabricated (Sh1roCu, The Unnamed), itself after TaCZ: Labs by
// Txt-Text (GPL-3.0); written here from how it looks and moves, with the numbers of its settings the owner plays with
// (the widest spread 20, the speed 20, the shot's jump 2.0), no code taken.
//  - The gap: the style's base, plus the spread of a shot fired now (CrosshairSpread: NTM's and the mod's, the stance,
//    moving, the air), plus the jump each shot gives. The spread as TaCZ's inaccuracy would be for the same scatter, and
//    0.8 GUI pixels to each of it, at most 20.
//  - The jump: the shot's upward kick (NTM's recoil, degrees) × 5, at least 1, × 2.0, all of it at most 20; it dies
//    away and the gap follows the sum smoothly, both at the speed 20 a second (frame rate free).
//  - Aiming down the sights it fades out where NTM hides its own crosshair; a gun NTM draws none for gets none.
//  - Kept NTM's: the Stinger's (its lock-on), the guns of no class (CrosshairClass). Setting: dynamicCrosshair.
public final class DynamicCrosshair {
    private DynamicCrosshair() {
    }

    public static final float MAX_SPREAD = 20F, SPEED = 20F, SHOT_JUMP = 2.0F, PIXELS_PER_INACCURACY = 0.8F;
    // TaCZ's bullets leave as vanilla's arrows do: off by a triangular share of 0.0172275 × inaccuracy on each axis, its
    // spread (standard deviation) that over √6. NTM's bullets: off by a normal share of the spread on each axis.
    public static final double INACCURACY_SPREAD = 0.0172275 / Math.sqrt(6);
    // TaCZ's colour: a pale yellow at 0.9, the shadow black at a half
    private static final int SHADOW = 0x80000000, COLOR = 0xE6FFFFCC;

    /** the gap and the jump; NaN before the first frame */
    public static final class Gap {
        double gap = Double.NaN, jump;

        /**
         * a frame {@code dt} seconds on, the spread as TaCZ's inaccuracy; the gap in whole GUI pixels, at least 1. The
         * jump dies away as e^(-20 t) and the gap follows the base and spread plus the jump at the same rate: worked out
         * exactly over the frame (the gap's lead u goes as (u + 20·jump·t)·e^(-20 t)), so the same at any frame rate
         */
        public int step(int base, double inaccuracy, double dt) {
            dt = Math.min(Math.max(dt, 0), 0.1);
            double k = Math.exp(-SPEED * dt);
            double target = base + Math.min(Math.max(inaccuracy * PIXELS_PER_INACCURACY, 0), MAX_SPREAD);
            if (!Double.isFinite(gap)) gap = target + jump;
            gap = target + (gap - target + SPEED * jump * dt) * k;
            jump *= k;
            if (jump < 0.05) jump = 0;
            return (int) Math.max(1, Math.round(gap));
        }

        /** from scratch: no gap to ease from, no jump */
        void reset() {
            gap = Double.NaN;
            jump = 0;
        }

        /** a shot whose upward kick is {@code kick} degrees */
        public void shot(float kick) {
            jump = Math.min(Math.max(jump + Math.max(1F, Math.abs(kick) * 5F) * SHOT_JUMP, 0), MAX_SPREAD);
        }
    }

    private static final Gap GAP = new Gap();
    private static long lastNanos;
    private static @Nullable Item drawnItem;
    private static @Nullable Item styledItem;
    private static @Nullable CrosshairStyle styled;
    private static long styledAt;
    // the stand: the gap and the style last drawn, the spread then (NTM's)
    public static int lastGap;
    public static @Nullable CrosshairStyle lastStyle;
    public static float lastSpread;
    // the stand: frames it took NTM's place, frames it drew lines in
    public static int took, drawn;

    /** the spread as TaCZ's inaccuracy for the same scatter */
    public static double inaccuracy(float spread) {
        return spread / INACCURACY_SPREAD;
    }

    /** NTM's recoil asked for a kick this tick (MixinItemGunBaseNT) */
    public static void kick(float vertical) {
        if (vertical != 0F && Config.dynamicCrosshair) GAP.shot(vertical);
    }

    /** the GUI pixel the crosshair stands on: vanilla's, the left (upper) of the two middle ones */
    public static int centre(int size) {
        return (size - 1) / 2;
    }

    /** the style for this gun, or null for NTM's crosshair */
    public static @Nullable CrosshairStyle style(ItemStack stack) {
        Item item = stack.getItem();
        long now = System.nanoTime();
        // the class looked up once a second at most (the gun editor may change the kick preset)
        if (item != styledItem || now - styledAt > 1_000_000_000L) {
            styledItem = item;
            styledAt = now;
            styled = CrosshairClass.of(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        return styled;
    }

    /**
     * Draws the crosshair for the gun in the main hand instead of NTM's: true when it took NTM's place (drawn or hidden
     * while aiming), false for NTM's own
     */
    public static boolean render(GuiGraphicsExtractor g) {
        if (!Config.enabled || !Config.dynamicCrosshair || !acup.gunfeel.compat.Feature.DYNAMIC_CROSSHAIR.on()) return false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return false;
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun) || gun instanceof com.hbm.items.weapon.sedna.impl.ItemGunStinger) return false;
        CrosshairStyle style = style(stack);
        if (style == null) return false;
        var config = gun.getConfig(stack, 0);
        long now = System.nanoTime();
        // another gun, or none drawn for a while: from scratch, no jump kept from before
        if (stack.getItem() != drawnItem || now - lastNanos > 500_000_000L) {
            GAP.reset();
            drawnItem = stack.getItem();
            lastNanos = now;
        }
        double dt = (now - lastNanos) / 1e9;
        lastNanos = now;
        boolean aiming = ItemGunBaseNT.getIsAiming(stack);
        float spread = CrosshairSpread.spread(player, stack, aiming);
        int gap = GAP.step(style.base, Float.isNaN(spread) ? 0 : inaccuracy(spread), dt);
        lastGap = gap;
        lastStyle = style;
        lastSpread = spread;
        took++;
        if (config.getCrosshair(stack) == Crosshair.NONE) return true;
        // fading out with the sights where NTM hides its crosshair
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float aim = config.getHideCrosshair(stack) ? ItemGunBaseNT.prevAimingProgress + (ItemGunBaseNT.aimingProgress - ItemGunBaseNT.prevAimingProgress) * pt : 0F;
        float fade = 1F - Math.min(Math.max(aim, 0F), 1F);
        if (fade <= 0F) return true;
        int line = (int) ((COLOR >>> 24) * fade) << 24 | COLOR & 0xFFFFFF, shadow = (int) ((SHADOW >>> 24) * fade) << 24;
        drawn++;
        int x = centre(g.guiWidth()), y = centre(g.guiHeight());
        for (int[] r : style.rects(x + 1, y + 1, gap)) g.fill(r[0], r[1], r[2], r[3], shadow);
        for (int[] r : style.rects(x, y, gap)) g.fill(r[0], r[1], r[2], r[3], line);
        return true;
    }
}
