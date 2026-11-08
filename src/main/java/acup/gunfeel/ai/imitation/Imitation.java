package acup.gunfeel.ai.imitation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiBot;
import acup.gunfeel.ai.CoverQuery;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Bots that do as a player did: the model trained on his recorded fights ({@link ImitationModel}, from
 * {@link #MODEL}) answers AiChoice where the bots' formula decides, while the server key {@link #KEY} is on (off by
 * default; on with no model, the formula still decides). Server thread.
 */
public final class Imitation {
    /** the server key, true: the model answers */
    public static final String KEY = "aiImitation";
    /** the model file in the server's folder */
    public static final String MODEL = "config/ntm-gunfeel-imitation.json";
    /** the stands: -Dntmgunfeel.imitationModel=<file> reads that model instead, -Dntmgunfeel.imitation=on as the key on */
    static final String MODEL_PROPERTY = "ntmgunfeel.imitationModel", ON_PROPERTY = "ntmgunfeel.imitation";

    private static @Nullable ImitationModel model;
    private static @Nullable String modelError;
    private static @Nullable Chooser chooser;
    private static boolean on;

    private Imitation() {
    }

    /** the bots' side of AiChoice: a view of each bot (its own history) when the model is asked */
    static final class Chooser implements AiChoice.Chooser {
        final ImitationCore<AiBot> core;
        private final Map<AiBot, FightHistory> histories = new WeakHashMap<>();
        private final Map<AiBot, FightView> views = new WeakHashMap<>();
        /** time on the views and the model, for /armsai imitation info and the stands */
        long nanos, viewsMade;

        Chooser(ImitationModel m) {
            core = new ImitationCore<>(m, 0x1317L);
        }

        /** this tick's view of the bot (made once a tick at most) */
        FightView view(AiBot bot, long tick) {
            FightView v = views.get(bot);
            if (v != null && v.tick == tick) return v;
            if (!(bot.mob.level() instanceof ServerLevel level)) return new FightView();
            viewsMade++;
            v = FightLook.bot(level, bot, histories.computeIfAbsent(bot, b -> new FightHistory()), tick);
            views.put(bot, v);
            return v;
        }

        @Override
        public boolean choose(AiBot bot, AiChoice.Ask ask, boolean formula, long tick) {
            long t0 = System.nanoTime();
            try {
                return core.choose(bot, ask, formula, tick, () -> FightFeatures.of(view(bot, tick)));
            } finally {
                nanos += System.nanoTime() - t0;
            }
        }

        @Override
        public int rank(AiBot bot, List<CoverQuery.Spot> spots, long tick) {
            long t0 = System.nanoTime();
            try {
                return core.rank(FightLook.spotRows(spots, view(bot, tick)));
            } finally {
                nanos += System.nanoTime() - t0;
            }
        }
    }

    /** reads the model file again; what came of it */
    public static String reload(MinecraftServer server) {
        String asked = System.getProperty(MODEL_PROPERTY, "");
        Path file = asked.isBlank() ? server.getServerDirectory().resolve(MODEL) : Path.of(asked);
        model = null;
        modelError = null;
        chooser = null;
        AiChoice.set(null);
        on = false;
        if (!Files.isRegularFile(file)) {
            modelError = "no model at " + file;
            return modelError;
        }
        try {
            model = ImitationModel.parse(Files.readString(file));
            chooser = new Chooser(model);
            check();
            return "model read: " + file + (model.about.isEmpty() ? "" : " (" + model.about + ")");
        } catch (Exception e) {
            modelError = file + ": " + e.getMessage();
            return modelError;
        }
    }

    public static void started(MinecraftServer server) {
        String said = reload(server);
        if (!System.getProperty(MODEL_PROPERTY, "").isBlank() || keyOn()) org.slf4j.LoggerFactory.getLogger("ntmgunfeel").info("imitation: {}", said);
        FightRecorder.startAsked(server);
    }

    /** the server stops: what the model did, to the log, for the stands */
    public static void stopping() {
        if (model != null) org.slf4j.LoggerFactory.getLogger("ntmgunfeel").info("imitation at the end: {}", info());
        FightRecorder.stopAll();
    }

    private static boolean keyOn() {
        return "on".equals(System.getProperty(ON_PROPERTY)) || acup.gunfeel.ReaimTimes.flag(KEY, false);
    }

    /** every second: the key's state into AiChoice */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0) check();
        // the stands stop their server with no clean stop: what the model did, every half minute
        if (model != null && "on".equals(System.getProperty(ON_PROPERTY)) && server.getTickCount() % 600 == 0)
            org.slf4j.LoggerFactory.getLogger("ntmgunfeel").info("imitation so far: {}", info());
    }

    private static void check() {
        boolean want = keyOn() && chooser != null;
        if (want != on) {
            on = want;
            AiChoice.set(want ? chooser : null);
        }
    }

    public static String info() {
        String key = KEY + " " + (keyOn() ? "on" : "off");
        if (model == null) return key + "; " + (modelError == null ? "no model" : modelError) + ": the formula decides";
        Chooser c = chooser;
        ImitationCore<AiBot> k = c.core;
        StringBuilder b = new StringBuilder(key).append(on ? "; the model answers" : "; the formula decides").append("; asked ").append(k.asks)
                .append(", decided ").append(k.decided).append(", unknown ").append(k.unknown).append(", forced ").append(k.forced).append("; views ")
                .append(c.viewsMade).append(", ").append(String.format(java.util.Locale.ROOT, "%.1f", c.nanos / 1e6)).append(" ms");
        for (AiChoice.Ask a : AiChoice.Ask.values())
            if (k.decisions[a.ordinal()] > 0) b.append("; ").append(a).append(' ').append(k.yes[a.ordinal()]).append('/').append(k.decisions[a.ordinal()]);
        return b.toString();
    }

    /** for the stands: the chooser in use, if the model answers */
    static @Nullable Chooser active() {
        return on ? chooser : null;
    }
}
