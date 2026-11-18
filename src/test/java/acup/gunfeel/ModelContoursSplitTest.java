package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

// F3+B outlines each of NTM's model groups on its own (the pieces of one group lie in one box, and its outline is the
// same whatever the pose, so it is built once) and the rest of a mob together, as before: its cubes and the meshes
// that are not NTM's groups (Flywheel's).
class ModelContoursSplitTest {
    // a closed wedge in the unit box, its two halves two meshes as NTM's pieces of one group would be
    static final ModelMesh FRONT=grouped(ModelMesh.register(new ModelMesh(new float[]{
            0,0,0, 1,0,0, 1,1,0, 0,0,0, 1,1,0, 0,1,0,
            0,0,0, 0,1,0, 0,0,1, 1,0,0, 1,0,1, 1,1,0})));
    static final ModelMesh BACK=grouped(ModelMesh.register(new ModelMesh(new float[]{
            0,0,0, 0,0,1, 1,0,1, 0,0,0, 1,0,1, 1,0,0,
            0,1,0, 1,1,0, 1,0,1, 0,1,0, 1,0,1, 0,0,1})));
    // a mesh Flywheel took (a vanilla model's part under CrankShaft): not one of NTM's groups
    static final ModelMesh FLYWHEEL=ModelMesh.register(new ModelMesh(new float[]{
            0,0,0, 1,0,0, 0,1,0, 0,0,0, 0,1,0, 0,0,1,
            0,0,0, 0,0,1, 1,0,0, 1,0,0, 0,0,1, 0,1,0}));
    static ModelMesh grouped(ModelMesh mesh) { mesh.grouped=true; return mesh; }

    static ModelBox box(String part,Matrix4f m,float x,float y,float z,float sx,float sy,float sz,long mesh) {
        var o=m.transformPosition(x,y,z,new Vector3f());
        var u=m.transformDirection(sx,0,0,new Vector3f()); var v=m.transformDirection(0,sy,0,new Vector3f());
        var w=m.transformDirection(0,0,sz,new Vector3f());
        return new ModelBox(part,o.x,o.y,o.z,u.x,u.y,u.z,v.x,v.y,v.z,w.x,w.y,w.z,mesh);
    }
    static List<ModelBox> mob(Matrix4f m,float arm) {
        var arms=new Matrix4f(m).rotateX(arm);
        var list=new ArrayList<ModelBox>();
        list.add(box("root/body",m,0,0,0,.5F,.75F,.25F,0));
        list.add(box("root/head",m,0,.75F,0,.5F,.5F,.5F,0));
        list.add(box("root/head/inner",m,.05F,.8F,.05F,.4F,.4F,.4F,0));
        for(var mesh:new ModelMesh[]{FRONT,BACK}) list.add(box("mesh/chest",m,-.05F,0,-.05F,.6F,.8F,.35F,mesh.id()));
        for(var mesh:new ModelMesh[]{FRONT,BACK}) list.add(box("mesh/arm",arms,.55F,.1F,0,.2F,.6F,.25F,mesh.id()));
        return list;
    }

    @Test void cubesTogetherMeshesByGroupAnInnerLayerLeftOut() {
        var split=ModelContours.split(mob(new Matrix4f(),.4F));
        assertEquals(List.of("root/body","root/head","root/head/inner"),split.together().stream().map(ModelBox::part).toList(),
                "the cubes in their order, all of them: collect leaves the inner layer out, and the list stays the same");
        // a mesh inside its own is left out here, collect sees a group on its own
        var nested=new ArrayList<>(mob(new Matrix4f(),.4F));
        nested.add(box("mesh/chest/lining",new Matrix4f(),0,.05F,0,.5F,.7F,.25F,FRONT.id()));
        var lined=ModelContours.split(nested);
        assertEquals(2,lined.groups().size());
        assertTrue(lined.groups().stream().flatMap(List::stream).noneMatch(b->b.part().equals("mesh/chest/lining")));
        assertEquals(2,split.groups().size(),"a group's two pieces are one group, the arm's another");
        assertEquals(List.of(FRONT.id(),BACK.id()),split.groups().get(0).stream().map(ModelBox::mesh).toList());
        assertEquals(List.of("mesh/arm","mesh/arm"),split.groups().get(1).stream().map(ModelBox::part).toList());
    }

    @Test void aMobOfCubesOnlyIsOutlinedAsBefore() {
        var boxes=new ArrayList<>(mob(new Matrix4f().translate(3,64,-7),0).subList(0,3));
        var split=ModelContours.split(boxes);
        assertTrue(split.groups().isEmpty());
        assertEquals(boxes,split.together());
        ModelContours.clear();
        var before=ModelContours.edges(boxes);
        ModelContours.clear();
        var now=ModelContours.edges(split.together());
        assertEquals(before.size(),now.size());
        for(int i=0;i<before.size();i++) assertArrayEquals(before.get(i),now.get(i),"the same outline, to the bit");
    }

    @Test void flywheelsMeshesGoWithTheCubesAsBefore() {
        var boxes=new ArrayList<>(mob(new Matrix4f(),.4F));
        boxes.add(2,box("mesh/root/body/tail",new Matrix4f(),.1F,0,.25F,.3F,.3F,.4F,FLYWHEEL.id()));
        var split=ModelContours.split(boxes);
        assertEquals(List.of("root/body","root/head","mesh/root/body/tail","root/head/inner"),
                split.together().stream().map(ModelBox::part).toList(),"in the mob's order, outlined with its cubes");
        assertEquals(2,split.groups().size(),"NTM's groups as before");
        var flywheelOnly=List.of(box("mesh/root/body",new Matrix4f(),0,0,0,.5F,.5F,.5F,FLYWHEEL.id()),
                box("mesh/root/head",new Matrix4f(),0,.5F,0,.5F,.5F,.5F,FLYWHEEL.id()));
        assertEquals(flywheelOnly,ModelContours.split(flywheelOnly).together(),"a vanilla model under CrankShaft is outlined as before");
        assertTrue(ModelContours.split(flywheelOnly).groups().isEmpty());
    }

    @Test void aGroupIsBuiltOnceWhateverThePose() {
        ModelContours.clear();
        var first=ModelContours.split(mob(new Matrix4f(),0)).groups();
        var outlines=ModelContours.outlines(new ArrayList<>(first),java.util.Collections.nCopies(first.size(),null));
        long builds=ModelContours.builds();
        for(int frame=1;frame<=60;frame++) {
            var m=new Matrix4f().translate(14.7F+frame*.05F,100.5F,-21.3F).rotateXYZ(frame*.01F,frame*.07F,frame*.03F);
            var groups=ModelContours.split(mob(m,frame*.05F)).groups();
            var now=ModelContours.outlines(new ArrayList<>(groups),java.util.Collections.nCopies(groups.size(),null));
            for(int g=0;g<groups.size();g++)
                assertSame(outlines.get(g).edges(),now.get(g).edges(),"the group's outline and its buffer are kept, only the matrix moves");
        }
        assertEquals(builds,ModelContours.builds(),"no group was built again");
    }
}
