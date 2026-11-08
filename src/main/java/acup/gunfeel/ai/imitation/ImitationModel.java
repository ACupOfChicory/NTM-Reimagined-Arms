package acup.gunfeel.ai.imitation;

import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The model trained on a player's recorded fights (tools/imitation/train.py writes it): for each question, a
 * logistic regression of whether the player did it within its window of ticks; for the cover to take, a score of
 * each spot (the one taken by the softmax of the scores). Each knows the range of every number it was trained on and
 * how many cases it saw: a view outside that range, or a question it saw too few cases of, is one it does not know,
 * and the formula answers. No library: a dot product.
 */
public final class ImitationModel {
    /** what the model is asked: the AiChoice places map to these */
    public enum Question {
        /** hidden in cover: out into an enemy's sight within the window */
        EXPOSE,
        /** in an enemy's sight by its cover: hidden again within the window */
        HIDE,
        /** in cover: off to other cover within the window */
        SHIFT,
        /** an enemy lost from sight: toward where it was within the window */
        PUSH
    }

    /** fewer cases than this, and it does not know the question */
    static final int MIN_CASES = 200;
    /** how far past the trained range a number may be */
    static final double SLACK = 0.1;

    public static final class Linear {
        final double[] w, min, max;
        final int window, cases;

        Linear(double[] w, double[] min, double[] max, int window, int cases) {
            this.w = w;
            this.min = min;
            this.max = max;
            this.window = window;
            this.cases = cases;
        }

        /** whether it was trained on views like this one */
        public boolean knows(double[] f) {
            if (cases < MIN_CASES || f.length != w.length) return false;
            for (int i = 0; i < f.length; i++) if (f[i] < min[i] - SLACK || f[i] > max[i] + SLACK) return false;
            return true;
        }

        public double score(double[] f) {
            double s = 0;
            for (int i = 0; i < w.length; i++) s += w[i] * f[i];
            return s;
        }

        /** the chance, within the window */
        public double chance(double[] f) {
            return 1 / (1 + Math.exp(-score(f)));
        }

        public int window() {
            return window;
        }
    }

    private final Map<Question, Linear> questions = new EnumMap<>(Question.class);
    private @Nullable Linear rank;
    public final String about;

    private ImitationModel(String about) {
        this.about = about;
    }

    public @Nullable Linear question(Question q) {
        return questions.get(q);
    }

    public @Nullable Linear rank() {
        return rank;
    }

    /** reads the model file; one whose numbers are not these (an older build's) is refused */
    public static ImitationModel parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (o.get("version").getAsInt() != 1) throw new IllegalArgumentException("model version " + o.get("version") + ", this build reads 1");
        check(o.getAsJsonArray("features"), FightFeatures.NAMES, "features");
        check(o.getAsJsonArray("spotFeatures"), FightFeatures.SPOT_NAMES, "spotFeatures");
        ImitationModel m = new ImitationModel(o.has("about") ? o.get("about").getAsString() : "");
        JsonObject qs = o.getAsJsonObject("questions");
        for (Question q : Question.values())
            if (qs.has(q.name())) m.questions.put(q, linear(qs.getAsJsonObject(q.name()), FightFeatures.SIZE));
        if (o.has("rank")) m.rank = linear(o.getAsJsonObject("rank"), FightFeatures.SPOT_SIZE);
        return m;
    }

    private static void check(JsonArray names, java.util.List<String> want, String what) {
        if (names == null || names.size() != want.size()) throw new IllegalArgumentException(what + ": not this build's");
        for (int i = 0; i < want.size(); i++)
            if (!names.get(i).getAsString().equals(want.get(i))) throw new IllegalArgumentException(what + ": " + names.get(i) + " where " + want.get(i));
    }

    private static Linear linear(JsonObject o, int size) {
        double[] w = numbers(o.getAsJsonArray("w"), size), min = numbers(o.getAsJsonArray("min"), size), max = numbers(o.getAsJsonArray("max"), size);
        return new Linear(w, min, max, o.has("window") ? o.get("window").getAsInt() : 1, o.get("cases").getAsInt());
    }

    private static double[] numbers(JsonArray a, int size) {
        if (a == null || a.size() != size) throw new IllegalArgumentException("a row of " + (a == null ? 0 : a.size()) + " numbers, not " + size);
        double[] out = new double[size];
        for (int i = 0; i < size; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }
}
