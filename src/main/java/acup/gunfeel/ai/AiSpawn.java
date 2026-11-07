package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * AI mobs by command, round a point: NTM's FBI agent (with its own raid loadout: a heavy revolver or a SPAS-12, now
 * and then armour) or NTM's test dummy (a heavy revolver, walking at a soldier's pace). A gun named replaces either's.
 * Each comes in with a full gun and {@link AiAdapter#RESERVE_MAGS} magazines besides, tagged for the mob AI.
 */
public final class AiSpawn {
    public enum Kind {
        FBI("entity_fbi"),
        DUMMY("entity_test_dummy");

        final String id;

        Kind(String id) {
            this.id = id;
        }
    }

    static final Identifier DEFAULT_GUN = Identifier.fromNamespaceAndPath("hbm", "gun_heavy_revolver");
    /** a dummy walks at this, a zombie's is 0.23, NTM's FBI 0.3 */
    static final double DUMMY_SPEED = 0.25;

    private AiSpawn() {
    }

    public static List<Mob> spawn(ServerLevel level, Vec3 at, Kind kind, int count, @Nullable Item gun) {
        List<Mob> out = new ArrayList<>();
        var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("hbm", kind.id));
        RandomSource rng = level.getRandom();
        for (int i = 0; i < count; i++) {
            if (!(type.create(level, EntitySpawnReason.COMMAND) instanceof Mob mob)) break;
            if (!place(level, mob, at, rng, count)) {
                mob.discard();
                continue;
            }
            if (kind == Kind.FBI) mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.COMMAND, null);
            Item hand = gun;
            if (hand == null && kind == Kind.DUMMY) hand = BuiltInRegistries.ITEM.getValue(DEFAULT_GUN);
            if (hand != null) mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(hand));
            if (kind == Kind.DUMMY) {
                var speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null) speed.setBaseValue(DUMMY_SPEED);
            }
            mob.setPersistenceRequired();
            mob.addTag(AiAdapter.TAG);
            level.addFreshEntity(mob);
            AiBot bot = AiBots.get(mob);
            if (bot != null) bot.gunner.loadFree();
            out.add(mob);
        }
        return out;
    }

    /** somewhere free round the point, on a floor, within a few blocks (wider for more mobs) */
    private static boolean place(ServerLevel level, Mob mob, Vec3 at, RandomSource rng, int count) {
        double reach = 2 + Math.sqrt(count) * 1.5;
        for (int attempt = 0; attempt < 16; attempt++) {
            double a = rng.nextDouble() * Math.PI * 2, r = 1.5 + rng.nextDouble() * reach;
            double x = Math.floor(at.x + Math.cos(a) * r) + 0.5, z = Math.floor(at.z + Math.sin(a) * r) + 0.5;
            for (int dy = 3; dy >= -4; dy--) {
                BlockPos feet = BlockPos.containing(x, at.y + dy, z);
                if (level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) continue;
                mob.setPos(x, feet.getY(), z);
                float yaw = rng.nextFloat() * 360F;
                mob.setYRot(yaw);
                mob.setYHeadRot(yaw);
                mob.setYBodyRot(yaw);
                if (level.noCollision(mob)) return true;
            }
        }
        return false;
    }
}
