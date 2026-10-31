package acup.gunfeel;

import java.util.List;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;

// port/continuum: a stand-in for ModelDebugMask, the instanced ownership mask of the F3+B outlines: 26.1's RenderPass
// draws no instances. The 26.1 node draws the outlines the way main's benchmarks call legacy (MobBenchMetrics.LEGACY,
// stonecutter.gradle.kts): one vertex buffer of all the faces, the edges as lines.
final class ModelDebugMask {
    private ModelDebugMask() {
    }

    static void draw(List<ModelDebugRender.Shape> shapes, RenderTarget destination, GpuBufferSlice transforms) {
    }

    static void clear() {
    }
}
