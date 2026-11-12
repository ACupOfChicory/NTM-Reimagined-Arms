package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

// Server side of suppression: a blast (NTM's ExplosionVNT, MixinSuppressionBlast; vanilla's, ExplosionEvent.Detonate)
// is told to the players near enough to feel it, not to the one who set it off (their own shot or grenade) nor to
// spectators. Bullets are not sent: each client sees them past and hitting by the tracers it flies (Suppression).
public final class SuppressionServer {
    private SuppressionServer() {
    }

    // ---- the server's settings (ntm-gunfeel-server.properties; a client reads the server's while connected)

    public static final String KEY = "suppression";

    public static boolean on() {
        return ReaimTimes.flag(KEY, true);
    }

    // each source's share of its force (0: it does not count) and the farthest it counts, blocks
    public static float passShare() {
        return Math.max(0F, ReaimTimes.number("suppressionPass", 1F));
    }

    public static float impactShare() {
        return Math.max(0F, ReaimTimes.number("suppressionImpact", 1F));
    }

    public static float blastShare() {
        return Math.max(0F, ReaimTimes.number("suppressionBlast", 1F));
    }

    public static double passRange() {
        return Math.max(0F, ReaimTimes.number("suppressionPassRange", 3.5F));
    }

    public static double impactRange() {
        return Math.max(0F, ReaimTimes.number("suppressionImpactRange", 8F));
    }

    public static double blastRange() {
        return Math.max(0F, ReaimTimes.number("suppressionBlastRange", 14F));
    }

    // seconds from a full scale to none
    public static float recoverySeconds() {
        return Math.max(0.5F, ReaimTimes.number("suppressionRecovery", 5F));
    }

    // the most the players' screens darken and their sounds go down, 0 to 1 (each player may take less)
    public static float darken() {
        return Math.clamp(ReaimTimes.number("suppressionDarken", 1F), 0F, 1F);
    }

    public static float muffle() {
        return Math.clamp(ReaimTimes.number("suppressionMuffle", 1F), 0F, 1F);
    }

    // the jolt of the view at each event (Squad's flinch), off by default, and its strength in degrees
    public static boolean flinch() {
        return ReaimTimes.flag("suppressionFlinch", false);
    }

    public static float flinchDeg() {
        return Math.clamp(ReaimTimes.number("suppressionFlinchDeg", 0.22F), 0F, 5F);
    }

    // ---- blasts

    // size: how far it hurts (NTM's ExplosionVNT size)
    public static void blast(ServerLevel level, Vec3 at, float size, @Nullable LivingEntity by) {
        // the AI mobs near feel it too, by their own setting (aiSuppression)
        acup.gunfeel.ai.AiUnderFire.blast(level, at, size, by);
        if (!on() || blastShare() <= 0F) return;
        double reach = SuppressionScale.blastRange(size, blastRange());
        var packet = new SuppressionBlastPayload(at.x, at.y, at.z, size);
        for (ServerPlayer p : level.players()) {
            if (p == by || p.isSpectator()) continue;
            if (p.getEyePosition().distanceToSqr(at) > reach * reach) continue;
            PacketDistributor.sendToPlayer(p, packet);
        }
    }

    // a vanilla blast or anything else that implements the game's Explosion; not a wind charge's gust
    public static void blast(net.minecraft.world.level.Explosion e) {
        if (!(e.level() instanceof ServerLevel level)) return;
        Entity direct = e.getDirectSourceEntity();
        if (direct instanceof net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge || e.radius() < 1F) return;
        // vanilla's radius is its power: it hurts out to twice that
        blast(level, e.center(), e.radius() * 2F, e.getIndirectSourceEntity());
    }
}
