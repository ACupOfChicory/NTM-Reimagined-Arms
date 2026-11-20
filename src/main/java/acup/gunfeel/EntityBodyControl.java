package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import acup.gunfeel.ai.AiMoveControl;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;

/** 26.2's BodyControl: each method one call of the game's on the entity behind the Body. */
final class EntityBodyControl implements BodyControl {
    @Override
    public void removeEffect(Body b, int which) {
        ((LivingEntity) EntityBody.entity(b)).removeEffect(switch (which) {
            case Body.EFFECT_REGENERATION -> net.minecraft.world.effect.MobEffects.REGENERATION;
            case Body.EFFECT_RESISTANCE -> net.minecraft.world.effect.MobEffects.RESISTANCE;
            default -> throw new IllegalArgumentException("no effect " + which);
        });
    }

    @Override
    public void tellBar(Body b, String key, int arg) {
        if (EntityBody.entity(b) instanceof net.minecraft.server.level.ServerPlayer sp)
            sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(key, arg), true);
    }

    private static Mob mob(Body b) {
        return (Mob) EntityBody.entity(b);
    }

    @Override
    public void stance(Body b, byte stance) {
        EntityBody.entity(b).setPose(EntityBody.pose(stance));
    }

    @Override
    public void speed(Body b, double modifier) {
        mob(b).getNavigation().setSpeedModifier(modifier);
    }

    @Override
    public void stop(Body b) {
        mob(b).getNavigation().stop();
    }

    @Override
    public boolean done(Body b) {
        return mob(b).getNavigation().isDone();
    }

    @Override
    public boolean walk(Body b, long[] cells, boolean reached, double modifier) {
        List<Node> nodes = new ArrayList<>(cells.length);
        for (long c : cells) {
            Node n = new Node(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c));
            n.type = PathType.WALKABLE;
            nodes.add(n);
        }
        long end = cells[cells.length - 1];
        Path p = new Path(nodes, BlockPos.of(end), reached);
        return mob(b).getNavigation().moveTo(p, modifier);
    }

    @Override
    public int pathNext(Body b) {
        Path p = mob(b).getNavigation().getPath();
        return p == null ? 0 : p.getNextNodeIndex();
    }

    @Override
    public void want(Body b, double x, double y, double z, double modifier) {
        mob(b).getMoveControl().setWantedPosition(x, y, z, modifier);
    }

    @Override
    public boolean stuck(Body b) {
        return mob(b).getMoveControl() instanceof AiMoveControl legs && legs.stuck();
    }

    @Override
    public void yRot(Body b, float v) {
        EntityBody.entity(b).setYRot(v);
    }

    @Override
    public void yHeadRot(Body b, float v) {
        EntityBody.entity(b).setYHeadRot(v);
    }

    @Override
    public void yBodyRot(Body b, float v) {
        ((LivingEntity) EntityBody.entity(b)).yBodyRot = v;
    }

    @Override
    public void xRot(Body b, float v) {
        EntityBody.entity(b).setXRot(v);
    }

    @Override
    public void face(Body b, float yaw) {
        if (mob(b).getMoveControl() instanceof AiMoveControl legs) legs.face(yaw);
    }

    @Override
    public boolean turned(Body b) {
        return mob(b).getMoveControl() instanceof AiMoveControl legs && legs.turned();
    }

    @Override
    public void lookAt(Body b, double x, double y, double z, float yMax, float xMax) {
        mob(b).getLookControl().setLookAt(x, y, z, yMax, xMax);
    }

    @Override
    public void snap(Body b, double x, double y, double z, float yRot, float xRot) {
        EntityBody.entity(b).snapTo(x, y, z, yRot, xRot);
    }

    @Override
    public void place(Body b, double x, double y, double z) {
        EntityBody.entity(b).setPos(x, y, z);
    }
}
