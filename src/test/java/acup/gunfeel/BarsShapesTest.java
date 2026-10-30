package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BarsShapesTest {
    private static Properties read(String file) throws Exception {
        var props=new Properties();try(var reader=Files.newBufferedReader(Path.of("src/main/resources/"+file))){props.load(reader);}return props;
    }
    private static long[] shape(Properties props,String key) {
        String value=props.getProperty(key);assertNotNull(value,"на сервере нет модели "+key);
        if(value.startsWith("@"))value=props.getProperty(value);
        assertNotNull(value);var shape=PassableClip.unpack(value);assertNotNull(shape);
        assertTrue(ModelSurface.present(shape),key+": старая плоская маска вместо граней");
        assertTrue(ModelSurface.valid(shape));return shape;
    }
    @Test void allMaskedVanillaBarsHaveServerGeometryForEveryConnectionAndWaterState() throws Exception {
        var masks=read("ntmgunfeel-bullet-masks.properties");var shapes=read("ntmgunfeel-bullet-shapes.properties");int types=0;
        for(String mask:masks.stringPropertyNames()) {
            if(!mask.startsWith("minecraft:")||!mask.endsWith("_bars.*"))continue;types++;
            String id=mask.substring(0,mask.length()-2);
            for(int flags=0;flags<16;flags++) {
                String before=id+"[east="+((flags&1)!=0)+",north="+((flags&2)!=0)+",south="+((flags&4)!=0)+",waterlogged=";
                String after=",west="+((flags&8)!=0)+"]";
                assertArrayEquals(shape(shapes,before+"false"+after),shape(shapes,before+"true"+after));
            }
        }
        assertTrue(types>=9);
    }
    @Test void reportedObliqueRayHitsTheActualBarRatherThanMissingAtTheCollisionBox() throws Exception {
        var shapes=read("ntmgunfeel-bullet-shapes.properties");
        var model=shape(shapes,"minecraft:copper_bars[east=false,north=true,south=true,waterlogged=false,west=false]");
        var from=new Vec3(1.53083216128368,1.62000000476837,.72382667504166);
        var to=new Vec3(-11.6696108181439,-9.1550446335061,-.09377151570526);
        var hit=ModelSurface.hit(model,BlockPos.ZERO,from,to,false);
        assertNotNull(hit);assertEquals(.5,hit.getLocation().x,1e-6);
    }
}
