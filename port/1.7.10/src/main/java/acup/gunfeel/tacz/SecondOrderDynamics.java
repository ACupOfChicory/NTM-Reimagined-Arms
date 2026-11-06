// SPDX-License-Identifier: GPL-3.0-only
// Copied from TaCZ (Timeless and Classics Zero), MCModderAnchor/TACZ and contributors, GPL-3.0.
// https://github.com/MCModderAnchor/TACZ, revision 19f14c8, src/main/java/com/tacz/guns/util/math/SecondOrderDynamics.java.
// Changes: the package; GunTime is acup.gunfeel.tacz.GunTime; the pool's threads are daemons (TaCZ's inherit the
// render thread's flag and keep the game from closing); target, py and pyd are volatile (written and read by both the
// frame and the filter's thread).
// port 1.7.10: Minecraft.getMinecraft().
package acup.gunfeel.tacz;

import net.minecraft.client.Minecraft;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class SecondOrderDynamics {
    public static final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(15, r -> {
        Thread t = new Thread(r, "reimagined-arms-dynamics");
        t.setDaemon(true);
        return t;
    });

    static {
        for (int i = 0; i < 15; i++) {
            executorService.execute(() -> {
            });
        }
    }

    private final float k1;
    private final float k2;
    private final float k3;

    private volatile float py;
    private volatile float pyd;
    private float px;

    private volatile float target;

    private boolean stop = false;

    /**
     * @param f  Natural frequency
     * @param z  Damping coefficient
     * @param r  Initial velocity
     * @param x0 Initial position
     */
    public SecondOrderDynamics(float f, float z, float r, float x0) {
        k1 = (float) (z / (Math.PI * f));
        k2 = (float) (1 / ((2 * Math.PI * f) * (2 * Math.PI * f)));
        k3 = (float) (r * z / (2 * Math.PI * f));

        py = px = x0;
        pyd = 0;

        target = x0;

        executorService.execute(this::update);
    }

    /**
     * @return processed y value
     */
    public float update(float x) {
        target = x;
        return get();
    }

    public float get() {
        // 修正罕见的 NAN 错误
        if (Float.isNaN(py)) {
            py = 0;
        }
        if (Float.isNaN(pyd)) {
            pyd = 0;
        }
        return py + 0.05f * pyd;
    }

    public void stop() {
        this.stop = true;
    }

    private void update() {
        while (!stop) {
            if (Minecraft.getMinecraft() == null || GunTime.shouldTick()) {
                // 修正罕见的 NAN 错误
                if (Float.isNaN(py)) {
                    py = 0;
                }
                if (Float.isNaN(pyd)) {
                    pyd = 0;
                }

                float t = 0.05f;
                float xd = (target - px) / t;
                float y = py + t * pyd;

                pyd = pyd + t * (px + k3 * xd - py - k1 * pyd) / k2;
                px = target;
                py = y;
            }

            try {
                Thread.sleep(6);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
