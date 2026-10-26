package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ModelContourTest {
    @Test void differentPartsOnOnePlaneHaveOnlyTheirCombinedContour() {
        var a=MeshModelPose.surface("a",0,0,0,2,1,0,new org.joml.Matrix4f(),0,0,0);
        var b=MeshModelPose.surface("b",1,0,0,2,1,0,new org.joml.Matrix4f(),0,0,0);
        var edges=ModelContours.edges(java.util.List.of(a,b));
        double length=0;
        for(var line:edges) for(int i=0;i<line.length;i+=2) {
            var p=line[i]; var q=line[i+1];
            assertTrue(p.y==0 && q.y==0 || p.y==1 && q.y==1 || p.x==0 && q.x==0 || p.x==3 && q.x==3,
                    "Граница соседней детали осталась внутри общей плоскости: "+p+" -> "+q);
            length+=p.distanceTo(q);
        }
        assertEquals(8,length,1e-5);
    }

    @Test void coplanarUnionKeepsAnActualHole() {
        var m=new org.joml.Matrix4f();
        var boxes=java.util.List.of(MeshModelPose.surface("left",0,0,0,1,3,0,m,0,0,0),
                MeshModelPose.surface("right",2,0,0,1,3,0,m,0,0,0),
                MeshModelPose.surface("bottom",1,0,0,1,1,0,m,0,0,0),
                MeshModelPose.surface("top",1,2,0,1,1,0,m,0,0,0));
        double length=0,hole=0;
        for(var edges:ModelContours.edges(boxes)) for(int i=0;i<edges.length;i+=2) {
            double n=edges[i].distanceTo(edges[i+1]); length+=n;
            var mid=edges[i].lerp(edges[i+1],.5);
            if(mid.x>=1 && mid.x<=2 && mid.y>=1 && mid.y<=2) hole+=n;
        }
        assertEquals(16,length,1e-5); assertEquals(4,hole,1e-5);
    }

    @Test void closeParallelPlanesRemainSeparate() {
        var m=new org.joml.Matrix4f();
        var boxes=java.util.List.of(MeshModelPose.surface("front",0,0,0,1,1,0,m,0,0,0),
                MeshModelPose.surface("back",0,0,.001F,1,1,0,m,0,0,0));
        double length=0;
        for(var edges:ModelContours.edges(boxes)) for(int i=0;i<edges.length;i+=2) length+=edges[i].distanceTo(edges[i+1]);
        assertEquals(8,length,1e-5,"Настоящий зазор между параллельными поверхностями не склеивается");
    }

    @Test void solidStepKeepsItsConcaveCrease() {
        var low=new ModelBox("low",0,0,0,1,0,0,0,1,0,0,0,1);
        var high=new ModelBox("high",1,0,0,1,0,0,0,2,0,0,0,1);
        double crease=0;
        for(var edges:ModelContours.edges(java.util.List.of(low,high))) for(int i=0;i<edges.length;i+=2) {
            var a=edges[i]; var b=edges[i+1];
            if(Math.abs(a.x-1)<1e-6 && Math.abs(b.x-1)<1e-6 && Math.abs(a.y-1)<1e-6 && Math.abs(b.y-1)<1e-6)
                crease+=a.distanceTo(b);
        }
        assertEquals(1,crease,1e-5,"Настоящий угол ступеньки должен остаться один раз");
    }

    @Test void diagonallyTouchingVolumesKeepTheirSharedCrease() {
        var a=new ModelBox("a",0,0,0,1,0,0,0,1,0,0,0,1);
        var b=new ModelBox("b",1,1,0,1,0,0,0,1,0,0,0,1);
        double crease=0;
        for(var edges:ModelContours.edges(java.util.List.of(a,b))) for(int i=0;i<edges.length;i+=2) {
            var p=edges[i]; var q=edges[i+1];
            if(Math.abs(p.x-1)<1e-6 && Math.abs(q.x-1)<1e-6 && Math.abs(p.y-1)<1e-6 && Math.abs(q.y-1)<1e-6)
                crease+=p.distanceTo(q);
        }
        assertEquals(1,crease,1e-5,"Смена стороны объёма не является плоским швом");
    }

    @Test void assembledFrogFacesHaveNoSeamsBetweenHeadBodyAndPaperLayers() throws Exception {
        var model=new net.minecraft.client.model.animal.frog.FrogModel(net.minecraft.client.model.animal.frog.FrogModel.createBodyLayer().bakeRoot());
        model.setupAnim(new net.minecraft.client.renderer.entity.state.FrogRenderState());
        var boxes=capture(model.root(),"frog/frog_temperate.png");
        int front=0,back=0;
        for(var edges:ModelContours.edges(boxes)) for(int i=0;i<edges.length;i+=2) {
            var a=edges[i]; var b=edges[i+1];
            boolean onFront=Math.abs(a.z+.25)<1e-5 && Math.abs(b.z+.25)<1e-5;
            boolean onBack=Math.abs(a.z-5./16)<1e-5 && Math.abs(b.z-5./16)<1e-5;
            if(!onFront && !onBack) continue;
            if(onFront) front++; if(onBack) back++;
            var mid=a.lerp(b,.5);
            assertFalse(Math.abs(a.y-b.y)<1e-5 && mid.y>18./16+1e-4 && mid.y<23./16-1e-4,
                    "Шов головы/тела лягушки: "+a+" -> "+b);
        }
        assertTrue(front>0 && back>0,"Настоящий внешний контур передней и задней граней должен остаться");
    }

    @Test void assembledFoxDoesNotOutlineLegTopsInsideItsFlank() throws Exception {
        var model=new net.minecraft.client.model.animal.fox.AdultFoxModel(net.minecraft.client.model.animal.fox.AdultFoxModel.createBodyLayer().bakeRoot());
        model.setupAnim(new net.minecraft.client.renderer.entity.state.FoxRenderState());
        var boxes=capture(model.root(),"fox/fox.png");
        for(var edges:ModelContours.edges(boxes)) for(int i=0;i<edges.length;i+=2) {
            var a=edges[i]; var b=edges[i+1]; var mid=a.lerp(b,.5);
            boolean insideFlank=Math.abs(a.x+3.001/16)<1e-5 && Math.abs(b.x+3.001/16)<1e-5
                    && Math.abs(a.y-17.999/16)<1e-5 && Math.abs(b.y-17.999/16)<1e-5 && Math.abs(mid.z)<1.01/16;
            assertFalse(insideFlank,"Служебный сдвиг ноги оставил шов на боку лисы: "+a+" -> "+b);
        }
    }

    private java.util.List<ModelBox> capture(net.minecraft.client.model.geom.ModelPart root,String texture) throws Exception {
        var mask=png(texture);
        var cache=new java.util.IdentityHashMap<net.minecraft.client.model.geom.ModelPart.Cube,ModelCubeGeometry>();
        var access=ModelPoseCaptureTest.PARTS;
        var parts=new ModelPoseCapture.Parts() {
            public java.util.List<net.minecraft.client.model.geom.ModelPart.Cube> cubes(net.minecraft.client.model.geom.ModelPart part) { return access.cubes(part); }
            public java.util.Map<String,net.minecraft.client.model.geom.ModelPart> children(net.minecraft.client.model.geom.ModelPart part) { return access.children(part); }
            public ModelCubeGeometry geometry(net.minecraft.client.model.geom.ModelPart.Cube cube) { return cache.computeIfAbsent(cube,c -> ModelCubeGeometry.bake(c,mask)); }
        };
        return ModelPoseCapture.collect(root,new com.mojang.blaze3d.vertex.PoseStack(),0,0,0,parts).boxes();
    }

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
