package acup.gunfeel;

import java.nio.ByteBuffer;
import java.util.List;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

// F3+B's line vertices written straight into memory, byte for byte as BufferBuilder writes them in vanilla's line
// format (position, colour, normal, line width). BufferBuilder takes some 150 ns a vertex, and the outline of a moving
// mob's cubes is new every frame: thousands of vertices a frame with ten walking bots in view.
final class ModelDebugLineVertices {
    static final float WIDTH=2.5F;
    final int size;
    private final int position,color,normal,width;
    private ModelDebugLineVertices(VertexFormat format) {
        size=format.getVertexSize();
        position=format.getElement("Position").offset(); color=format.getElement("Color").offset();
        normal=format.getElement("Normal").offset(); width=format.getElement("LineWidth").offset();
    }
    // null where the lines are drawn in another format (BufferBuilder writes them then)
    static @Nullable ModelDebugLineVertices of(VertexFormat format) {
        return format.equals(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH)?new ModelDebugLineVertices(format):null;
    }
    // the vertices the strips make: four a segment, each end twice running (BufferBuilder writes a vertex of LINES
    // twice, the line drawn as a quad from them), a segment of no length left out (as ModelDebugLines left it out)
    static int count(List<Vec3[]> strips) {
        int vertices=0;
        for(var strip:strips) for(int i=0;i<strip.length;i+=2) if(!skipped(strip[i],strip[i+1])) vertices+=4;
        return vertices;
    }
    // the one rule count and write share (the frame's buffer is laid out by count): a segment shorter than 1e-8 is
    // left out, one with a NaN end is written, as ModelDebugLines wrote it through BufferBuilder
    private static boolean skipped(Vec3 a,Vec3 b) { double x=b.x-a.x,y=b.y-a.y,z=b.z-a.z; return x*x+y*y+z*z<1e-16; }
    // the strips' vertices from byte at of to, which is in the native byte order; the vertices written
    int write(ByteBuffer to,int at,List<Vec3[]> strips) {
        int vertices=0;
        for(var strip:strips) for(int i=0;i<strip.length;i+=2) {
            var a=strip[i]; var b=strip[i+1];
            if(skipped(a,b)) continue;
            double x=b.x-a.x,y=b.y-a.y,z=b.z-a.z,inverse=1/Math.sqrt(x*x+y*y+z*z);
            byte nx=normal((float)(x*inverse)),ny=normal((float)(y*inverse)),nz=normal((float)(z*inverse));
            vertex(to,at+vertices++*size,a,nx,ny,nz); vertex(to,at+vertices++*size,a,nx,ny,nz);
            vertex(to,at+vertices++*size,b,nx,ny,nz); vertex(to,at+vertices++*size,b,nx,ny,nz);
        }
        return vertices;
    }
    private void vertex(ByteBuffer to,int at,Vec3 p,byte nx,byte ny,byte nz) {
        to.putFloat(at+position,(float)p.x).putFloat(at+position+4,(float)p.y).putFloat(at+position+8,(float)p.z);
        to.putInt(at+color,-1);
        to.put(at+normal,nx).put(at+normal+1,ny).put(at+normal+2,nz);
        to.putFloat(at+width,WIDTH);
    }
    // BufferBuilder.normalIntValue
    private static byte normal(float v) { return (byte)(int)(Mth.clamp(v,-1F,1F)*127F); }
}
