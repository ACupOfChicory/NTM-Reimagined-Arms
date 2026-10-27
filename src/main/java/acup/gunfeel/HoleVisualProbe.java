package acup.gunfeel;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

// В одном кадре рисуем прежний и кэшированный пути поверх одной копии мира.
// Сравниваем только область, которую следы действительно изменили.
final class HoleVisualProbe {
    private static final int[][] PIXELS=new int[3][];
    private static boolean started;
    static volatile boolean finished;
    private HoleVisualProbe() {}
    static void capture(Runnable reference,Runnable cached) {
        if(started) return; started=true;
        var main=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        Screenshot.takeScreenshot(main,image -> {
            try(image) { PIXELS[0]=image.getPixels(); image.writeToFile(java.nio.file.Path.of("../notes/hole-bench/visual-base.png")); }
            catch(Exception ex) { failed(ex); }
            compare();
        });
        for(int mode=0;mode<2;mode++) {
            var target=new TextureTarget("Hole comparison",main.width,main.height,main.useDepth,
                    main.getDepthTexture().getFormat().hasStencilAspect(),main.getColorTexture().getFormat());
            var encoder=RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(main.getColorTexture(),target.getColorTexture(),0,0,0,0,0,main.width,main.height);
            target.copyDepthFrom(main);
            var color=RenderSystem.outputColorTextureOverride; var depth=RenderSystem.outputDepthTextureOverride;
            try {
                RenderSystem.outputColorTextureOverride=target.getColorTextureView(); RenderSystem.outputDepthTextureOverride=target.getDepthTextureView();
                (mode==0?reference:cached).run();
                final int index=mode+1;
                Screenshot.takeScreenshot(target,image -> {
                    try(image) {
                        PIXELS[index]=image.getPixels();
                        image.writeToFile(java.nio.file.Path.of("../notes/hole-bench/visual-"+(index==1?"reference":"cached")+".png"));
                    } catch(Exception ex) { failed(ex); }
                    finally { target.destroyBuffers(); }
                    compare();
                });
            } finally { RenderSystem.outputColorTextureOverride=color; RenderSystem.outputDepthTextureOverride=depth; }
        }
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
