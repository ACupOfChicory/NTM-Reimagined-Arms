package acup.gunfeel;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Ручные области выше любых процедурных предположений. */
final class HeadProfiles {
    record Ref(String name,double[] size,long shape,Vec3 center) {}
    record Zone(int part,int group,List<HeadZone.Plane> planes,long face) {}
    record Profile(boolean none,List<Ref> parts,List<Zone> zones) {}
    record Binding(List<Zone> zones,int missing,String reason) {}
    private static final Map<String,Profile> PROFILES=load();
    private static final Map<Long,Long> SHAPES=new HashMap<>();
    private HeadProfiles() {}
    static Profile get(String type) {
        return get(PROFILES,type);
    }
    static Profile get(Map<String,Profile> bank,String type) {
        var exact=bank.get(type);if(exact!=null) return exact;
        return type.equals("minecraft:player")?bank.get("minecraft:mannequin"):null;
    }
    static Map<String,Profile> all() { return PROFILES; }
    static int count() { return PROFILES.size(); }
    private static Map<String,Profile> load() {
        return loadBank("/ntmgunfeel-heads.json",Path.of("config/ntmgunfeel-heads.json"),true);
    }
    static Map<String,Profile> loadBank(String resource,Path override,boolean required) {
        var out=new HashMap<String,Profile>();
        try(var stream=HeadProfiles.class.getResourceAsStream(resource)) {
            if(stream==null && required) throw new IOException("Нет ресурса разметки "+resource);
            if(stream!=null) read(new InputStreamReader(stream,StandardCharsets.UTF_8),out);
        } catch(Exception e) { throw new IllegalStateException("Не удалось загрузить ручную разметку "+resource,e); }
        if(Files.isRegularFile(override)) try(var reader=Files.newBufferedReader(override)) { read(reader,out); }
        catch(Exception e) { GunFeel.LOGGER.error("Не удалось прочитать локальную разметку {}; встроенная сохранена",override,e); }
        return Map.copyOf(out);
    }
    private static void read(Reader reader,Map<String,Profile> destination) {
        var root=JsonParser.parseReader(reader).getAsJsonObject();
        if(root.get("schema").getAsInt()!=1) throw new IllegalArgumentException("Неизвестная версия разметки голов");
        var parsed=new HashMap<String,Profile>();
        for(var entry:root.getAsJsonObject("profiles").entrySet()) {
            var value=entry.getValue().getAsJsonObject();
            boolean none=value.get("none").getAsBoolean();
            if(none && !value.has("parts")) { parsed.put(entry.getKey(),new Profile(true,List.of(),List.of()));continue; }
            var parts=new ArrayList<Ref>();var zones=new ArrayList<Zone>();
            for(var p:value.getAsJsonArray("parts")) {
                var part=p.getAsJsonObject();var s=numbers(part.getAsJsonArray("size"),3);var c=numbers(part.getAsJsonArray("center"),3);
                if(Arrays.stream(s).anyMatch(v->v<=0)) throw new IllegalArgumentException("Неверный размер части");
                parts.add(new Ref(part.get("name").getAsString(),s,Long.parseUnsignedLong(part.get("shape").getAsString(),16),new Vec3(c[0],c[1],c[2])));
            }
            for(var z:value.getAsJsonArray("zones")) {
                var zone=z.getAsJsonObject();int part=zone.get("part").getAsInt(),group=zone.get("group").getAsInt();
                if(part<0 || part>=parts.size() || group<1) throw new IllegalArgumentException("Неверная привязка головы");
                var planes=new ArrayList<HeadZone.Plane>();
                for(var p:zone.getAsJsonArray("planes")) { var n=numbers(p.getAsJsonArray(),4);planes.add(new HeadZone.Plane(n[0],n[1],n[2],n[3])); }
                long face=0;
                if(zone.has("face")) {
                    var data=numbers(zone.getAsJsonArray("face"),9);float[] f=new float[9];for(int i=0;i<9;i++) f[i]=(float)data[i];
                    face=ModelMesh.register(new ModelMesh(f)).id();
                }
                zones.add(new Zone(part,group,List.copyOf(planes),face));
            }
            if(!none && zones.isEmpty()) throw new IllegalArgumentException("Пустая разметка головы");
            parsed.put(entry.getKey(),new Profile(none,List.copyOf(parts),List.copyOf(zones)));
        }
        destination.putAll(parsed);
    }
    private static double[] numbers(JsonArray values,int count) {
        if(values.size()!=count) throw new IllegalArgumentException("Размер массива разметки");
        double[] out=new double[count];for(int i=0;i<count;i++) { out[i]=values.get(i).getAsDouble();if(!Double.isFinite(out[i])) throw new IllegalArgumentException("Нечисловая разметка"); }
        return out;
    }
    static double[] size(ModelBox box) {
        return new double[]{Math.sqrt(box.ux()*box.ux()+box.uy()*box.uy()+box.uz()*box.uz()),Math.sqrt(box.vx()*box.vx()+box.vy()*box.vy()+box.vz()*box.vz()),Math.sqrt(box.wx()*box.wx()+box.wy()*box.wy()+box.wz()*box.wz())};
    }
    static synchronized long shape(ModelBox box) {
        if(box.mesh()==0) return 0;
        Long saved=SHAPES.get(box.mesh());if(saved!=null) return saved;
        var mesh=ModelMesh.get(box.mesh());if(mesh==null) return Long.MIN_VALUE;
        var data=mesh.triangles();boolean cube=data.length==108;double area=0;
        for(float f:data) if(Math.abs(f-Math.round(f))>1e-5) cube=false;
        if(cube) for(int at=0;at<data.length;at+=9) {
            var u=new Vec3(data[at+3]-data[at],data[at+4]-data[at+1],data[at+5]-data[at+2]);
            var v=new Vec3(data[at+6]-data[at],data[at+7]-data[at+1],data[at+8]-data[at+2]);area+=u.cross(v).length()/2;
        }
        long hash=0xcbf29ce484222325L;
        if(cube && Math.abs(area-6)<1e-5) hash=0;
        else for(float f:data) { hash^=Math.round(f*10000);hash*=1099511628211L; }
        if(SHAPES.size()>2048) SHAPES.clear();SHAPES.put(box.mesh(),hash);return hash;
    }
    private static String name(String name) {
        if(name.startsWith("mesh/")) name=name.substring(5);
        while(name.startsWith("root/root/")) name=name.substring(5);
        return name;
    }
    private static boolean anonymous(String name) { return name.equals("submitted") || name.equals("mesh/submitted"); }
    private static double error(Ref ref,ModelBox box,double scale) {
        var dimensions=size(box);double error=0;
        for(int i=0;i<3;i++) error=Math.max(error,Math.abs(Math.log(dimensions[i]/(ref.size()[i]*scale))));
        return error;
    }
    static Binding bind(Profile profile,List<ModelBox> boxes,float yaw) {
        if(profile.none()) return new Binding(List.of(),0,"датасет: без головы");
        var ratios=new ArrayList<Double>();
        for(var ref:profile.parts()) {
            var matches=boxes.stream().filter(b->name(b.part()).equals(name(ref.name()))).toList();
            if(matches.size()==1 && !anonymous(ref.name())) {
                var s=size(matches.getFirst());for(int i=0;i<3;i++) ratios.add(s[i]/ref.size()[i]);
            }
        }
        if(ratios.isEmpty() && boxes.size()==profile.parts().size()) for(int i=0;i<boxes.size();i++) {
            var a=size(boxes.get(i));var b=profile.parts().get(i).size();for(int k=0;k<3;k++) ratios.add(a[k]/b[k]);
        }
        ratios.sort(Double::compare);double scale=ratios.isEmpty()?1:ratios.get(ratios.size()/2);
        int[] mapping=new int[profile.parts().size()];Arrays.fill(mapping,-1);
        boolean ordered=boxes.size()==mapping.length;
        if(ordered) for(int i=0;i<mapping.length;i++) {
            var r=profile.parts().get(i);var b=boxes.get(i);
            if(!name(r.name()).equals(name(b.part())) || error(r,b,scale)>.22 || anonymous(r.name()) && r.shape()!=shape(b)) { ordered=false;break; }
        }
        if(ordered) for(int i=0;i<mapping.length;i++) mapping[i]=i;
        else for(int i=0;i<mapping.length;i++) {
            var ref=profile.parts().get(i);int best=-1;double score=Double.POSITIVE_INFINITY,second=score;
            for(int j=0;j<boxes.size();j++) {
                var box=boxes.get(j);if(!name(ref.name()).equals(name(box.part()))) continue;
                double mismatch=error(ref,box,scale);if(mismatch>.35 || anonymous(ref.name()) && ref.shape()!=shape(box)) continue;
                var c=ModelContours.point(box,.5,.5,.5);double angle=Math.toRadians(yaw),cos=Math.cos(angle),sin=Math.sin(angle);
                var relative=new Vec3(cos*c.x+sin*c.z,c.y,-sin*c.x+cos*c.z).scale(1/scale);
                double distance=relative.distanceTo(ref.center())+mismatch*2+(ref.shape()==shape(box)?0:.2);
                if(distance<score) { second=score;score=distance;best=j; } else second=Math.min(second,distance);
            }
            // Равные варианты с разными ролями нельзя разрешать случайным порядком.
            if(best>=0 && (second-score>.03 || second==Double.POSITIVE_INFINITY)) mapping[i]=best;
        }
        var zones=new ArrayList<Zone>();int missing=0;
        for(var zone:profile.zones()) {
            int match=mapping[zone.part()];if(match<0) { missing++;continue; }
            zones.add(new Zone(match,zone.group(),zone.planes(),zone.face()));
        }
        return new Binding(List.copyOf(zones),missing,missing==0?"ручная разметка":"ручная разметка: часть модели не сопоставлена");
    }
    static List<HeadZone> zones(Binding binding,List<ModelBox> boxes) {
        var out=new ArrayList<HeadZone>();
        for(var zone:binding.zones()) {
            var box=boxes.get(zone.part());if(zone.face()!=0) box=box.withMesh(zone.face());
            out.add(HeadZone.local(box,zone.planes()));
        }
        return List.copyOf(out);
    }
}
