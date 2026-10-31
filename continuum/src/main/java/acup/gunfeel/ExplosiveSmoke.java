package acup.gunfeel;

import java.util.function.BiConsumer;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// port/continuum: a stand-in for the 26.2 explosive-ammo self-test (written for NTM NEXT): it does not run on this node.
final class ExplosiveSmoke {
    private ExplosiveSmoke() {
    }

    static void run(ServerLevel level, LivingEntity target, Vec3 head, Vec3 body, Vec3 limb, BiConsumer<Boolean, String> check) {
    }

    static void hole(BulletConfig config, float size) {
    }

    static boolean finish() {
        return true;
    }
}
