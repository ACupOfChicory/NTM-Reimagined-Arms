package acup.gunfeel.port;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

// NtmRenderFixes (ntmRenderFixes): DE's laminate and its pane drawn translucent. The wrapper has to answer for the item
// too: without getRenderPasses and applyTransform of its own, BakedModelWrapper hands out the original model, which
// asks for the cutout sheet, and the item is opaque again.
class NtmRenderFixesTest {
    @Test
    void theWrapperAnswersForBlockAndItem() throws IOException {
        ClassNode c = new ClassNode();
        try (InputStream in = NtmRenderFixes.class.getResourceAsStream("NtmRenderFixes$Translucent.class")) {
            new ClassReader(in).accept(c, ClassReader.SKIP_CODE);
        }
        Set<String> methods = new HashSet<>();
        for (MethodNode m : c.methods) methods.add(m.name + m.desc);
        String[] needed = {
            "getRenderTypes(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;)Lnet/neoforged/neoforge/client/ChunkRenderTypeSet;",
            "getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)Ljava/util/List;",
            "getRenderTypes(Lnet/minecraft/world/item/ItemStack;Z)Ljava/util/List;",
            "getRenderPasses(Lnet/minecraft/world/item/ItemStack;Z)Ljava/util/List;",
            "applyTransform(Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Z)Lnet/minecraft/client/resources/model/BakedModel;"};
        for (String m : needed) assertTrue(methods.contains(m), "NtmRenderFixes.Translucent does not override " + m);
    }

    // the key is read from the file when the models are baked: the config is loaded only on the first frame, after
    @Test
    void theKeyIsReadBeforeTheFirstFrame() throws IOException {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("port/1.21.1-de/src/main/java/acup/gunfeel/port/NtmRenderFixes.java"));
        assertTrue(src.contains("if (!on()) {"), "wrap() asks Config.ntmRenderFixes, which is still the default at the first baking");
        assertTrue(src.contains("p.getProperty(\"ntmRenderFixes\")"), "on() does not read the key from the file");
    }
}
