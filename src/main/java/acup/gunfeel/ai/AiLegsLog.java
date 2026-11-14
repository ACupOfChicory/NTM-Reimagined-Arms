package acup.gunfeel.ai;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The AI check's record of how its fighters move, for the legs' replay (BotLegsReplayTest: the client's walk and pose
 * worked out from it, the feet's height over the ground): per fighter a tick, where it is, its body's turn, its pose
 * as synced, what it does (its mode and why) and how far it is from its cover, the check's stage. Only with -PaiLegs=file (ntmgunfeel.checkAiLegs).
 */
final class AiLegsLog {
    static final String PROPERTY = "ntmgunfeel.checkAiLegs";
    private static PrintWriter out;
    private static boolean opened;
    private static int fight;
    /** the check's stage it is in (set by AiCheck as each stage begins) */
    static String stage = "teams";

    private AiLegsLog() {
    }

    /** a fight begins */
    static void begin() {
        fight++;
    }

    static void tick(ServerLevel level, List<AiBot> bots) {
        if (!opened) {
            opened = true;
            String f = System.getProperty(PROPERTY);
            if (f != null && !f.isBlank()) try {
                Files.createDirectories(Path.of(f).toAbsolutePath().getParent());
                out = new PrintWriter(Files.newBufferedWriter(Path.of(f), StandardCharsets.UTF_8));
                out.println("fight\ttick\tid\tx\tz\tbodyYaw\tstance\tlean\tprevLean\tleanTick\tarms\taction\tactionTick\tactionTicks\tmode\tspot\twhy\tstage");
            } catch (IOException e) {
                out = null;
            }
        }
        if (out == null) return;
        long t = level.getGameTime();
        for (AiBot b : bots) {
            if (!b.mob.isAlive()) continue;
            AiPose p = AiPose.of(b.mob);
            String mode = "-", why = "-";
            double spot = -1;
            if (b.brain instanceof AiSoldier s) {
                mode = s.mode().name();
                why = s.why.name();
                if (s.spot() != null) {
                    long c = s.spot().cell();
                    spot = Math.hypot(b.mob.getX() - (BlockPos.getX(c) + 0.5), b.mob.getZ() - (BlockPos.getZ(c) + 0.5));
                }
            }
            out.printf(Locale.ROOT, "%d\t%d\t%d\t%.4f\t%.4f\t%.2f\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%.2f\t%s\t%s%n", fight, t, b.mob.getId(), b.mob.getX(), b.mob.getZ(),
                    b.mob.yBodyRot, p.stance(), p.lean(), p.prevLean(), p.leanTick(), p.arms(), p.action(), p.actionTick(), p.actionTicks(), mode, spot, why, stage);
        }
    }

    static void flush() {
        if (out != null) out.flush();
    }
}
