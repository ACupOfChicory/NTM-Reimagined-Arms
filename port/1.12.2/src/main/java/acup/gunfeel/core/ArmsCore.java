package acup.gunfeel.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

/**
 * The coremod: only there to hand MixinBooter the mixins on Minecraft's own classes (mixins.ntmgunfeel.json), which
 * must be queued before Minecraft loads. The mixins on NTM's classes come later (ArmsLateMixins).
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("NTM Reimagined Arms core")
@IFMLLoadingPlugin.TransformerExclusions({"acup.gunfeel.core"})
public final class ArmsCore implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.ntmgunfeel.json");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
