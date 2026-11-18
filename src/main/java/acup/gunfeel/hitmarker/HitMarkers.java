package acup.gunfeel.hitmarker;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBeamBase;

import acup.gunfeel.HeadDamage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

// Server: a player's shot hurt someone, the player is told (HitMarkerPayload) and their client draws the hit marker
// (HitMarkerClient). A shot: the damage of an NTM gun's bullet or beam, or one going through the mod's head and limb
// damage (HeadDamage.attack: NTM's bullets and beams, the legacy ones, the blasts of its rounds and grenades). Only
// damage that went through, after the armour: a hit that took nothing off draws nothing. On the head: HeadDamage's
// part of that hit; a kill: the hit left it dying. One packet a hit: a shotgun's pellets are put together on the client.
public final class HitMarkers {
    private HitMarkers() {
    }

    public static int sent; // the stand

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDamageEvent.Post.class, HitMarkers::hurt);
    }

    private static void hurt(LivingDamageEvent.Post e) {
        LivingEntity target = e.getEntity();
        if (target.level().isClientSide() || !(e.getInflictedDamage() > 0F)) return;
        if (!(e.getSource().getEntity() instanceof ServerPlayer shooter) || shooter == target) return;
        var direct = e.getSource().getDirectEntity();
        if (!HeadDamage.inAttack(target) && !(direct instanceof EntityBulletBaseMK4) && !(direct instanceof EntityBulletBeamBase)) return;
        sent++;
        PacketDistributor.sendToPlayer(shooter, new HitMarkerPayload(HeadDamage.onHead(target), target.isDeadOrDying()));
    }
}
