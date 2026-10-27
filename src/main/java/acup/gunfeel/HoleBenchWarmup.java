package acup.gunfeel;

import java.lang.reflect.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// Вращаем камеру всё время прогрева. Начинаем стрельбу только после двух
// минут и пустых очередей построения; после возврата на цель ждём ещё раз.
final class HoleBenchWarmup {
    private static boolean rotate;
    private static long centeredAt,quietAt,nextSample,nextLog;
    private static boolean quiet;
    private HoleBenchWarmup() {}
    static void reset(boolean first) {
        rotate=first; centeredAt=quietAt=nextSample=nextLog=0; quiet=false;
    }
    private static Object field(Object owner,String name) throws ReflectiveOperationException {
        var field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Object call(Object owner,String method) throws ReflectiveOperationException {
        return owner.getClass().getMethod(method).invoke(owner);
    }
    static boolean ready(long now,long since) {
        var mc=Minecraft.getInstance();
        double elapsed=(now-since)/1e9;
        if(elapsed>600) { HoleBenchMetrics.failure="Voxy не стабилизировался за 10 минут"; return false; }
        mc.player.setYRot(rotate?(float)(elapsed*30%360):0);
        if(now>=nextSample) {
            nextSample=now+1_000_000_000L;
            try {
                var holder=Class.forName("me.cortex.voxy.client.core.IVoxyRenderSystemHolder");
                var renderer=holder.getMethod("getNullable").invoke(null);
                if(renderer==null) throw new IllegalStateException("Рендерер Voxy ещё не создан");
                int geometry=((Number)call(field(renderer,"renderGen"),"getTaskCount")).intValue();
                int models=((Number)call(field(renderer,"modelService"),"getProcessingCount")).intValue();
                var instance=Class.forName("me.cortex.voxy.commonImpl.VoxyCommon").getMethod("getInstance").invoke(null);
                int ingest=((Number)call(call(instance,"getIngestService"),"getTaskCount")).intValue();
                quiet=geometry==0 && models==0 && ingest==0;
                if(!quiet) quietAt=0; else if(quietAt==0) quietAt=now;
                String status="Прогрев Voxy: "+(int)elapsed+" с · "+(rotate?"вращение":"вид на дверь")+
                        " · очереди "+geometry+"/"+models+"/"+ingest;
                mc.gui.hud.setOverlayMessage(Component.literal(status),false);
                if(now>=nextLog) { nextLog=now+5_000_000_000L; GunFeel.LOGGER.info("[holebench] {}",status); }
            } catch(ReflectiveOperationException|RuntimeException ex) {
                HoleBenchMetrics.failure="Не удалось подтвердить стабилизацию Voxy: "+ex; return false;
            }
        }
        if(rotate) {
            if(elapsed>=120 && quiet && now-quietAt>=5_000_000_000L) {
                rotate=false; centeredAt=now; quietAt=0; quiet=false; nextSample=0;
                mc.player.setYRot(0);
            }
            return false;
        }
        if(centeredAt==0) centeredAt=since;
        boolean ready=quiet && now-quietAt>=5_000_000_000L && now-centeredAt>=5_000_000_000L;
        if(ready) mc.gui.hud.setOverlayMessage(Component.empty(),false);
        return ready;
    }
}
