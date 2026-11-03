package acup.gunfeel.core;

import java.util.Collections;
import java.util.List;

import zone.rong.mixinbooter.ILateMixinLoader;

/** The mixins on NTM CE's classes (mixins.ntmgunfeel.late.json), queued once the mods are on the class path. */
public final class ArmsLateMixins implements ILateMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.ntmgunfeel.late.json");
    }
}
