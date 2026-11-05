package acup.gunfeel;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

// В одном кадре рисуем прежний и кэшированный пути поверх одной копии мира.
// Сравниваем только область, которую следы действительно изменили.
// Копия порта (notes/ports/de-1.21.1.md, «Копии»): в 1.21.1 нет GpuDevice и подмены выходной текстуры. Копия кадра
// делается blit кадрового буфера, рисование идёт в свой TextureTarget, снимок синхронный и в файлы не пишется:
// сравнение только числами.
final class HoleVisualProbe {
    private static final int[][] PIXELS=new int[3][];
    private static boolean started;
    static volatile boolean finished;
    private HoleVisualProbe() {}
    static void capture(Runnable reference,Runnable cached) {
        if(started) return; started=true;
        var main=Minecraft.getInstance().getMainRenderTarget();
        try(NativeImage image=Screenshot.takeScreenshot(main)) {
            PIXELS[0]=acup.gunfeel.port.Mc.pixels(image);
        } catch(Exception ex) { failed(ex); }
        for(int mode=0;mode<2;mode++) {
            var target=new TextureTarget(main.width,main.height,true,Minecraft.ON_OSX);
            try {
                GlStateManager._glBindFramebuffer(36008,main.frameBufferId);
                GlStateManager._glBindFramebuffer(36009,target.frameBufferId);
                GlStateManager._glBlitFrameBuffer(0,0,main.width,main.height,0,0,main.width,main.height,16384,9728);
                target.copyDepthFrom(main);
                target.bindWrite(true);
                (mode==0?reference:cached).run();
                main.bindWrite(true);
                final int index=mode+1;
                try(NativeImage image=Screenshot.takeScreenshot(target)) {
                    PIXELS[index]=acup.gunfeel.port.Mc.pixels(image);
                } catch(Exception ex) { failed(ex); }
            // destroyBuffers привязывает framebuffer 0: основная цель возвращается после него
            } finally { target.destroyBuffers(); main.bindWrite(true); }
        }
        compare();
    }
    private static void failed(Exception ex) { HoleBenchMetrics.failure="GPU-проверка следов: "+ex; finished=true; }
    private static synchronized void compare() {
        if(finished || PIXELS[0]==null || PIXELS[1]==null || PIXELS[2]==null) return;
        long affected=0,different=0,error=0;
        for(int i=0;i<PIXELS[0].length;i++) {
            int base=PIXELS[0][i],a=PIXELS[1][i],b=PIXELS[2][i];
            if(delta(a,base)<=2 && delta(b,base)<=2) continue;
            affected++; if(delta(a,b)>2) different++;
            for(int shift:new int[]{0,8,16}) error+=Math.abs((a>>shift&255)-(b>>shift&255));
        }
        double fraction=different/(double)Math.max(1,affected),average=error/(3.0*Math.max(1,affected));
        ClientSmoke.check(affected>0 && fraction<=.05 && average<=1,"GPU-сравнение следов: область="+affected+
                ", различающихся пикселей="+different+", доля="+fraction+", средняя ошибка канала="+average);
        finished=true;
    }
    private static int delta(int a,int b) {
        return Math.max(Math.abs((a&255)-(b&255)),Math.max(Math.abs((a>>8&255)-(b>>8&255)),Math.abs((a>>16&255)-(b>>16&255))));
    }
}
