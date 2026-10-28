package acup.gunfeel;

import java.util.*;
import java.util.function.Consumer;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/** Общий редактор ключевых кадров для оружия в руке. */
public final class ArmAnimationEditor extends Screen {
    private final Screen parent;
    private final Item item;
    private final String gun;
    private final Map<String,String> original=Grips.snapshot();
    private final Deque<String[]> history=new ArrayDeque<>();
    private final String[] paths=new String[2];
    private GunAnimation cue=GunAnimation.RELOAD;
    private List<ArmTimeline.Key> keys=new ArrayList<>();
    private int hand=1, selected, scroll;
    private String status="Выбери кадр; изменения сразу видны на модели";
    private static final int W=300, H=18;
    private boolean loading;
    private Timeline timeline;
    private float anchorX=Float.NaN,anchorY;
    void anchor(boolean right,org.joml.Vector4f point) {
        if(right!=(hand==0)) return;
        anchorX=point.w>1e-5F?(point.x/point.w+1)*.5F:Float.NaN;
        anchorY=(1-point.y/point.w)*.5F;
    }
    public ArmAnimationEditor(Screen parent,Item item,String gun) {
        super(Component.literal("Ключевые кадры рук")); this.parent=parent; this.item=item; this.gun=gun;
        paths[0]=Config.armRightPath; paths[1]=Config.armLeftPath;
        loadKeys();
    }
    @Override public boolean isPauseScreen() { return false; }
    private void loadKeys() {
        try {
            float roll=hand==0?Config.armLoadRollRight:Config.armLoadRollLeft;
            float hold=hand==0?Config.armRollRight:Config.armRollLeft;
            keys=ArmTimeline.read(paths[hand],cue.name(),Config.armPathTimes,Float.isFinite(roll)?roll-hold:0);
            selected=Math.clamp(selected,0,Math.max(0,keys.size()-1));
        } catch(RuntimeException e) { keys=new ArrayList<>(); status="Не удалось разобрать ключи: "+e.getMessage(); }
    }
    private void preview() {
        try { ArmAnimationPreview.start(cue); status=ArmAnimationPreview.animation()==null?"У оружия нет такой анимации":"Предпросмотр: "+label(cue); }
        catch(RuntimeException e) { ArmAnimationPreview.clear(); status="Анимация недоступна: "+e.getClass().getSimpleName(); }
    }
    private static String label(GunAnimation value) {
        return switch(value.name()) {
            case "RELOAD" -> "Перезарядка"; case "RELOAD_CYCLE" -> "Цикл зарядки";
            case "RELOAD_END" -> "Конец зарядки"; case "INSPECT" -> "Осмотр";
            case "EQUIP" -> "Доставание"; case "CYCLE" -> "Передёргивание";
            case "CYCLE_EMPTY" -> "Передёргивание пустого"; case "CYCLE_DRY" -> "Холостой цикл";
            case "ALT_CYCLE" -> "Альтернативный цикл"; case "SPINUP" -> "Раскрутка"; case "SPINDOWN" -> "Остановка вращения";
            case "JAMMED" -> "Осечка"; default -> value.name();
        };
    }
    private Button button(String text,int x,int y,int width,Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text),b->action.run()).bounds(x,y-scroll,width,H).build());
    }
    private void changed() {
        if(loading) return;
        var next=ArmTimeline.write(paths[hand],cue.name(),keys);
        if(next.equals(paths[hand])) return;
        history.push(paths.clone()); if(history.size()>200) history.removeLast();
        paths[hand]=next; apply();
    }
    private void apply() {
        Grips.put(gun,"armRightPath",paths[0],false); Grips.put(gun,"armLeftPath",paths[1],false);
        Grips.put(gun,"armPathBlend","true",false); Config.select(gun);
    }
    private EditBox field(String value,int x,int y,int width,Consumer<String> setter) {
        var box=new EditBox(font,x,y-scroll,width,H,Component.literal("значение")) {
            @Override public boolean mouseScrolled(double mx,double my,double sx,double sy) {
                try {
                    float v=Float.parseFloat(getValue());
                    float step=Minecraft.getInstance().hasShiftDown()?.005F:.05F;
                    setValue(ArmTimeline.number(Math.round((v+(float)sy*step)*1000F)/1000F));return true;
                } catch(NumberFormatException e) { return false; }
            }
        };
        box.setMaxLength(512); box.setValue(value);
        box.setResponder(text->{ try { setter.accept(text); changed(); status="Изменения в предпросмотре; сохранить — кнопкой внизу"; }
            catch(RuntimeException e) { status="Неполное или неверное значение: "+text; } });
        return addRenderableWidget(box);
    }
    private void scalar(String name,float value,int y,Consumer<Float> setter) {
        field(ArmTimeline.number(value),158,y,138,text->{ float v=Float.parseFloat(text); if(!Float.isFinite(v)) throw new IllegalArgumentException(); setter.accept(v); });
    }
    private void vector(float[] values,int y) {
        for(int c=0;c<3;c++) { int axis=c; field(ArmTimeline.number(values[c]),100+c*66,y,64,text->{
            float v=Float.parseFloat(text); if(!Float.isFinite(v)) throw new IllegalArgumentException(); values[axis]=v;
        }); }
    }
    @Override protected void init() {
        loading=true;
        if(ArmAnimationPreview.animation()==null) preview();
        button("Анимация: "+label(cue),4,22,W,()->{
            var types=GunAnimation.values(); cue=types[(cue.ordinal()+1)%types.length]; loadKeys(); preview(); rebuildWidgets();
        });
        button(ArmAnimationPreview.playing()?"Пауза":"Играть по кругу",4,42,148,()->{
            ArmAnimationPreview.play(!ArmAnimationPreview.playing()); rebuildWidgets();
        });
        button("Скорость: ×"+ArmAnimationPreview.speed(),156,42,148,()->{
            double[] speeds={.05,.1,.25,.5,1,2}; int next=0;
            for(int i=0;i<speeds.length;i++) if(speeds[i]==ArmAnimationPreview.speed()) next=(i+1)%speeds.length;
            ArmAnimationPreview.speed(speeds[next]); rebuildWidgets();
        });
        timeline=addRenderableWidget(new Timeline(4,62-scroll,W,H));
        button("−10 мс",4,82,72,()->seek(-10)); button("+10 мс",80,82,72,()->seek(10));
        button("В начало",156,82,72,()->{ArmAnimationPreview.play(false);ArmAnimationPreview.seek(0);});
        button("В конец",232,82,72,()->{ArmAnimationPreview.play(false);ArmAnimationPreview.seek(ArmAnimationPreview.duration());});
        button(hand==0?"Правая рука":"Левая рука",4,102,148,()->{ hand=1-hand;loadKeys();rebuildWidgets(); });
        button("Модель: "+Config.armModel,156,102,148,()->{
            Grips.put(gun,"armModel",switch(Config.armModel){case "auto"->"classic";case "classic"->"slim";default->"auto";},false);
            Config.select(gun);rebuildWidgets();
        });
        button("← Кадр",4,122,72,()->select(-1)); button("Кадр →",80,122,72,()->select(1));
        button("+ Ключ",156,122,72,()->{
            float time=ArmAnimationPreview.millis();
            for(int i=0;i<keys.size();i++) if(Math.abs(keys.get(i).time-time)<.5F) { selected=i;rebuildWidgets();return; }
            var k=keys.isEmpty()?new ArmTimeline.Key():keys.get(selected).copy(); k.time=time;
            keys.add(k); keys.sort(Comparator.comparingDouble(v->v.time)); selected=keys.indexOf(k);changed();rebuildWidgets();
        });
        button("Удалить",232,122,72,()->{ if(!keys.isEmpty()){keys.remove(selected);selected=Math.min(selected,Math.max(0,keys.size()-1));changed();rebuildWidgets();} });
        if(!keys.isEmpty()) {
            var key=keys.get(selected);
            scalar("Время, мс",key.time,162,v->{
                if(v<0 || keys.stream().anyMatch(k->k!=key && Math.abs(k.time-v)<.5F)) throw new IllegalArgumentException(); key.time=v;
            });
            button("Цель: "+key.part,4,182,W,()->{
                var parts=new ArrayList<String>();parts.add("grip");parts.addAll(Arms.editorParts());
                int i=parts.indexOf(key.part);key.part=parts.get((i+1)%parts.size());changed();rebuildWidgets();
            });
            field(key.part,100,202,196,text->{ if(text.isBlank()) throw new IllegalArgumentException(); key.part=text.trim(); });
            vector(key.point,222); vector(key.side,242); vector(key.shoulder,262);
            scalar("Поворот кисти, °",key.roll,282,v->key.roll=v);
            button(key.corner?"Контакт: внутренний угол":"Контакт: центр / отступ",4,302,W,()->{key.corner=!key.corner;changed();rebuildWidgets();});
            button(key.follow?"Плечо: вслед за кистью":"Плечо: сдвиг от обычного",4,322,W,()->{key.follow=!key.follow;changed();rebuildWidgets();});
            button("Обычный хват",4,342,148,()->{key.part="grip";changed();rebuildWidgets();});
            button("Курсор → время ключа",156,342,148,()->{
                float time=ArmAnimationPreview.millis();
                if(keys.stream().noneMatch(k->k!=key && Math.abs(k.time-time)<.5F)){key.time=time;changed();rebuildWidgets();}
            });
        }
        button("Отменить шаг",4,364,148,()->{ if(!history.isEmpty()){var old=history.pop();paths[0]=old[0];paths[1]=old[1];apply();loadKeys();rebuildWidgets();} });
        button("Вернуть как при входе",156,364,148,()->{
            Grips.restore(original);Config.select(gun);paths[0]=Config.armRightPath;paths[1]=Config.armLeftPath;history.clear();loadKeys();rebuildWidgets();
        });
        button("Сохранить",4,384,148,()->{Grips.flush();status="Сохранено в config/ntm-gunfeel-guns.properties";});
        button("Сохранить и назад",156,384,148,this::onClose);
        loading=false;
    }
    private void select(int direction) {
        if(keys.isEmpty()) return; selected=Math.floorMod(selected+direction,keys.size());
        ArmAnimationPreview.play(false);ArmAnimationPreview.seek(keys.get(selected).time);rebuildWidgets();
    }
    private void seek(int delta) { ArmAnimationPreview.play(false);ArmAnimationPreview.seek(ArmAnimationPreview.millis()+delta); }
    @Override public void tick() {
        var player=Minecraft.getInstance().player;
        if(player==null || player.getMainHandItem().getItem()!=item) onClose();
        if(timeline!=null) timeline.sync();
    }
    @Override public void onClose() { Grips.flush();ArmAnimationPreview.clear();Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void removed() { ArmAnimationPreview.clear();Grips.flush(); }
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float a) { g.fill(0,0,W+8,height,0x80101018); }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float a) {
        if(timeline!=null) timeline.sync();
        super.extractRenderState(g,x,y,a);
        if(Float.isFinite(anchorX) && anchorX>=0 && anchorX<=1 && anchorY>=0 && anchorY<=1 && !keys.isEmpty() && !keys.get(selected).part.equals("grip")) {
            int ax=(int)(anchorX*width),ay=(int)(anchorY*height);
            g.fill(ax-5,ay,ax+6,ay+1,0xFF55FFFF);g.fill(ax,ay-5,ax+1,ay+6,0xFF55FFFF);
        }
        text(g,"Ключевые кадры · "+gun,4,4,0xFFFFFFFF);
        text(g,"Ключ "+(keys.isEmpty()?0:selected+1)+" / "+keys.size()+"    XYZ: ←/↑/вдоль модели",4,144,0xFFFFCC55);
        if(!keys.isEmpty()) {
            text(g,"Время, мс",4,166,0xFFFFFFFF);text(g,"Деталь (#n / #last)",4,206,0xFFFFFFFF);
            text(g,"Точка XYZ",4,226,0xFFFFFFFF);text(g,"Сторона XYZ",4,246,0xFFFFFFFF);
            text(g,"Плечо XYZ",4,266,0xFFFFFFFF);text(g,"Кисть, °",4,286,0xFFFFFFFF);
        }
        text(g,status,4,410,0xFFB5D8FF);
        text(g,"Колесо на числе: шаг; Shift — точнее. Вне: прокрутка.",4,424,0xFFAAAAAA);
    }
    private void text(GuiGraphicsExtractor g,String text,int x,int y,int color) { g.text(font,text,x,y-scroll,color); }
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if(!(getFocused() instanceof EditBox)) {
            if(event.key()==32) { ArmAnimationPreview.play(!ArmAnimationPreview.playing());rebuildWidgets();return true; }
            if(event.key()==262 || event.key()==263) { seek((event.key()==262?1:-1)*(Minecraft.getInstance().hasShiftDown()?1:10));return true; }
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x,double y,double sx,double sy) {
        if(super.mouseScrolled(x,y,sx,sy)) return true;
        if(x>W+8) return false;scroll=Math.clamp(scroll-(int)(sy*30),0,Math.max(0,442-height));rebuildWidgets();return true;
    }
    private final class Timeline extends AbstractSliderButton {
        Timeline(int x,int y,int w,int h) { super(x,y,w,h,Component.empty(),0);sync(); }
        void sync(){value=ArmAnimationPreview.duration()==0?0:ArmAnimationPreview.millis()/(double)ArmAnimationPreview.duration();updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(ArmAnimationPreview.millis()+" / "+ArmAnimationPreview.duration()+" мс"));}
        @Override protected void applyValue(){ArmAnimationPreview.play(false);ArmAnimationPreview.seek(value*ArmAnimationPreview.duration());}
    }
}
