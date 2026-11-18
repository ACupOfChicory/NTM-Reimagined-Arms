package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import com.hbm.interfaces.injected.IBufferBuilderExtension;
import com.hbm.render.loader.GroupObject;
import com.hbm.render.util.Vertices;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

// The hitbox's buffer in front of the game's passes NTM's fast ways on (a group's whole mesh at once, a block of
// vertices) and reads off of them, to the bit, what it read off NTM's slow way before: the same vertices, places and
// texture points. A buffer without NTM's ways gets a plain wrapper, which NTM sends its slow way, as before.
class SubmittedModelGeometryPassTest {
    /** a buffer of the game's kind: counts the vertices it is given */
    static class Plain implements VertexConsumer {
        int vertices;
        @Override public VertexConsumer addVertex(float x,float y,float z) { vertices++; return this; }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u,float v) { return this; }
        @Override public VertexConsumer setUv1(int u,int v) { return this; }
        @Override public VertexConsumer setUv2(int u,int v) { return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
    /** a BufferBuilder as NTM makes it: its own ways take the vertices (or decline them, as a full buffer would), and
     *  every call with all it was given is written down */
    static final class Fast extends Plain implements IBufferBuilderExtension {
        final boolean takes;
        int bulk, blocks, ends;
        final List<String> calls=new ArrayList<>();
        Fast() { this(true); }
        Fast(boolean takes) { this.takes=takes; }
        @Override public VertexConsumer addVertex(float x,float y,float z) { calls.add("vertex "+x+" "+y+" "+z); return super.addVertex(x,y,z); }
        @Override public void hbm$ribbon(float hx,float hy,float hz,float tx,float ty,float tz,int a,int b,float c,float d,int e) {
            calls.add("ribbon "+hx+" "+hy+" "+hz+" "+tx+" "+ty+" "+tz+" "+a+" "+b+" "+c+" "+d+" "+e);
        }
        @Override public boolean hbm$positionColor(float x,float y,float z,int color) {
            calls.add("positionColor "+x+" "+y+" "+z+" "+color);
            if(takes) bulk++; return takes;
        }
        @Override public boolean hbm$putPart(float[] src,int stride,int count,PoseStack.Pose pose,int color,int overlay,int light,
                float us,float vs,float uo,float vo) {
            calls.add("putPart "+Arrays.toString(Arrays.copyOf(src,stride*count))+" "+stride+" "+count+" "+pose.pose()+" "+pose.normal()
                    +" "+color+" "+overlay+" "+light+" "+us+" "+vs+" "+uo+" "+vo);
            if(takes) bulk+=count; return takes;
        }
        @Override public boolean hbm$beginBlock(int count) { calls.add("beginBlock "+count); if(takes) blocks++; return takes; }
        @Override public void hbm$blockVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float nx,float ny,float nz) {
            calls.add("blockVertex "+x+" "+y+" "+z+" "+color+" "+u+" "+v+" "+overlay+" "+light+" "+nx+" "+ny+" "+nz); bulk++;
        }
        @Override public boolean hbm$beginColorBlock(int count) { calls.add("beginColorBlock "+count); if(takes) blocks++; return takes; }
        @Override public void hbm$colorVertex(float x,float y,float z,int color) { calls.add("colorVertex "+x+" "+y+" "+z+" "+color); bulk++; }
        @Override public void hbm$endBlock() { calls.add("endBlock"); ends++; }
    }

    static GroupObject group(Random random,int faces) {
        float[] data=new float[faces*20];
        for(int f=0;f<faces;f++) {
            float x=random.nextFloat()*2-1,y=random.nextFloat()*2-1,z=random.nextFloat()*2-1,u=random.nextFloat()*.8F,v=random.nextFloat()*.8F;
            float[][] corners={{0,0},{.31F,0},{.31F,.17F},{0,.17F}};
            for(int c=0;c<4;c++) {
                int p=f*20+c*5;
                data[p]=x+corners[c][0]; data[p+1]=y+corners[c][1]*(1+f%3); data[p+2]=z+corners[c][0]*corners[c][1];
                data[p+3]=u+corners[c][0]*.5F; data[p+4]=v+corners[c][1]*.7F;
            }
        }
        return GroupObject.ofPosUv("Test",data);
    }

    static PoseStack.Pose pose(int i) {
        var stack=new PoseStack();
        stack.translate(-3.25F+i*.17F,1.5F,7.75F);
        stack.mulPose(new Quaternionf().rotationXYZ(.3F*i,1.1F-i*.07F,.21F));
        stack.scale(.0625F*1.07F,.0625F,-.0625F*.93F);
        return stack.last();
    }

    @Test void aWholeGroupGoesToNtmsWayAndIsReadOffAsItsSlowWay() {
        var random=new Random(7);
        for(int i=0;i<12;i++) {
            var group=group(random,40+i*13);
            var p=pose(i);
            var fast=new Fast(); var plain=new Plain(); var direct=new Fast();
            var a=SubmittedModelGeometry.of(fast); var b=SubmittedModelGeometry.of(plain);
            float us=1+i*.25F,vs=.5F+i*.125F,uo=i*.03125F,vo=-.0625F;
            group.emit(p,a,0xF000F0,0xFFFFFFFF,us,vs,uo,vo,i%2==0);
            group.emit(p,b,0xF000F0,0xFFFFFFFF,us,vs,uo,vo,i%2==0);
            group.emit(p,direct,0xF000F0,0xFFFFFFFF,us,vs,uo,vo,i%2==0);
            assertEquals(group.faceCount()*4,fast.bulk,"the whole mesh in NTM's one call");
            assertEquals(0,fast.vertices,"none of it the slow way");
            assertTrue(fast.calls.getFirst().contains("] "+GroupObject.STRIDE+" "+group.faceCount()*4+" "),
                    "NTM's own stride (position, texture point, normal), the one the texture point is read at");
            assertEquals(direct.calls,fast.calls,"the buffer is given what NTM gives it without the hitboxes");
            assertEquals(group.faceCount()*4,plain.vertices,"a buffer without NTM's ways is sent them one by one");
            assertArrayEquals(b.recorded(),a.recorded(),"what is read off, to the bit");
            assertEquals(group.faceCount()*20,a.recorded().length);
        }
    }

    @Test void aBufferThatDeclinesGetsTheSlowWayAndItIsReadOffOnce() {
        var random=new Random(19);
        var group=group(random,45); var p=pose(5);
        var full=new Fast(false); var plain=new Plain();
        var a=SubmittedModelGeometry.of(full); var b=SubmittedModelGeometry.of(plain);
        group.emit(p,a,0xF000F0,0xFFFFFFFF,1,1,0,0,true);
        group.emit(p,b,0xF000F0,0xFFFFFFFF,1,1,0,0,true);
        int[] light=new int[group.faceCount()*4]; Arrays.fill(light,0xF000F0);
        group.emit(p,a,light,0xFFFFFFFF,false);
        group.emit(p,b,light,0xFFFFFFFF,false);
        for(int i=0;i<16;i++) { Vertices.emit(a,p,i*.1F,0,1,0xFF00FF00); Vertices.emit(b,p,i*.1F,0,1,0xFF00FF00); }
        assertEquals(plain.vertices,full.vertices,"the slow way, all of it");
        assertEquals(0,full.bulk); assertEquals(0,full.ends,"no block was begun, none ended");
        assertArrayEquals(b.recorded(),a.recorded(),"read off once, as the slow way reads it");
    }

    @Test void theWholeVertexReadsOffAsTheOneByOneCalls() {
        var random=new Random(11);
        var whole=SubmittedModelGeometry.of(new Plain()); var calls=SubmittedModelGeometry.of(new Plain());
        for(int i=0;i<500;i++) {
            float x=random.nextFloat()*30-15,y=random.nextFloat(),z=random.nextFloat()*-4,u=random.nextFloat(),v=random.nextFloat();
            whole.addVertex(x,y,z,0xFF00FF00,u,v,655360,0xF000F0,0,1,0);
            // the default of VertexConsumer: what the buffer was given before
            calls.addVertex(x,y,z).setColor(0xFF00FF00).setUv(u,v).setOverlay(655360).setLight(0xF000F0).setNormal(0,1,0);
        }
        assertArrayEquals(calls.recorded(),whole.recorded());
    }

    @Test void aBlockOfVerticesAndThePointsOfColourReadOffAsTheSlowWay() {
        var random=new Random(13);
        var group=group(random,57);
        var p=pose(3);
        int[] light=new int[group.faceCount()*4];
        for(int i=0;i<light.length;i++) light[i]=random.nextInt(0xF000F0);
        var fast=new Fast(); var plain=new Plain(); var direct=new Fast();
        var a=SubmittedModelGeometry.of(fast); var b=SubmittedModelGeometry.of(plain);
        group.emit(p,a,light,0xFFFFFFFF,false);
        group.emit(p,b,light,0xFFFFFFFF,false);
        group.emit(p,direct,light,0xFFFFFFFF,false);
        assertEquals(1,fast.blocks); assertEquals(1,fast.ends,"the block begun is ended");
        assertEquals(light.length,fast.bulk); assertEquals(0,fast.vertices);
        assertEquals(direct.calls,fast.calls);
        assertArrayEquals(b.recorded(),a.recorded());

        var c=SubmittedModelGeometry.of(new Fast()); var d=SubmittedModelGeometry.of(new Plain());
        for(int i=0;i<64;i++) {
            float x=random.nextFloat(),y=random.nextFloat(),z=random.nextFloat();
            Vertices.emit(c,p,x,y,z,0xFF0000FF);
            Vertices.emit(d,p,x,y,z,0xFF0000FF);
        }
        assertArrayEquals(d.recorded(),c.recorded());

        var colour=new Fast();
        var e=SubmittedModelGeometry.of(colour); var f=SubmittedModelGeometry.of(new Plain());
        var block=(IBufferBuilderExtension)e;
        assertTrue(block.hbm$beginColorBlock(32));
        for(int i=0;i<32;i++) {
            float x=random.nextFloat(),y=random.nextFloat(),z=random.nextFloat();
            Vertices.emitColorBlock(block,p,x,y,z,0xFF0000FF);
            Vertices.emit(f,p,x,y,z,0xFF0000FF);
        }
        block.hbm$endBlock();
        assertEquals(1,colour.ends);
        assertArrayEquals(f.recorded(),e.recorded());
    }

    @Test void anIgnoredBufferPassesAllOnAndReadsNothing() {
        var group=group(new Random(17),33);
        var fast=new Fast(); var a=SubmittedModelGeometry.of(fast);
        a.ignore();
        group.emit(pose(1),a,0xF000F0,0xFFFFFFFF,1,1,0,0,true);
        int[] light=new int[group.faceCount()*4]; Arrays.fill(light,0xF000F0);
        group.emit(pose(2),a,light,0xFFFFFFFF,false);
        Vertices.emit(a,pose(2),0,0,0,0xFF0000FF);
        a.addVertex(1,2,3,0xFFFFFFFF,.5F,.5F,0,0,0,1,0);
        assertEquals(group.faceCount()*8+1,fast.bulk);
        assertEquals(1,fast.ends);
        assertEquals(0,a.recorded().length);
    }

    @Test void theFastWaysAreNtmsBuffersOnly() {
        assertFalse(SubmittedModelGeometry.of(new Plain()) instanceof IBufferBuilderExtension,
                "in front of another buffer NTM sees a plain one and takes its slow way, as before");
        var fast=new Fast();
        var ribbon=(IBufferBuilderExtension)SubmittedModelGeometry.of(fast);
        ribbon.hbm$ribbon(1,2,3,4,5,6,7,8,9,10,11);
        var direct=new Fast(); direct.hbm$ribbon(1,2,3,4,5,6,7,8,9,10,11);
        assertEquals(direct.calls,fast.calls,"a tracer's ribbon is the buffer's own");
    }
}
