package acup.gunfeel.ai.imitation;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import acup.gunfeel.ai.imitation.AiChoice.Ask;
import acup.gunfeel.ai.imitation.ImitationModel.Linear;
import acup.gunfeel.ai.imitation.ImitationModel.Question;

/**
 * How the model answers AiChoice, for any fighter (key: the bot; plain here, so it is tested without a world).
 * <ul>
 * <li>A place it is asked at again and again (look out now? back in now?) it decides every {@link #EVERY} ticks: the
 * model's chance within its window, as a chance within EVERY ticks, drawn once; a yes is one tick's yes (the bot acts
 * on it), then no till the next decision. Asked again after a gap, it decides at once.</li>
 * <li>A place asked once (other cover after a hit, after a peek) it draws the model's chance then, at most once in
 * {@link #ONCE_GAP} ticks; asked again sooner, no.</li>
 * <li>A view the model does not know, or a question it has no part for: the formula's answer.</li>
 * <li>Never stuck: the formula saying yes longer than {@link #late} ticks running, the answer is yes.</li>
 * </ul>
 */
final class ImitationCore<K> {
    /** ticks between two decisions at one place */
    static final int EVERY = 12;
    /** a place asked once is drawn at most once this often a bot (a burst's every hit is no new news), ticks */
    static final int ONCE_GAP = 40;
    /** asks this far apart are no longer one run of the formula's yes (a reload, a step between), ticks */
    static final int RUN_GAP = 40;

    static Question question(Ask a) {
        return switch (a) {
            case PEEK_FIGHT, LOOK_LOST, LOOK_NOISE -> Question.EXPOSE;
            case BACK_IN -> Question.HIDE;
            case SHIFT_AFTER_PEEK -> Question.SHIFT_BACK;
            case SHIFT_ON_HIT, HIT_MOVE -> Question.SHIFT_HIT;
            case BETTER_COVER -> Question.SHIFT;
            case PUSH_NOW -> Question.PUSH;
        };
    }

    /** asked once at the moment, not again and again */
    static boolean once(Ask a) {
        return a == Ask.SHIFT_AFTER_PEEK || a == Ask.SHIFT_ON_HIT || a == Ask.HIT_MOVE;
    }

    /** the formula's yes this long running, ticks, and it is yes (0: never forced) */
    static int late(Ask a) {
        return switch (a) {
            case PEEK_FIGHT, LOOK_LOST, LOOK_NOISE -> 200;
            case PUSH_NOW -> 400;
            default -> 0;
        };
    }

    private static final int N = Ask.values().length;

    static final class Mind {
        final long[] next = new long[N], last = new long[N], formulaSince = new long[N];
        final boolean[] answer = new boolean[N], unknown = new boolean[N];

        Mind() {
            java.util.Arrays.fill(last, Long.MIN_VALUE / 2);
            java.util.Arrays.fill(formulaSince, Long.MIN_VALUE);
        }
    }

    final ImitationModel model;
    private final java.util.Random random;
    private final Map<K, Mind> minds = new WeakHashMap<>();
    // for /armsai imitation info and the stands: asks, decisions the model made, decisions left to the formula
    long asks, decided, unknown, forced;
    final long[] yes = new long[N], decisions = new long[N];

    ImitationCore(ImitationModel model, long seed) {
        this.model = model;
        this.random = new java.util.Random(seed);
    }

    boolean choose(K key, Ask a, boolean formula, long tick, Supplier<double[]> features) {
        asks++;
        Question q = question(a);
        Linear lin = model.question(q);
        // a model with no part for the moment itself: its part for being in cover
        if (lin == null && (q == Question.SHIFT_BACK || q == Question.SHIFT_HIT)) lin = model.question(Question.SHIFT);
        if (lin == null) return formula;
        int i = a.ordinal();
        Mind m = minds.computeIfAbsent(key, k -> new Mind());
        if (once(a)) {
            if (tick - m.last[i] < ONCE_GAP) return false;
            double[] f = features.get();
            if (!lin.knows(f)) {
                unknown++;
                return formula;
            }
            m.last[i] = tick;
            decided++;
            decisions[i]++;
            boolean y = random.nextDouble() < lin.chance(f);
            if (y) yes[i]++;
            return y;
        }
        boolean gap = tick - m.last[i] > 2;
        boolean run = tick - m.last[i] <= RUN_GAP;
        m.last[i] = tick;
        if (!formula) m.formulaSince[i] = Long.MIN_VALUE;
        else if (!run || m.formulaSince[i] == Long.MIN_VALUE) m.formulaSince[i] = tick;
        int late = late(a);
        if (formula && late > 0 && tick - m.formulaSince[i] >= late) {
            forced++;
            return true;
        }
        if (gap || tick >= m.next[i]) {
            m.next[i] = tick + EVERY;
            double[] f = features.get();
            m.unknown[i] = !lin.knows(f);
            if (m.unknown[i]) unknown++;
            else {
                decided++;
                decisions[i]++;
                m.answer[i] = random.nextDouble() < within(lin.chance(f), lin.window(), EVERY);
                if (m.answer[i]) yes[i]++;
            }
        } else if (!m.unknown[i]) {
            // a yes is one tick's
            m.answer[i] = false;
        }
        return m.unknown[i] ? formula : m.answer[i];
    }

    /** the chance of it within ticks, from the chance within window, at an even rate */
    static double within(double p, int window, int ticks) {
        if (window <= 0 || ticks == window) return p;
        p = Math.max(0, Math.min(1 - 1e-9, p));
        return 1 - Math.pow(1 - p, ticks / (double) window);
    }

    /** which of the spots' rows to take: by the softmax of the model's scores; any row it does not know, 0 */
    int rank(double[][] spots) {
        Linear lin = model.rank();
        if (lin == null || spots.length < 2) return 0;
        double[] s = new double[spots.length];
        double top = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < spots.length; k++) {
            if (!lin.knows(spots[k])) {
                unknown++;
                return 0;
            }
            s[k] = lin.score(spots[k]);
            top = Math.max(top, s[k]);
        }
        double sum = 0;
        for (int k = 0; k < s.length; k++) sum += s[k] = Math.exp(s[k] - top);
        double r = random.nextDouble() * sum;
        decided++;
        for (int k = 0; k < s.length; k++) if ((r -= s[k]) <= 0) return k;
        return s.length - 1;
    }
}
