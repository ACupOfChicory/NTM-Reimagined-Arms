package acup.gunfeel.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The mob AI's operator command:
 * <pre>
 * /armsai debug [radius]   show (again: hide) the tactical map's cells around you, radius 16 blocks by default
 * /armsai info             the map of this level: sections, builds, their time, block changes seen
 * </pre>
 */
public final class AiCommand {
    private AiCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("armsai").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        root.then(Commands.literal("debug").executes(c -> debug(c, 16))
                .then(Commands.argument("radius", IntegerArgumentType.integer(4, 48))
                        .executes(c -> debug(c, IntegerArgumentType.getInteger(c, "radius")))));
        root.then(Commands.literal("info").executes(AiCommand::info));
        d.register(root);
    }

    private static int debug(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        boolean on = AiDebug.toggle(p, radius);
        c.getSource().sendSuccess(() -> Component.literal(on ? "AI cells shown, radius " + radius : "AI cells hidden"), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        TacticalMap map = TacticalMap.peek(c.getSource().getLevel());
        String text = map == null ? "no tactical map in this level" : map.info();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
