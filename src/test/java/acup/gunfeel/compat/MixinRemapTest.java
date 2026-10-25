package acup.gunfeel.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MixinRemapTest {
    @Test void actualGlyphidMixinReproducesTheLaunchFailureWithImplicitShadowRemapping() {
        var mixin = HbmMixinPlugin.read(getClass().getClassLoader(), "acup/gunfeel/mixin/MixinGlyphidModelHits");
        assertNotNull(mixin);
        var probe = new MixinProbe(CompatTest.classes());
        assertTrue(probe.check(mixin,1).isEmpty());
        var poses = mixin.fields.stream().filter(f -> f.name.equals("poses")).findFirst().orElseThrow();
        var shadow = MixinProbe.annotation(poses.invisibleAnnotations,poses.visibleAnnotations,MixinProbe.MIXIN + "Shadow;");
        assertNotNull(shadow);
        shadow.values = List.of(); // Метаданные сломанного X314: неявное remap=true, несмотря на @Mixin(remap=false).
        assertTrue(probe.check(mixin,1).stream().anyMatch(p -> p.text().contains("remappable @Shadow")));
    }
}
