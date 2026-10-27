package acup.gunfeel;

import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;

/** Проверка проекции и настоящих обработчиков мыши в игровом окне. */
final class HeadLabelSelfTest {
    private static int frames;
    private static int stage;
    private static boolean finished;
    static void frame(HeadLabelScreen screen) {
        if(Boolean.getBoolean("ntmgunfeel.headCutSelfTest")) { HeadLabelCutSelfTest.frame(screen);return; }
        if(!Boolean.getBoolean("ntmgunfeel.headLabelSelfTest") || finished || ++frames<40) return;
        try {
            if(stage==3) {
                require(Minecraft.getInstance().gui.screen()==screen && screen.isPauseScreen() && !screen.waiting(),"после переключения открыт готовый интерфейс разметки");
                finished=true;Minecraft.getInstance().gui.setScreen(null);HeadLabels.suspended=true;ClientSmoke.finish();return;
            }
            var candidates=screen.boxes.stream().filter(b->b.part().toLowerCase(java.util.Locale.ROOT).contains("head")).toList();
            require(!candidates.isEmpty(),"в модели скелета найдена настоящая деталь головы");
            var head=candidates.getFirst();
            var center=ModelContours.point(head,.5,.5,.5);
            var projected=screen.project(center);
            var hit=screen.pick(projected.x,projected.y);
            require(hit!=null && screen.boxes.get(hit.box()).part().toLowerCase(java.util.Locale.ROOT).contains("head"),"проекция головы выбирает голову, а не тело");
            var event=new MouseButtonEvent(projected.x,projected.y,new MouseButtonInfo(0,0));
            if(stage==1) {
                require(hit!=null && screen.boxes.get(hit.box()).part().toLowerCase(java.util.Locale.ROOT).contains("head"),"после поворота камеры мышь по-прежнему выбирает голову");
                screen.mouseClicked(event,false);screen.mouseReleased(event);
                screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER,0,0));
                require(HeadLabels.store.done(MobSmoke.current()),"Enter подтверждает разметку");
                stage=2;frames=0;return;
            }
            if(stage==2) {
                stage=4;
                net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(),image->{
                    try(image) {
                        long cyan=java.util.Arrays.stream(image.getPixels()).filter(pixel->(pixel>>16&255)<90 && (pixel>>8&255)>230 && (pixel&255)>230).count();
                        ClientSmoke.check(cyan>5,"Разметка: на настоящем GPU видна бирюзовая голова, пикселей="+cyan);
                        image.writeToFile(Path.of("../notes/head-labels/self-test/screen.png"));
                    } catch(Exception e) { ClientSmoke.check(false,"Разметка: снимок "+e); }
                    screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_RIGHT,0,0));
                    ClientSmoke.check(Minecraft.getInstance().gui.screen()==screen && screen.waiting() && !screen.isPauseScreen(),
                            "Разметка: стрелка сохраняет интерфейс и отпускает паузу для загрузки моба");
                    stage=3;frames=0;
                });
                return;
            }
            if(stage==4) return;
            screen.mouseClicked(event,false);screen.mouseReleased(event);
            require(screen.labels.size()==1,"ЛКМ выделяет деталь в настоящем экране");
            var saved=new HeadLabelStore(Path.of(System.getProperty("ntmgunfeel.headLabelDir")));
            require(saved.get(MobSmoke.current()).labels().equals(screen.labels),"черновик переживает чтение файла");
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_BACKSPACE,0,0));
            require(screen.labels.isEmpty(),"Backspace отменяет выделение");
            screen.mouseClicked(new MouseButtonEvent(projected.x-8,projected.y-8,new MouseButtonInfo(0,0)),false);
            screen.mouseReleased(new MouseButtonEvent(projected.x+8,projected.y+8,new MouseButtonInfo(0,0)));
            require(screen.labels.size()==1,"рамка выделяет голову без тела");
            var shift=new MouseButtonEvent(projected.x,projected.y,new MouseButtonInfo(0,GLFW.GLFW_MOD_SHIFT));
            screen.mouseClicked(shift,false);screen.mouseReleased(shift);
            require(screen.labels.isEmpty(),"Shift и ЛКМ удаляют выделение");
            var mc=Minecraft.getInstance();
            double modeX=6+2*(mc.getWindow().getGuiScaledWidth()-12)/5+35,modeY=mc.getWindow().getGuiScaledHeight()-14;
            var mode=new MouseButtonEvent(modeX,modeY,new MouseButtonInfo(0,0));
            screen.mouseClicked(mode,false);screen.mouseReleased(mode);
            screen.mouseClicked(event,false);screen.mouseReleased(event);
            require(screen.labels.size()==1 && screen.labels.keySet().stream().noneMatch(key->key.endsWith(":-1")),"режим граней выделяет треугольник анонимной модели");
            screen.mouseClicked(mode,false);screen.mouseReleased(mode);
            screen.mouseClicked(event,false);screen.mouseReleased(event);
            var decision=new HeadLabelStore(Path.of(System.getProperty("ntmgunfeel.headLabelDir"))).get(MobSmoke.current());
            var file=Path.of(System.getProperty("ntmgunfeel.headLabelDir"),"samples",decision.sample(),"model.json");
            require(Files.readString(file).contains("triangles"),"геометрия сохранена вместе с разметкой");
            screen.mouseDragged(new MouseButtonEvent(projected.x,projected.y,new MouseButtonInfo(1,0)),50,0);
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_BACKSPACE,0,0));
            stage=1;frames=0;return;
        } catch(Exception e) { ClientSmoke.check(false,"Разметка: "+e); }
        finished=true;
        Minecraft.getInstance().gui.setScreen(null);
        HeadLabels.suspended=true;
        ClientSmoke.finish();
    }
    private static void require(boolean condition,String message) {
        ClientSmoke.check(condition,"Разметка: "+message);
        if(!condition) throw new IllegalStateException(message);
    }
}
