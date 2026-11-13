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
        String word = p.length > 1 ? p[1].strip() : "";
        // "super-heavy" as the tooltip writes it is "super_heavy" too
        if (!word.isEmpty() && !word.equalsIgnoreCase("none")) try {
            w = Weight.valueOf(word.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            // a typo would quietly take the slowing away: said in the log
            org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").warn("{}: \"{}\" is no weight (light, medium, heavy, super_heavy, none)", FILE, line);
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
        if (w == Weight.HEAVY) return share(ReaimTimes.number(HEAVY_KEY, HEAVY), HEAVY);
        if (w == Weight.SUPER_HEAVY) return share(ReaimTimes.number(SUPER_KEY, SUPER_HEAVY), SUPER_HEAVY);
        return 1F;
    }

    // a key's share, 0.5 to 1; one that is no number (NaN) is its default
    static float share(float v, float def) {
        return Float.isNaN(v) ? def : Math.clamp(v, 0.5F, 1F);
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

    /**
     * Client: the game narrows the view as the speed falls ({@code AbstractClientPlayer.getFieldOfViewModifier}: the
     * speed's share of the walking speed, plus 1, halved); a heavy gun is no reason for it, so its share is taken out
     * of that factor again, and only it: running, flying and the rest stay as they were. A shock's slowing is taken out
     * too and narrows the view by ShockServer.FOV times its strength instead (the game would make a tunnel of it). Not
     * while looking through a spyglass (the game's 0.1 then is no speed).
     */
    public static void fov(net.neoforged.neoforge.client.event.ComputeFovModifierEvent e) {
        var p = e.getPlayer();
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        AttributeModifier gun = speed.getModifier(MODIFIER), shock = speed.getModifier(acup.gunfeel.shock.ShockServer.SLOW_ID);
        float walking = p.getAbilities().getWalkingSpeed();
        if (gun == null && shock == null || walking == 0F || p.isScoping()) return;
        double g = (gun == null ? 1.0 : 1.0 + gun.amount()) * (shock == null ? 1.0 : 1.0 + shock.amount());
        if (!(g > 0.0)) return;
        // the shock's strength 0..1 back from its modifier (-SLOW times it)
        double k = shock == null ? 0.0 : Math.min(1.0, -shock.amount() / acup.gunfeel.shock.ShockServer.SLOW);
        float r = (float) speed.getValue() / walking;
        float m = e.getFovModifier();
        float m0 = m * (float) ((r / g + 1.0) / (r + 1.0) * (1.0 - acup.gunfeel.shock.ShockServer.FOV * k));
        e.setNewFovModifier(e.getNewFovModifier() + e.getFovScale() * (m0 - m));
    }

    /** Client: the tooltip's line, the class and what it costs in speed (the client key {@code gunWeightTooltip}). */
    public static void tooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent e) {
        if (!Config.gunWeightTooltip) return;
        Gun g = gun(e.getItemStack());
        if (g != null) e.getToolTip().add(text(g));
    }

    /** The tooltip's line of a gun: its kind, its weight class and what it costs in speed on this server. */
    public static net.minecraft.network.chat.MutableComponent text(Gun g) {
        // a kind with no name in the language file is shown as it is in the data file
        var kind = net.minecraft.network.chat.Component.translatableWithFallback("ntmgunfeel.gun_kind." + g.kind(), g.kind().replace('_', ' '));
        Weight w = g.weight();
        var line = w == null ? kind.copy() : net.minecraft.network.chat.Component.translatable("ntmgunfeel.gun_weight.line", kind,
                net.minecraft.network.chat.Component.translatable("ntmgunfeel.gun_weight." + w.key()));
        float s = enabled() ? speed(w) : 1F;
        if (s < 1F) line.append(net.minecraft.network.chat.Component.translatable("ntmgunfeel.gun_weight.slow", Math.round((1F - s) * 100F)));
        return line.withStyle(net.minecraft.ChatFormatting.GRAY);
    }
}
