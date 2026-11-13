package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.hbm.items.armor.ModArmorItem;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineBelt;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import acup.gunfeel.AmmoLock;
import acup.gunfeel.BalanceGuns;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The kit of an AI mob by command (AiKit has the words): its armour and the round its gun loads, when it is spawned
 * (/armsai spawn … armor … ammo …) and on those that stand already:
 * <pre>
 * /armsai kit &lt;targets&gt; [armor …] [ammo …]   the AI mobs among the targets change armour, load the round
 * </pre>
 * The round: NTM fills a mob's gun from nothing (no inventory), a magazine with the type it had, so the type is set
 * on the gun and its reloads keep it; a belt feeds its first type from nothing, so a belt gun is locked on the type
 * (AmmoLock: a locked belt feeds that type alone). A powered piece comes charged.
 */
public final class AiKitCommand {
    private AiKitCommand() {
    }

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** what runs with the words after the gun */
    interface Run {
        int run(CommandContext<CommandSourceStack> c, String words) throws CommandSyntaxException;
    }

    /** the guns the words are about (their rounds are offered after "ammo") */
    interface Guns {
        Collection<ItemStack> of(CommandContext<CommandSourceStack> c) throws CommandSyntaxException;
    }

    static CommandSyntaxException bad(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }

    /** hangs "armor …", "ammo …" (and "team …") on a node: the rest of the line is the kit's words, offered word by word */
    static void words(ArgumentBuilder<CommandSourceStack, ?> node, boolean team, Guns guns, Run run) {
        for (String word : team ? List.of("armor", "ammo", "team") : List.of("armor", "ammo")) {
            node.then(Commands.literal(word).then(Commands.argument("kit", StringArgumentType.greedyString())
                    .suggests((c, b) -> suggest(c, b, word, team, guns))
                    .executes(c -> run.run(c, word + " " + StringArgumentType.getString(c, "kit")))));
        }
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> c, SuggestionsBuilder b, String word, boolean team, Guns guns) {
        Collection<String> rounds = new TreeSet<>();
        try {
            for (ItemStack gun : guns.of(c)) rounds.addAll(rounds(gun).keySet());
        } catch (CommandSyntaxException | RuntimeException e) {
            // no gun to go by (no such targets, no such item): no rounds offered
        }
        AiKit.Next next = AiKit.next(word + " " + b.getRemaining(), rounds, team ? c.getSource().getLevel().getScoreboard().getTeamNames() : null);
        return SharedSuggestionProvider.suggest(next.words(), b.createOffset(b.getStart() + next.from() - word.length() - 1));
    }

    /** /armsai kit &lt;targets&gt; [armor …] [ammo …] */
    static LiteralArgumentBuilder<CommandSourceStack> kit() {
        var targets = Commands.argument("targets", EntityArgument.entities());
        words(targets, false, c -> {
            List<ItemStack> guns = new ArrayList<>();
            for (AiBot bot : bots(c)) guns.add(bot.mob.getMainHandItem());
            return guns;
        }, AiKitCommand::kit);
        return Commands.literal("kit").then(targets);
    }

    private static List<AiBot> bots(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<AiBot> out = new ArrayList<>();
        for (Entity e : EntityArgument.getEntities(c, "targets")) {
            AiBot bot = AiBots.get(e);
            if (bot != null) out.add(bot);
        }
        if (out.isEmpty()) throw bad("none of those is an AI mob (/armsai spawn makes them; they carry the tag " + AiAdapter.TAG + ")");
        return out;
    }

    private static int kit(CommandContext<CommandSourceStack> c, String words) throws CommandSyntaxException {
        Kit kit = read(words, false);
        List<AiBot> bots = bots(c);
        List<Mob> mobs = new ArrayList<>();
        for (AiBot b : bots) mobs.add(b.mob);
        // a round none of them takes is a mistake, told before anything is changed
        if (kit.ammo != null) {
            boolean any = false;
            String why = null;
            for (AiBot b : bots) {
                String w = cannotLoad(b.mob.getMainHandItem(), kit.ammo);
                any |= w == null;
                if (why == null) why = w;
            }
            if (!any) throw bad(why);
        }
        String told = kit.putOn(mobs);
        int n = bots.size();
        c.getSource().sendSuccess(() -> Component.literal(n + " AI mob" + (n == 1 ? "" : "s") + told), true);
        return n;
    }

    /** a kit ready to put on: the pieces by slot (null: the slot as it is, air: emptied), the round's name, the team */
    record Kit(Item @Nullable [] armor, @Nullable String ammo, @Nullable String team) {
        /** before any mob is made: the gun they will all hold (empty: each its own) takes the round */
        void fits(ItemStack hand) throws CommandSyntaxException {
            if (ammo == null || hand.isEmpty()) return;
            String why = cannotLoad(hand, ammo);
            if (why != null) throw bad(why);
        }

        /** puts it on the mobs; what was done, for the command's answer (", wearing …, loading …") */
        String putOn(List<Mob> mobs) {
            StringBuilder told = new StringBuilder();
            if (armor != null) {
                for (Mob m : mobs) dress(m, armor);
                List<String> pieces = new ArrayList<>();
                boolean bare = true;
                for (int i = 0; i < 4; i++) {
                    if (armor[i] == null) continue;
                    bare &= armor[i] == Items.AIR;
                    pieces.add(armor[i] == Items.AIR ? "no " + AiKit.SLOTS[i] + " piece" : BuiltInRegistries.ITEM.getKey(armor[i]).getPath());
                }
                told.append(bare && pieces.size() == 4 ? ", no armour" : ", wearing " + String.join(", ", pieces));
            }
            if (ammo != null) {
                int loaded = 0;
                Map<String, Integer> not = new LinkedHashMap<>();
                for (Mob m : mobs) {
                    AiBot bot = AiBots.get(m);
                    String why = bot == null ? "is no AI mob" : load(bot, ammo);
                    if (why == null) loaded++;
                    else not.merge(why, 1, Integer::sum);
                }
                if (loaded > 0) told.append(", ").append(loaded == mobs.size() ? "" : loaded + " ").append("loading ").append(ammo);
                not.forEach((why, n) -> told.append("; ").append(n).append(" keep").append(n == 1 ? "s its" : " their").append(" rounds: ").append(why));
            }
            return told.toString();
        }
    }

    /** the words after the gun as a kit, its items looked up */
    static Kit read(@Nullable String words, boolean team) throws CommandSyntaxException {
        AiKit.Parsed p;
        try {
            p = AiKit.parse(words, team);
        } catch (AiKit.Bad e) {
            throw bad(e.getMessage());
        }
        Item[] armor = null;
        if (p.armor() != null) {
            armor = new Item[4];
            for (int i = 0; i < 4; i++) {
                String id = p.armor()[i];
                if (id == null) continue;
                if (id.isEmpty()) {
                    armor[i] = Items.AIR;
                    continue;
                }
                Identifier key = Identifier.tryParse(id);
                Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
                if (item == null || item == Items.AIR) throw bad("no item " + id + " (armour slot " + AiKit.SLOTS[i] + ")");
                armor[i] = item;
            }
        }
        return new Kit(armor, p.ammo(), p.team());
    }

    /** the pieces on the mob; a powered piece charged */
    static void dress(Mob mob, Item[] armor) {
        for (int i = 0; i < 4; i++) {
            if (armor[i] == null) continue;
            ItemStack stack = armor[i] == Items.AIR ? ItemStack.EMPTY : new ItemStack(armor[i]);
            if (armor[i] instanceof ModArmorItem piece && piece.isPowered()) piece.setCharge(stack, piece.maxPower(stack));
            mob.setItemSlot(SLOTS[i], stack);
        }
    }

    /**
     * the rounds a gun's first magazine takes (the one a mob fires), by the balance's names, in NTM's order; none for
     * what is no NTM gun, a gun with no rounds of kinds (energy, fuel) and the GL-54 (its magazines hold the grenades)
     */
    static Map<String, BulletConfig> rounds(ItemStack gun) {
        Map<String, BulletConfig> out = new LinkedHashMap<>();
        if (!(gun.getItem() instanceof ItemGunBaseNT)) return out;
        IMagazine<?> mag;
        try {
            mag = AmmoLock.primary(gun);
        } catch (RuntimeException e) {
            return out;
        }
        if (mag == null || mag instanceof acup.gunfeel.gl54.GrenadeMagazine) return out;
        for (BulletConfig b : AmmoLock.accepted(mag)) out.putIfAbsent(BalanceGuns.ammoName(b), b);
        return out;
    }

    /** a round as typed: the balance's name, or the ammo item's id (hbm:ammo_standard_r556_ap) */
    private static String roundName(String typed) {
        String p = typed.startsWith("hbm:") ? typed.substring(4) : typed;
        if (p.startsWith("ammo_standard_")) return p.substring("ammo_standard_".length());
        return p.startsWith("ammo_") ? p.substring(5) : p;
    }

    /** why the gun cannot load the round, in words, or null when it can */
    static @Nullable String cannotLoad(ItemStack gun, String round) {
        String name = BuiltInRegistries.ITEM.getKey(gun.getItem()).toString();
        if (!(gun.getItem() instanceof ItemGunBaseNT)) return (gun.isEmpty() ? "an empty hand" : name) + " is no NTM gun: no round to choose";
        Map<String, BulletConfig> rounds = rounds(gun);
        if (rounds.isEmpty()) return name + " has no rounds to choose from";
        if (!rounds.containsKey(roundName(round))) return name + " does not take " + round + "; it takes: " + String.join(", ", rounds.keySet());
        return null;
    }

    /**
     * The mob's gun loads this round from now on, and is full of it now (from nothing, not from its reserve). What
     * went wrong in words, or null.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static @Nullable String load(AiBot bot, String round) {
        ItemStack stack = bot.mob.getMainHandItem();
        String why = cannotLoad(stack, round);
        if (why != null) return why;
        BulletConfig type = rounds(stack).get(roundName(round));
        IMagazine mag = AmmoLock.primary(stack);
        int id = BulletConfig.configs.indexOf(type);
        if (id < 0) return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " cannot be told to load " + round + " (NTM does not list the round)";
        if (mag instanceof MagazineBelt) {
            // a belt feeds its first type from nothing; locked, the type it has alone (MixinAmmoLockBelt)
            MagazineBelt.setMagType(stack, id);
            ItemGunBaseNT.setValueInt(stack, AmmoLock.KEY, 1);
        } else if (mag instanceof MagazineSingleTypeBase) {
            mag.setType(stack, type);
        } else return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " has no rounds to choose from";
        bot.gunner.loadFree();
        return null;
    }
}
