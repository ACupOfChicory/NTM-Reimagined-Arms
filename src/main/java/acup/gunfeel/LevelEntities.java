package acup.gunfeel;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** 26.2's entities for core (EntityView): a level's, by the game's own lookups. */
public final class LevelEntities implements EntityView {
    private final Level level;

    public LevelEntities(Level level) {
        this.level = level;
    }

    @Override
    public @Nullable Body byId(int id) {
        Entity e = level.getEntity(id);
        return e == null ? null : EntityBody.of(e);
    }

    private static Class<? extends Entity> kind(int kind) {
        return switch (kind) {
            case LIVING -> LivingEntity.class;
            case MOB -> Mob.class;
            case PROJECTILE -> Projectile.class;
            default -> throw new IllegalArgumentException("kind " + kind);
        };
    }

    @Override
    public int near(int kind, double x0, double y0, double z0, double x1, double y1, double z1, Body[] out) {
        // the game's two-argument getEntitiesOfClass: spectators left out (EntitySelector.NO_SPECTATORS)
        List<? extends Entity> found = level.getEntitiesOfClass(kind(kind), new AABB(x0, y0, z0, x1, y1, z1));
        int n = found.size();
        if (n > out.length) return -n;
        for (int i = 0; i < n; i++) out[i] = EntityBody.of(found.get(i));
        return n;
    }
}
