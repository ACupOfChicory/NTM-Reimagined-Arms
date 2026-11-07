package acup.gunfeel.gas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

// The doses, the protection and each gas's damage, caps and clocks, tick by tick without the game.
class GasModelTest {
    private static final int CL = GasState.CHLORINE, PH = GasState.PHOSGENE, MU = GasState.MUSTARD, AC = GasState.ACID;
    private final Random rng = new Random(3);

    private static float[] air(int kind, float c) {
        float[] a = new float[GasState.N];
        a[kind] = c;
        return a;
    }

    private static final float[] NO_PROTECTION = new float[GasState.N];

    // runs seconds of a gas at C, with a protection; returns the HP of damage taken (all of it dealt, as lumps)
    private float run(GasState s, int kind, float c, float protect, float seconds) {
        float[] p = air(kind, protect);
        float taken = 0F;
        for (int t = 0; t < Math.round(seconds * 20); t++) {
            GasModel.tick(s, air(kind, c), p, 0F, 0, false, rng::nextInt);
            taken += GasModel.takeDamage(s);
        }
        return taken + flush(s);
    }

    // the damage still waiting for its lump
    private static float flush(GasState s) {
        float p = s.pending;
        s.pending = 0F;
        return p;
    }

    private float outside(GasState s, float seconds) {
        float taken = 0F;
        for (int t = 0; t < Math.round(seconds * 20); t++) {
            GasModel.tick(s, new float[GasState.N], NO_PROTECTION, 0F, 0, false, rng::nextInt);
            taken += GasModel.takeDamage(s);
        }
        return taken + flush(s);
    }

    // ---- protection

    @Test
    void theMaskKeepsOut95PercentWithAFullFilterAndFallsToSeventyBelowAQuarter() {
        assertEquals(0.95F, GasModel.coefficient(1F), 1e-6F);
        assertEquals(0.95F, GasModel.coefficient(0.25F), 1e-6F);
        assertEquals(0.825F, GasModel.coefficient(0.125F), 1e-5F);
        assertEquals(0.70F, GasModel.coefficient(0F), 1e-6F);
        assertEquals(0.70F, GasModel.coefficient(-1F), 1e-6F);
        float last = 1F;
        for (float f = 1F; f >= 0F; f -= 0.01F) {
            float c = GasModel.coefficient(f);
            assertTrue(c <= last + 1e-6F);
            last = c;
        }
    }

    @Test
    void mustardNeedsTheHelmetAndTheSuitAndEachMissingPartHalvesIt() {
        assertEquals(0.95F, GasModel.mustardProtection(true, true, 1F), 1e-6F);
        assertEquals(0.475F, GasModel.mustardProtection(true, false, 1F), 1e-6F);
        assertEquals(0.475F, GasModel.mustardProtection(false, true, 1F), 1e-6F);
        assertEquals(0F, GasModel.mustardProtection(false, false, 1F));
        // a worn filter weakens the helmet's part
        assertEquals(0.70F, GasModel.mustardProtection(true, true, 0F), 1e-6F);
    }

    @Test
    void theFilterWearsByWTimesC() {
        assertEquals(10F, GasModel.filterWear(GasKind.CHLORINE, 1F));
        assertEquals(14F, GasModel.filterWear(GasKind.PHOSGENE, 1F));
        assertEquals(20F, GasModel.filterWear(GasKind.MUSTARD, 1F));
        assertEquals(5F, GasModel.filterWear(GasKind.CHLORINE, 0.5F));
        assertEquals(10F, GasModel.filterWear(GasKind.CHLORINE, 7F), "C is at most 1");
        assertEquals(0F, GasModel.filterWear(GasKind.ACID, 1F));
        // a standard 20000 filter: 100 s of thick chlorine, 70 s of phosgene, 50 s of mustard
        assertEquals(100F, 20000F / GasModel.filterWear(GasKind.CHLORINE, 1F) / 20F);
        assertEquals(71.4F, 20000F / GasModel.filterWear(GasKind.PHOSGENE, 1F) / 20F, 0.1F);
        assertEquals(50F, 20000F / GasModel.filterWear(GasKind.MUSTARD, 1F) / 20F);
    }

    @Test
    void protectionDecidesHowMuchGasGetsIn() {
        GasState bare = new GasState(), masked = new GasState();
        run(bare, CL, 1F, 0F, 10F);
        run(masked, CL, 1F, 0.95F, 10F);
        assertEquals(10F, bare.dose[CL], 0.01F);
        assertEquals(0.5F, masked.dose[CL], 0.01F);
    }

    // ---- chlorine

    @Test
    void chlorineMakesYouCoughAtOnceSlowsYouAndShutsTheSprint() {
        GasState s = new GasState();
        int coughs = 0, first = -1;
        for (int t = 0; t < 400; t++) {
            GasModel.tick(s, air(CL, 1F), NO_PROTECTION, 0F, 0, false, rng::nextInt);
            if (s.coughNow) {
                if (first < 0) first = t;
                coughs++;
            }
            if (t > 20) {
                assertEquals(GasRules.CHLORINE_SLOW, s.slow, 1e-6F);
                assertTrue(s.noSprint);
            }
        }
        assertTrue(first <= 10, "first cough at tick " + first);
        // one every 1.5 to 3 s: 20 s is 7 to 13 coughs
        assertTrue(coughs >= 6 && coughs <= 14, "coughs in 20 s: " + coughs);
    }

    @Test
    void coughsComeEveryOneAndAHalfToThreeSeconds() {
        GasState s = new GasState();
        int last = -1;
        for (int t = 0; t < 2000; t++) {
            GasModel.tick(s, air(CL, 1F), NO_PROTECTION, 0F, 0, false, rng::nextInt);
            if (!s.coughNow) continue;
            if (last >= 0) {
                assertTrue(t - last >= GasRules.COUGH_MIN + 1 && t - last <= GasRules.COUGH_MAX + 2, "gap " + (t - last));
            }
            last = t;
        }
    }

    @Test
    void chlorineDamageStartsAtDoseTwoAtSevenTenthsAndCanKill() {
        GasState s = new GasState();
        float before = run(s, CL, 1F, 0F, 1.9F);
        assertEquals(0F, before, "no damage before a dose of 2");
        float taken = run(s, CL, 1F, 0F, 10F);
        assertEquals(7F, taken, 0.3F);
        // there is no floor: 30 s more is 21 HP
        assertTrue(run(s, CL, 1F, 0F, 30F) > 20F);
    }

    @Test
    void chlorineDamageGoesByTheConcentration() {
        GasState s = new GasState();
        run(s, CL, 1F, 0F, 3F);
        assertEquals(3.5F, run(s, CL, 0.5F, 0F, 10F), 0.2F);
    }

    @Test
    void chlorineDoseGoesAtHalfAPerSecondOutsideTheCloud() {
        GasState s = new GasState();
        run(s, CL, 1F, 0F, 6F);
        float taken = outside(s, 4F);
        assertEquals(0F, taken, "no damage with no gas about");
        assertEquals(4F, s.dose[CL], 0.05F);
        outside(s, 20F);
        assertEquals(0F, s.dose[CL]);
        assertFalse(s.noSprint);
        assertEquals(0F, s.slow);
    }

    // ---- phosgene

    @Test
    void phosgeneSmellsAtDoseOneThenWaitsFifteenSecondsThenKillsBy2xDose() {
        GasState s = new GasState();
        boolean smelled = false;
        int smellTick = -1, swollen = -1;
        float dealt = 0F;
        for (int t = 0; t < 20 * 120; t++) {
            // 5 s in the core, then out
            float c = t < 100 ? 1F : 0F;
            GasModel.tick(s, air(PH, c), NO_PROTECTION, 0F, 0, false, rng::nextInt);
            if (s.smellNow) {
                assertFalse(smelled, "the smell once");
                smelled = true;
                smellTick = t;
            }
            float hp = GasModel.takeDamage(s);
            if (hp > 0F && swollen < 0) swollen = t;
            dealt += hp;
        }
        assertTrue(smelled);
        // the dose reaches 1 after a second
        assertEquals(20, smellTick, 2);
        // the latent phase is 15 s from the smell
        assertEquals(smellTick + 300, swollen, 12);
        // a dose of 5 is 10 HP in all (the dose went on to 5 in the core; the clock runs from the first 1)
        assertEquals(10F, dealt, 0.6F);
        assertEquals(-1, s.latent);
        assertEquals(0F, s.dose[PH]);
    }

    @Test
    void phosgeneNeverHurtsDuringTheQuietAndMakesNoCough() {
        GasState s = new GasState();
        for (int t = 0; t < 20 * 14; t++) {
            GasModel.tick(s, air(PH, t < 40 ? 1F : 0F), NO_PROTECTION, 0F, 0, false, rng::nextInt);
            assertFalse(s.coughNow);
            assertEquals(0F, s.slow);
            assertEquals(0F, GasModel.takeDamage(s));
        }
    }

    @Test
    void phosgeneDoseTenKillsAndFiveDoesNot() {
        GasState five = new GasState(), ten = new GasState();
        run(five, PH, 1F, 0F, 5F);
        run(ten, PH, 1F, 0F, 10F);
        float a = outside(five, 200F), b = outside(ten, 200F);
        assertTrue(a < 20F, "dose 5: " + a);
        assertTrue(b >= 19F, "dose 10: " + b);
    }

    @Test
    void milkHalvesThePhosgeneDose() {
        GasState s = new GasState();
        run(s, PH, 1F, 0F, 6F);
        float d = s.dose[PH];
        GasModel.milk(s);
        assertEquals(d / 2F, s.dose[PH], 1e-5F);
        // so the lungs take half the damage
        assertEquals(6F, outside(s, 200F), 0.6F);
    }

    @Test
    void aLittlePhosgeneThatNeverReachedTheSmellGoesAway() {
        GasState s = new GasState();
        run(s, PH, 1F, 0F, 0.5F);
        assertTrue(s.dose[PH] > 0.4F);
        outside(s, 40F);
        assertEquals(0F, s.dose[PH]);
        assertEquals(-1, s.latent);
    }

    // ---- mustard

    @Test
    void mustardLevelsHurtMoreWithTheDose() {
        GasState s = new GasState();
        run(s, MU, 1F, 0F, 1.9F);
        assertFalse(s.noRegen);
        assertEquals(0F, run(new GasState(), MU, 1F, 0F, 1.9F));
        // from 2: 0.2 a second, regeneration shut
        GasState a = new GasState();
        a.dose[MU] = 2.5F;
        float hp = outside(a, 2F);
        assertEquals(0.4F, hp, 0.05F);
        assertTrue(a.noRegen);
        assertFalse(a.nausea);
        // from 5: 0.4 and nausea
        GasState b = new GasState();
        b.dose[MU] = 6F;
        assertEquals(0.8F, outside(b, 2F), 0.08F);
        assertTrue(b.nausea);
        assertFalse(b.darkness);
        // from 9: 0.7 and darkness
        GasState c = new GasState();
        c.dose[MU] = 10F;
        assertEquals(1.4F, outside(c, 2F), 0.12F);
        assertTrue(c.nausea && c.darkness);
    }

    @Test
    void mustardDoseDropsBy015AndTheDamageStopsWithIt() {
        GasState s = new GasState();
        s.dose[MU] = 3F;
        outside(s, 10F);
        assertEquals(1.5F, s.dose[MU], 0.05F);
        assertFalse(s.noRegen);
        // and no cap at 4 HP: a long stay in the thick of it kills
        GasState t = new GasState();
        assertTrue(run(t, MU, 1F, 0F, 40F) > 20F);
    }

    @Test
    void mustardFromTheFloorAddsSixTenthsOfG() {
        GasState s = new GasState();
        for (int t = 0; t < 200; t++) GasModel.tick(s, new float[GasState.N], NO_PROTECTION, 0.5F, 0, false, rng::nextInt);
        // 10 s on a floor with G = 0.5: 0.3 a second
        assertEquals(3F, s.dose[MU], 0.05F);
        // in the air as well: C + 0.6 G
        GasState u = new GasState();
        for (int t = 0; t < 200; t++) GasModel.tick(u, air(MU, 1F), NO_PROTECTION, 0.5F, 0, false, rng::nextInt);
        assertEquals(13F, u.dose[MU], 0.1F);
    }

    @Test
    void theFloorDriesIn180SecondsAndIsCapped() {
        GasFloor f = new GasFloor();
        f.mark(1L, GasRules.MUSTARD_FLOOR_G);
        f.dry(20 * 90);
        assertEquals(0.25F, f.at(1L), 0.002F);
        f.dry(20 * 91);
        assertEquals(0F, f.at(1L));
        assertEquals(0, f.size());
        GasFloor small = new GasFloor(3);
        for (long i = 0; i < 10; i++) small.mark(i, 0.5F);
        assertEquals(3, small.size());
        assertEquals(0F, small.at(9L));
        // a cell that is there can still be marked again
        assertTrue(small.mark(1L, 0.5F));
        assertFalse(small.mark(99L, 0.5F));
        assertEquals(4096, GasRules.MUSTARD_FLOOR_CELLS);
    }

    // ---- acid

    @Test
    void acidCorrosionGrowsBy02TimesCAndStopsAt075() {
        GasState s = new GasState();
        run(s, AC, 1F, 0F, 2F);
        assertEquals(0.4F, s.k, 0.01F);
        run(s, AC, 0.5F, 0F, 2F);
        assertEquals(0.6F, s.k, 0.01F);
        run(s, AC, 1F, 0F, 20F);
        assertEquals(0.75F, s.k, 1e-6F);
    }

    @Test
    void acidDoesNotWearOffInsideAndDecaysOutsideByAHundredthASecond() {
        GasState s = new GasState();
        run(s, AC, 1F, 0F, 10F);
        run(s, AC, 0.0F, 0F, 0F);
        outside(s, 30F);
        assertEquals(0.45F, s.k, 0.01F);
        outside(s, 100F);
        assertEquals(0F, s.k);
    }

    @Test
    void waterWashesTheAcidOffTenTimesFaster() {
        GasState s = new GasState();
        s.k = 0.75F;
        for (int t = 0; t < 20 * 5; t++) GasModel.tick(s, new float[GasState.N], NO_PROTECTION, 0F, 0, true, rng::nextInt);
        assertEquals(0.25F, s.k, 0.01F);
    }

    @Test
    void aMaskDoesNotKeepTheAcidOffTheArmour() {
        GasState masked = new GasState(), bare = new GasState();
        run(masked, AC, 1F, 0.95F, 3F);
        run(bare, AC, 1F, 0F, 3F);
        assertEquals(bare.k, masked.k, 1e-6F);
        assertTrue(masked.dose[AC] < bare.dose[AC] / 10F);
    }

    @Test
    void acidBurnsTheBareSlotsAndTheFullyCoveredAreSpared() {
        for (int bare = 0; bare <= 4; bare++) {
            GasState s = new GasState();
            float hp = 0F;
            for (int t = 0; t < 200; t++) {
                GasModel.tick(s, air(AC, 1F), NO_PROTECTION, 0F, bare, false, rng::nextInt);
                hp += GasModel.takeDamage(s);
            }
            assertEquals(GasRules.ACID_BURN_PER_SLOT * bare * 10F, hp, 0.1F, "bare slots " + bare);
        }
    }

    @Test
    void bareSlotsCountWhatLacksOrIsNotPowerArmourInAPowerSet() {
        boolean[] all = {true, true, true, true}, none = {false, false, false, false};
        // plain armour all round: covered; nothing: bare
        assertEquals(0, GasModel.bareSlots(all, none));
        assertEquals(4, GasModel.bareSlots(none, none));
        assertEquals(1, GasModel.bareSlots(new boolean[] {true, true, false, true}, none));
        // a power set with one piece swapped for a plain one: that slot is bare; a piece of another power set is fine
        assertEquals(1, GasModel.bareSlots(all, new boolean[] {true, true, false, true}));
        assertEquals(0, GasModel.bareSlots(all, all));
        assertEquals(1, GasModel.bareSlots(new boolean[] {true, true, false, true}, new boolean[] {true, true, false, true}));
    }

    @Test
    void corrosionCutsTheThresholdAndThePercentageOfTheArmour() {
        // NTM: amount - DT, then times (1 - DR). A steel set (2, 10%) against a 1.25 shard
        float dt = 2F, dr = 0.10F, shard = 1.25F;
        assertEquals(0F, after(shard, dt, dr), "uncorroded steel stops it");
        float k = 0.75F;
        float dt2 = GasModel.corrode(dt, k), dr2 = GasModel.corrode(dr, k);
        assertEquals(0.5F, dt2, 1e-6F);
        assertEquals(0.025F, dr2, 1e-6F);
        assertEquals(0.73F, after(shard, dt2, dr2), 0.01F);
        // half way
        assertEquals(1.0F, GasModel.corrode(2F, 0.5F), 1e-6F);
        assertEquals(2F, GasModel.corrode(2F, 0F), 1e-6F);
        // never past the cap
        assertEquals(0.5F, GasModel.corrode(2F, 5F), 1e-6F);
        // the vanilla armour points go the same way
        assertEquals(5F, GasModel.corrode(20F, 0.75F), 1e-5F);
    }

    private static float after(float amount, float dt, float dr) {
        if (dt >= amount) return 0F;
        return (amount - dt) * (1F - dr);
    }

    // ---- the lungs

    @Test
    void theNonLethalGasesAddBlackLungByTheDose() {
        for (int kind : new int[] {CL, MU, AC}) {
            GasState s = new GasState();
            int sum = 0;
            for (int t = 0; t < 200; t++) {
                GasModel.tick(s, air(kind, 1F), NO_PROTECTION, 0F, 0, false, rng::nextInt);
                sum += s.blackLung;
            }
            // 10 s of thick gas is a dose of 10
            assertEquals(10 * GasRules.BLACK_LUNG_PER_DOSE, sum, 2, "kind " + kind);
        }
        GasState p = new GasState();
        int sum = 0;
        for (int t = 0; t < 200; t++) {
            GasModel.tick(p, air(PH, 1F), NO_PROTECTION, 0F, 0, false, rng::nextInt);
            sum += p.blackLung;
        }
        assertEquals(0, sum, "phosgene kills; it adds no black lung");
        // a mask keeps most of it out of the lungs
        GasState m = new GasState();
        int masked = 0;
        for (int t = 0; t < 200; t++) {
            GasModel.tick(m, air(CL, 1F), air(CL, 0.95F), 0F, 0, false, rng::nextInt);
            masked += m.blackLung;
        }
        assertEquals(0.5F * GasRules.BLACK_LUNG_PER_DOSE, masked, 2);
    }

    @Test
    void theStateIsSavedAndReadBack() {
        GasState s = new GasState();
        s.dose[CL] = 1.5F;
        s.dose[PH] = 4F;
        s.k = 0.3F;
        s.latent = 120;
        s.smelled = true;
        var tag = GasState.CODEC.codec().encodeStart(com.mojang.serialization.JsonOps.INSTANCE, s).getOrThrow();
        GasState back = GasState.CODEC.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, tag).getOrThrow();
        assertEquals(1.5F, back.dose[CL]);
        assertEquals(4F, back.dose[PH]);
        assertEquals(0.3F, back.k);
        assertEquals(120, back.latent);
        assertTrue(back.smelled);
        assertTrue(back.persists());
        assertFalse(new GasState().persists());
    }

    @Test
    void damageComesInLumpsOfAtMostOneHurtEveryTenTicks() {
        GasState s = new GasState();
        s.dose[CL] = 3F;
        int hurts = 0;
        float sum = 0F;
        for (int t = 0; t < 200; t++) {
            GasModel.tick(s, air(CL, 1F), NO_PROTECTION, 0F, 0, false, rng::nextInt);
            float hp = GasModel.takeDamage(s);
            if (hp > 0F) {
                hurts++;
                sum += hp;
            }
        }
        assertTrue(hurts <= 21, "hurts " + hurts);
        assertEquals(7F, sum, 0.4F);
    }
}
