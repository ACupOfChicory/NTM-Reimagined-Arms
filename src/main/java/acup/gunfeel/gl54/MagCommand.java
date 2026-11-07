package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

// /gl54mag <kinds>   for operators: gives a magazine of those grenades, in the order they fire, e.g. /gl54mag he,he,airburst
// (up to 8). One kind alone fills the magazine with it; "empty" gives the empty magazine. This is how a mixed magazine is
// made until the equipment window (stage 3).
public final class MagCommand {
    private MagCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("gl54mag").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("kinds", StringArgumentType.greedyString()).executes(MagCommand::give)));
    }

    // the grenades of a list "he,he,airburst" (keys or codes); null when a word is not a kind; one kind fills the magazine
    static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        for (String word : text.split("[,\\s]+")) {
            if (word.isEmpty()) continue;
            if (word.equalsIgnoreCase(GrenadeKind.EMPTY)) return out.isEmpty() ? List.of() : null;
            String key = null;
            for (GrenadeKind k : GrenadeKind.values()) if (k.key.equalsIgnoreCase(word) || k.code.equalsIgnoreCase(word)) key = k.key;
            if (key == null) return null;
            out.add(key);
        }
        if (out.size() > MagContents.CAPACITY) return null;
        if (out.size() == 1) return MagContents.filled(out.get(0), MagContents.CAPACITY).rounds();
        return out;
    }

    private static int give(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String text = StringArgumentType.getString(c, "kinds");
        List<String> rounds = parse(text);
        if (rounds == null || (rounds.isEmpty() && !text.trim().equalsIgnoreCase(GrenadeKind.EMPTY))) {
            c.getSource().sendFailure(Component.translatable("message.ntmgunfeel.gl54_mag_bad", text));
            return 0;
        }
        var player = c.getSource().getPlayerOrException();
        ItemStack stack = Gl54Items.magazine(new MagContents(rounds));
        Component name = stack.getHoverName();
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        c.getSource().sendSuccess(() -> Component.translatable("message.ntmgunfeel.gl54_mag", name), false);
        return 1;
    }
}
