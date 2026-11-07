package acup.gunfeel.smoke;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server, for testing until GL-54 throws its smoke grenade (operators only):
 * <pre>
 * /gunsmoke                         a cloud where the player looks (the cell in front of the block hit), fanned along the look
 * /gunsmoke at x y z [count] [step] count clouds from x y z, step blocks apart along x (a stand's 8 clouds)
 * /gunsmoke kind name ...           the same of another kind (smoke)
 * /gunsmoke clear                   every cloud of the level gone
 * /gunsmoke info                    clouds and the server's time on them
 * </pre>
 */
public final class SmokeCommand {
    private SmokeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("gunsmoke").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> look(c, SmokeKind.SMOKE));
        root.then(Commands.literal("clear").executes(SmokeCommand::clear));
        root.then(Commands.literal("info").executes(SmokeCommand::info));
        root.then(Commands.literal("at").then(Commands.argument("pos", Vec3Argument.vec3())
                .executes(c -> at(c, SmokeKind.SMOKE, 1, 0F))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, SmokeClouds.LIMIT))
                        .executes(c -> at(c, SmokeKind.SMOKE, IntegerArgumentType.getInteger(c, "count"), 12F))
                        .then(Commands.argument("step", FloatArgumentType.floatArg(0F, 64F))
                                .executes(c -> at(c, SmokeKind.SMOKE, IntegerArgumentType.getInteger(c, "count"), FloatArgumentType.getFloat(c, "step")))))));
        root.then(Commands.literal("kind").then(Commands.argument("name", StringArgumentType.word())
                .executes(c -> look(c, kind(c)))));
        d.register(root);
    }

    private static SmokeKind kind(CommandContext<CommandSourceStack> c) {
        SmokeKind k = SmokeKind.byName(StringArgumentType.getString(c, "name"));
        return k == null ? SmokeKind.SMOKE : k;
    }

    // the server's key turns smoke off: say so, rather than blaming the spot
    private static boolean off(CommandSourceStack src) {
        if (SmokeClouds.enabled()) return false;
        src.sendFailure(Component.literal("Smoke is off on this server (smoke = false in ntmgunfeel-server.properties)"));
        return true;
    }

    private static int look(CommandContext<CommandSourceStack> c, SmokeKind kind) {
        var src = c.getSource();
        if (off(src)) return 0;
        Vec3 eye = src.getPosition(), dir = Vec3.directionFromRotation(src.getRotation());
        if (src.getEntity() != null) eye = src.getEntity().getEyePosition();
        BlockHitResult hit = src.getLevel().clip(new ClipContext(eye, eye.add(dir.scale(64)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY,
                src.getEntity() == null ? net.minecraft.world.phys.shapes.CollisionContext.empty() : net.minecraft.world.phys.shapes.CollisionContext.of(src.getEntity())));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? eye.add(dir.scale(16)) : hit.getLocation().subtract(dir.scale(0.3));
        int id = SmokeClouds.spawn(src.getLevel(), at, dir, kind, 1F);
        if (id < 0) {
            src.sendFailure(Component.literal("No smoke: the point is shut in"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Smoke cloud " + id + " at " + String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", at.x, at.y, at.z)), false);
        return 1;
    }

    private static int at(CommandContext<CommandSourceStack> c, SmokeKind kind, int count, float step) {
        var src = c.getSource();
        if (off(src)) return 0;
        Vec3 pos = Vec3Argument.getVec3(c, "pos");
        Vec3 dir = Vec3.directionFromRotation(src.getRotation());
        int made = 0;
        for (int i = 0; i < count; i++) if (SmokeClouds.spawn(src.getLevel(), pos.add(i * step, 0, 0), dir, kind, 1F) >= 0) made++;
        int n = made;
        src.sendSuccess(() -> Component.literal(n + " smoke clouds"), false);
        return n;
    }

    private static int clear(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        SmokeClouds all = SmokeClouds.get(level);
        int n = 0;
        if (all != null) for (SmokeClouds.Cloud cloud : all.clouds()) {
            all.remove(cloud.id);
            SmokeNet.ended(level, cloud);
            n++;
        }
        int cleared = n;
        c.getSource().sendSuccess(() -> Component.literal(cleared + " smoke clouds cleared"), false);
        return n;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        SmokeClouds all = SmokeClouds.get(c.getSource().getLevel());
        int n = all == null ? 0 : all.clouds().size();
        c.getSource().sendSuccess(() -> Component.literal(n + " smoke clouds; server " + SmokeClouds.serverCost()), false);
        return n;
    }
}
