package acup.gunfeel.ai.imitation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.imitation.AiChoice.Ask;
import acup.gunfeel.ai.imitation.ImitationModel.Question;

// The model file read as train.py writes it, and how its answers become AiChoice's: a decision every few ticks at a
// place asked again and again, a yes one tick long, the formula where the model does not know the view or the
// question, never stuck past the formula's yes.
class ImitationCoreTest {
    /** a model whose questions answer with this chance whatever the view (bias only), trained on views of 0..1 */
    static String model(double chance, int cases, String... questions) {
        double bias = Math.log(chance / (1 - chance));
        StringBuilder b = new StringBuilder("{\"version\":1,\"about\":\"test\",\"features\":").append(names(FightFeatures.NAMES)).append(",\"spotFeatures\":")
                .append(names(FightFeatures.SPOT_NAMES)).append(",\"questions\":{");
        for (int i = 0; i < questions.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(questions[i]).append("\":").append(linear(FightFeatures.SIZE, bias, 12, cases, 0));
        }
        // the rank: the place's number weighs, a later place is liked more
        b.append("},\"rank\":").append(linear(FightFeatures.SPOT_SIZE, 0, 1, cases, 50)).append('}');
        return b.toString();
    }

    static String linear(int size, double bias, int window, int cases, double first) {
        double[] w = new double[size], min = new double[size], max = new double[size];
        w[0] = bias == 0 ? first : bias;
        Arrays.fill(min, -1);
        Arrays.fill(max, 1);
        return "{\"w\":" + Arrays.toString(w) + ",\"min\":" + Arrays.toString(min) + ",\"max\":" + Arrays.toString(max) + ",\"window\":" + window + ",\"cases\":" + cases + "}";
    }

    static String names(java.util.List<String> n) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < n.size(); i++) b.append(i > 0 ? "," : "").append('"').append(n.get(i)).append('"');
        return b.append(']').toString();
    }

    static double[] row() {
        double[] f = new double[FightFeatures.SIZE];
        f[0] = 1;
        return f;
    }

    @Test
    void readsWhatTrainWrites() {
        ImitationModel m = ImitationModel.parse(model(0.25, 500, "EXPOSE", "SHIFT"));
        assertEquals("test", m.about);
        assertEquals(0.25, m.question(Question.EXPOSE).chance(row()), 1e-9);
        assertEquals(null, m.question(Question.PUSH));
        assertEquals(12, m.question(Question.SHIFT).window());
    }

    @Test
    void refusesAnotherBuildsNumbers() {
        String bad = model(0.5, 500, "EXPOSE").replace("\"sinceHurt\"", "\"somethingElse\"");
        assertThrows(IllegalArgumentException.class, () -> ImitationModel.parse(bad));
        assertThrows(IllegalArgumentException.class, () -> ImitationModel.parse(model(0.5, 500).replace("\"version\":1", "\"version\":2")));
    }

    @Test
    void decidesEveryFewTicksAndAYesIsOneTick() {
        ImitationCore<String> c = new ImitationCore<>(ImitationModel.parse(model(0.999999, 500, "EXPOSE")), 1);
        int[] views = {0};
        java.util.function.Supplier<double[]> f = () -> {
            views[0]++;
            return row();
        };
        assertTrue(c.choose("a", Ask.PEEK_FIGHT, false, 100, f), "the model's sure yes before the formula's");
        assertFalse(c.choose("a", Ask.PEEK_FIGHT, false, 101, f), "a yes is one tick's");
        for (long t = 102; t < 100 + ImitationCore.EVERY; t++) assertFalse(c.choose("a", Ask.PEEK_FIGHT, false, t, f));
        assertEquals(1, views[0], "one view a decision");
        assertTrue(c.choose("a", Ask.PEEK_FIGHT, false, 100 + ImitationCore.EVERY, f));
        assertEquals(2, views[0]);
        // another bot has its own times
        assertTrue(c.choose("b", Ask.PEEK_FIGHT, false, 101, f));
    }

    @Test
    void neverStuckPastTheFormulasYes() {
        ImitationCore<String> c = new ImitationCore<>(ImitationModel.parse(model(1e-9, 500, "EXPOSE")), 1);
        int late = ImitationCore.late(Ask.PEEK_FIGHT);
        boolean any = false;
        for (long t = 0; t < late; t++) any |= c.choose("a", Ask.PEEK_FIGHT, true, t, ImitationCoreTest::row);
        assertFalse(any, "the model holds it in");
        assertTrue(c.choose("a", Ask.PEEK_FIGHT, true, late, ImitationCoreTest::row), "the formula's yes, " + late + " ticks on");
        assertEquals(1, c.forced);
        // asks a few ticks apart (a reload between) are one run: no fresh count
        ImitationCore<String> d = new ImitationCore<>(ImitationModel.parse(model(1e-9, 500, "EXPOSE")), 1);
        for (long t = 0; t < late; t += 10) assertFalse(d.choose("a", Ask.PEEK_FIGHT, true, t, ImitationCoreTest::row));
        assertTrue(d.choose("a", Ask.PEEK_FIGHT, true, late, ImitationCoreTest::row));
    }

    @Test
    void theFormulaWhereTheModelDoesNotKnow() {
        // too few cases
        ImitationCore<String> few = new ImitationCore<>(ImitationModel.parse(model(1e-9, 10, "EXPOSE")), 1);
        assertTrue(few.choose("a", Ask.PEEK_FIGHT, true, 0, ImitationCoreTest::row));
        // a view outside what it was trained on
        ImitationCore<String> c = new ImitationCore<>(ImitationModel.parse(model(1e-9, 500, "EXPOSE", "SHIFT")), 1);
        double[] odd = row();
        odd[5] = 3;
        assertTrue(c.choose("a", Ask.PEEK_FIGHT, true, 0, () -> odd));
        assertTrue(c.choose("a", Ask.PEEK_FIGHT, true, 1, () -> odd), "unknown till the next decision: the formula live");
        assertFalse(c.choose("a", Ask.PEEK_FIGHT, false, 2, () -> odd));
        assertTrue(c.choose("a", Ask.SHIFT_ON_HIT, true, 3, () -> odd));
        // no part for the question
        assertTrue(c.choose("a", Ask.PUSH_NOW, true, 0, ImitationCoreTest::row));
        assertFalse(c.choose("a", Ask.PUSH_NOW, false, 0, ImitationCoreTest::row));
        assertEquals(2, c.unknown, "a decision unknown counts once, not each tick of it");
    }

    @Test
    void onceIsDrawnEachTime() {
        ImitationCore<String> c = new ImitationCore<>(ImitationModel.parse(model(0.3, 500, "SHIFT")), 7);
        int yes = 0, n = 20000;
        for (int i = 0; i < n; i++) if (c.choose("a", Ask.SHIFT_ON_HIT, false, (long) i * ImitationCore.ONCE_GAP, ImitationCoreTest::row)) yes++;
        assertEquals(0.3, yes / (double) n, 0.02);
        // a burst's hits: one draw, then no till the gap is over
        ImitationCore<String> sure = new ImitationCore<>(ImitationModel.parse(model(0.999999, 500, "SHIFT")), 7);
        assertTrue(sure.choose("a", Ask.HIT_MOVE, false, 1000, ImitationCoreTest::row));
        for (long t = 1001; t < 1000 + ImitationCore.ONCE_GAP; t += 3) assertFalse(sure.choose("a", Ask.HIT_MOVE, true, t, ImitationCoreTest::row));
        assertTrue(sure.choose("a", Ask.HIT_MOVE, false, 1000 + ImitationCore.ONCE_GAP, ImitationCoreTest::row));
    }

    @Test
    void aChanceWithinFewerTicks() {
        assertEquals(0.5, ImitationCore.within(0.5, 12, 12), 1e-12);
        // two halves of the window: 1 - sqrt(0.5) each, together 0.5
        double half = ImitationCore.within(0.5, 24, 12);
        assertEquals(0.5, 1 - (1 - half) * (1 - half), 1e-9);
    }

    @Test
    void rankBySoftmax() {
        ImitationCore<String> c = new ImitationCore<>(ImitationModel.parse(model(0.5, 500)), 3);
        double[][] spots = new double[3][FightFeatures.SPOT_SIZE];
        for (int k = 0; k < 3; k++) spots[k][0] = k / 7.0;
        int[] got = new int[3];
        for (int i = 0; i < 2000; i++) got[c.rank(spots)]++;
        assertTrue(got[2] > 1900, "weight 50 on the place: the last nearly always " + Arrays.toString(got));
        spots[1][4] = 5;
        assertEquals(0, c.rank(spots), "a spot it does not know: the formula's");
        assertEquals(0, c.rank(new double[1][FightFeatures.SPOT_SIZE]));
    }
}
