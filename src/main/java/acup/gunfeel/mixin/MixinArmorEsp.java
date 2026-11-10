package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.client.render.ArmorOverheadRenderer;
import com.hbm.items.armor.ArmorFullSetBonus;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.ArmorAbilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

// Client: the sets' VATS and thermal vision (ArmorOverheadRenderer) show no players (ArmorAbilities): the thermal
// outline of a player is dropped unless a tier 8 set sees them within abilities.esp.playerRange, VATS draws no
// player's health bar. A gun's thermal sight (AMAT Penance, the same outlines while aiming) shows players as a tier 8
// set does: within the range and in sight.
@Mixin(value = ArmorOverheadRenderer.class, remap = false)
public abstract class MixinArmorEsp {
    @Unique private static int gunfeel$tier;
    @Unique private static String gunfeel$mode = "all";
    @Unique private static double gunfeel$range = 16;

    @Shadow
    private static boolean aimsThermalSights(LocalPlayer player) {
        throw new AssertionError();
    }

    @Shadow
    private static ArmorFullSetBonus activeChestBonus(LocalPlayer player) {
        throw new AssertionError();
    }

    @Inject(method = "submit", at = @At("HEAD"), require = 1)
    private static void gunfeel$frame(PoseStack poseStack, SubmitNodeCollector collector, LevelRenderState state, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        gunfeel$mode = ArmorAbilities.espModeNow();
        if (player == null) return;
        gunfeel$tier = aimsThermalSights(player) ? ArmorAbilities.GUN_SIGHT_TIER : ArmorAbilities.tierOf(activeChestBonus(player));
        gunfeel$range = ArmorAbilities.espRangeNow();
    }

    @Inject(method = "colorFor", at = @At("RETURN"), cancellable = true, require = 1)
    private static void gunfeel$thermal(Entity ent, LocalPlayer viewer, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() < 0 || !(ent instanceof Player)) return;
        if (!ArmorAbilities.thermalKeeps(gunfeel$mode, gunfeel$range, gunfeel$tier, viewer, ent)) cir.setReturnValue(-1);
    }

    @WrapOperation(method = "submitHealthBars", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"), require = 1)
    private static Iterable<Entity> gunfeel$vats(ClientLevel level, Operation<Iterable<Entity>> original) {
        Iterable<Entity> all = original.call(level);
        if (ArmorAbilities.vatsShows(gunfeel$mode)) return all;
        java.util.List<Entity> out = new java.util.ArrayList<>();
        for (Entity e : all) if (ArmorAbilities.vatsKeeps(gunfeel$mode, e)) out.add(e);
        return out;
    }
}
