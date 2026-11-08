package acup.gunfeel.gas;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The gases' wiring: the attachment, the server tick, the listeners, the packets and (client) the HUD and the cough. */
public final class GasSetup {
    private GasSetup() {
    }

    public static void init(IEventBus modBus, Dist dist) {
        GasAttach.register(modBus);
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, e -> {
            if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                GasServer.tick(level);
                GasPoison.tick(level);
            }
        });
        bus.addListener(net.neoforged.neoforge.event.level.LevelEvent.Unload.class, e -> {
            if (e.getLevel() instanceof net.minecraft.world.level.Level level) {
                GasServer.forget(level);
                // the server's thread only: the sums are the server's
                if (level instanceof net.minecraft.server.level.ServerLevel) GasPoison.forget(level);
            }
        });
        bus.addListener(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent.class, e -> {
            if (e.getEntity() instanceof net.minecraft.world.entity.LivingEntity living) GasServer.joined(living);
        });
        bus.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class, e -> GasServer.forget(e.getEntity().getUUID()));
        // no healing while the mustard's dose is at its first level
        bus.addListener(net.neoforged.neoforge.event.entity.living.LivingHealEvent.class, e -> {
            if (GasServer.noRegen(e.getEntity())) e.setCanceled(true);
        });
        // vanilla milk halves the phosgene dose
        bus.addListener(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish.class, e -> {
            if (e.getItem().is(net.minecraft.world.item.Items.MILK_BUCKET) && !e.getEntity().level().isClientSide()) GasServer.milk(e.getEntity());
        });
        // a vanilla blast (TNT, a creeper, a bed) blows the gas away and holes the smoke too; NTM's own go through
        // MixinExplosionContext
        bus.addListener(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate.class, e -> {
            if (e.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                GasStir.blast(level, e.getExplosion().center(), e.getExplosion().radius() * 2F);
                acup.gunfeel.smoke.SmokeHole.blast(level, e.getExplosion().center(), e.getExplosion().radius() * 2F);
            }
        });
        // bone meal does nothing on poisoned ground
        bus.addListener(net.neoforged.neoforge.event.entity.player.BonemealEvent.class, e -> {
            if (GasPoison.grows(e.getState()) && GasPoison.enabled() && GasPoison.poisoned(e.getLevel(), e.getPos())) e.setCanceled(true);
        });
        if (dist.isClient()) acup.gunfeel.gas.client.GasClient.init(modBus);
    }

    /** The packets (the client side registers its handlers). */
    public static void payloads(PayloadRegistrar r, boolean client) {
        if (client) acup.gunfeel.gas.client.GasClient.payloads(r);
        else {
            r.playToClient(GasPayloads.State.TYPE, GasPayloads.State.CODEC);
            r.playToClient(GasPayloads.Cough.TYPE, GasPayloads.Cough.CODEC);
        }
    }
}
