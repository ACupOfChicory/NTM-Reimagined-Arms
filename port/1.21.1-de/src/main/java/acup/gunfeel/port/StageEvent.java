package acup.gunfeel.port;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;

import com.hbm.backport.client.core.CameraRenderState;
import com.hbm.backport.client.core.LevelRenderState;
import com.hbm.backport.client.core.OrderedSubmitNodeCollector;
import com.hbm.backport.client.core.SubmitNodeCollector;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// 26.2's SubmitCustomGeometryEvent, RenderLevelStageEvent.AfterWeather and AfterOpaqueFeatures for Tracers, made from
// 1.21.1's one RenderLevelStageEvent (rewrite.txt; the port's GunFeel picks the stages). As 26.2's collector, the
// geometry submitted is kept and drawn later: flush() draws order 0 (after the batched holes, as 26.2 draws the
// submitted geometry after AfterOpaqueFeatures), flushLate() order -1 (26.2: after the translucent terrain, the holes
// on glass with improved transparency). The rest of the collector draws at once.
public final class StageEvent {
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));
    private static final List<Submit> NOW = new ArrayList<>(), LATE = new ArrayList<>();
    private static final SubmitNodeCollector IMMEDIATE = SubmitNodeCollector.immediate(BUFFERS);

    private record Submit(PoseStack.Pose pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
    }

    private final LevelRenderState state = new LevelRenderState();
    private final Matrix4f modelView;
    private final PoseStack pose = new PoseStack();
    private final Collector collector = new Collector(NOW);
    private final net.minecraft.client.renderer.culling.Frustum frustum;

    private StageEvent(RenderLevelStageEvent event) {
        state.cameraRenderState = CameraRenderState.of(event.getCamera());
        modelView = new Matrix4f(event.getModelViewMatrix());
        frustum = event.getFrustum();
    }

    public static StageEvent of(RenderLevelStageEvent event) {
        return new StageEvent(event);
    }

    public LevelRenderState getLevelRenderState() {
        return state;
    }

    public Matrix4f getModelViewMatrix() {
        return modelView;
    }

    public PoseStack getPoseStack() {
        return pose;
    }

    // 26.2 CameraRenderState.cullFrustum (rewrite.txt)
    public net.minecraft.client.renderer.culling.Frustum getFrustum() {
        return frustum;
    }

    public Collector getSubmitNodeCollector() {
        return collector;
    }

    public void flush() {
        draw(NOW);
    }

    public static void flushLate() {
        draw(LATE);
    }

    // a frame that never reached the late stage: what it kept is not drawn in the next one
    public static void endFrame() {
        NOW.clear();
        LATE.clear();
    }

    private static void draw(List<Submit> list) {
        if (list.isEmpty()) return;
        List<Submit> copy = new ArrayList<>(list);
        list.clear();
        for (Submit s : copy) s.renderer().render(s.pose(), BUFFERS.getBuffer(s.type()));
        BUFFERS.endBatch();
    }

    public static final class Collector implements SubmitNodeCollector {
        private final List<Submit> into;

        private Collector(List<Submit> into) {
            this.into = into;
        }

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return order < 0 ? new Collector(LATE) : this;
        }

        @Override
        public void submitCustomGeometry(PoseStack pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
            into.add(new Submit(pose.last().copy(), type, renderer));
        }

        @Override
        public void submitText(PoseStack pose, float x, float y, FormattedCharSequence text, boolean shadow, Font.DisplayMode mode,
                int light, int color, int background, int outline) {
            IMMEDIATE.submitText(pose, x, y, text, shadow, mode, light, color, background, outline);
        }

        @Override
        public void submitNameTag(PoseStack pose, Vec3 at, int y, Component text, boolean seeThrough, int light, CameraRenderState camera) {
            IMMEDIATE.submitNameTag(pose, at, y, text, seeThrough, light, camera);
        }

        @Override
        public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline, int[] tints,
                List<com.hbm.backport.client.blockmodel.BakedQuad> quads, com.hbm.backport.client.itemmodel.ItemStackRenderState.FoilType foil) {
            IMMEDIATE.submitItem(pose, context, light, overlay, outline, tints, quads, foil);
        }
    }
}
