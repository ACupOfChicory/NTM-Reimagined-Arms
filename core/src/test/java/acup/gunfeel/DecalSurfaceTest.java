package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class DecalSurfaceTest {
    private static final Vector3d Z=new Vector3d(0,0,1),X=new Vector3d(1,0,0);
    private static DecalSurface.Frame frame(){return new DecalSurface.Frame(new Vector3d(),new Vector3d(1,0,0),new Vector3d(0,1,0),Z);}
    private static DecalSurface.Face face(Vector3d normal,double... xyz) {
        List<Vector3d> points=new ArrayList<>();for(int i=0;i<xyz.length;i+=3)points.add(new Vector3d(xyz[i],xyz[i+1],xyz[i+2]));
        return new DecalSurface.Face(points,normal);
    }
    @Test void nearbyParallelPostDoesNotReceiveAnotherCopy() {
        var first=face(Z,-.3,-.3,0,.3,-.3,0,.3,.3,0,-.3,.3,0);
        var separate=face(Z,-.3,-.3,.2,.3,-.3,.2,.3,.3,.2,-.3,.3,.2);
        var result=DecalSurface.unfold(List.of(first,separate),0,frame(),.45);
        assertNotNull(result[0]);assertNull(result[1]);
    }
    @Test void wallSeamAndCornerKeepContinuousTextureCoordinates() {
        var first=face(Z,-.3,-.3,0,.1,-.3,0,.1,.3,0,-.3,.3,0);
        var corner=face(X,.1,-.3,0,.1,.3,0,.1,.3,-.2,.1,-.3,-.2);
        var continuation=face(X,.1,-.3,-.2,.1,.3,-.2,.1,.3,-.3,.1,-.3,-.3);
        var result=DecalSurface.unfold(List.of(first,corner,continuation),0,frame(),.45);
        assertNotNull(result[1]);assertNotNull(result[2]);
        for(double y:new double[]{-.2,0,.2}) {
            var hinge=new Vector3d(.1,y,0);assertEquals(result[0].x(hinge),result[1].x(hinge),1e-8);assertEquals(result[0].y(hinge),result[1].y(hinge),1e-8);
        }
        assertEquals(.4,result[2].x(new Vector3d(.1,0,-.3)),1e-8);
    }
    @Test void connectedStepConsumesDistanceInsteadOfRepeatingTheCenter() {
        var first=face(Z,-.1,-.2,0,.1,-.2,0,.1,.2,0,-.1,.2,0);
        var rise=face(new Vector3d(-1,0,0),.1,-.2,0,.1,-.2,.625,.1,.2,.625,.1,.2,0);
        var far=face(Z,.1,-.2,.625,.4,-.2,.625,.4,.2,.625,.1,.2,.625);
        var result=DecalSurface.unfold(List.of(first,rise,far),0,frame(),.45);
        assertNotNull(result[1]);assertNull(result[2]);
        result=DecalSurface.unfold(List.of(first,rise,far),0,frame(),1);
        assertNotNull(result[2]);assertEquals(.725,result[2].x(new Vector3d(.1,0,.625)),1e-8);
    }
    @Test void partialEdgeContactWorksButGapAndPointContactDoNotSpreadTheMark() {
        var first=face(Z,-.3,-.3,0,.1,-.3,0,.1,.3,0,-.3,.3,0);
        var partial=face(X,.1,-.1,0,.1,.1,0,.1,.1,-.1,.1,-.1,-.1);
        var gap=face(Z,.1001,-.2,0,.2,-.2,0,.2,.2,0,.1001,.2,0);
        var point=face(X,.1,.3,0,.1,.5,0,.1,.5,-.1,.1,.3,-.1);
        var result=DecalSurface.unfold(List.of(first,partial,gap,point),0,frame(),.45);
        assertNotNull(result[1]);assertNull(result[2]);assertNull(result[3]);
    }
    @Test void degenerateTriangleCannotConnectUnrelatedSurfaces() {
        var first=face(Z,-.3,-.3,0,.1,-.3,0,.1,.3,0,-.3,.3,0);
        var line=face(X,.1,-.1,0,.1,0,0,.1,.1,0);
        assertNull(DecalSurface.unfold(List.of(first,line),0,frame(),.45)[1]);
    }
    @Test void movingTheModelFarFromWorldOriginPreservesTheUnfolding() {
        var offset=new Vector3d(20_000_000,-400,20_000_000);
        var first=face(Z,-.3,-.3,0,.1,-.3,0,.1,.3,0,-.3,.3,0);
        var corner=face(X,.1,-.3,0,.1,.3,0,.1,.3,-.2,.1,-.3,-.2);
        List<DecalSurface.Face> moved=new ArrayList<>();
        for(var source:List.of(first,corner))moved.add(new DecalSurface.Face(source.points.stream().map(p->new Vector3d(p).add(offset)).toList(),source.normal));
        var result=DecalSurface.unfold(moved,0,new DecalSurface.Frame(offset,new Vector3d(1,0,0),new Vector3d(0,1,0),Z),.45);
        assertNotNull(result[1]);assertEquals(.3,result[1].x(new Vector3d(.1,0,-.2).add(offset)),1e-7);
    }
    @Test void denseWallKeepsItsConnectedTilesAndRejectsNearbyFloatingCopy() {
        List<DecalSurface.Face> faces=new ArrayList<>();
        for(int layer=0;layer<2;layer++)for(int x=0;x<16;x++)for(int y=0;y<16;y++) {
            double a=(x-8)/8.,b=(y-8)/8.,z=layer*.1;
            faces.add(face(Z,a,b,z,a+.125,b,z,a+.125,b+.125,z,a,b+.125,z));
        }
        var frames=DecalSurface.unfold(faces,8*16+8,frame(),1);
        for(int i=0;i<256;i++)assertNotNull(frames[i]);
        for(int i=256;i<512;i++)assertNull(frames[i]);
    }
}
