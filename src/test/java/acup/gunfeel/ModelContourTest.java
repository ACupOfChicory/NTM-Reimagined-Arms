package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ModelContourTest {
    @Test void coplanarTJunctionHasOnlyTheOuterContour() {
        // Слева один прямоугольник, справа два: длинная граница совпадает
        // с двумя короткими. Ни границы прямоугольников, ни диагонали не видны.
        var mesh=new ModelMesh(new float[]{
                0,0,0, .5F,0,0, .5F,1,0, 0,0,0, .5F,1,0, 0,1,0,
                .5F,0,0, 1,0,0, 1,.5F,0, .5F,0,0, 1,.5F,0, .5F,.5F,0,
                .5F,.5F,0, 1,.5F,0, 1,1,0, .5F,.5F,0, 1,1,0, .5F,1,0});
        var edges=mesh.edges(); assertTrue(edges.length>0);
        for(int i=0;i<edges.length;i+=6) {
            boolean border=edges[i]==0 && edges[i+3]==0 || edges[i]==1 && edges[i+3]==1
                    || edges[i+1]==0 && edges[i+4]==0 || edges[i+1]==1 && edges[i+4]==1;
            assertTrue(border,"Внутренний шов попал в обводку");
        }
        double perimeter=0;
        for(int i=0;i<edges.length;i+=6) perimeter+=Math.hypot(edges[i+3]-edges[i],edges[i+4]-edges[i+1]);
        assertEquals(4,perimeter,1e-6);
    }

    @Test void actualMobFacesDoNotShowRectanglePartitionSeams() throws Exception {
        var roots=java.util.List.of(
                net.minecraft.client.model.monster.enderman.EndermanModel.createBodyLayer().bakeRoot(),
                net.minecraft.client.model.monster.illager.IllagerModel.createBodyLayer().bakeRoot(),
                net.minecraft.client.model.animal.fox.AdultFoxModel.createBodyLayer().bakeRoot(),
                net.minecraft.client.model.animal.frog.FrogModel.createBodyLayer().bakeRoot());
        String[] textures={"enderman/enderman.png","illager/evoker.png","fox/fox.png","frog/frog_temperate.png"};
        for(int i=0;i<roots.size();i++) {
            var mask=png(textures[i]); String mob=textures[i];
            roots.get(i).visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,name,index,cube) -> {
                for(var mesh:ModelCubeGeometry.bake(cube,mask).meshes()) {
                    var edges=mesh.edges(); var triangles=mesh.triangles();
                    for(int k=0;k<edges.length;k+=6) {
                        int plane=-1,planes=0;
                        for(int axis=0;axis<3;axis++) if(edges[k+axis]==edges[k+3+axis] && (edges[k+axis]==0 || edges[k+axis]==1)) { plane=axis; planes++; }
                        if(planes!=1) continue;
                        int a=(plane+1)%3,b=(plane+2)%3;
                        double[] p={(edges[k]+edges[k+3])/2.,(edges[k+1]+edges[k+4])/2.,(edges[k+2]+edges[k+5])/2.};
                        double dx=edges[k+3+a]-edges[k+a],dy=edges[k+3+b]-edges[k+b],length=Math.hypot(dx,dy);
                        if(length<1e-8) continue;
                        double[] left=p.clone(),right=p.clone();
                        left[a]-=dy/length*1e-4; left[b]+=dx/length*1e-4;
                        right[a]+=dy/length*1e-4; right[b]-=dx/length*1e-4;
                        assertFalse(onFace(triangles,left,plane,p[plane]) && onFace(triangles,right,plane,p[plane]),mob+name+": внутренний шов плоскости");
                    }
                }
            });
        }
    }

    private static boolean onFace(float[] triangles,double[] p,int plane,double value) {
        int a=(plane+1)%3,b=(plane+2)%3;
        for(int k=0;k<triangles.length;k+=9) {
            if(Math.abs(triangles[k+plane]-value)>1e-6 || Math.abs(triangles[k+3+plane]-value)>1e-6 || Math.abs(triangles[k+6+plane]-value)>1e-6) continue;
            double[] s=new double[3];
            for(int j=0;j<3;j++) {
                int u=k+j*3,v=k+(j+1)%3*3;
                s[j]=(triangles[v+a]-triangles[u+a])*(p[b]-triangles[u+b])-(triangles[v+b]-triangles[u+b])*(p[a]-triangles[u+a]);
            }
            if(s[0]>=-1e-8 && s[1]>=-1e-8 && s[2]>=-1e-8 || s[0]<=1e-8 && s[1]<=1e-8 && s[2]<=1e-8) return true;
        }
        return false;
    }

    private ModelTextureMask.Alpha png(String name) throws Exception {
        try(var stream=getClass().getResourceAsStream("/assets/minecraft/textures/entity/"+name)) {
            assertNotNull(stream,name); var image=javax.imageio.ImageIO.read(stream);
            return ModelTextureMask.alpha(image.getWidth(),image.getHeight(),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()));
        }
    }

    @Test void hiddenLegEdgesAreCutAtTheActualBodyBoundary() {
        var body=new ModelBox("body",0,0,0,2,0,0,0,2,0,0,0,2);
        var leg=new ModelBox("leg",.5,-1,.5,.5,0,0,0,2,0,0,0,.5);
        var lines=ModelContours.edges(java.util.List.of(body,leg));
        assertEquals(2,lines.size());
        for(var p:lines.get(1)) assertTrue(p.y<=.00001,"Ребро ноги погружено в тело");
        assertTrue(leg.clip(.75,-2,.75,.75,2,.75)>=0,"Физическая геометрия ноги сохранена");
    }

    @Test void actualDragonIsCapturedAsAnAnimatedModel() {
        var model=new net.minecraft.client.model.monster.dragon.EnderDragonModel(
                net.minecraft.client.model.monster.dragon.EnderDragonModel.createBodyLayer().bakeRoot());
        var state=new net.minecraft.client.renderer.entity.state.EnderDragonRenderState();
        model.setupAnim(state);
        var result=ModelPoseCapture.collect(model.root(),new com.mojang.blaze3d.vertex.PoseStack(),0,0,0,ModelPoseCaptureTest.PARTS);
        assertTrue(result.complete()); assertFalse(result.boxes().isEmpty());
        assertTrue(result.boxes().stream().anyMatch(b -> b.part().contains("wing")));
        assertTrue(result.boxes().stream().anyMatch(b -> b.part().contains("tail11")));
    }
}
