package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

// F3+B writes its line vertices straight into memory: the bytes BufferBuilder wrote for them, every element of every
// vertex, and the same vertices left out (a segment of no length)
class ModelDebugLineVerticesTest {
    static final VertexFormat FORMAT=DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH;

    static List<Vec3[]> strips(long seed) {
        var random=new Random(seed);
        var strips=new ArrayList<Vec3[]>();
        for(int s=0;s<40;s++) {
            var strip=new Vec3[2*(1+random.nextInt(6))];
            for(int i=0;i<strip.length;i++)
                strip[i]=new Vec3(random.nextGaussian()*3-1e3,random.nextGaussian()*2+64,random.nextGaussian()*5+2e4);
            // a segment along an axis (a normal of ±1), one of no length, one shorter than the cut-off
            if(s%7==0) strip[1]=strip[0].add(0,0,-.5);
            if(s%9==0) strip[strip.length-1]=strip[strip.length-2];
            if(s%11==0) strip[1]=strip[0].add(1e-9,0,0);
            // a part of no size gives a NaN end: BufferBuilder wrote it, and count has to count it as write writes it
            if(s%13==0) strip[strip.length-1]=new Vec3(Double.NaN,strip[0].y,strip[0].z);
            strips.add(strip);
        }
        return strips;
    }

    static byte[] builder(List<Vec3[]> strips) {
        try(var storage=new ByteBufferBuilder(1<<20)) {
            var builder=new BufferBuilder(storage,PrimitiveTopology.LINES,FORMAT);
            for(var strip:strips) for(int i=0;i<strip.length;i+=2) {
                var a=strip[i]; var b=strip[i+1];
                double x=b.x-a.x,y=b.y-a.y,z=b.z-a.z,length=x*x+y*y+z*z;
                if(length<1e-16) continue;
                double inverse=1/Math.sqrt(length);
                builder.addVertex((float)a.x,(float)a.y,(float)a.z).setColor(-1)
                        .setNormal((float)(x*inverse),(float)(y*inverse),(float)(z*inverse)).setLineWidth(2.5F);
                builder.addVertex((float)b.x,(float)b.y,(float)b.z).setColor(-1)
                        .setNormal((float)(x*inverse),(float)(y*inverse),(float)(z*inverse)).setLineWidth(2.5F);
            }
            try(var mesh=builder.build()) {
                var data=mesh.vertexBuffer(); var bytes=new byte[data.remaining()]; data.get(bytes); return bytes;
            }
        }
    }

    @Test void theBytesBufferBuilderWrote() {
        var vertices=ModelDebugLineVertices.of(FORMAT);
        assertNotNull(vertices);
        for(long seed=1;seed<=20;seed++) {
            var strips=strips(seed);
            var expected=builder(strips);
            int count=ModelDebugLineVertices.count(strips);
            assertEquals(expected.length,count*vertices.size,"the same vertices left out");
            // written after other lines in the buffer, as the frame's buffer holds them
            int at=3*vertices.size;
            var to=ByteBuffer.allocateDirect(at+expected.length).order(ByteOrder.nativeOrder());
            assertEquals(count,vertices.write(to,at,strips));
            var got=new byte[expected.length]; to.get(at,got);
            // the bytes BufferBuilder writes of each element (of the normal three, its fourth is padding)
            var written=java.util.Map.of("Position",12,"Color",4,"Normal",3,"LineWidth",4);
            assertEquals(written.keySet(),new java.util.HashSet<>(FORMAT.getElements().stream().map(e->e.name()).toList()));
            for(var element:FORMAT.getElements()) {
                int width=written.get(element.name());
                for(int v=0;v<count;v++) for(int b=0;b<width;b++) {
                    int i=v*vertices.size+element.offset()+b;
                    assertEquals(expected[i],got[i],"seed "+seed+" vertex "+v+" "+element.name()+" byte "+b);
                }
            }
        }
    }

    @Test void anotherFormatIsLeftToBufferBuilder() {
        assertNull(ModelDebugLineVertices.of(DefaultVertexFormat.POSITION_COLOR));
        assertNull(ModelDebugLineVertices.of(DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH));
    }
}
