package acup.gunfeel.ai.imitation;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The imitation's part of the operator command /armsai (Brigadier puts it under the same node as AiCommand's):
 * <pre>
 * /armsai record on|off        record your fights (FightRecorder) to logs/ntm-gunfeel-fights/; off by default
 * /armsai record bots on|off   record every AI mob's of this level, for the stands
 * /armsai course               the ground to fight on in front of you (FightCourse), in creative
 * /armsai course clear         takes it away
 * /armsai imitation info       the server key, the model, what it decided
 * /armsai imitation reload     reads config/ntm-gunfeel-imitation.json again
 * </pre>
 */
public final class ImitationCommand {
    private ImitationCommand() {
    }

    @FunctionalInterface
    interface Run {
        String run(CommandContext<CommandSourceStack> c) throws Exception;
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("armsai").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        root.then(Commands.literal("record")
                .then(Commands.literal("on").executes(c -> say(c, x -> FightRecorder.start(player(x)), true)))
                .then(Commands.literal("off").executes(c -> say(c, x -> FightRecorder.stop(player(x)), true)))
                .then(Commands.literal("bots").then(Commands.literal("on").executes(c -> say(c, x -> FightRecorder.startBots(x.getSource().getLevel()), true)))
                        .then(Commands.literal("off").executes(c -> say(c, x -> FightRecorder.stopBots(), true))))
                .executes(c -> say(c, x -> FightRecorder.info(), false)));
        root.then(Commands.literal("course").executes(c -> say(c, x -> FightCourse.build(player(x)), true))
                .then(Commands.literal("clear").executes(c -> say(c, x -> FightCourse.clear(player(x)), true))));
        root.then(Commands.literal("imitation").then(Commands.literal("info").executes(c -> say(c, x -> Imitation.info(), false)))
                .then(Commands.literal("reload").executes(c -> say(c, x -> Imitation.reload(x.getSource().getServer()), true))));
        d.register(root);
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return c.getSource().getPlayerOrException();
    }

    private static int say(CommandContext<CommandSourceStack> c, Run r, boolean broadcast) throws CommandSyntaxException {
        String text;
        try {
            text = r.run(c);
        } catch (CommandSyntaxException e) {
            throw e;
        } catch (Exception e) {
            c.getSource().sendFailure(Component.literal(e.toString()));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal(text), broadcast);
        return 1;
    }
}
