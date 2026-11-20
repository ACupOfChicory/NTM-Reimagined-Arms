package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunityBase.Phase;
import acup.gunfeel.EuphImmunityBase.Pool;
import acup.gunfeel.EuphImmunityBase.State;

// The pools of DNS, FAU and euphemium for our AI's soldiers, without the game: the hooks take a player or a soldier
// (not other mobs), the soldier's euphemium goes by the pool's own hook, the window's message is a player's, a soldier
// gone is forgotten; and a soldier's round is the player's (one State by the mob's id).
class BotImmunityTest {
    private static final Path SRC = Path.of("src/main/java/acup/gunfeel");

    private static String src(String file) throws IOException {
        return Files.readString(SRC.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void hooksTakePlayersAndSoldiersOnly() throws IOException {
        String imm = src("EuphImmunity.java");
        assertTrue(imm.contains("return e instanceof Player || acup.gunfeel.ai.AiBots.get(e) != null;"), "pooled: a player or an AI soldier");
        for (String sig : new String[] {"Kit kitOf(LivingEntity", "boolean early(Kit kit, LivingEntity", "boolean accepted(Kit kit, LivingEntity",
                "boolean absorbs(Kit kit, LivingEntity", "void forget(LivingEntity"})
            assertTrue(imm.contains(sig), sig);
        // euphemium by the pool's hook for a soldier only: a player's goes by NTM's own check (MixinEuphImmunity); the
        // kit is core's (EuphImmunityBase.kitOfWorn), NTM's suit EuphImmunity.Reads'
        assertTrue(imm.contains("return kitOfWorn(EntityBody.of(player), BalanceOn.ON);"), "kitOf: core's on the body");
        assertTrue(Files.readString(Path.of("core/src/main/java/acup/gunfeel/EuphImmunityBase.java"), StandardCharsets.UTF_8)
                .contains("if (!b.player() && a.fullSet(b, acup.gunfeel.host.ArmorSuits.EUPHEMIUM))"), "euphemium: a soldier's only");
        assertTrue(imm.contains("case acup.gunfeel.host.ArmorSuits.EUPHEMIUM -> com.hbm.items.armor.ModArmorItem.Suit.EUPHEMIUM;"));
        // the window's message: a player's only
        assertTrue(Files.readString(Path.of("core/src/main/java/acup/gunfeel/EuphImmunityBase.java"), StandardCharsets.UTF_8)
                .contains("control.tellBar(b, kit.message, p.window() / 20);"), "the window's message: core's through BodyControl");
        assertTrue(src("EntityBodyControl.java").contains("if (EntityBody.entity(b) instanceof net.minecraft.server.level.ServerPlayer sp)"),
                "the message goes to a player only");

        String hooks = src("mixin/MixinBalanceImmunity.java");
        assertFalse(hooks.contains("instanceof Player"), "no player-only gate left in the pool's hooks");
        assertEquals(2, hooks.split("!EuphImmunity\\.pooled\\(entity\\)", -1).length - 1, "both hooks gate on pooled");
        assertTrue(src("mixin/MixinRadonTomb.java").contains("EuphImmunity.pooled(entity)"), "the radon takes a soldier's pool off too");
        String bots = src("ai/AiBots.java");
        assertEquals(2, bots.split("acup\\.gunfeel\\.EuphImmunity\\.forget\\(b\\.mob\\);", -1).length - 1, "a soldier gone, and the world unloaded, forgotten");
        // the void and /kill go through on a soldier: it has no way out of them as a player has
        assertTrue(Files.readString(Path.of("core/src/main/java/acup/gunfeel/EuphImmunityBase.java"), StandardCharsets.UTF_8)
                .contains("if (!b.player() && hurts.is(hurt, Hurts.BYPASSES_INVULNERABILITY)) return false;"), "the void and /kill: a soldier's only");
    }

    @Test
    void soldiersRoundIsThePlayers() {
        // DNS's numbers: a pool of 45, a window of 15 s, a recovery of 60 s, the hold 10 s
        Pool p = new Pool(45F, 300, 1200, 200);
        State s = new State(p.size(), 0);
        // shots take the pool down, every one called off
        for (int t = 0; t < 4; t++) assertTrue(EuphImmunity.hit(s, 20L * t, 10F, true, true, p, 0));
        assertEquals(5F, EuphImmunity.poolAt(s, 60, p), 1e-3F);
        // the one that empties it is still called off; the window from the next tick, hits go through
        assertTrue(EuphImmunity.hit(s, 80, 10F, true, true, p, 0));
        assertEquals(Phase.WINDOW, EuphImmunity.phase(s, 81));
        assertFalse(EuphImmunity.hit(s, 81, 10F, true, true, p, 0));
        // the window its own 15 s (the hit at 81 holds it to 281, inside it), then the recovery, invulnerable, then the full pool
        assertEquals(Phase.RECOVER, EuphImmunity.phase(s, 81 + 300 + 1));
        assertTrue(EuphImmunity.hit(s, 81 + 300 + 1, 10F, true, true, p, 0));
        assertEquals(Phase.POOL, EuphImmunity.phase(s, 81 + 300 + 1200 + 1));
        assertEquals(p.size(), EuphImmunity.poolAt(s, 81 + 300 + 1200 + 1, p), 1e-3F);
    }
}
