package acup.gunfeel;

import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

// The arm editor screen in a real client (-ParmScreenSelfTest): for five guns every control of the model has its
// button (label, grey or not, tooltip), the buttons fit the screen and do not overlap, every active button does
// something, and "what moves the hand" is what the game draws, frame by frame, with NTM's real buses.
final class ArmScreenCheck {
    private static final String[] GUNS = {"hbm:gun_autoshotgun_sexy", "hbm:gun_g3", "hbm:gun_maresleg", "hbm:gun_double_barrel",
            "hbm:gun_autoshotgun_heretic", "hbm:gun_spas12"};
    private static final int SAMPLE = 100;
    private static final Deque<Runnable> TODO = new ArrayDeque<>();
    private static final Map<String, Integer> WRONG = new TreeMap<>();
    private static ArmEditorScreen screen;
    // compared: frames where the game drew an arm and the editor was asked; slots: hand-frames sampled
    private static int compared, slots, pressedAll;
    private static boolean started;

    private ArmScreenCheck() {
    }

    static void frame() {
        if (!started) {
            started = true;
            for (String gun : GUNS) plan(gun);
            TODO.add(ArmScreenCheck::done);
        }
        var next = TODO.poll();
        if (next != null) next.run();
    }

    private static void plan(String gun) {
        TODO.add(() -> {
            var mc = Minecraft.getInstance();
            var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(gun));
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
            Config.select(gun);
            screen = new ArmEditorScreen(null, item, gun);
            mc.gui.setScreen(screen);
        });
        for (int i = 0; i < 10; i++) TODO.add(() -> {});
        TODO.add(() -> {
            var m = screen.model();
            ClientSmoke.check(!m.clipList().isEmpty(), gun + ": редактор нашёл анимации оружия: " + m.clipList().size());
            ClientSmoke.check(ArmAnimationPreview.active(), gun + ": предпросмотр включён");
            layout(gun + " · " + m.page.label);
            if (gun.endsWith("sexy")) {
                m.seek(Math.min(1500, m.duration()));
                screen.refresh();
            }
        });
        if (gun.endsWith("sexy")) TODO.add(() -> {});
        if (gun.endsWith("sexy")) TODO.add(() -> shot("arm-screen-check.png"));
        // what moves each hand, compared with what the game drew, in every animation of the gun
        TODO.add(() -> {
            var m = screen.model();
            List<Runnable> steps = new ArrayList<>();
            for (int c = 0; c < m.clipList().size(); c++) {
                int clip = c;
                for (int t = 0; t <= m.clipList().get(c).ms(); t += SAMPLE) {
                    int time = t;
                    steps.add(() -> {
                        m.clip = clip;
                        m.playing = false;
                        m.seek(time);
                        screen.refresh();
                    });
                    steps.add(() -> {
                        ClientSmoke.drawnBranch[0] = null;
                        ClientSmoke.drawnBranch[1] = null;
                    });
                    steps.add(() -> compare(gun, m));
                }
            }
            // run them right after this step, in order
            for (int i = steps.size() - 1; i >= 0; i--) TODO.addFirst(steps.get(i));
        });
        TODO.add(() -> pressAll(gun + " · " + ArmEditor.Page.TIME.label));
        // the settings pages: each laid out and pressed through; a slider dragged on one of them
        for (var page : List.of(ArmEditor.Page.KEY, ArmEditor.Page.GRIP, ArmEditor.Page.ARM, ArmEditor.Page.BOTH, ArmEditor.Page.FILE)) {
            TODO.add(() -> {
                var b = screen.button("page." + page.name().toLowerCase(Locale.ROOT));
                ClientSmoke.check(b != null && b.active, gun + ": есть вкладка «" + page.label + "»");
                if (b != null) b.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                ClientSmoke.check(screen.model().page == page, gun + ": вкладка «" + page.label + "» открыла свою страницу");
                if (page == ArmEditor.Page.KEY) ClientSmoke.check(true, gun + (atKey(screen.model()) ? ": страница «Ключ» на ключе"
                        : ": ключей в анимациях нет, страница «Ключ» проверена без ключа"));
                screen.refresh();
            });
            TODO.add(() -> {});
            if (gun.endsWith("sexy")) TODO.add(() -> shot("arm-screen-" + page.name().toLowerCase(Locale.ROOT) + ".png"));
            TODO.add(() -> {
                layout(gun + " · " + page.label);
                pressAll(gun + " · " + page.label);
                if (page == ArmEditor.Page.BOTH) drag(gun);
                if (page == ArmEditor.Page.FILE) exported(gun);
                if (page == ArmEditor.Page.GRIP) typedNumber(gun);
            });
            // the key page's other views: on the key it stands on, then the settings of the carrying, releasing and
            // going-round tracks, in an animation where the track has them
            if (page == ArmEditor.Page.KEY) {
                for (var v : List.of(ArmEditor.KeyView.APPROACH, ArmEditor.KeyView.TRACK)) TODO.add(() -> view(gun, v));
                for (var t : List.of(ArmEditor.Track.CARRY, ArmEditor.Track.RELEASE, ArmEditor.Track.AROUND))
                    for (var v : t == ArmEditor.Track.CARRY ? List.of(ArmEditor.KeyView.TRACK, ArmEditor.KeyView.HOLD) : List.of(ArmEditor.KeyView.TRACK)) {
                        TODO.add(() -> {
                            var m = screen.model();
                            m.track = t;
                            m.hand = 1;
                            m.clip = 0;
                            for (int c = 0; c < m.clipList().size(); c++) if (m.clipList().get(c).name().equals("RELOAD")) m.clip = c;
                            m.playing = false;
                            m.seek(m.duration() / 2F);
                            screen.refresh();
                        });
                        TODO.add(() -> view(gun, v));
                        if (gun.endsWith("sexy") && t == ArmEditor.Track.CARRY && v == ArmEditor.KeyView.TRACK) {
                            TODO.add(() -> {});
                            TODO.add(() -> shot("arm-screen-carry.png"));
                        }
                    }
                // the reload from an empty magazine, as NTM plays it (SPAS-12): its own keys are the ones shown
                TODO.add(() -> {
                    var m = screen.model();
                    int empty = -1;
                    for (int c = 0; c < m.clipList().size(); c++) if (m.clipList().get(c).empty()) empty = c;
                    if (gun.endsWith("spas12")) ClientSmoke.check(empty >= 0, gun + ": есть «Перезарядка из пустого»");
                    if (empty < 0) return;
                    m.clip = empty;
                    m.hand = 1;
                    m.track = ArmEditor.Track.CARRY;
                    m.playing = false;
                    m.seek(m.duration() / 2F);
                    screen.refresh();
                });
                TODO.add(() -> {
                    var m = screen.model();
                    if (m.clip() == null || !m.clip().empty()) return;
                    view(gun, ArmEditor.KeyView.TRACK);
                    m.keyView = ArmEditor.KeyView.TRACK;
                    screen.refresh();
                    boolean own = false;
                    for (var t : screen.texts()) own |= t.text().startsWith("Ключи: только при ");
                    ClientSmoke.check(!gun.endsWith("spas12") || own, gun + ": у перезарядки из пустого видны её свои ключи");
                    m.keyView = ArmEditor.KeyView.WHERE;
                    screen.refresh();
                });
            }
        }
        TODO.add(() -> {
            var close = screen.button("close");
            ClientSmoke.check(close != null, gun + ": есть кнопка «Готово»");
            if (close != null) close.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
            ClientSmoke.check(Minecraft.getInstance().gui.screen() == null, gun + ": «Готово» закрыла экран");
            ClientSmoke.check(!ArmAnimationPreview.active(), gun + ": после закрытия предпросмотр выключен");
        });
    }

    // a view of the key page through its button: shown, laid out, pressed through; then back to the key
    private static void view(String gun, ArmEditor.KeyView v) {
        var m = screen.model();
        String name = v.name().toLowerCase(Locale.ROOT);
        var b = screen.button("key.view." + name);
        ClientSmoke.check(b != null, gun + ": есть кнопка вида «" + v.label + "»");
        if (b == null || !b.active) return;
        b.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        String where = gun + " · Ключ · " + m.track.label + " · " + v.label;
        ClientSmoke.check(m.shownView() == v, where + ": вид показан");
        layout(where);
        pressAll(where);
        m.keyView = ArmEditor.KeyView.WHERE;
        screen.refresh();
    }

    private static void compare(String gun, ArmEditor m) {
        int keep = m.hand;
        for (int h = 0; h < 2; h++) {
            slots++;
            String drawn = ClientSmoke.drawnBranch[h];
            if (drawn == null || drawn.equals("rest")) continue;
            m.hand = h;
            String expected = m.driver(m.time).name().toLowerCase(Locale.ROOT);
            compared++;
            if (!expected.equals(drawn))
                WRONG.merge(gun + " " + m.clip().name() + (h == 0 ? " R " : " L ") + "редактор " + expected + ", игра " + drawn, 1, Integer::sum);
        }
        m.hand = keep;
    }

    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(Path.of("../notes/" + name));
            } catch (java.io.IOException e) {
                GunFeel.LOGGER.warn("Не удалось сохранить кадр экрана рук", e);
            }
        });
    }

    // a slider of the screen taken to a quarter of its way: the setting moves, one step of undo takes it back
    private static void drag(String gun) {
        var m = screen.model();
        ArmEditorScreen.Slider follow = null;
        for (var sl : screen.sliders()) if (sl.setting.id().equals("armFollow")) follow = sl;
        ClientSmoke.check(follow != null, gun + ": есть ползунок «Руки за оружием»");
        if (follow == null) return;
        var before = m.state();
        int undo = m.undoSize();
        follow.clickAt(0.25);
        Config.select(m.gun);
        ClientSmoke.check(Math.abs(Config.armFollow - 0.25F) < 1e-3, gun + ": ползунок поставил «Руки за оружием» 0,25: " + Config.armFollow);
        ClientSmoke.check(m.undoSize() == undo + 1, gun + ": ползунок — один шаг отмены");
        var back = screen.button("undo");
        if (back != null && back.active) back.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        ClientSmoke.check(m.state().data().equals(before.data()), gun + ": «Отменить» вернула значение ползунка");
    }

    // every action of the model has its button, as active as the action; all widgets on screen, none overlapping
    private static void layout(String gun) {
        var s = Minecraft.getInstance().gui.screen();
        var widgets = new ArrayList<AbstractWidget>();
        for (var w : s.children()) if (w instanceof AbstractWidget a) widgets.add(a);
        boolean fits = true, apart = true;
        String bad = "";
        for (var a : widgets) {
            if (a.getX() < 0 || a.getY() < 0 || a.getX() + a.getWidth() > s.width || a.getY() + a.getHeight() > s.height) {
                fits = false;
                bad = a.getMessage().getString();
            }
            for (var b : widgets)
                if (a != b && a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                        && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) {
                    apart = false;
                    bad = a.getMessage().getString() + " / " + b.getMessage().getString();
                }
        }
        var font = Minecraft.getInstance().font;
        String cramped = "";
        for (var a : widgets)
            if (a instanceof AbstractWidget btn && font.width(btn.getMessage()) > btn.getWidth() - 4) cramped += " «" + btn.getMessage().getString() + "»";
        for (var t : screen.texts()) if (font.width(t.text()) > t.w()) cramped += " «" + t.text() + "»";
        ClientSmoke.check(cramped.isEmpty(), gun + ": подписи помещаются в кнопки" + cramped);
        ClientSmoke.check(fits, gun + ": все элементы на экране " + s.width + "×" + s.height + (fits ? "" : ", вылез: " + bad));
        ClientSmoke.check(apart, gun + ": элементы не налезают друг на друга" + (apart ? "" : ": " + bad));
        int missing = 0;
        for (var a : screen.model().actions()) {
            var b = screen.button(a.id());
            if (b == null || b.active != a.active()) {
                missing++;
                GunFeel.LOGGER.info("[clientcheck] нет кнопки или не та серость: {} «{}»", a.id(), a.label());
            }
        }
        ClientSmoke.check(missing == 0, gun + ": у каждого действия своя кнопка с той же серостью (" + screen.model().actions().size() + ")");
    }

    // presses every active button (but «Готово») from the same state: each changes something, time buttons move
    // the real preview, a change of the settings goes back with «Отменить» (or «Повторить» after «Отменить»)
    // the key page: the left hand's first path key on a part (else the first key of any track), in its animation
    private static boolean atKey(ArmEditor m) {
        for (boolean part : new boolean[] {true, false})
            for (int h : new int[] {1, 0})
                for (var t : ArmEditor.Track.values())
                    for (int c = 0; c < m.clipList().size(); c++) {
                        if (part && t != ArmEditor.Track.PATH) continue;
                        m.clip = c;
                        m.hand = h;
                        var k = m.keysOf(t);
                        if (k == null) continue;
                        for (var key : k.keys()) {
                            if (key.ms() > m.duration() || part && k.pathEntry(key).grip()) continue;
                            m.track = t;
                            m.playing = false;
                            m.seek(key.ms());
                            return true;
                        }
                    }
        return false;
    }

    // a number typed for a slider: right click opens a box, a wrong text is not taken, Enter takes the number, undo
    // gives the old value back
    private static void typedNumber(String gun) {
        var m = screen.model();
        m.hand = 1;
        screen.refresh();
        ArmEditorScreen.Slider sl = null;
        for (var x : screen.sliders()) if (x.setting.id().equals("grip.0") && x.active) sl = x;
        ClientSmoke.check(sl != null, gun + ": у хвата есть ползунок «Хват ← →»");
        if (sl == null) return;
        var s = sl.setting;
        int cx = sl.getX() + sl.getWidth() / 2, cy = sl.getY() + sl.getHeight() / 2;
        screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(cx, cy, new net.minecraft.client.input.MouseButtonInfo(1, 0)), false);
        var box = screen.typing();
        ClientSmoke.check(box != null, gun + ": правая кнопка на ползунке открыла поле для числа");
        if (box == null) return;
        var before = m.state();
        box.setValue("пять");
        screen.keyPressed(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        ClientSmoke.check(screen.typing() != null && m.state().equals(before), gun + ": не число не принято, поле осталось открытым");
        // a number off the slider's step, as the jar has them
        float want = Float.parseFloat(ArmRig.number(s.min() + (s.max() - s.min()) * 0.37F + 0.0013F));
        screen.typing().setValue(ArmRig.number(want).replace('.', ','));
        screen.keyPressed(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        float now = Float.NaN;
        for (var x : screen.sliders()) if (x.setting.id().equals("grip.0")) now = x.setting.value();
        ClientSmoke.check(screen.typing() == null && Math.abs(now - want) < 1e-5F, gun + ": введённое число " + want + " стало значением хвата (" + now + ")");
        var undo = screen.button("undo");
        if (undo != null && undo.active) undo.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        ClientSmoke.check(m.state().data().equals(before.data()), gun + ": «Отменить» вернул хват после ввода числа");
        screen.refresh();
    }

    // "Выгрузить": a file in the folder, its text on the clipboard, and it reads back as the arms the gun has
    private static void exported(String gun) {
        var b = screen.button("export.write");
        ClientSmoke.check(b != null && b.active, gun + ": есть «Выгрузить»");
        if (b == null) return;
        b.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        var files = ArmFiles.files(Grips.armsGun(Grips.baseOf(screen.model().gun)));
        String text;
        try {
            text = files.isEmpty() ? null : java.nio.file.Files.readString(files.get(0).path());
        } catch (java.io.IOException e) {
            text = null;
        }
        ClientSmoke.check(text != null && text.equals(clip), gun + ": «Выгрузить» записал файл и положил его текст в буфер обмена");
        var r = ArmFiles.read(clip);
        ClientSmoke.check(r.problem() == null && r.arms().equals(Grips.armsOf(screen.model().gun)), gun + ": выгрузка читается обратно в те же руки"
                + (r.problem() == null ? "" : " (" + r.problem() + ")"));
        screen.refresh();
    }

    private static void pressAll(String gun) {
        var m = screen.model();
        if (m.page != ArmEditor.Page.KEY) {
            m.clip = 0;
            if (m.page != ArmEditor.Page.BOTH) m.hand = 1;
            m.seek(m.duration() / 2F);
        }
        screen.refresh();
        int dead = 0, pressed = 0;
        var before = m.state();
        var undo = m.undoCopy();
        var redo = m.redoCopy();
        for (var a : m.actions()) {
            if (!a.active() || a.id().equals("close")) continue;
            var b = screen.button(a.id());
            if (b == null) continue;
            b.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
            pressed++;
            var after = m.state();
            if (after.equals(before)) {
                dead++;
                GunFeel.LOGGER.info("[clientcheck] кнопка ничего не сделала: {}", a.label());
            }
            if (!after.playing() && ArmAnimationPreview.millis() != Math.round(after.time())) {
                dead++;
                GunFeel.LOGGER.info("[clientcheck] предпросмотр не на {} мс после «{}»: {}", after.time(), a.label(), ArmAnimationPreview.millis());
            }
            if (!after.data().equals(before.data())) {
                var back = screen.button(a.id().equals("undo") ? "redo" : "undo");
                if (back == null || !back.active) dead++;
                else {
                    back.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                    if (!m.state().data().equals(before.data())) dead++;
                }
            }
            m.restore(before, undo, redo);
            screen.refresh();
        }
        pressedAll += pressed;
        ClientSmoke.check(pressed > 0 && dead == 0, gun + ": нажато кнопок " + pressed + ", без толку " + dead);
    }

    private static void done() {
        // most sampled frames must reach the comparison: an arm not drawn at all would hide a mismatch
        ClientSmoke.check(compared > 500 && compared * 2 >= slots,
                "«Сейчас руку ведёт» сверено с игрой на кадрах: " + compared + " из " + slots);
        WRONG.forEach((k, n) -> ClientSmoke.check(false, k + " ×" + n));
        ClientSmoke.check(WRONG.isEmpty(), "«Сейчас руку ведёт» совпало с игрой везде");
        ClientSmoke.check(pressedAll > 0, "кнопок нажато всего: " + pressedAll);
        ClientSmoke.finish();
    }
}
