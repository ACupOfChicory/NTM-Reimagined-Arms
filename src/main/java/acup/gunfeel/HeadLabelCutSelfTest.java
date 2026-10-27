package acup.gunfeel;

import java.util.*;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Реальное выделение шеи верблюда, отсечение мышью, отмена и чтение файла. */
final class HeadLabelCutSelfTest {
    private static int frames,stage;
    private static boolean finished;
    private static void click(HeadLabelScreen screen,double x,double y) {
        var event=new MouseButtonEvent(x,y,new MouseButtonInfo(0,0));screen.mouseClicked(event,false);screen.mouseReleased(event);
    }
    static void frame(HeadLabelScreen screen) {
        if(finished || ++frames<40) return;
        try {
            if(stage==1) {
                finished=true;
                net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(),image->{
                    try(image) {
                        long cyan=Arrays.stream(image.getPixels()).filter(pixel->(pixel>>16&255)<90 && (pixel>>8&255)>230 && (pixel&255)>230).count();
                        require(cyan>5,"отсечённая область видна на GPU, бирюзовых пикселей="+cyan);
                        image.writeToFile(Path.of("../notes/head-labels/self-test/camel-cut.png"));
                        screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_BACKSPACE,0,0));require(screen.cuts.isEmpty(),"отмена восстанавливает целую деталь");
                    } catch(Exception e) { ClientSmoke.check(false,"Отсечение: "+e); }
                    Minecraft.getInstance().gui.setScreen(null);HeadLabels.suspended=true;ClientSmoke.finish();
                });
                return;
            }
            int index=-1;double span=-1;
            for(int i=0;i<screen.boxes.size();i++) {
                var box=screen.boxes.get(i);if(!box.part().contains("head") || box.part().contains("ear")) continue;
                double extent=Math.abs(box.uy())+Math.abs(box.vy())+Math.abs(box.wy());
                if(extent>span) { span=extent;index=i; }
            }
            require(index>=0 && span>.6,"найдена общая деталь головы и шеи верблюда");
            var box=screen.boxes.get(index);var data=HeadLabelGeometry.triangles(box);
            Vec3 head=null,neck=null;
            for(int at=0;at<data.length;at+=9) {
                var point=HeadLabelGeometry.point(box,data,at).add(HeadLabelGeometry.point(box,data,at+3)).add(HeadLabelGeometry.point(box,data,at+6)).scale(1.0/3);
                if(head==null || point.y>head.y) head=point;
                if(neck==null || point.y<neck.y) neck=point;
            }
            var h=screen.project(head);var n=screen.project(neck);
            click(screen,h.x,h.y);
            require(screen.labels.containsKey(index+":-1"),"ЛКМ выбирает общую деталь");
            double boundary=(h.y+n.y)*.5;
            screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_C,0,0));
            screen.mouseClicked(new MouseButtonEvent(h.x-60,boundary,new MouseButtonInfo(0,0)),false);
            screen.mouseReleased(new MouseButtonEvent(h.x+60,boundary,new MouseButtonInfo(0,0)));
            click(screen,h.x,h.y);
            var planes=screen.cuts.get(index+":-1");
            require(planes!=null && planes.size()==1,"линия и выбор стороны создают плоскость");
            require(HeadLabelCuts.contains(head,planes) && !HeadLabelCuts.contains(neck,planes),"голова сохранена, нижняя шея исключена");
            var loaded=new HeadLabelStore(Path.of(System.getProperty("ntmgunfeel.headLabelDir")));
            require(loaded.get(MobSmoke.current()).cuts().equals(screen.cuts),"отсечение переживает чтение датасета");
            stage=1;frames=0;return;
        } catch(Exception e) { ClientSmoke.check(false,"Отсечение: "+e); }
        finished=true;
        Minecraft.getInstance().gui.setScreen(null);HeadLabels.suspended=true;ClientSmoke.finish();
    }
    private static void require(boolean condition,String message) {
        ClientSmoke.check(condition,"Отсечение: "+message);if(!condition) throw new IllegalStateException(message);
    }
}
