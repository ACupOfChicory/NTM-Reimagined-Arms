package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import acup.gunfeel.ai.AiMoveControl;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;

/** 26.2's BodyControl: each method one call of the game's on the entity behind the Body. */
final class EntityBodyControl implements BodyControl {
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
}
