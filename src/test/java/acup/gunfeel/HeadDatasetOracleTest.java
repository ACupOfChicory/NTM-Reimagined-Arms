package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;

/** Повторяет сохранённую геометрию тем же ModelBox.clip, что использует игра. */
class HeadDatasetOracleTest {
    record Sample(Vec3 point,Vec3 normal,double area) {}
    record Result(List<Integer> indices,List<Boolean> truth,List<Boolean> visible) {}
    record Oracle(String indexSha256,Map<String,Result> samples) {}
    @Test void replay() throws Exception {
        String directory=System.getProperty("ntmgunfeel.headDataset");
        Assumptions.assumeTrue(directory!=null,"не задан локальный датасет");
        Path root=Path.of(directory);
        var index=JsonParser.parseString(Files.readString(root.resolve("index.json"))).getAsJsonObject();
        var output=new LinkedHashMap<String,Result>();
        for(var entry:index.entrySet()) {
            String id=entry.getKey();var annotation=entry.getValue().getAsJsonObject();
            var model=JsonParser.parseString(Files.readString(root.resolve("samples").resolve(annotation.get("sample").getAsString()).resolve("model.json"))).getAsJsonObject();
            var body=new ArrayList<ModelBox>();var geometries=new ArrayList<float[]>();
            for(var value:model.getAsJsonArray("geometry")) {
                var part=value.getAsJsonObject();var frame=part.getAsJsonArray("frame");var data=part.getAsJsonArray("triangles");
                float[] triangles=new float[data.size()];for(int i=0;i<triangles.length;i++) triangles[i]=data.get(i).getAsFloat();
                double[] n=new double[12];for(int i=0;i<12;i++) n[i]=frame.get(i).getAsDouble();
                var mesh=ModelMesh.register(new ModelMesh(triangles));
                body.add(new ModelBox(part.get("part").getAsString(),n[0],n[1],n[2],n[3],n[4],n[5],n[6],n[7],n[8],n[9],n[10],n[11],mesh.id()));
                geometries.add(triangles);
            }
            var zones=new ArrayList<HeadZone>();
            for(var label:annotation.getAsJsonObject("labels").entrySet()) {
                String token=label.getKey();var fields=token.split(":");int part=Integer.parseInt(fields[0]),triangle=Integer.parseInt(fields[1]);
                var box=body.get(part);
                if(triangle>=0) box=box.withMesh(ModelMesh.register(new ModelMesh(Arrays.copyOfRange(geometries.get(part),triangle*9,(triangle+1)*9))).id());
                var planes=new ArrayList<HeadZone.Plane>();
                if(annotation.has("cuts") && annotation.getAsJsonObject("cuts").has(token)) for(var plane:annotation.getAsJsonObject("cuts").getAsJsonArray(token)) {
                    var p=plane.getAsJsonObject();planes.add(new HeadZone.Plane(p.get("nx").getAsDouble(),p.get("ny").getAsDouble(),p.get("nz").getAsDouble(),p.get("d").getAsDouble()));
                }
                zones.add(new HeadZone(box,planes));
            }
            var binding=HeadProfiles.bind(HeadProfiles.get(id),body,model.get("yaw").getAsFloat());
            assertEquals(0,binding.missing(),id+": ручная разметка должна привязаться к своей модели");
            assertEquals(zones.size(),binding.zones().size(),id+": потеряна голова или срез");
            var samples=new ArrayList<Sample>();
            double[][] bary={{1.0/3,1.0/3,1.0/3},{.6,.2,.2},{.2,.6,.2},{.2,.2,.6}};
            double lowX=Double.POSITIVE_INFINITY,highX=-lowX,lowY=lowX,highY=highX,lowZ=lowX,highZ=highX,total=0;
            for(int i=0;i<body.size();i++) {
                var box=body.get(i);var data=geometries.get(i);
                for(int at=0;at<data.length;at+=9) {
                    var a=point(box,data,at);var b=point(box,data,at+3);var c=point(box,data,at+6);
                    var cross=b.subtract(a).cross(c.subtract(a));double area=cross.length()/8;var normal=cross.normalize();
                    for(var weights:bary) {
                        var p=a.scale(weights[0]).add(b.scale(weights[1])).add(c.scale(weights[2]));
                        samples.add(new Sample(p,normal,area));total+=area;
                        lowX=Math.min(lowX,p.x);highX=Math.max(highX,p.x);lowY=Math.min(lowY,p.y);highY=Math.max(highY,p.y);lowZ=Math.min(lowZ,p.z);highZ=Math.max(highZ,p.z);
                    }
                }
            }
            assertTrue(total>0,id);
            double reach=Math.max(highX-lowX,Math.max(highY-lowY,highZ-lowZ))*4+1;
            var truth=new ArrayList<Boolean>();
            // Другая анимационная поза: рамки повёрнуты, перенесены и
            // уменьшены. Срезы обязаны следовать части, а не координатам стенда.
            var moved=new ArrayList<ModelBox>();
            for(var b:body) moved.add(new ModelBox(b.part(),-b.z()*.5+7,b.y()*.5-30,b.x()*.5-4,
                    -b.uz()*.5,b.uy()*.5,b.ux()*.5,-b.vz()*.5,b.vy()*.5,b.vx()*.5,-b.wz()*.5,b.wy()*.5,b.wx()*.5,b.mesh()));
            var bound=HeadProfiles.zones(binding,moved);
            for(var sample:samples) {
                boolean expected=HeadZone.contains(zones,sample.point());truth.add(expected);
                var p=sample.point();var next=new Vec3(-p.z*.5+7,p.y*.5-30,p.x*.5-4);
                assertEquals(expected,HeadZone.contains(bound,next),id+": срез не следует движению/масштабу");
            }
            var indices=new ArrayList<Integer>();
            if(samples.size()<=1024) for(int i=0;i<samples.size();i++) indices.add(i);
            else {
                double cumulative=0;int at=0;
                for(int i=0;i<1024;i++) {
                    double target=(i+.5)*total/1024;
                    while(at<samples.size()-1 && cumulative+samples.get(at).area()<target) cumulative+=samples.get(at++).area();
                    indices.add(at);
                }
            }
            var visible=new ArrayList<Boolean>();
            for(int at:indices) { var s=samples.get(at);visible.add(s.area()>1e-20 && HeadZone.visible(body,s.point(),s.normal(),reach)); }
            output.put(id,new Result(indices,truth,visible));
        }
        assertEquals(index.size(),output.size());
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("index.json"))));
        Files.writeString(root.resolve("research/oracle.json"),new Gson().toJson(new Oracle(hash,output)));
    }
    private Vec3 point(ModelBox box,float[] data,int at) { return ModelContours.point(box,data[at],data[at+1],data[at+2]); }
}
