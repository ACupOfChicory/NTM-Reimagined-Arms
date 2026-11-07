package acup.gunfeel.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import org.jspecify.annotations.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The mob AI's operator command:
 * <pre>
 * /armsai debug [radius]   show (again: hide) the tactical map's cells around you, radius 16 blocks by default
 * /armsai info             the map of this level: sections, builds, their time, block changes seen; the AI mobs and
 *                          what their looks cost
 * /armsai spawn fbi|dummy [count] [gun] [team &lt;name&gt;]   AI mobs round you (NTM's FBI agent or test dummy), 1 by
 *                          default; in a team of the game's (made if there is none) they fight the other teams (AiTeams)
 * /armsai clear            every AI mob of this level goes
 * /armsai ammo infinite|&lt;rounds&gt;   what every AI mob of this level carries besides its gun
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
        for (AiSpawn.Kind kind : AiSpawn.Kind.values()) {
            String name = kind.name().toLowerCase(java.util.Locale.ROOT);
            // spawn <kind> [count] [gun] [team <name>]
            root.then(Commands.literal("spawn").then(Commands.literal(name).executes(c -> spawn(c, kind, 1, null, null))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                            .executes(c -> spawn(c, kind, IntegerArgumentType.getInteger(c, "count"), null, null))
                            .then(team(c -> spawn(c, kind, IntegerArgumentType.getInteger(c, "count"), null, StringArgumentType.getString(c, "team"))))
                            .then(Commands.argument("gun", IdentifierArgument.id()).suggests((c, b) -> SharedSuggestionProvider.suggestResource(guns(), b))
                                    .executes(c -> spawn(c, kind, IntegerArgumentType.getInteger(c, "count"), IdentifierArgument.getId(c, "gun"), null))
                                    .then(team(c -> spawn(c, kind, IntegerArgumentType.getInteger(c, "count"), IdentifierArgument.getId(c, "gun"),
                                            StringArgumentType.getString(c, "team"))))))));
        }
        root.then(Commands.literal("clear").executes(AiCommand::clear));
        root.then(Commands.literal("ammo").then(Commands.literal("infinite").executes(c -> ammo(c, AiAmmo.INFINITE)))
                .then(Commands.argument("rounds", IntegerArgumentType.integer(0, 100000))
                        .executes(c -> ammo(c, IntegerArgumentType.getInteger(c, "rounds")))));
        d.register(root);
    }

    private static int debug(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        boolean on = AiDebug.toggle(p, radius);
        c.getSource().sendSuccess(() -> Component.literal(on ? "AI cells shown, radius " + radius : "AI cells hidden"), false);
        return 1;
    }

    private static java.util.List<Identifier> guns() {
        java.util.List<Identifier> out = new java.util.ArrayList<>();
        for (var item : BuiltInRegistries.ITEM)
            if (item instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT) out.add(BuiltInRegistries.ITEM.getKey(item));
        return out;
    }

    // "team <name>": the game's team the mobs join (made if there is none), see AiTeams
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> team(com.mojang.brigadier.Command<CommandSourceStack> run) {
        return Commands.literal("team").then(Commands.argument("team", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getLevel().getScoreboard().getTeamNames(), b)).executes(run));
    }

    private static int spawn(CommandContext<CommandSourceStack> c, AiSpawn.Kind kind, int count, @Nullable Identifier gunId, @Nullable String team)
            throws CommandSyntaxException {
        Item gun = null;
        if (gunId != null) {
            gun = BuiltInRegistries.ITEM.getOptional(gunId).orElse(null);
            if (gun == null) {
                c.getSource().sendFailure(Component.literal("no item " + gunId));
                return 0;
            }
        }
        var mobs = AiSpawn.spawn(c.getSource().getLevel(), c.getSource().getPosition(), kind, count, gun);
        if (team != null) for (var m : mobs) AiTeams.join(c.getSource().getLevel(), m, team);
        int n = mobs.size();
        c.getSource().sendSuccess(() -> Component.literal(n + " AI " + kind.name().toLowerCase(java.util.Locale.ROOT) + (n == 1 ? "" : "s")
                + " spawned" + (team == null ? "" : " in team " + team) + (n < count ? " (no room for " + (count - n) + ")" : "")), true);
        return n;
    }

    private static int clear(CommandContext<CommandSourceStack> c) {
        var bots = AiBots.all(c.getSource().getLevel());
        for (AiBot b : bots) b.mob.discard();
        int n = bots.size();
        c.getSource().sendSuccess(() -> Component.literal(n + " AI mobs removed"), true);
        return n;
    }

    private static int ammo(CommandContext<CommandSourceStack> c, int rounds) {
        var bots = AiBots.all(c.getSource().getLevel());
        for (AiBot b : bots) {
            if (rounds == AiAmmo.INFINITE) b.gunner.ammo.setInfinite();
            else b.gunner.ammo.set(rounds);
            AiAdapter.keep(b);
        }
        c.getSource().sendSuccess(() -> Component.literal(bots.size() + " AI mobs now carry "
                + (rounds == AiAmmo.INFINITE ? "endless rounds" : rounds + " rounds") + " besides their guns"), true);
        return bots.size();
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        TacticalMap map = TacticalMap.peek(c.getSource().getLevel());
        String text = (map == null ? "no tactical map in this level" : map.info()) + "\nAI mobs "
                + AiBots.count(c.getSource().getLevel()) + "; senses: " + Senses.info() + "; " + CoverQuery.info();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
