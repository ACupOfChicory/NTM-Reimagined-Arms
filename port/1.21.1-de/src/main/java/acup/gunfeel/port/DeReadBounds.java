package acup.gunfeel.port;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

// The plugin of ntmgunfeel-de.mixins.json, the patches to DE's own bugs: one line in the log when a patch is in.
public final class DeReadBounds implements IMixinConfigPlugin {
    private static final Logger LOG = LoggerFactory.getLogger("ntm-gunfeel");
    static final String TARGET = "com.hbm.util.BlockReadBounds$Client";

    // a block of chunk column (blockX >> 4, blockZ >> 4) is one of the 3 x 3 of a RenderChunkRegion whose lower corner
    // chunk is (minChunkX, minChunkZ): what DE's BlockReadBounds.Client.canRead checks
    public static boolean inRegion(int minChunkX, int minChunkZ, int blockX, int blockZ) {
        int x = (blockX >> 4) - minChunkX, z = (blockZ >> 4) - minChunkZ;
        return x >= 0 && x < 3 && z >= 0 && z < 3;
    }

    // canRead calls the patch's handler: the injection went in (require = 0 lets it miss without a crash)
    static boolean patched(ClassNode target) {
        for (MethodNode m : target.methods) {
            if (!m.name.equals("canRead") || !m.desc.equals("(Lnet/minecraft/world/level/BlockGetter;III)Z")) continue;
            for (AbstractInsnNode in : m.instructions)
                if (in instanceof MethodInsnNode call && call.name.contains("gunfeel$canRead")) return true;
        }
        return false;
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!targetClassName.equals(TARGET)) return;
        if (patched(targetClass))
            LOG.info("DE patch on: BlockReadBounds.Client.canRead keeps reads inside the RenderChunkRegion (DE beta.14 leaves them unbounded)");
        else LOG.warn("DE patch not in: BlockReadBounds.Client.canRead is not what it was in DE beta.14");
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
