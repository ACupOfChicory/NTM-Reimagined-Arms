package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The guns' kinds and weight classes ({@code ntmgunfeel-gun-weight.txt}, {@code <item id> = <kind>, <weight>} a line; the
 * file is the one place they are set, a new gun or kind needs no code): the tooltip names both, and a heavy or super-heavy
 * gun in either hand slows the player who carries it a little, walking and running alike, by a speed modifier (no
 * effect icon, no particles) that goes the tick the gun leaves the hands. The heavier of the two hands counts, not the
 * sum: two miniguns weigh as one. Not in creative or spectator (building and looking round, not fighting). Only players:
 * the AI's soldiers keep their own speeds. Server keys {@code gunWeightSlow} (on), {@code gunWeightHeavySpeed} (0.94)
 * and {@code gunWeightSuperHeavySpeed} (0.88); the speed share a class leaves, 0.5 to 1.
 */
public final class GunWeight {
    public enum Weight {
        LIGHT, MEDIUM, HEAVY, SUPER_HEAVY;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A gun's kind (pistol, smg, rifle, ...) and its weight. */
    public record Gun(String kind, Weight weight) {
    }

    public static final String FILE = "/ntmgunfeel-gun-weight.txt";
    public static final String SLOW_KEY = "gunWeightSlow", HEAVY_KEY = "gunWeightHeavySpeed", SUPER_KEY = "gunWeightSuperHeavySpeed";
    public static final float HEAVY = 0.94F, SUPER_HEAVY = 0.88F;
    static final Identifier MODIFIER = Identifier.fromNamespaceAndPath("ntmgunfeel", "gun_weight");
    private static final Map<String, Gun> GUNS = load();

    private GunWeight() {
    }

    static Map<String, Gun> load() {
        Map<String, Gun> out = new HashMap<>();
        try (var in = GunWeight.class.getResourceAsStream(FILE)) {
            if (in == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null;) {
                line = line.strip();
                Gun g = line(line);
                if (g != null) out.put(line.substring(0, line.indexOf('=')).strip(), g);
            }
        } catch (java.io.IOException e) {
            // no file: no classes
        }
        return Map.copyOf(out);
    }

    /**
     * One line of the file: {@code id = kind, weight}. Null for a comment, a broken line, or "none, none" (not a weapon);
     * an unknown weight leaves the kind with no weight (no slowing).
     */
    static Gun line(String line) {
        line = line.strip();
        if (line.isEmpty() || line.startsWith("#")) return null;
        int eq = line.indexOf('=');
        if (eq <= 0) return null;
        String[] p = line.substring(eq + 1).split(",");
        String kind = p[0].strip().toLowerCase(Locale.ROOT);
        Weight w = null;
        if (p.length > 1) try {
            w = Weight.valueOf(p[1].strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // "none" or a word that is no weight
        }
        if (kind.isEmpty() || kind.equals("none") && w == null) return null;
        return new Gun(kind, w);
    }

    /** A gun by its item id ({@code hbm:gun_minigun}), or null. */
    public static Gun gun(String id) {
        return GUNS.get(id);
    }

    public static Gun gun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return gun(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    /** The weight class of a gun by its item id, or null. */
    public static Weight of(String id) {
        Gun g = gun(id);
        return g == null ? null : g.weight();
    }

    public static Weight of(ItemStack stack) {
        Gun g = gun(stack);
        return g == null ? null : g.weight();
    }

    static Map<String, Gun> all() {
        return GUNS;
    }

    /** Whether this server slows the players with heavy guns. */
    public static boolean enabled() {
        return ReaimTimes.flag(SLOW_KEY, true);
    }

    /** The share of its speed a class leaves on this server: 1 for light and medium. */
    public static float speed(Weight w) {
        if (w == Weight.HEAVY) return Math.clamp(ReaimTimes.number(HEAVY_KEY, HEAVY), 0.5F, 1F);
        if (w == Weight.SUPER_HEAVY) return Math.clamp(ReaimTimes.number(SUPER_KEY, SUPER_HEAVY), 0.5F, 1F);
        return 1F;
    }

    /** The heavier of the two: null below everything. */
    static Weight heavier(Weight a, Weight b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** The share of its speed what the player holds leaves: 1 when nothing slows. */
    static float carried(ItemStack main, ItemStack off, boolean exempt, boolean on) {
        if (!on || exempt) return 1F;
        return speed(heavier(of(main), of(off)));
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post.class, e -> {
            if (e.getEntity() instanceof ServerPlayer sp) tick(sp);
        });
    }

    static void tick(ServerPlayer p) {
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        float s = carried(p.getMainHandItem(), p.getOffhandItem(), p.isCreative() || p.isSpectator(), enabled());
        AttributeModifier now = speed.getModifier(MODIFIER);
        if (s >= 1F) {
            if (now != null) speed.removeModifier(MODIFIER);
            return;
        }
        double v = s - 1.0;
        if (now == null || now.amount() != v) speed.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER, v, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** Client: the tooltip's line, the class and what it costs in speed (the client key {@code gunWeightTooltip}). */
    public static void tooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent e) {
        if (!Config.gunWeightTooltip) return;
        Gun g = gun(e.getItemStack());
        if (g == null) return;
        // a kind with no name in the language file is shown as it is in the data file
        var kind = net.minecraft.network.chat.Component.translatableWithFallback("ntmgunfeel.gun_kind." + g.kind(), g.kind().replace('_', ' '));
        Weight w = g.weight();
        var line = w == null ? kind.copy() : net.minecraft.network.chat.Component.translatable("ntmgunfeel.gun_weight.line", kind,
                net.minecraft.network.chat.Component.translatable("ntmgunfeel.gun_weight." + w.key()));
        float s = enabled() ? speed(w) : 1F;
        if (s < 1F) line.append(net.minecraft.network.chat.Component.translatable("ntmgunfeel.gun_weight.slow", Math.round((1F - s) * 100F)));
        e.getToolTip().add(line.withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
