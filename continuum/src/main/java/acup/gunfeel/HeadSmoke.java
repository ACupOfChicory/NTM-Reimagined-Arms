package acup.gunfeel;

import java.util.function.BiConsumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

// port/continuum: a stand-in for the 26.2 head-hit self-test (written for NTM NEXT's beam lambdas): it does not run on this node.
final class HeadSmoke {
    private HeadSmoke() {
    }

    static void bindings(LivingEntity target, BiConsumer<Boolean, String> check) {
    }

    static void frame() {
    }

    static void run(ServerLevel level, LivingEntity target, BiConsumer<Boolean, String> check) {
    }
}
