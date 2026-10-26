package acup.gunfeel;

import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Проверка в настоящем окне: клавиши, заметка, отмена, файл и снимок GPU. */
final class MobReviewSelfTest {
    private static final boolean ENABLED=Boolean.getBoolean("ntmgunfeel.mobReviewSelfTest");
    private static final String ID="minecraft:skeleton",NOTE="Руки и ноги: тест сохранения заметки";
    private static int stage;
    private static long started;

    static void frame() {
        if(!ENABLED || MobSmoke.busy()) return;
        if(started==0) started=System.nanoTime();
        try {
            if(System.nanoTime()-started>90_000_000_000L) throw new IllegalStateException("таймаут самопроверки обзора");
            var mc=Minecraft.getInstance();
            if(stage==0) {
                var cases=MobSmoke.cases();
                require(cases.size()>12 && cases.contains("minecraft:ender_dragon") && cases.contains("minecraft:wither")
                        && cases.contains("hbm:entity_pigeon") && cases.contains("hbm:entity_cyber_crab"),"полный реестр с боссами: "+cases.size());
                MobSmoke.select(cases.indexOf(ID)); stage=1;
            } else if(stage==1 && MobSmoke.current().equals(ID) && MobSmoke.hasPose()) {
                press(GLFW.GLFW_KEY_UP);
                require(MobReview.store().get(ID).status().equals("ok"),"↑ сохраняет правильно"); stage=2;
            } else if(stage==2) {
                press(GLFW.GLFW_KEY_BACKSPACE);
                require(MobReview.store().get(ID)==null && MobSmoke.current().equals(ID),"Backspace отменяет оценку и возвращает моба");
                stage=3;
            } else if(stage==3 && MobSmoke.hasPose()) {
                press(GLFW.GLFW_KEY_N); stage=4;
            } else if(stage==4 && mc.gui.screen()!=null) {
                var screen=mc.gui.screen();
                var input=screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
                input.setValue(NOTE); screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER,0,0));
                require(MobReview.store().get(ID).note().equals(NOTE),"N и Enter сохраняют русскую заметку"); stage=5;
            } else if(stage==5 && MobSmoke.hasPose()) {
                press(GLFW.GLFW_KEY_DOWN);
                require(MobReview.store().get(ID).status().equals("bad"),"↓ сохраняет ошибку"); stage=6;
            } else if(stage==6) {
                if(!Files.isDirectory(MobReview.directory().resolve("screenshots"))) return;
                boolean png;
                try(var files=Files.list(MobReview.directory().resolve("screenshots"))) { png=files.anyMatch(p -> p.toString().endsWith(".png")); }
                if(!png) return;
                var loaded=new MobReviewStore(MobReview.directory().resolve("reviews.tsv"));
                require(loaded.get(ID).status().equals("bad") && loaded.get(ID).note().equals(NOTE),"оценка переживает загрузку файла");
                require(png,"сохранён снимок настоящего кадра");
                press(GLFW.GLFW_KEY_F6);
                stage=7;
            } else if(stage==7) {
                press(GLFW.GLFW_KEY_F6);
                require(MobSmoke.current().equals(ID),"фильтр ошибок возвращает отмеченного моба");
                stage=8;
            } else if(stage==8) { ClientSmoke.finish(); stage=9; }
        } catch(Exception e) {
            ClientSmoke.check(false,"Обзор: "+e); ClientSmoke.finish(); stage=8;
        }
    }
    private static void press(int key) { NeoForge.EVENT_BUS.post(new InputEvent.Key(new KeyEvent(key,0,0),GLFW.GLFW_PRESS)); }
    private static void require(boolean ok,String text) {
        ClientSmoke.check(ok,"Обзор: "+text);
        if(!ok) throw new IllegalStateException(text);
    }
}
