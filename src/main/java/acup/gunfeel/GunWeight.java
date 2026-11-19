package acup.gunfeel;

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
public final class GunWeight extends GunWeightBase {
    static final Identifier MODIFIER = Identifier.fromNamespaceAndPath("ntmgunfeel", "gun_weight");

    private GunWeight() {
    }

    public static Gun gun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return gun(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    public static Weight of(ItemStack stack) {
        Gun g = gun(stack);
        return g == null ? null : g.weight();
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
