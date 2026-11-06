package acup.gunfeel;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Ручные области выше любых процедурных предположений. */
final class HeadProfiles {
    // instead: the part this one is drawn in place of (another form of it), -1 = none
    record Ref(String name,double[] size,long shape,Vec3 center,int instead) {
        Ref(String name,double[] size,long shape,Vec3 center) { this(name,size,shape,center,-1); }
    }
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
                parts.add(new Ref(part.get("name").getAsString(),s,Long.parseUnsignedLong(part.get("shape").getAsString(),16),new Vec3(c[0],c[1],c[2]),
                        part.has("instead")?part.get("instead").getAsInt():-1));
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
            for(var ref:parts) if(ref.instead()>=parts.size() || ref.instead()==parts.indexOf(ref)) throw new IllegalArgumentException("Неверная замена части");
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
        // Flywheel (CrankShaft) puts a legacy NTM model's parts under "root/tree/"; without Flywheel they come as root/<part>
        if(name.startsWith("root/tree/")) name="root/"+name.substring(10);
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
        var names=new HashMap<String,Integer>();for(var ref:profile.parts()) names.merge(name(ref.name()),1,Integer::sum);
        // Without Flywheel NTM's OBJ mobs (glyphids, Mask Man) come under their model's group names (mesh/Body), none of
        // them a name of the profile, which holds the Flywheel visual's (root/parts/0/base): its parts are matched by order
        // and place below.
        boolean foreign=!boxes.isEmpty() && boxes.stream().noneMatch(b->names.containsKey(name(b.part())));
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
        else {
            var own=new HashSet<String>();for(var ref:profile.parts()) own.add(name(ref.name()));
            var other=new ArrayList<Integer>();
            for(int i=0;i<mapping.length;i++) {
                var ref=profile.parts().get(i);var exact=name(ref.name());
                if(boxes.stream().anyMatch(b->name(b.part()).equals(exact))) mapping[i]=best(ref,boxes,scale,yaw,b->name(b.part()).equals(exact)).box();
                else if(!layerFree(exact).equals(exact)) other.add(i);
            }
            // Слой, который в позе стоит под другим номером draw: у CrankShaft draws — все слои вида по порядку
            // (у жителя: тип со шляпой, тип без шляпы, профессия, уровень), показан из них не каждый. Берётся та же
            // часть из другого draw, если такого имени нет в профиле; коробку, нужную двум частям, получает та, что
            // ближе по месту и размеру.
            var picks=new ArrayList<int[]>();var scores=new HashMap<Integer,Double>();
            for(int i:other) {
                var ref=profile.parts().get(i);var free=layerFree(name(ref.name()));
                // Ближайший номер: варианты одного слоя (со шляпой и без) стоят в списке рядом, профессия — дальше.
                int at=draw(ref.name());
                for(int distance=1;distance<=MAX_DRAWS;distance++) {
                    int d=distance;
                    var pick=best(ref,boxes,scale,yaw,b->!own.contains(name(b.part())) && layerFree(name(b.part())).equals(free) && Math.abs(draw(b.part())-at)==d);
                    if(pick.box()>=0) { picks.add(new int[]{i,pick.box()});scores.put(i,pick.score()); }
                    if(pick.score()<Double.POSITIVE_INFINITY) break;
                }
            }
            picks.sort(Comparator.comparingDouble(p->scores.get(p[0])));
            var taken=new HashSet<Integer>();
            for(var p:picks) if(taken.add(p[1])) mapping[p[0]]=p[1];
        }
        // Without Flywheel the game draws a layer over the mob with the model's own part names (root/<part>), its cubes
        // in the same box as the model's, or not at all when the layer's texture adds nothing: a layer's part
        // (".../draws/N/tree/<part>") that has no box of its own takes that box. The model's part and its layer's are
        // then one box (a skeleton's bone in its clothes): when it fits the layer's part, the model's takes it too (the
        // model is drawn under the layer; a layer is not always drawn over the model: a llama without its carpet); when
        // it fits none (the clothes cover half the bone), it is matched as the box around them all.
        for(int i=0;i<mapping.length;i++) {
            if(mapping[i]>=0 || !layer(profile.parts().get(i))) continue;
            var plain=vanilla(profile.parts().get(i));
            var pick=best(profile.parts().get(i),boxes,scale,yaw,b->!b.part().startsWith("mesh/") && name(b.part()).equals(plain));
            if(pick.box()>=0) mapping[i]=pick.box();
        }
        var shared=new LinkedHashMap<String,List<Integer>>();
        for(int i=0;i<mapping.length;i++) shared.computeIfAbsent(vanilla(profile.parts().get(i)),k->new ArrayList<>()).add(i);
        for(var entry:shared.entrySet()) {
            var group=entry.getValue();var plain=entry.getKey();
            if(group.stream().map(i->name(profile.parts().get(i).name())).distinct().count()<2 || group.stream().allMatch(i->mapping[i]>=0)) continue;
            int box=group.stream().filter(i->mapping[i]>=0 && !boxes.get(mapping[i]).part().startsWith("mesh/") && name(boxes.get(mapping[i]).part()).equals(plain)).map(i->mapping[i]).findFirst().orElse(-1);
            if(box>=0) { for(int i:group) if(mapping[i]<0 && !layer(profile.parts().get(i))) mapping[i]=box;continue; }
            box=best(around(plain,group.stream().map(i->profile.parts().get(i)).toList()),boxes,scale,yaw,b->!b.part().startsWith("mesh/") && name(b.part()).equals(plain)).box();
            if(box>=0) for(int i:group) if(mapping[i]<0) mapping[i]=box;
        }
        // Without Flywheel a part's box is around its whole cubes; the profile holds the box of the mesh Flywheel draws:
        // cut to the opaque pixels of its texture (the wandering trader's hat, a piglin brute's pants), or around flat
        // cubes that a large mob loses whole without Flywheel (PlaneHitPolicy; under Flywheel only their triangles go:
        // the elder guardian's fin, the creaking's twigs). The one box of the part's name, inside it or holding it on
        // every axis, is that part. A name the profile holds more than once (a part made of cubes, a camel husk's
        // head) is left to the steps above: its one box would stand for two parts.
        for(int i=0;i<mapping.length;i++) {
            var ref=profile.parts().get(i);if(mapping[i]>=0 || names.get(name(ref.name()))!=1) continue;
            var own=new ArrayList<Integer>();
            for(int j=0;j<boxes.size();j++) if(!boxes.get(j).part().startsWith("mesh/") && name(boxes.get(j).part()).equals(name(ref.name()))) own.add(j);
            if(own.size()==1 && nested(ref,boxes.get(own.getFirst()),scale)) mapping[i]=own.getFirst();
        }
        // NTM draws an OBJ mob's groups in the order of its Flywheel visual's parts (GlyphidVisual's list: a glyphid's
        // three pairs of legs are told apart by it, not by place, as they swing while it walks), less what is not drawn
        // (armor that fell off); a part it draws out of that order (a nuclear glyphid's mini nuke) is matched by place.
        if(foreign) {
            // a mob of another size (its scale attribute) that does not draw every part gives no scale by order above:
            // it is guessed from the volume drawn, then taken from the parts that guess pairs, when they are at least half
            // the profile (a box or two would fit any scale)
            double fit=scale;
            if(boxes.size()!=mapping.length) {
                double drawn=0,held=0;
                for(var box:boxes) { var s=size(box);drawn+=s[0]*s[1]*s[2]; }
                for(var ref:profile.parts()) held+=ref.size()[0]*ref.size()[1]*ref.size()[2];
                var guess=align(profile,boxes,drawn>0 && held>0?Math.cbrt(drawn/held):scale,yaw);var found=new ArrayList<Double>();
                int paired=0;for(int i=0;i<guess.length;i++) if(guess[i]>=0) {
                    paired++;
                    var s=size(boxes.get(guess[i]));for(int k=0;k<3;k++) found.add(s[k]/profile.parts().get(i).size()[k]);
                }
                found.sort(Double::compare);if(paired>0 && paired*2>=mapping.length) fit=found.get(found.size()/2);
            }
            var aligned=align(profile,boxes,fit,yaw);
            Set<ModelBox> used=Collections.newSetFromMap(new IdentityHashMap<>());
            for(int i=0;i<mapping.length;i++) if(aligned[i]>=0) { mapping[i]=aligned[i];used.add(boxes.get(aligned[i])); }
            var picks=new ArrayList<int[]>();var scores=new HashMap<Integer,Double>();
            for(int i=0;i<mapping.length;i++) {
                var ref=profile.parts().get(i);if(mapping[i]>=0 || anonymous(ref.name())) continue;
                var pick=best(ref,boxes,fit,yaw,b->!used.contains(b));
                if(pick.box()>=0) { picks.add(new int[]{i,pick.box()});scores.put(i,pick.score()); }
            }
            picks.sort(Comparator.comparingDouble(p->scores.get(p[0])));
            var taken=new HashSet<Integer>();
            for(var p:picks) if(taken.add(p[1])) mapping[p[0]]=p[1];
        }
        var zones=new ArrayList<Zone>();int missing=0;
        for(var zone:profile.zones()) {
            int match=mapping[zone.part()];if(match<0) { if(!otherVariant(profile,mapping,zone.part())) missing++;continue; }
            zones.add(new Zone(match,zone.group(),zone.planes(),zone.face()));
        }
        return new Binding(List.copyOf(zones),missing,missing==0?"ручная разметка":"ручная разметка: часть модели не сопоставлена");
    }
    // Without Flywheel (its backend off) a part comes cube by cube: several boxes with one name, each smaller than the
    // part the profile holds. Where the profile has that name once, its cubes in one frame are matched as one box
    // around them all, as Flywheel draws the part; a profile made from such cubes (a name in it more than once) keeps
    // them apart. Flywheel's boxes (names from "mesh/") are left as they are.
    static List<ModelBox> unite(Profile profile,List<ModelBox> boxes) {
        var once=new HashMap<String,Integer>();for(var ref:profile.parts()) once.merge(name(ref.name()),1,Integer::sum);
        // A legacy NTM model numbers the cubes of a part that its Flywheel visual draws as one mesh (the cyber crab's
        // shell0..shell19, root/shell in the profile): a cube whose name the profile lacks, numbered after a name the
        // profile holds once and no box carries, is a cube of that part.
        var carried=new HashSet<String>();for(var box:boxes) carried.add(name(box.part()));
        var renamed=new ArrayList<ModelBox>();
        for(var box:boxes) {
            var plain=name(box.part());var base=plain.replaceFirst("\\d+$","");
            if(!box.part().startsWith("mesh/") && !base.equals(plain) && !once.containsKey(plain) && once.getOrDefault(base,0)==1 && !carried.contains(base))
                box=new ModelBox(base,box.x(),box.y(),box.z(),box.ux(),box.uy(),box.uz(),box.vx(),box.vy(),box.vz(),box.wx(),box.wy(),box.wz(),box.mesh());
            renamed.add(box);
        }
        boxes=renamed;
        var groups=new LinkedHashMap<String,List<ModelBox>>();
        for(var box:boxes) if(!box.part().startsWith("mesh/")) groups.computeIfAbsent(box.part(),k->new ArrayList<>()).add(box);
        if(groups.values().stream().allMatch(group->group.size()==1)) return boxes;
        var out=new ArrayList<ModelBox>();var done=new HashSet<String>();
        for(var box:boxes) {
            var key=box.part();var group=groups.get(key);
            if(group==null || group.size()==1 || once.getOrDefault(name(key),0)!=1 || anonymous(key)) { out.add(box);continue; }
            if(!done.add(key)) continue;
            var united=around(group);
            if(united==null) out.addAll(group); else out.add(united);
        }
        return List.copyOf(out);
    }
    // every side of the box no shorter than the part's, or every side no longer (a little over for rounding)
    private static boolean nested(Ref ref,ModelBox box,double scale) {
        var size=size(box);boolean holds=true,inside=true;
        for(int a=0;a<3;a++) {
            double ratio=size[a]/(ref.size()[a]*scale);
            holds&=ratio>=1/1.05;inside&=ratio<=1.05;
        }
        return holds || inside;
    }
    // the box around boxes whose edges run the same ways (the cubes of one part), null if they do not
    private static ModelBox around(List<ModelBox> group) {
        var first=group.getFirst();
        Vec3[] axis={new Vec3(first.ux(),first.uy(),first.uz()).normalize(),new Vec3(first.vx(),first.vy(),first.vz()).normalize(),new Vec3(first.wx(),first.wy(),first.wz()).normalize()};
        var inverse=new org.joml.Matrix3d(axis[0].x,axis[0].y,axis[0].z,axis[1].x,axis[1].y,axis[1].z,axis[2].x,axis[2].y,axis[2].z);
        if(Math.abs(inverse.determinant())<1e-9) return null;
        inverse.invert();
        double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},max={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        var origin=new Vec3(first.x(),first.y(),first.z());
        for(var box:group) {
            Vec3[] edges={new Vec3(box.ux(),box.uy(),box.uz()),new Vec3(box.vx(),box.vy(),box.vz()),new Vec3(box.wx(),box.wy(),box.wz())};
            for(int a=0;a<3;a++) if(edges[a].normalize().dot(axis[a])<1-1e-4) return null;
            for(int corner=0;corner<8;corner++) {
                var point=new Vec3(box.x(),box.y(),box.z());
                for(int a=0;a<3;a++) if((corner>>a&1)!=0) point=point.add(edges[a]);
                var d=point.subtract(origin);var c=inverse.transform(new org.joml.Vector3d(d.x,d.y,d.z));
                double[] at={c.x,c.y,c.z};
                for(int a=0;a<3;a++) { min[a]=Math.min(min[a],at[a]);max[a]=Math.max(max[a],at[a]); }
            }
        }
        var start=origin.add(axis[0].scale(min[0])).add(axis[1].scale(min[1])).add(axis[2].scale(min[2]));
        var u=axis[0].scale(max[0]-min[0]);var v=axis[1].scale(max[1]-min[1]);var w=axis[2].scale(max[2]-min[2]);
        return new ModelBox(first.part(),start.x,start.y,start.z,u.x,u.y,u.z,v.x,v.y,v.z,w.x,w.y,w.z);
    }
    // the part around the profile's parts (the model's and its layers'), as the game draws them in one box
    private static Ref around(String name,List<Ref> parts) {
        double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},max={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var ref:parts) {
            double[] c={ref.center().x,ref.center().y,ref.center().z};
            for(int a=0;a<3;a++) { min[a]=Math.min(min[a],c[a]-ref.size()[a]/2);max[a]=Math.max(max[a],c[a]+ref.size()[a]/2); }
        }
        return new Ref(name,new double[]{max[0]-min[0],max[1]-min[1],max[2]-min[2]},0,new Vec3((min[0]+max[0])/2,(min[1]+max[1])/2,(min[2]+max[2])/2),-1);
    }
    // The profile's parts in its order paired with the boxes in theirs, either list skipping what the other lacks: the
    // cheapest pairing by place (to the part's size) and size, each part or box left over costing SKIP
    private static final double SKIP=.5;
    private static int[] align(Profile profile,List<ModelBox> boxes,double scale,float yaw) {
        int n=profile.parts().size(),m=boxes.size();
        double[][] cost=new double[n+1][m+1];byte[][] step=new byte[n+1][m+1];
        for(var row:cost) Arrays.fill(row,Double.POSITIVE_INFINITY);
        cost[0][0]=0;
        for(int i=0;i<=n;i++) for(int j=0;j<=m;j++) {
            double here=cost[i][j];
            if(i<n && here+SKIP<cost[i+1][j]) { cost[i+1][j]=here+SKIP;step[i+1][j]=1; }
            if(j<m && here+SKIP<cost[i][j+1]) { cost[i][j+1]=here+SKIP;step[i][j+1]=2; }
            if(i==n || j==m) continue;
            var ref=profile.parts().get(i);double error=error(ref,boxes.get(j),scale);
            if(error>.35 || anonymous(ref.name())) continue;
            double largest=Arrays.stream(ref.size()).max().orElse(1);
            double pair=here+local(boxes.get(j),scale,yaw).distanceTo(ref.center())/largest+2*error;
            if(pair<cost[i+1][j+1]) { cost[i+1][j+1]=pair;step[i+1][j+1]=3; }
        }
        int[] mapping=new int[n];Arrays.fill(mapping,-1);
        for(int i=n,j=m;i>0 || j>0;) {
            if(step[i][j]==3) mapping[--i]=--j; else if(step[i][j]==1) i--; else j--;
        }
        return mapping;
    }
    // a box's center in the frame of the profile: the mob turned to yaw 0, at the profile's size
    private static Vec3 local(ModelBox box,double scale,float yaw) {
        var c=ModelContours.point(box,.5,.5,.5);double angle=Math.toRadians(yaw),cos=Math.cos(angle),sin=Math.sin(angle);
        return new Vec3(cos*c.x+sin*c.z,c.y,-sin*c.x+cos*c.z).scale(1/scale);
    }
    private record Pick(int box,double score) {}
    private static Pick best(Ref ref,List<ModelBox> boxes,double scale,float yaw,java.util.function.Predicate<ModelBox> candidate) {
        int best=-1;double score=Double.POSITIVE_INFINITY,second=score;
        for(int j=0;j<boxes.size();j++) {
            var box=boxes.get(j);if(!candidate.test(box)) continue;
            double mismatch=error(ref,box,scale);if(mismatch>.35 || anonymous(ref.name()) && ref.shape()!=shape(box)) continue;
            double distance=local(box,scale,yaw).distanceTo(ref.center())+mismatch*2+(ref.shape()==shape(box)?0:.2);
            if(distance<score) { second=score;score=distance;best=j; } else second=Math.min(second,distance);
        }
        // Равные варианты с разными ролями нельзя разрешать случайным порядком.
        return best>=0 && (second-score>.03 || second==Double.POSITIVE_INFINITY)?new Pick(best,score):new Pick(-1,score);
    }
    private static final int MAX_DRAWS=16;
    private static final java.util.regex.Pattern TREE=java.util.regex.Pattern.compile("/draws/\\d+/tree/(.+)$");
    private static final java.util.regex.Pattern DRAW=java.util.regex.Pattern.compile("/draws/(\\d+)/");
    // the name the part comes under without Flywheel: a layer's part under the model's own name
    private static String vanilla(Ref ref) {
        var tree=TREE.matcher(name(ref.name()));return tree.find()?name("root/"+tree.group(1)):name(ref.name());
    }
    private static boolean layer(Ref ref) { return TREE.matcher(name(ref.name())).find(); }
    private static String layerFree(String name) { return DRAW.matcher(name).replaceAll("/draws/*/"); }
    private static int draw(String name) { var m=DRAW.matcher(name);return m.find()?Integer.parseInt(m.group(1)):-1; }
    // Часть, которую модель рисует вместо другой (череп Mask Man вместо головы с половины здоровья, instead в
    // разметке): у каждой своя зона, зона найдена, если подошла любая из пары.
    private static boolean otherVariant(Profile profile,int[] mapping,int part) {
        for(var zone:profile.zones()) {
            int other=zone.part();
            if(other!=part && mapping[other]>=0 && (profile.parts().get(other).instead()==part || profile.parts().get(part).instead()==other)) return true;
        }
        return false;
    }
    static List<HeadZone> zones(Binding binding,List<ModelBox> boxes) { return zones(binding,boxes,boxes); }
    // cubes: the boxes before unite. A zone on the box around a part's cubes is those cubes (HeadZone.pieces), as
    // Flywheel's box of the part holds their mesh: the box alone would take in what lies between them (the back
    // under a zombie villager's skull, beside its nose).
    static List<HeadZone> zones(Binding binding,List<ModelBox> boxes,List<ModelBox> cubes) {
        var out=new ArrayList<HeadZone>();
        for(var zone:binding.zones()) {
            var box=boxes.get(zone.part());if(zone.face()!=0) box=box.withMesh(zone.face());
            var part=box.part();var pieces=zone.face()!=0 || part.startsWith("mesh/")?List.<ModelBox>of():cubes.stream().filter(b->b.part().equals(part)).toList();
            if(pieces.size()<2 || pieces.contains(box)) pieces=List.of();
            out.add(HeadZone.local(box,zone.planes(),pieces));
        }
        return List.copyOf(out);
    }
}
