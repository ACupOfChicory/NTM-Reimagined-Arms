package acup.gunfeel;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Разметка фиксированного снимка модели. Индексы относятся только к этому снимку. */
final class HeadLabelScreen extends Screen {
    final Entity entity=Objects.requireNonNull(MobSmoke.target());
    List<ModelBox> boxes=List.copyOf(ModelHits.worldBoxes(entity));
    final Map<String,Integer> labels=new LinkedHashMap<>();
    final Map<String,List<HeadLabelCuts.Plane>> cuts=new LinkedHashMap<>();
    private record Edit(Map<String,Integer> labels,Map<String,List<HeadLabelCuts.Plane>> cuts) {}
    private final ArrayDeque<Edit> undo=new ArrayDeque<>();
    private final String id=MobSmoke.current();
    private String sample,message="Выдели голову и нажми Enter. Бирюзовый — текущая голова.";
    private final Vec3 original=Minecraft.getInstance().player.position();
    private final float originalYaw=Minecraft.getInstance().player.getYRot(),originalPitch=Minecraft.getInstance().player.getXRot();
    private Vec3 center;
    private double distance,phi,theta=.1,startX,startY;
    private boolean faces,drag;
    private boolean cutMode;
    private HeadLabelCuts.Plane pendingCut;
    private double cutX,cutY;
    private int group=1;
    private HeadLabelGeometry.Pick hover;
    private Button groupButton;
    private int poseFrames;
    private boolean waiting,cameraRestored;
    boolean waiting() { return waiting; }
    private final Map<List<String>,List<Vec3[]>> edgeCache=new HashMap<>();

    HeadLabelScreen() {
        super(Component.literal("Разметка голов"));
        double x0=Double.POSITIVE_INFINITY,y0=x0,z0=x0,x1=-x0,y1=x1,z1=x1;
        for(var box:boxes) for(int i=0;i<8;i++) {
            var p=ModelContours.point(box,i&1,(i>>1)&1,(i>>2)&1);
            x0=Math.min(x0,p.x); y0=Math.min(y0,p.y); z0=Math.min(z0,p.z);
            x1=Math.max(x1,p.x); y1=Math.max(y1,p.y); z1=Math.max(z1,p.z);
        }
        center=new Vec3((x0+x1)/2,(y0+y1)/2,(z0+z1)/2);
        distance=Math.max(2,new Vec3(x1-x0,y1-y0,z1-z0).length()*1.5);
        if(boxes.isEmpty()) { center=entity.position().add(0,entity.getBbHeight()/2,0);distance=Math.max(2,entity.getBbHeight()*2);message="Нет точной геометрии. Отложи этого моба: это не означает отсутствие головы."; }
    }
    private void restore() {
        var previous=HeadLabels.store.get(id);
        if(previous!=null) {
            try {
                var geometry=HeadLabels.store.model(previous.sample()).getAsJsonArray("geometry");
                boolean same=geometry.size()==boxes.size();
                for(int i=0;same && i<boxes.size();i++) {
                    var saved=geometry.get(i).getAsJsonObject();var frame=saved.getAsJsonArray("frame");
                    same=saved.get("part").getAsString().equals(boxes.get(i).part());
                    var numbers=boxes.get(i).numbers();
                    for(int k=0;same && k<numbers.length;k++) same=Math.abs(frame.get(k).getAsDouble()-numbers[k])<1e-6;
                    var triangles=saved.getAsJsonArray("triangles");var current=HeadLabelGeometry.triangles(boxes.get(i));
                    same&=triangles.size()==current.length;
                    for(int k=0;same && k<current.length;k++) same=Math.abs(triangles.get(k).getAsFloat()-current[k])<1e-6;
                }
                if(same) { if(previous.status().equals("draft")) sample=previous.sample();labels.putAll(previous.labels());cuts.putAll(previous.cuts());group=labels.values().stream().mapToInt(Integer::intValue).max().orElse(1);message="Восстановлена разметка этой позы"; }
                else message="Предыдущая поза сохранена. Это новый пример: разметь голову заново.";
            } catch(Exception e) { message="Старый пример сохранён; не удалось восстановить: "+e.getMessage(); }
        }
    }
    @Override protected void init() {
        String[] names={"Сохранить →","Нет головы","Отложить →","← Предыдущий","Следующий →","Голова №1","Новая голова","Детали / грани","Отмена","Очистить"};
        Runnable[] actions={()->save("done"),()->save("none"),()->save("skip"),()->navigate(-1),()->navigate(1),
                ()->{int max=labels.values().stream().mapToInt(Integer::intValue).max().orElse(1);group=group>=max?1:group+1;},
                ()->{group=labels.values().stream().mapToInt(Integer::intValue).max().orElse(0)+1;message="Выбирается голова №"+group;},()->faces=!faces,this::undo,()->edit(Map.of())};
        for(int i=0;i<names.length;i++) {
            final Runnable action=actions[i];
            var button=addRenderableWidget(Button.builder(Component.literal(names[i]),b->action.run()).bounds(6+(i%5)*(width-12)/5,height-47+(i/5)*23,(width-12)/5-2,20).build());
            if(i==5) groupButton=button;
        }
        if(!waiting) orbit();
    }
    private void orbit() {
        pendingCut=null;
        var eye=center.add(Math.sin(phi)*Math.cos(theta)*distance,Math.sin(theta)*distance,-Math.cos(phi)*Math.cos(theta)*distance);
        var player=Minecraft.getInstance().player;
        player.snapTo(eye.subtract(0,player.getEyeHeight(),0),(float)Math.toDegrees(phi),(float)Math.toDegrees(theta));
        player.setOldPosAndRot();
    }
    Vec3 project(Vec3 point) {
        var camera=Minecraft.getInstance().gameRenderer.mainCamera();
        var p=point.subtract(camera.position());
        var v=camera.getViewRotationProjectionMatrix(new Matrix4f()).transformProject(new Vector3f((float)p.x,(float)p.y,(float)p.z));
        return new Vec3((v.x+1)*width/2,(1-v.y)*height/2,v.z);
    }
    HeadLabelGeometry.Pick pick(double x,double y) {
        var camera=Minecraft.getInstance().gameRenderer.mainCamera();
        var v=camera.getViewRotationProjectionMatrix(new Matrix4f()).invert().transformProject(new Vector3f((float)(2*x/width-1),(float)(1-2*y/height),.5F));
        var from=camera.position();
        return HeadLabelGeometry.pick(boxes,from,from.add(new Vec3(v.x,v.y,v.z).normalize().scale(4096)));
    }
    private Vec3 ray(double x,double y) {
        var camera=Minecraft.getInstance().gameRenderer.mainCamera();
        var v=camera.getViewRotationProjectionMatrix(new Matrix4f()).invert().transformProject(new Vector3f((float)(2*x/width-1),(float)(1-2*y/height),.5F));
        return new Vec3(v.x,v.y,v.z).normalize();
    }
    private void snapshot() throws Exception {
        if(sample!=null) return;
        var model=new LinkedHashMap<String,Object>();
        model.put("schema",1); model.put("entity",id); model.put("renderer",Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity).getClass().getName());
        model.put("position",entity.position()); model.put("eye",entity.getEyePosition()); model.put("look",entity.getLookAngle());
        model.put("yaw",entity.getYRot()); model.put("pitch",entity.getXRot());
        model.put("camera",Minecraft.getInstance().gameRenderer.mainCamera().position());
        var bounds=entity.getBoundingBox();
        model.put("bounds",new double[]{bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ});
        model.put("width",entity.getBbWidth());model.put("height",entity.getBbHeight());
        model.put("geometry",boxes.stream().map(b->Map.of("part",b.part(),"frame",b.numbers(),"triangles",HeadLabelGeometry.triangles(b))).toList());
        model.put("evidence",MobSmoke.evidence());
        sample=HeadLabels.store.snapshot(id,model);
    }
    private void edit(Map<String,Integer> next) {
        var nextCuts=new LinkedHashMap<>(cuts);
        nextCuts.keySet().removeIf(token->!Objects.equals(next.get(token),labels.get(token)));
        edit(next,nextCuts);
    }
    private void edit(Map<String,Integer> next,Map<String,List<HeadLabelCuts.Plane>> nextCuts) {
        if(next.equals(labels) && nextCuts.equals(cuts)) return;
        var previous=new Edit(new LinkedHashMap<>(labels),new LinkedHashMap<>(cuts));
        try {
            snapshot(); HeadLabels.store.save(id,sample,"draft",next,nextCuts);
            undo.push(previous); labels.clear(); labels.putAll(next);cuts.clear();cuts.putAll(nextCuts);edgeCache.clear(); message="Черновик сохранён · голова №"+group;
        } catch(Exception e) { message="Не сохранено: "+e.getMessage();GunFeel.LOGGER.error("[headlabels] сохранение",e); }
    }
    private void undo() {
        if(undo.isEmpty()) return;
        var previous=undo.pop();
        try { snapshot();HeadLabels.store.save(id,sample,"draft",previous.labels(),previous.cuts()); labels.clear(); labels.putAll(previous.labels());cuts.clear();cuts.putAll(previous.cuts());edgeCache.clear();pendingCut=null; }
        catch(Exception e) { undo.push(previous); message="Не сохранено: "+e.getMessage(); }
    }
    private void save(String status) {
        if(pendingCut!=null) { message="Сначала щёлкни со стороны головы или нажми C для отмены отсечения.";return; }
        if(boxes.isEmpty() && !status.equals("skip")) { message="Нет геометрии: можно только отложить моба, а не объявить его безголовым.";return; }
        try {
            snapshot(); HeadLabels.store.save(id,sample,status,status.equals("none")?Map.of():labels,status.equals("none")?Map.of():cuts);
            if(status.equals("none")) { labels.clear();cuts.clear();edgeCache.clear(); }
            if(status.equals("done") || status.equals("none")) sample=null;
            if(MobSmoke.cases().stream().allMatch(HeadLabels.store::done)) { message="Все мобы размечены. ← → позволяют пересмотреть примеры.";return; }
            transition(1,true);
        } catch(Exception e) { message=e.getMessage(); }
    }
    private void navigate(int direction) {
        transition(direction,false);
    }
    private void transition(int direction,boolean unlabeled) {
        if(waiting) return;
        restoreCamera();waiting=true;drag=false;hover=null;
        children().stream().filter(Button.class::isInstance).map(Button.class::cast).forEach(button->button.active=false);
        HeadLabels.next(direction,unlabeled);
    }
    private void select(Collection<String> tokens,boolean remove,boolean toggle) {
        var next=new LinkedHashMap<>(labels);
        for(String token:tokens) {
            if(remove || toggle && Objects.equals(next.get(token),group)) next.remove(token);
            else {
                String prefix=token.substring(0,token.indexOf(':')+1);
                if(token.endsWith(":-1")) next.keySet().removeIf(key->key.startsWith(prefix));
                else next.remove(prefix+"-1");
                next.put(token,group);
            }
        }
        edit(next);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubled) {
        if(waiting || poseFrames<8) return true;
        if(super.mouseClicked(event,doubled)) return true;
        if(cutMode && pendingCut!=null && event.button()==0 && event.y()>48 && event.y()<height-78) {
            var plane=pendingCut;double side=plane.nx()*ray(event.x(),event.y()).x+plane.ny()*ray(event.x(),event.y()).y+plane.nz()*ray(event.x(),event.y()).z;
            if(Math.abs(side)<1e-8) { message="Щёлкни дальше от линии, со стороны головы.";return true; }
            if(side<0) plane=plane.flip();
            var next=new LinkedHashMap<>(cuts);
            for(var entry:labels.entrySet()) if(entry.getValue()==group) {
                var list=new ArrayList<>(cuts.getOrDefault(entry.getKey(),List.of()));list.add(plane);next.put(entry.getKey(),List.copyOf(list));
            }
            var tokens=labels.entrySet().stream().filter(e->e.getValue()==group).map(Map.Entry::getKey).toList();
            if(HeadLabelCuts.edges(boxes,tokens,next).isEmpty()) { message="С этой стороны выделение пустое. Выбери другую сторону или нажми C.";return true; }
            edit(new LinkedHashMap<>(labels),next);pendingCut=null;cutMode=false;return true;
        }
        if(event.button()==0 && event.y()>48 && event.y()<height-78) { startX=event.x();startY=event.y();drag=true;return true; }
        return event.button()==1;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy) {
        if(waiting) return true;
        if(event.button()==1) { phi-=dx*.01;theta=Math.clamp(theta+dy*.01,-1.45,1.45);orbit();return true; }
        if(drag) return true;
        return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if(waiting) return true;
        if(event.button()!=0 || !drag) return super.mouseReleased(event);
        drag=false;
        if(cutMode) {
            if(labels.values().stream().noneMatch(value->value==group)) { message="Сначала выдели деталь головы обычным ЛКМ.";return true; }
            if(Math.hypot(event.x()-startX,event.y()-startY)<8) { message="Проведи линию с зажатой ЛКМ, затем выбери сторону головы.";return true; }
            var normal=ray(startX,startY).cross(ray(event.x(),event.y())).normalize();
            var camera=Minecraft.getInstance().gameRenderer.mainCamera().position();
            pendingCut=new HeadLabelCuts.Plane(normal.x,normal.y,normal.z,-normal.dot(camera));cutX=event.x();cutY=event.y();
            message="Теперь щёлкни со стороны головы. Срез проходит через всю глубину модели. C — отмена.";return true;
        }
        boolean remove=(event.modifiers()&GLFW.GLFW_MOD_SHIFT)!=0;
        if(Math.hypot(event.x()-startX,event.y()-startY)<5) {
            var selected=pick(event.x(),event.y()); if(selected!=null) select(List.of(selected.token(faces)),remove,true);
        } else {
            var tokens=new ArrayList<String>();
            for(int i=0;i<boxes.size();i++) {
                var box=boxes.get(i); var data=HeadLabelGeometry.triangles(box);
                for(int at=0;at<(faces?data.length:9);at+=9) {
                    var p=faces?HeadLabelGeometry.point(box,data,at).add(HeadLabelGeometry.point(box,data,at+3)).add(HeadLabelGeometry.point(box,data,at+6)).scale(1.0/3):ModelContours.point(box,.5,.5,.5);
                    var q=project(p);
                    if(q.z>0 && q.x>=Math.min(startX,event.x()) && q.x<=Math.max(startX,event.x()) && q.y>=Math.min(startY,event.y()) && q.y<=Math.max(startY,event.y())) tokens.add(i+":"+(faces?at/9:-1));
                }
            }
            select(tokens,remove,false);
        }
        return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) { if(!waiting) { distance=Math.clamp(distance*Math.exp(-vertical*.12),.2,2048);orbit(); }return true; }
    @Override public boolean keyPressed(KeyEvent event) {
        if(waiting && event.key()!=GLFW.GLFW_KEY_ESCAPE && event.key()!=GLFW.GLFW_KEY_H) return true;
        switch(event.key()) {
            case GLFW.GLFW_KEY_C -> { cutMode=!cutMode;pendingCut=null;drag=false;message=cutMode?"Отсечение: проведи линию ЛКМ, затем щёлкни со стороны головы. C — отмена.":"Обычное выделение"; }
            case GLFW.GLFW_KEY_ENTER -> save("done");
            case GLFW.GLFW_KEY_RIGHT -> navigate(1);
            case GLFW.GLFW_KEY_LEFT -> navigate(-1);
            case GLFW.GLFW_KEY_BACKSPACE -> undo();
            case GLFW.GLFW_KEY_G -> MobReview.search();
            case GLFW.GLFW_KEY_H,GLFW.GLFW_KEY_ESCAPE -> onClose();
            default -> { return super.keyPressed(event); }
        }
        return true;
    }
    @Override public void onClose() { HeadLabels.suspended=true;super.onClose(); }
    @Override public void removed() { restoreCamera(); }
    private void restoreCamera() {
        if(cameraRestored) return;
        cameraRestored=true;
        var player=Minecraft.getInstance().player;
        if(player!=null) { player.snapTo(original,originalYaw,originalPitch);player.setOldPosAndRot(); }
    }
    @Override public boolean isPauseScreen() { return !waiting; }
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float partial) {}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float partial) {
        super.extractRenderState(g,x,y,partial);
        if(!waiting && poseFrames<8 && ++poseFrames==8) { boxes=List.copyOf(ModelHits.worldBoxes(entity));restore(); }
        hover=!waiting && !cutMode && y>48 && y<height-78?pick(x,y):null;
        groupButton.setMessage(Component.literal("Голова №"+group+" ↻"));
        g.fill(0,0,width,46,0xDD181B25);
        g.text(font,"Головы · "+(MobSmoke.index()+1)+"/"+MobSmoke.cases().size()+" · "+id+" · готово "+HeadLabels.store.completed(),8,6,0xFFFFCC55);
        var old=HeadLabels.store.get(id);
        String status=old==null?"не размечен":switch(old.status()) { case "done"->"подтверждён";case "none"->"без головы";case "skip"->"отложен";default->"черновик"; };
        g.text(font,"Голова №"+group+" · "+(faces?"грани":"целые детали")+" · выбрано "+labels.size()+" · "+status,8,18,0xFF77FFFF);
        g.text(font,font.plainSubstrByWidth(waiting?"Подготовка следующего моба… Выделение временно отключено.":message,width-16),8,30,0xFFF2EFF8);
        g.fill(0,height-76,width,height-49,0xDD181B25);
        g.text(font,"ЛКМ — выбрать · рамка — несколько · Shift — убрать · C — отсечь лишнее · ПКМ — вращать · колесо — масштаб",8,height-71,0xFFF2EFF8);
        g.text(font,"Enter — сохранить · ← → — листать · G — поиск · Backspace — отменить · H/Esc — игровой вид, H — вернуться",8,height-60,0xFFD7D2E8);
        if(cutMode && (drag || pendingCut!=null)) {
            double ex=pendingCut==null?x:cutX,ey=pendingCut==null?y:cutY;
            double dx=ex-startX,dy=ey-startY,length=Math.hypot(dx,dy);
            if(length>0) for(double t=-Math.max(width,height)*2;t<Math.max(width,height)*2;t+=1) {
                int px=(int)(startX+dx/length*t),py=(int)(startY+dy/length*t);
                if(px>=0 && px<width && py>46 && py<height-78) g.fill(px,py,px+2,py+2,0xFF55FFFF);
            }
        } else if(drag) {
            int a=(int)Math.min(startX,x),b=(int)Math.min(startY,y),c=(int)Math.max(startX,x),d=(int)Math.max(startY,y);
            g.fill(a,b,c,b+1,0xFF55FFFF);g.fill(a,d,c,d+1,0xFF55FFFF);g.fill(a,b,a+1,d,0xFF55FFFF);g.fill(c,b,c+1,d,0xFF55FFFF);
        }
        if(!waiting) HeadLabelSelfTest.frame(this);
    }
    void overlay(List<ModelDebugRender.Shape> shapes,int owner) {
        if(waiting) return;
        for(boolean active:new boolean[]{false,true}) {
            var tokens=labels.entrySet().stream().filter(e->(e.getValue()==group)==active).map(Map.Entry::getKey).toList();
            if(!tokens.isEmpty()) shapes.add(new ModelDebugRender.Shape(owner,true,boxes,edges(tokens),null,active?.15F:.35F));
        }
        if(hover!=null && !labels.containsKey(hover.token(faces))) shapes.add(new ModelDebugRender.Shape(owner,true,boxes,edges(List.of(hover.token(faces))),null,.5F));
    }
    private List<Vec3[]> edges(List<String> tokens) {
        if(edgeCache.size()>64) edgeCache.clear();
        return edgeCache.computeIfAbsent(tokens,key->HeadLabelCuts.edges(boxes,key,cuts));
    }
}
