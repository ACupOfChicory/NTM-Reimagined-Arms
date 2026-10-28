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

/** Простые поправки к подготовленным этапам движения. */
public final class ArmAnimationEditor extends Screen {
    private final Screen parent;
    private final Item item;
    private final String gun;
    private final Map<String,String> original=Grips.snapshot();
    private final Deque<String[]> history=new ArrayDeque<>();
    private final String[] paths=new String[2], edits=new String[2];
    private GunAnimation cue=GunAnimation.RELOAD;
    private List<ArmTimeline.Key> keys=new ArrayList<>();
    private record Stage(int first,int last,String name) {}
    private final List<Stage> stages=new ArrayList<>();
    private int hand=1, selected, scroll;
    private boolean changing, dynamic;
    private float[] begin=new float[6],end=new float[6];
    private String status="Выбери действие и подстрой руку. Поправки сразу видны.";
    private static final int W=270,H=18;
    private static final String[] PARAMETERS={"Вправо, пикс.","Вверх, пикс.","К себе, пикс.","Наклон руки, °","Разворот кисти, °","Отвод вбок, °"};
    private static final String[] SIDES={"Снаружи","Изнутри","Снизу","Сверху","Спереди","Сзади"};
    private static final float[][] SIDE={{1,0,0},{-1,0,0},{0,-1,0},{0,1,0},{0,0,1},{0,0,-1}};
    private Timeline timeline;
    private float anchorX=Float.NaN,anchorY;
    public ArmAnimationEditor(Screen parent,Item item,String gun) {
        super(Component.literal("Движения рук"));this.parent=parent;this.item=item;this.gun=gun;
        paths[0]=Config.armRightPath;paths[1]=Config.armLeftPath;
        edits[0]=Config.armRightAdjustments;edits[1]=Config.armLeftAdjustments;
        load();
    }
    void anchor(boolean right,org.joml.Vector4f point) {
        if(right!=(hand==0))return;
        anchorX=point.w>1e-5F?(point.x/point.w+1)*.5F:Float.NaN;
        anchorY=(1-point.y/point.w)*.5F;
    }
    @Override public boolean isPauseScreen(){return false;}
    private static String label(GunAnimation cue) {
        return switch(cue.name()) {
            case "RELOAD"->"Перезарядка";case "RELOAD_CYCLE"->"Цикл зарядки";case "RELOAD_END"->"Конец зарядки";
            case "INSPECT"->"Осмотр";case "EQUIP"->"Доставание";case "CYCLE"->"Передёргивание";
            case "CYCLE_EMPTY"->"Передёргивание пустого";case "CYCLE_DRY"->"Холостой цикл";case "ALT_CYCLE"->"Другой цикл";
            case "SPINUP"->"Раскрутка";case "SPINDOWN"->"Остановка";case "JAMMED"->"Осечка";default->cue.name();
        };
    }
    private static String partName(String part) {
        return switch(part.toLowerCase(Locale.ROOT)) {
            case "grip"->"Обычный хват";case "handle","lever","plug","guide_and_bolt"->"Рукоятка затвора";
            case "magazine","mag"->"Магазин";case "rifle","gun"->"Корпус оружия";case "hood"->"Крышка";
            default->part;
        };
    }
    private String stageName(int first,int ordinal) {
        String part=keys.get(first).part.toLowerCase(Locale.ROOT);
        if(part.equals("grip"))return first==0?"Держать оружие":"Вернуться к хвату";
        if(part.equals("handle")||part.equals("lever"))return keys.subList(0,first).stream().anyMatch(k->k.part.equals(keys.get(first).part))?"Вернуть затвор":"Отвести затвор";
        if(part.equals("magazine")||part.equals("mag"))return "Поднять и вставить магазин";
        if(part.equals("rifle")||part.equals("gun"))return "Опустить руку";
        return "Работа с деталью: "+partName(keys.get(first).part);
    }
    private void load() {
        try {
            float roll=hand==0?Config.armLoadRollRight:Config.armLoadRollLeft;
            float hold=hand==0?Config.armRollRight:Config.armRollLeft;
            keys=ArmTimeline.read(paths[hand],cue.name(),Config.armPathTimes,Float.isFinite(roll)?roll-hold:0);
            if(keys.isEmpty()) {
                var a=new ArmTimeline.Key();var b=new ArmTimeline.Key();b.time=Math.max(1,ArmAnimationPreview.duration());
                keys.add(a);keys.add(b);
            }
            stages.clear();
            for(int i=0;i<keys.size();) {
                int first=i;String part=keys.get(i).part;
                while(i+1<keys.size() && keys.get(i+1).part.equals(part))i++;
                stages.add(new Stage(first,i,stageName(first,stages.size())));i++;
            }
            selected=Math.clamp(selected,0,stages.size()-1);
            readPose();
        } catch(RuntimeException e){stages.clear();status="Не удалось открыть движение: "+e.getMessage();}
    }
    private Stage stage(){return stages.get(selected);}
    private float start(){return keys.get(stage().first()).time;}
    private float finish(){
        if(stage().first()!=stage().last())return keys.get(stage().last()).time;
        return stage().last()+1<keys.size()?Math.max(start(),keys.get(stage().last()+1).time-1):Math.max(start(),ArmAnimationPreview.duration());
    }
    private void readPose(){
        begin=ArmPoseEdits.sample(edits[hand],cue.name(),start());end=ArmPoseEdits.sample(edits[hand],cue.name(),finish());
        dynamic=!Arrays.equals(begin,end);
    }
    private void preview(){
        try{ArmAnimationPreview.start(cue);if(ArmAnimationPreview.animation()==null)status="У оружия нет такой анимации";}
        catch(RuntimeException e){ArmAnimationPreview.clear();status="Предпросмотр недоступен";}
    }
    private void remember(){history.push(new String[]{paths[0],paths[1],edits[0],edits[1]});if(history.size()>100)history.removeLast();}
    private void apply(){
        Grips.put(gun,"armRightPath",paths[0],false);Grips.put(gun,"armLeftPath",paths[1],false);
        Grips.put(gun,"armRightAdjustments",edits[0],false);Grips.put(gun,"armLeftAdjustments",edits[1],false);
        Config.select(gun);
    }
    private void show(float time){ArmAnimationPreview.play(false);ArmAnimationPreview.seek(time);}
    private void poseChanged(int parameter,boolean atEnd,float value){
        if(changing || stages.isEmpty())return;
        remember();
        if(!dynamic){begin[parameter]=value;end[parameter]=value;}
        else (atEnd?end:begin)[parameter]=value;
        var tracks=ArmPoseEdits.read(edits[hand]);
        var track=tracks.computeIfAbsent(cue.name(),k->new TreeMap<>());
        // Нейтральные опоры соседних действий не дают одной правке захватить всю анимацию.
        for(var key:keys)track.putIfAbsent(key.time,new float[6]);
        track.putIfAbsent(0F,new float[6]);
        track.putIfAbsent((float)ArmAnimationPreview.duration(),new float[6]);
        float duration=finish()-start();
        for(int i=stage().first();i<=stage().last();i++){
            float t=duration<=0?0:(keys.get(i).time-start())/duration;
            var values=new float[6];for(int c=0;c<6;c++)values[c]=begin[c]+(end[c]-begin[c])*t;
            track.put(keys.get(i).time,values);
        }
        track.put(start(),begin.clone());track.put(finish(),end.clone());
        edits[hand]=ArmPoseEdits.write(tracks);apply();show(atEnd?finish():start());
        status="Изменение показано "+(atEnd?"в конце":"в начале")+" выбранного действия.";
    }
    private Button button(String name,int x,int y,int width,Runnable action){
        return addRenderableWidget(Button.builder(Component.literal(name),b->action.run()).bounds(x,y-scroll,width,H).build());
    }
    private void number(float value,int x,int y,int width,int parameter,boolean atEnd){
        var box=new EditBox(font,x,y-scroll,width,H,Component.literal(PARAMETERS[parameter])){
            @Override public boolean mouseScrolled(double mx,double my,double sx,double sy){
                try{
                    float step=parameter<3?.1F:1F;if(Minecraft.getInstance().hasShiftDown())step*=.1F;
                    setValue(Float.toString(Math.round((Float.parseFloat(getValue())+(float)sy*step)*1000)/1000F));return true;
                }catch(NumberFormatException e){return false;}
            }
        };
        box.setValue(Float.toString(value));
        box.setResponder(text->{try{float v=Float.parseFloat(text);if(Float.isFinite(v))poseChanged(parameter,atEnd,v);}
            catch(NumberFormatException e){status="Введи число или покрути колесо над полем.";}});
        addRenderableWidget(box);
    }
    @Override protected void init(){
        changing=true;
        if(ArmAnimationPreview.animation()==null){preview();load();}
        button("Анимация: "+label(cue),4,22,W,()->{
            var types=GunAnimation.values();cue=types[(cue.ordinal()+1)%types.length];preview();load();show(startSafe());rebuildWidgets();
        });
        button(ArmAnimationPreview.playing()?"Пауза":"Посмотреть движение",4,42,150,()->{ArmAnimationPreview.play(!ArmAnimationPreview.playing());rebuildWidgets();});
        button("Скорость: ×"+ArmAnimationPreview.speed(),158,42,116,()->{
            double[] speeds={.05,.1,.25,.5,1};int next=0;
            for(int i=0;i<speeds.length;i++)if(speeds[i]==ArmAnimationPreview.speed())next=(i+1)%speeds.length;
            ArmAnimationPreview.speed(speeds[next]);rebuildWidgets();
        });
        timeline=addRenderableWidget(new Timeline(4,62-scroll,W,H));
        button(hand==0?"Правая рука":"Левая рука",4,82,132,()->{hand=1-hand;selected=0;load();show(startSafe());rebuildWidgets();});
        button("Модель: "+switch(Config.armModel){case "classic"->"обычная";case "slim"->"skinny";default->"по скину";},140,82,134,()->{
            Grips.put(gun,"armModel",switch(Config.armModel){case "auto"->"classic";case "classic"->"slim";default->"auto";},false);Config.select(gun);rebuildWidgets();
        });
        button("← Действие",4,102,132,()->select(-1));button("Действие →",140,102,134,()->select(1));
        if(!stages.isEmpty()){
            button(dynamic?"Разное начало и конец":"Одинаково весь этап",4,142,W,()->{
                dynamic=!dynamic;if(!dynamic){end=begin.clone();poseChanged(0,false,begin[0]);}rebuildWidgets();
            });
            if(dynamic){
                button("Показать начало",4,162,132,()->show(start()));
                button("Показать конец",140,162,134,()->show(finish()));
            }else button("Показать это действие",4,162,W,()->show(start()));
            for(int p=0;p<6;p++){
                number(begin[p],142,202+p*20,dynamic?64:132,p,false);
                if(dynamic)number(end[p],210,202+p*20,64,p,true);
            }
            var key=keys.get(stage().first());
            button("Привязка: "+partName(key.part),4,324,W,()->{
                var parts=new ArrayList<String>();parts.add("grip");parts.addAll(Arms.editorParts());
                int n=parts.indexOf(key.part);String target=parts.get((n+1)%parts.size());remember();
                var center=Arms.editorPartCenter(target);
                for(int i=stage().first();i<=stage().last();i++){keys.get(i).part=target;keys.get(i).point=center.clone();}
                paths[hand]=ArmTimeline.write(paths[hand],cue.name(),keys);apply();show(start());
                // Границы действий сохраняются до следующего открытия: смена детали не объединяет соседние действия.
                rebuildWidgets();
            });
            button("Подойти к детали: "+sideName(key.side),4,344,W,()->{
                int side=0;for(int i=0;i<SIDE.length;i++)if(Arrays.equals(key.side,SIDE[i]))side=(i+1)%SIDE.length;
                remember();for(int i=stage().first();i<=stage().last();i++)keys.get(i).side=SIDE[side].clone();
                paths[hand]=ArmTimeline.write(paths[hand],cue.name(),keys);apply();show(start());rebuildWidgets();
            }).active=!key.part.equals("grip");
            int contact=key.contactId();
            button(contact==0?"Контакт: как в исходной анимации":contact<=4?"Касаться углом кисти":contact>=10?"Касаться ребром кисти":"Касаться серединой плоскости",4,364,W,()->{
                contact(contact==0?1:contact<=4?10:contact>=10?5:0);
            }).active=!key.part.equals("grip");
            button(contactName(contact),4,384,W,()->{
                contact(contact<=4?contact%4+1:contact>=10?contact==13?10:contact+1:contact==9?5:contact+1);
            }).active=!key.part.equals("grip") && contact>0;
        }
        button("Отменить шаг",4,406,132,()->{
            if(history.isEmpty())return;var old=history.pop();paths[0]=old[0];paths[1]=old[1];edits[0]=old[2];edits[1]=old[3];apply();load();show(startSafe());rebuildWidgets();
        });
        button("Сбросить мои правки",140,406,134,()->{
            Grips.restore(original);Config.select(gun);paths[0]=Config.armRightPath;paths[1]=Config.armLeftPath;
            edits[0]=Config.armRightAdjustments;edits[1]=Config.armLeftAdjustments;history.clear();load();show(startSafe());rebuildWidgets();
        });
        button("Сохранить",4,426,132,()->{Grips.flush();status="Правки сохранены для этого оружия.";});
        button("Готово",140,426,134,this::onClose);
        changing=false;
    }
    private float startSafe(){return stages.isEmpty()?0:start();}
    private void contact(int id){
        remember();for(int i=stage().first();i<=stage().last();i++){keys.get(i).contact=id;keys.get(i).corner=id>=1 && id<=4;}
        paths[hand]=ArmTimeline.write(paths[hand],cue.name(),keys);apply();show(start());rebuildWidgets();
    }
    private static String contactName(int id){
        return switch(id){
            case 1->"Угол: внутренний, передний";case 2->"Угол: внутренний, задний";
            case 3->"Угол: внешний, передний";case 4->"Угол: внешний, задний";
            case 5->"Плоскость: внутренняя";case 6->"Плоскость: внешняя";
            case 7->"Плоскость: передняя";case 8->"Плоскость: задняя";case 9->"Плоскость: торец кисти";
            case 10->"Ребро: внутреннее";case 11->"Ребро: внешнее";
            case 12->"Ребро: переднее";case 13->"Ребро: заднее";
            default->"Точка контакта: автоматически";
        };
    }
    private static String sideName(float[] value){for(int i=0;i<SIDE.length;i++)if(Arrays.equals(value,SIDE[i]))return SIDES[i];return "как в анимации";}
    private void select(int delta){if(stages.isEmpty())return;selected=Math.floorMod(selected+delta,stages.size());readPose();show(start());rebuildWidgets();}
    @Override public void tick(){var player=Minecraft.getInstance().player;if(player==null||player.getMainHandItem().getItem()!=item)onClose();}
    @Override public void onClose(){Grips.flush();ArmAnimationPreview.clear();Minecraft.getInstance().gui.setScreen(parent);}
    @Override public void removed(){ArmAnimationPreview.clear();Grips.flush();}
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float a){g.fill(0,0,W+8,height,0x80101018);}
    private void text(GuiGraphicsExtractor g,String value,int x,int y,int color){g.text(font,value,x,y-scroll,color);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float a){
        if(timeline!=null)timeline.sync();super.extractRenderState(g,x,y,a);
        if(Float.isFinite(anchorX) && anchorX>=0 && anchorX<=1 && anchorY>=0 && anchorY<=1){
            int ax=(int)(anchorX*width),ay=(int)(anchorY*height);
            g.fill(ax-4,ay,ax+5,ay+1,0xFF55FFFF);g.fill(ax,ay-4,ax+1,ay+5,0xFF55FFFF);
        }
        text(g,"Движения рук",4,4,0xFFFFFFFF);
        text(g,stages.isEmpty()?"Движение не задано":(selected+1)+". "+stage().name(),4,126,0xFFFFCC55);
        if(!stages.isEmpty()){
            text(g,dynamic?"В начале    В конце":"Весь этап",142,188,0xFFB5D8FF);
            for(int p=0;p<6;p++)text(g,PARAMETERS[p],4,206+p*20,0xFFFFFFFF);
        }
        text(g,status,4,452,0xFFB5D8FF);
        text(g,"Бирюзовая метка — выбранный контакт кисти.",4,466,0xFFAAAAAA);
    }
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event){
        if(!(getFocused() instanceof EditBox)){
            if(event.key()==32){ArmAnimationPreview.play(!ArmAnimationPreview.playing());rebuildWidgets();return true;}
            if(event.key()==262||event.key()==263){show(ArmAnimationPreview.millis()+(event.key()==262?10:-10));return true;}
        }return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x,double y,double sx,double sy){
        if(super.mouseScrolled(x,y,sx,sy))return true;if(x>W+8)return false;
        scroll=Math.clamp(scroll-(int)(sy*30),0,Math.max(0,484-height));rebuildWidgets();return true;
    }
    private final class Timeline extends AbstractSliderButton{
        Timeline(int x,int y,int w,int h){super(x,y,w,h,Component.empty(),0);sync();}
        void sync(){value=ArmAnimationPreview.duration()==0?0:ArmAnimationPreview.millis()/(double)ArmAnimationPreview.duration();updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(ArmAnimationPreview.millis()+" / "+ArmAnimationPreview.duration()+" мс"));}
        @Override protected void applyValue(){show((float)(value*ArmAnimationPreview.duration()));}
    }
}
