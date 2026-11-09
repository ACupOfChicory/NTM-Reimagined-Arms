package acup.gunfeel.ai;

import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * What an AI mob's body does, as the server decides it and every client that sees the mob draws it: its stance, its
 * lean round a corner, what its arms and hands do. The server sets it (the mob's brain), the client only reads it
 * (the pose layer in the model's setupAnim). Synced to those who see the mob each time it changes, not every tick;
 * times are the level's game ticks, so each side works out a transition itself (AiPoseGeometry.leanFrac). The fields
 * and their values are the agreement with the pose layer (notes: pose-contract), not to be changed one-sided.
 *
 * @param stance      {@link #STAND}, {@link #CROUCH}, {@link #PRONE}
 * @param lean        the lean it goes to, -100 (left) to 100 (right), as seen from the mob
 * @param prevLean    the lean it had got to when {@code lean} changed, the same scale
 * @param leanTick    the game tick {@code lean} changed
 * @param arms        {@link #HOLD}, {@link #AIM}, {@link #BLIND}, {@link #NO_GUN}
 * @param action      {@link #NONE}, {@link #RELOAD}, {@link #THROW}, {@link #SIGNAL_POINT}, {@link #SIGNAL_HALT},
 *                    {@link #SIGNAL_COME}, {@link #SHOT}, {@link #SLIDE}, {@link #ROLL}
 * @param actionTick  the game tick the action began
 * @param actionTicks how long it lasts (a reload: till NTM's animation ends); 0: as long as its clip
 * @param alert       {@link #CALM} or {@link #FIGHT}: whether it is in a fight (its brain out of its idle mode); the
 *                    pose layer walks it with its gun ready, or strolls with it lowered (pose-contract §5)
 */
public record AiPose(byte stance, byte lean, byte prevLean, int leanTick, byte arms, byte action, int actionTick, short actionTicks,
        byte alert) {
    public static final byte CALM = 0, FIGHT = 1;

    /** calm */
    public AiPose(byte stance, byte lean, byte prevLean, int leanTick, byte arms, byte action, int actionTick, short actionTicks) {
        this(stance, lean, prevLean, leanTick, arms, action, actionTick, actionTicks, CALM);
    }

    public static final byte STAND = 0, CROUCH = 1, PRONE = 2;
    /** the gun at the chest, aimed where it looks, held up blind over cover, no gun (gestures) */
    public static final byte HOLD = 0, AIM = 1, BLIND = 2, NO_GUN = 3;
    public static final byte NONE = 0, RELOAD = 1, THROW = 2, SIGNAL_POINT = 3, SIGNAL_HALT = 4, SIGNAL_COME = 5, SHOT = 6;
    /**
     * a slide into cover, a forward roll under fire (AiDash): the whole body, crouched, no shot; only for these
     * actions' ticks the drawn head leaves the eye the server keeps (pose-contract §4)
     */
    public static final byte SLIDE = 7, ROLL = 8;

    /** a crouching AI mob's size: a crouching player's (Avatar.CROUCHING_DIMENSIONS) */
    public static final net.minecraft.world.entity.EntityDimensions CROUCH_SIZE = net.minecraft.world.entity.EntityDimensions.scalable(0.6F, 1.5F)
            .withEyeHeight(1.27F);

    public static final AiPose IDLE = new AiPose(STAND, (byte) 0, (byte) 0, 0, HOLD, NONE, 0, (short) 0);

    public static final StreamCodec<ByteBuf, AiPose> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AiPose decode(ByteBuf b) {
            return new AiPose(b.readByte(), b.readByte(), b.readByte(), ByteBufCodecs.VAR_INT.decode(b), b.readByte(), b.readByte(),
                    ByteBufCodecs.VAR_INT.decode(b), b.readShort(), b.readByte());
        }

        @Override
        public void encode(ByteBuf b, AiPose p) {
            b.writeByte(p.stance);
            b.writeByte(p.lean);
            b.writeByte(p.prevLean);
            ByteBufCodecs.VAR_INT.encode(b, p.leanTick);
            b.writeByte(p.arms);
            b.writeByte(p.action);
            ByteBufCodecs.VAR_INT.encode(b, p.actionTick);
            b.writeShort(p.actionTicks);
            b.writeByte(p.alert);
        }
    };

    /**
     * The attachment's registration, in a class of its own: loaded on first use (the game's), so a unit test that
     * makes an AiPose needs no registries (plain JUnit has none).
     */
    public static final class Sync {
        private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "ntmgunfeel");
        /** not saved: a mob loaded from disk starts standing, and its brain sets the rest on its first ticks */
        public static final Supplier<AttachmentType<AiPose>> TYPE = TYPES.register("ai_pose",
                () -> AttachmentType.builder(() -> IDLE).sync(STREAM_CODEC).build());

        private Sync() {
        }
    }

    public static void register(IEventBus modBus) {
        Sync.TYPES.register(modBus);
    }

    /** the mob's pose; standing with nothing on for any that has none (any mob not under the AI), either side */
    public static AiPose of(LivingEntity e) {
        AiPose p = e.getExistingDataOrNull(Sync.TYPE);
        return p == null ? IDLE : p;
    }

    /** the server: the pose, sent on only if it changed */
    static void set(LivingEntity e, AiPose p) {
        if (!p.equals(of(e))) e.setData(Sync.TYPE, p);
    }

    AiPose withStance(byte s) {
        return s == stance ? this : new AiPose(s, lean, prevLean, leanTick, arms, action, actionTick, actionTicks, alert);
    }

    AiPose withArms(byte a) {
        return a == arms ? this : new AiPose(stance, lean, prevLean, leanTick, a, action, actionTick, actionTicks, alert);
    }

    /**
     * A new lean to go to from this tick on: from where it has got to now (AiPoseGeometry.leanFrac), not from the old
     * target, so a change halfway does not jump; the same lean stays as it was.
     */
    AiPose withLean(byte l, long now) {
        if (l == lean) return this;
        byte at = (byte) Math.round(AiPoseGeometry.leanFrac(this, now, 0F) * 100F);
        return new AiPose(stance, l, at, (int) now, arms, action, actionTick, actionTicks, alert);
    }

    /** in a fight or calm (CALM, FIGHT) */
    public AiPose withAlert(byte a) {
        return a == alert ? this : new AiPose(stance, lean, prevLean, leanTick, arms, action, actionTick, actionTicks, a);
    }

    /** a new action from this tick on (the same one going on stays as it was) */
    AiPose withAction(byte a, int tick, short ticks) {
        return a == action ? this : new AiPose(stance, lean, prevLean, leanTick, arms, a, tick, ticks, alert);
    }
}
