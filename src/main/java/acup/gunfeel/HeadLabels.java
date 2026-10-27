package acup.gunfeel;

import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

final class HeadLabels {
    static final boolean ENABLED=Boolean.getBoolean("ntmgunfeel.headLabels");
    static HeadLabelStore store;
    static boolean suspended;
    private HeadLabels() {}
    static void init() {
        if(!ENABLED) return;
        try { store=new HeadLabelStore(Path.of(System.getProperty("ntmgunfeel.headLabelDir"))); }
        catch(Exception e) { throw new IllegalStateException("Не удалось открыть датасет голов",e); }
    }
    static int resume() {
        for(int i=0;i<MobSmoke.cases().size();i++) if(!store.done(MobSmoke.cases().get(i))) return i;
        return 0;
    }
    static void frame() {
        var current=Minecraft.getInstance().gui.screen();
        if(!ENABLED || suspended || current!=null && !(current instanceof HeadLabelScreen screen && screen.waiting()) || !MobSmoke.reviewReady()) return;
        try { Minecraft.getInstance().gui.setScreen(new HeadLabelScreen()); }
        catch(Exception e) { suspended=true; GunFeel.LOGGER.error("Не удалось открыть разметку головы",e); }
    }
    static void next(int direction,boolean unlabeled) {
        int size=MobSmoke.cases().size();
        for(int step=1;step<=size;step++) {
            int index=Math.floorMod(MobSmoke.index()+direction*step,size);
            if(!unlabeled || !store.done(MobSmoke.cases().get(index))) { MobSmoke.select(index); return; }
        }
        suspended=true;
    }
    static void overlay(List<ModelDebugRender.Shape> shapes,int id,Entity entity) {
        if(Minecraft.getInstance().gui.screen() instanceof HeadLabelScreen screen && screen.entity==entity) screen.overlay(shapes,id);
    }
}
