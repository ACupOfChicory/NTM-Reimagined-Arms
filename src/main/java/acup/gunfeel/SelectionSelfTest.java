package acup.gunfeel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;

/** Настоящая рамка и GPU-пиксели, отдельно от пользовательского датасета. */
final class SelectionSelfTest {
    private static int frames,stage,baselineOrange;
    private static boolean pending;
    static void frame(HeadLabelScreen screen) {
        if(pending || ++frames<30) return;
        try {
            var mc=Minecraft.getInstance();int width=mc.getWindow().getGuiScaledWidth(),height=mc.getWindow().getGuiScaledHeight();
            if(stage==0) {
                rectangle(screen,width,height,true);
                stage=4;frames=0;return;
            }
            pending=true;final int mode=stage;
            net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(),image->{
                try(image) {
                    int cyan=0,orange=0;int[] pixels=image.getPixels();double scale=image.getWidth()/(double)width;
                    for(int y=(int)(50*scale);y<(int)((height-80)*scale);y++) for(int x=5;x<image.getWidth()-5;x++) {
                        int p=pixels[y*image.getWidth()+x]&0xFFFFFF;
                        if((p>>16&255)<90 && (p>>8&255)>240 && (p&255)>240) cyan++;
                        if(p==0xFFCC55) orange++;
                    }
                    ClientSmoke.check(mode==3 || mode==4?cyan==0 && orange>30:cyan>30 && orange==0,
                            "GPU выделение: этап="+mode+", бирюзовых="+cyan+", жёлтых="+orange);
                    if(mode==4) baselineOrange=orange;
                    if(mode==1) ClientSmoke.check(cyan>=baselineOrange*.9,"Бирюзовые передние линии не теряются: до="+baselineOrange+", после="+cyan);
                    image.writeToFile(java.nio.file.Path.of("../notes/selection-self-test/gpu-"+mode+".png"));
                } catch(Exception ex) { ClientSmoke.check(false,"GPU выделение: "+ex); }
                if(mode==4) {
                    rectangle(screen,width,height,false);
                    ClientSmoke.check(screen.labels.size()==screen.boxes.size(),"Рамка выбрала всю модель: "+screen.labels.size()+" / "+screen.boxes.size());
                    stage=1;
                } else if(mode==1) {
                    screen.mouseDragged(new MouseButtonEvent(width*.5,height*.5,new MouseButtonInfo(1,0)),70,0);
                    stage=2;
                } else if(mode==2) { rectangle(screen,width,height,true);stage=3; }
                else { mc.gui.setScreen(null);HeadLabels.suspended=true;ClientSmoke.finish(); }
                frames=0;pending=false;
            });
        } catch(Exception ex) { ClientSmoke.check(false,"Выделение: "+ex);HeadLabels.suspended=true;ClientSmoke.finish(); }
    }
    private static void rectangle(HeadLabelScreen screen,int width,int height,boolean remove) {
        var info=new MouseButtonInfo(0,remove?GLFW.GLFW_MOD_SHIFT:0);
        screen.mouseClicked(new MouseButtonEvent(8,50,info),false);
        screen.mouseReleased(new MouseButtonEvent(width-8,height-80,info));
    }
}
