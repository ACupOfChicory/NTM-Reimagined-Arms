package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ai.AiBots;
import acup.gunfeel.ai.AiPose;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// The game sends a mob's yaw and its head's to those who see it every third tick (its type's update interval): an AI
// mob turning by its aim 30 degrees a tick comes to them 90 degrees at a time, and the client draws its turn either
// that much late or in jerks. An AI mob's yaw and head go out every tick they change, as the rest is sent: nothing
// more is sent of a mob standing still or of any other, and nothing the server does changes. The yaw goes with the
// mob's place: a turn alone starts the client's move to the place it last had over again (InterpolationHandler), and
// a mob walking and turning would come there ever slower between the places sent.
@Mixin(ServerEntity.class)
public abstract class MixinAiTurnSync {
    @Shadow
    @Final
    private Entity entity;
    @Shadow
    @Final
    private ServerEntity.Synchronizer synchronizer;
    @Shadow
    @Final
    private VecDeltaCodec positionCodec;
    @Shadow
    private byte lastSentYRot;
    @Shadow
    private byte lastSentXRot;
    @Shadow
    private byte lastSentYHeadRot;

    @Inject(method = "sendChanges", at = @At("TAIL"))
    private void gunfeel$aiTurnEveryTick(CallbackInfo ci) {
        // a mob under the mob AI, or one posed as one (the bot stands)
        if (!(entity instanceof LivingEntity mob) || entity.isPassenger()
                || AiBots.get(mob) == null && !mob.hasData(AiPose.Sync.TYPE)) return;
        byte y = Mth.packDegrees(entity.getYRot()), x = Mth.packDegrees(entity.getXRot());
        if (y != lastSentYRot || x != lastSentXRot) {
            Vec3 at = entity.trackingPosition();
            long i = positionCodec.encodeX(at), j = positionCodec.encodeY(at), k = positionCodec.encodeZ(at);
            if (gunfeel$short(i) && gunfeel$short(j) && gunfeel$short(k)) {
                synchronizer.sendToTrackingPlayers(new ClientboundMoveEntityPacket.PosRot(entity.getId(), (short) i, (short) j, (short) k, y, x,
                        entity.onGround()));
                positionCodec.setBase(at);
            } else {
                // too far for a move: the game's own teleport follows on its tick
                synchronizer.sendToTrackingPlayers(new ClientboundMoveEntityPacket.Rot(entity.getId(), y, x, entity.onGround()));
            }
            lastSentYRot = y;
            lastSentXRot = x;
        }
        byte head = Mth.packDegrees(mob.getYHeadRot());
        if (head != lastSentYHeadRot) {
            synchronizer.sendToTrackingPlayers(new ClientboundRotateHeadPacket(entity, head));
            lastSentYHeadRot = head;
        }
    }

    private static boolean gunfeel$short(long v) {
        return v >= Short.MIN_VALUE && v <= Short.MAX_VALUE;
    }
}
