package acup.gunfeel.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
 * /armsai debug [radius]   show (again: hide) the tactical map's cells around you, radius 16 blocks by default, and
 *                          what the AI mobs round you think; while anyone watches, their decisions go to the journal
 * /armsai debug rooms [radius]  show (again: hide) the buildings round you as the AI reads them, radius 24 by default:
 *                          rooms, corridors, ways with the cells beside them, windows, stairs, places under a roof
 *                          that are no building; the colours are said in the chat
 * /armsai debug walls      with them, the blocks of the rooms' shells by their kind (again: without)
 * /armsai debug dump       the same in numbers: a line in the chat, the rest in the journal
 * /armsai log on|off       the AI mobs' decision journal in logs/ntm-gunfeel-debug.log with no one watching too
 *                          (AiThoughts); off by default
 * /armsai info             the map of this level: sections, builds, their time, block changes seen; the AI mobs and
 *                          what their looks cost
 * /armsai spawn fbi|dummy [count] [gun] [armor …] [ammo &lt;round&gt;] [role &lt;name&gt;] [team &lt;name&gt;]   AI mobs round you (NTM's FBI agent
 *                          or test dummy), 1 by default; in a team of the game's (made if there is none) they fight the
 *                          other teams (AiTeams); the armour they wear and the round their guns load, in any order (AiKit)
 * /armsai kit &lt;targets&gt; [armor …] [ammo &lt;round&gt;] [role &lt;name&gt;]   the AI mobs among the targets change armour, load
 *                          the round, take the role (what each is to its squad whatever its gun; auto: by its gun)
 * /armsai clear            every AI mob of this level goes
 * /armsai ammo infinite|&lt;rounds&gt;   what every AI mob of this level carries besides its gun
 * </pre>
 */
public final class AiCommand {
    /** where the decision journal goes (DebugLog's file) */
    static final String JOURNAL = "logs/ntm-gunfeel-debug.log";

    private AiCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("armsai").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        root.then(Commands.literal("debug").executes(c -> debug(c, 16))
                .then(Commands.argument("radius", IntegerArgumentType.integer(4, 48))
                        .executes(c -> debug(c, IntegerArgumentType.getInteger(c, "radius"))))
                .then(Commands.literal("rooms").executes(c -> rooms(c, 24))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 48))
                                .executes(c -> rooms(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("terrain").executes(c -> terrain(c, 48))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, 96))
                                .executes(c -> terrain(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("walls").executes(AiCommand::walls))
                .then(Commands.literal("dump").executes(AiCommand::dump)));
        root.then(Commands.literal("info").executes(AiCommand::info));
        root.then(Commands.literal("log").then(Commands.literal("on").executes(c -> log(c, true)))
                .then(Commands.literal("off").executes(c -> log(c, false))));
        for (AiSpawn.Kind kind : AiSpawn.Kind.values()) {
            String name = kind.name().toLowerCase(java.util.Locale.ROOT);
            // spawn <kind> [count] [gun] [armor …] [ammo <round>] [team <name>]: the words after the gun in any order
            // (AiKit), after the count or the kind too (the kind's own gun)
            var byGun = Commands.argument("gun", IdentifierArgument.id()).suggests((c, b) -> SharedSuggestionProvider.suggestResource(guns(), b))
                    .executes(c -> spawn(c, kind, IntegerArgumentType.getInteger(c, "count"), IdentifierArgument.getId(c, "gun"), null));
            AiKitCommand.words(byGun, true, c -> java.util.List.of(hand(kind, BuiltInRegistries.ITEM.getOptional(IdentifierArgument.getId(c, "gun")).orElse(null))),
                    (c, words) -> spawn(c, kind, IntegerArgumentType.getInteger(c, "count"), IdentifierArgument.getId(c, "gun"), words));
            var byCount = Commands.argument("count", IntegerArgumentType.integer(1, 200))
                    .executes(c -> spawn(c, kind, IntegerArgumentType.getInteger(c, "count"), null, null));
            AiKitCommand.words(byCount, true, c -> java.util.List.of(hand(kind, null)),
                    (c, words) -> spawn(c, kind, IntegerArgumentType.getInteger(c, "count"), null, words));
            var byKind = Commands.literal(name).executes(c -> spawn(c, kind, 1, null, null));
            AiKitCommand.words(byKind, true, c -> java.util.List.of(hand(kind, null)), (c, words) -> spawn(c, kind, 1, null, words));
            root.then(Commands.literal("spawn").then(byKind.then(byCount.then(byGun))));
        }
        root.then(AiKitCommand.kit());
        root.then(Commands.literal("clear").executes(AiCommand::clear));
        root.then(Commands.literal("ammo").then(Commands.literal("infinite").executes(c -> ammo(c, AiAmmo.INFINITE)))
                .then(Commands.argument("rounds", IntegerArgumentType.integer(0, 100000))
                        .executes(c -> ammo(c, IntegerArgumentType.getInteger(c, "rounds")))));
        d.register(root);
    }

    private static int debug(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        boolean on = AiDebug.toggle(p, radius);
        c.getSource().sendSuccess(() -> Component.literal(on ? "AI cells shown, radius " + radius + "; AI decisions go to " + JOURNAL : "AI cells hidden"),
                false);
        return 1;
    }

    /** what the colours of /armsai debug rooms mean, said as it is turned on */
    static final String[] LEGEND = {
            "Buildings as the AI reads them, radius %d (again: hide):",
            " floor in a colour: a room, each its own; over its middle its number, room or corridor, its cells",
            " orange cell with a frame: a way (a doorway, a hole, a corridor's mouth); red: with a door; bright blue: over a sill (a window they climb through); a post in it: it leads out from under the roof",
            " small yellow and pink blocks: the cells beside a way where a squad stacks up (pink: the first room's side, yellow: the other side or outside)",
            " light blue pane in a wall: a window seen and shot through and not walked through; paler: glazed",
            " white bars on a floor: stairs; white rungs: a ladder",
            " grey cross: under a roof, but no building (a cave, under a tree)",
            " /armsai debug walls: the blocks of the rooms' shells (brown: the ground, blue-grey: put there, green: someone lives there)",
            " /armsai debug dump: all of it in numbers, here and in " + JOURNAL};

    private static int rooms(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        boolean on = AiDebug.toggleRooms(p, radius);
        if (!on) c.getSource().sendSuccess(() -> Component.literal("AI buildings hidden"), false);
        else for (String line : LEGEND) c.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT, line, radius)), false);
        return 1;
    }

    private static int terrain(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        boolean on = AiDebug.toggleTerrain(p, radius);
        c.getSource().sendSuccess(() -> Component.literal(!on ? "AI terrain hidden"
                : "The lie of the land as the AI reads it, radius " + radius + " (again: hide): squares of 4 x 4 blocks, red where one sees nowhere to green where one sees far; "
                        + "a white frame: a height, dark blue: a hollow, light blue: water; a green cross: under the crowns of trees; "
                        + "over each chunk how far one sees from it (blocks) and how high it stands over the land around"), false);
        return 1;
    }

    private static int walls(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        boolean on = AiDebug.toggleWalls(p);
        c.getSource().sendSuccess(() -> Component.literal(on ? "AI buildings: the blocks of the rooms' shells shown (brown: the ground, blue-grey: put there, green: someone lives there)"
                : "AI buildings: the blocks of the rooms' shells hidden"), false);
        return 1;
    }

    private static int dump(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        int radius = AiDebug.roomsRadius(p);
        java.util.List<String> lines = AiDebug.dump(TacticalMap.of(p.level()), p.blockPosition(), radius < 0 ? 24 : radius);
        for (String line : lines) acup.gunfeel.DebugLog.LOGGER.info("armsai rooms: " + line);
        c.getSource().sendSuccess(() -> Component.literal(lines.get(0)), false);
        c.getSource().sendSuccess(() -> Component.literal((lines.size() - 1) + " more lines in " + JOURNAL), false);
        return 1;
    }

    private static int log(CommandContext<CommandSourceStack> c, boolean on) {
        AiThoughts.log = on;
        c.getSource().sendSuccess(() -> Component.literal(on ? "AI decisions go to " + JOURNAL
                : "AI decision journal off" + (AiDebug.watching() ? " (on while anyone watches /armsai debug)" : "")), true);
        return 1;
    }

    private static java.util.List<Identifier> guns() {
        java.util.List<Identifier> out = new java.util.ArrayList<>();
        for (var item : BuiltInRegistries.ITEM)
            if (item instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT) out.add(BuiltInRegistries.ITEM.getKey(item));
        return out;
    }

    /** the gun every mob of the kind comes with: the one named, a dummy's own; empty when each has its own (FBI agents) */
    private static net.minecraft.world.item.ItemStack hand(AiSpawn.Kind kind, @Nullable Item gun) {
        if (gun == null && kind == AiSpawn.Kind.DUMMY) gun = BuiltInRegistries.ITEM.getValue(AiSpawn.DEFAULT_GUN);
        return gun == null ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(gun);
    }

    /** words: what follows the gun (AiKit): the armour, the round, the game's team the mobs join (made if there is none, AiTeams) */
    private static int spawn(CommandContext<CommandSourceStack> c, AiSpawn.Kind kind, int count, @Nullable Identifier gunId, @Nullable String words)
            throws CommandSyntaxException {
        Item gun = null;
        if (gunId != null) {
            gun = BuiltInRegistries.ITEM.getOptional(gunId).orElse(null);
            if (gun == null) {
                c.getSource().sendFailure(Component.literal("no item " + gunId));
                return 0;
            }
        }
        // a kit that makes no sense is told before any mob is made
        AiKitCommand.Kit kit = AiKitCommand.read(words, true);
        kit.fits(hand(kind, gun));
        String team = kit.team();
        var mobs = AiSpawn.spawn(c.getSource().getLevel(), c.getSource().getPosition(), kind, count, gun);
        if (team != null) for (var m : mobs) AiTeams.join(c.getSource().getLevel(), m, team);
        // a number and no role named: the roles are dealt among them (one supplier to each six, from four up)
        int dealt = kit.role() == null ? AiRole.deal(mobs, c.getSource().getLevel().getRandom()) : 0;
        String kitted = kit.putOn(mobs) + (dealt == 0 ? "" : ", " + dealt + (dealt == 1 ? " of them a supplier" : " of them suppliers"));
        int n = mobs.size();
        c.getSource().sendSuccess(() -> Component.literal(n + " AI " + kind.name().toLowerCase(java.util.Locale.ROOT) + (n == 1 ? "" : "s")
                + " spawned" + (team == null ? "" : " in team " + team) + kitted + (n < count ? " (no room for " + (count - n) + ")" : "")), true);
        if (c.getSource().getEntity() instanceof ServerPlayer p && (p.isCreative() || p.isSpectator()))
            c.getSource().sendSuccess(() -> Component.translatable("ntmgunfeel.armsai.unseen"), false);
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
                + AiBots.count(c.getSource().getLevel()) + " (" + AiSquads.roles(AiBots.all(c.getSource().getLevel())) + "); senses: " + Senses.info() + "; " + CoverQuery.info();
        AiTerrain terrain = AiTerrain.peek(c.getSource().getLevel());
        String all = terrain == null ? text : text + "\n" + terrain.info();
        c.getSource().sendSuccess(() -> Component.literal(all), false);
        return 1;
    }
}
