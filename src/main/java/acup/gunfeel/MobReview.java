package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Управление существует только в отдельном runReviewMobs. */
final class MobReview {
    private record Change(String id,MobReviewStore.Decision before) {}
    private static final ArrayDeque<Change> UNDO=new ArrayDeque<>();
    private static final boolean SWEEP=Boolean.getBoolean("ntmgunfeel.mobRenderSweep");
    private static MobReviewStore store;
    private static Path directory;
    private static int filter;
    private static String message="",automatic="Ожидание модели";
    private static boolean ready;
    private static int checks,failures;
    private static String pendingEvidence;
    private static int evidenceFrames;

    private MobReview() {}
    static void init() {
        HeadLabels.init();
        try {
            directory=Path.of(System.getProperty("ntmgunfeel.mobReviewDir"));
            store=new MobReviewStore(directory.resolve("reviews.tsv"));
            Minecraft.getInstance().getWindow().setTitle(HeadLabels.ENABLED?"Разметка: "+HeadLabels.title()+" — NTM":"Просмотр мобов — NTM");
            ready=true;
            NeoForge.EVENT_BUS.addListener(InputEvent.Key.class,e -> {
                if(e.getAction()==GLFW.GLFW_PRESS && Minecraft.getInstance().gui.screen()==null) key(e.getKey());
            });
            NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class,e -> draw(e.getGuiGraphics()));
        } catch(Exception e) { throw new IllegalStateException("Не удалось открыть оценки: "+e,e); }
    }

    static int resume() {
        int requested=MobSmoke.cases().indexOf(System.getProperty("ntmgunfeel.mobStart",""));
        if(requested>=0) return requested;
        if(HeadLabels.ENABLED) return HeadLabels.resume();
        for(int i=0;i<MobSmoke.cases().size();i++) if(!store.reviewed(MobSmoke.cases().get(i))) return i;
        for(int i=0;i<MobSmoke.cases().size();i++) {
            var decision=store.get(MobSmoke.cases().get(i));
            if(decision!=null && decision.status().equals("bad")) {
                filter=2; message="Первый обзор завершён. Повторная проверка отмеченных ошибок"; return i;
            }
        }
        for(int i=0;i<MobSmoke.cases().size();i++) {
            var decision=store.get(MobSmoke.cases().get(i));
            if(decision!=null && decision.status().equals("skip")) {
                filter=3; message="Остались отложенные мобы"; return i;
            }
        }
        message="Все мобы уже просмотрены. F6 — фильтр ошибок";
        return 0;
    }
    static void autoStatus(String text) { automatic=text; }
    static void checked(boolean ok,String text) {
        checks++;
        if(!ok) failures++;
        automatic="Автоматические проверки: "+checks+" · ошибок: "+failures;
    }
    static void frame() {
        HeadSmoke.frame();
        if(HeadLabels.ENABLED) { HeadLabels.frame(); return; }
        if(pendingEvidence!=null) {
            if(--evidenceFrames<=0) {
                try { evidence(pendingEvidence); }
                catch(Exception e) { message="Оценка сохранена; не удалось сохранить кадр: "+e.getMessage(); }
                pendingEvidence=null;
                navigate(1,filter<2);
            }
            return;
        }
        if(ready) MobReviewSelfTest.frame();
    }

    static void key(int key) {
        if(HeadLabels.ENABLED) {
            if(key==GLFW.GLFW_KEY_H) HeadLabels.suspended=false;
            if(key==GLFW.GLFW_KEY_G) search();
            if(key==GLFW.GLFW_KEY_RIGHT) HeadLabels.next(1,false);
            if(key==GLFW.GLFW_KEY_LEFT) HeadLabels.next(-1,false);
            return;
        }
        if(SWEEP) return;
        if(!ready || MobSmoke.busy() || MobSmoke.index()<0 || pendingEvidence!=null) return;
        try {
            switch(key) {
                case GLFW.GLFW_KEY_RIGHT -> navigate(1,false);
                case GLFW.GLFW_KEY_LEFT -> navigate(-1,false);
                case GLFW.GLFW_KEY_UP -> rate("ok");
                case GLFW.GLFW_KEY_DOWN -> rate("bad");
                case GLFW.GLFW_KEY_SPACE -> {
                    ((acup.gunfeel.mixin.mc.KeyMappingAccess)Minecraft.getInstance().options.keyJump).gunfeel$setIsDown(false);
                    rate("skip");
                }
                case GLFW.GLFW_KEY_BACKSPACE -> undo();
                case GLFW.GLFW_KEY_N -> Minecraft.getInstance().gui.setScreen(new NoteScreen(false));
                case GLFW.GLFW_KEY_G -> Minecraft.getInstance().gui.setScreen(new NoteScreen(true));
                case GLFW.GLFW_KEY_HOME -> MobSmoke.camera();
                case GLFW.GLFW_KEY_PAGE_UP -> MobSmoke.rotate(45);
                case GLFW.GLFW_KEY_PAGE_DOWN -> MobSmoke.rotate(-45);
                case GLFW.GLFW_KEY_F6 -> {
                    filter=(filter+1)%4;
                    navigate(1,false);
                }
                default -> {}
            }
        } catch(Exception e) { message="Не сохранено: "+e.getMessage(); GunFeel.LOGGER.error("[mobreview]",e); }
    }

    static void search() { Minecraft.getInstance().gui.setScreen(new NoteScreen(true)); }

    private static void rate(String status) throws Exception {
        String id=MobSmoke.current();
        var old=store.get(id);
        store.set(id,new MobReviewStore.Decision(status,old==null?"":old.note()));
        UNDO.push(new Change(id,old));
        message=id+": "+label(status)+" сохранено";
        if(status.equals("bad")) {
            pendingEvidence=id; evidenceFrames=3;
            return;
        }
        navigate(1,filter<2);
    }

    private static void evidence(String id) throws Exception {
        Files.createDirectories(directory);
        String name=id.replaceAll("[^a-zA-Z0-9_.-]","_")+"-"+System.currentTimeMillis();
        Files.writeString(directory.resolve(name+".txt"),MobSmoke.evidence()+"\nnote="+store.get(id).note());
        Screenshot.grab(directory.toFile(),name+".png",Minecraft.getInstance().gameRenderer.mainRenderTarget(),1,
                result -> GunFeel.LOGGER.info("[mobreview] {}",result.getString()));
    }

    private static void undo() throws Exception {
        if(UNDO.isEmpty()) { message="Нет оценки для отмены в этом запуске"; return; }
        Change change=UNDO.peek();
        store.set(change.id(),change.before());
        UNDO.pop(); filter=0;
        select(MobSmoke.cases().indexOf(change.id()));
        message="Отменена оценка "+change.id();
    }

    private static void navigate(int direction,boolean unrated) {
        int size=MobSmoke.cases().size();
        for(int step=1;step<=size;step++) {
            int next=Math.floorMod(MobSmoke.index()+direction*step,size);
            String id=MobSmoke.cases().get(next);
            var decision=store.get(id);
            boolean match=unrated?!store.reviewed(id):filter==0 || filter==1 && !store.reviewed(id)
                    || filter==2 && decision!=null && decision.status().equals("bad")
                    || filter==3 && decision!=null && decision.status().equals("skip");
            if(match) { select(next); return; }
        }
        message="В этом списке больше нет мобов. F6 — сменить фильтр";
    }

    private static void select(int index) {
        checks=failures=0;
        automatic="Ожидание модели";
        MobSmoke.select(index);
    }

    private static String label(String status) {
        return switch(status) { case "ok" -> "правильно"; case "bad" -> "ошибка"; case "skip" -> "отложено"; default -> "не оценено"; };
    }

    private static void draw(GuiGraphicsExtractor g) {
        if(HeadLabels.ENABLED) return;
        if(!ready) return;
        var mc=Minecraft.getInstance();
        String id=MobSmoke.current();
        var decision=store.get(id);
        String name=MobSmoke.target()==null?"Сущность пока не появилась":
                (MobCatalog.baby(id)?"Детёныш: ":"")+MobSmoke.target().getName().getString();
        String[] lines={(SWEEP?"Автопроверка — оценки отключены  ":"Просмотр мобов  ")+(MobSmoke.index()+1)+" / "+MobSmoke.cases().size()+"  ·  "+name,
                id+"  ·  "+(decision==null?"не оценено":label(decision.status()))+"  ·  фильтр: "+new String[]{"все","не оценённые","ошибки","отложенные"}[filter],
                "Правильно: "+store.count("ok")+" · Ошибки: "+store.count("bad")+" · Отложено: "+store.count("skip"),
                MobSmoke.status(),MobSmoke.busy()?"Создание / автоматическая проверка…":automatic,
                decision!=null && !decision.note().isBlank()?"Заметка: "+decision.note():message};
        g.fill(3,3,g.guiWidth()-3,68,0xB0181B25);
        for(int i=0;i<lines.length;i++) g.text(mc.font,mc.font.plainSubstrByWidth(lines[i],g.guiWidth()-16),8,7+i*10,
                i==0?0xFFFFCC55:i==4?0xFF9DCFFF:0xFFF2EFF8);
        int bottom=g.guiHeight()-34;
        g.fill(3,bottom-3,g.guiWidth()-3,g.guiHeight()-3,0xC0181B25);
        g.text(mc.font,"← → — листать   ↑ — правильно   ↓ — ошибка + кадр   Пробел — отложить",8,bottom,0xFFFFCC55);
        g.text(mc.font,"G — найти · N — заметка · Backspace — отмена · F6 — фильтр · Home — камера · PgUp/PgDn — поворот",8,bottom+11,0xFFD7D2E8);
    }

    static MobReviewStore store() { return store; }
    static Path directory() { return directory; }

    private static final class NoteScreen extends Screen {
        private EditBox input;
        private final String id=MobSmoke.current();
        private final boolean search;
        NoteScreen(boolean search) { super(Component.literal(search?"Поиск по ID или имени моба":"Заметка о мобе")); this.search=search; }
        @Override protected void init() {
            input=addRenderableWidget(new EditBox(font,20,height-74,width-40,20,Component.literal("Что не так")));
            input.setMaxLength(1000);
            var old=store.get(id);
            input.setValue(search || old==null?"":old.note());
            addRenderableWidget(Button.builder(Component.literal(search?"Найти (Enter)":"Сохранить (Enter)"),b -> save()).bounds(20,height-48,190,20).build());
            setInitialFocus(input);
        }
        private void save() {
            try {
                if(search) {
                    String query=input.getValue().strip().toLowerCase(java.util.Locale.ROOT);
                    if(query.isEmpty()) return;
                    var matches=MobSmoke.cases().stream().filter(key -> key.toLowerCase(java.util.Locale.ROOT).contains(query)
                            || net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(MobCatalog.type(key)))
                            .getDescription().getString().toLowerCase(java.util.Locale.ROOT).contains(query)).toList();
                    if(matches.isEmpty()) { input.setMessage(Component.literal("Моб не найден")); return; }
                    String selected=matches.contains(query)?query:matches.getFirst();
                    filter=0; select(MobSmoke.cases().indexOf(selected)); onClose(); return;
                }
                var old=store.get(id);
                store.set(id,new MobReviewStore.Decision(old==null?"note":old.status(),input.getValue()));
                message="Заметка сохранена"; onClose();
            } catch(Exception e) { message="Не сохранено: "+e.getMessage(); }
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if(event.key()==GLFW.GLFW_KEY_ENTER || event.key()==GLFW.GLFW_KEY_KP_ENTER) { save(); return true; }
            return super.keyPressed(event);
        }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float partial) { g.fill(0,height-102,width,height,0xD0181B25); }
        @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float partial) {
            super.extractRenderState(g,x,y,partial);
            g.text(font,title,20,height-95,0xFFFFCC55);
        }
    }
}
