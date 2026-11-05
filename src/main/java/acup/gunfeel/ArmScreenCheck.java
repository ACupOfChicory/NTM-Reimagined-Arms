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

    // the screens of a player's game: 1280×720 and 1920×1080, at every GUI scale the game offers there
    private static final int[][] SCREENS = {{1280, 720}, {1920, 1080}};
    // the widest a layout wanted, its labels whole, and where
    private static int widest, scrolls;
    private static String widestWhere = "";

    // the largest GUI scale the game's «Авто» picks for this window
    static int maxScale(int w, int h) {
        int i = 1;
        while (i < w && i < h && w / (i + 1) >= 320 && h / (i + 1) >= 240) i++;
        return i;
    }

    // what is wrong with the layout now on screen: labels cut, widgets off the screen or on each other; "" = nothing
    private static String problems(ArmEditorScreen s) {
        var widgets = new ArrayList<AbstractWidget>();
        for (var w : s.children()) if (w instanceof AbstractWidget a) widgets.add(a);
        var font = Minecraft.getInstance().font;
        String out = "";
        for (int i = 0; i < widgets.size(); i++) {
            var a = widgets.get(i);
            if (font.width(a.getMessage()) > a.getWidth() - 4) out += " обрезана «" + a.getMessage().getString() + "»;";
            if (a.getX() < 0 || a.getY() < 0 || a.getX() + a.getWidth() > s.width || a.getY() + a.getHeight() > s.height)
                out += " вылез «" + a.getMessage().getString() + "»;";
            if (a.getX() + a.getWidth() > 4 + s.panel()) out += " за панелью «" + a.getMessage().getString() + "»;";
            for (var b : widgets.subList(i + 1, widgets.size()))
                if (a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                        && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight())
                    out += " налезают «" + a.getMessage().getString() + "» / «" + b.getMessage().getString() + "»;";
        }
        for (var t : s.texts()) if (font.width(t.text()) > t.w()) out += " обрезана «" + t.text() + "»;";
        for (String line : List.of(s.title(), s.now())) if (font.width(line) > s.panel()) out += " за панелью «" + line + "»;";
        // the gun is drawn in the right half: the panel leaves at least 40 % of the width to it
        if ((s.panel() + 8) * 10 > s.width * 6) out += " панель " + (s.panel() + 8) + " из " + s.width + " закрывает оружие;";
        return out;
    }

    // the lines of settings that do not fit, scrolled through with ▼ and back with ▲: the layout whole at each place,
    // each line reached; "" = all well (or nothing to scroll)
    private static String scrolled(ArmEditorScreen s) {
        var m = s.model();
        if (s.button("rows.down") == null) return "";
        int first = m.first, n = m.rows().size(), presses = 0;
        String out = "";
        var last = m.rows().get(n - 1);
        boolean reached = false;
        for (int guard = 0; guard < 200; guard++) {
            var shown = m.shownRows();
            if (shown.get(shown.size() - 2).equals(last)) reached = true;
            var down = s.button("rows.down");
            if (down == null || !down.active) break;
            down.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
            presses++;
            String p = problems(s);
            if (!p.isEmpty() && !out.contains(p)) out += " после ▼ ×" + presses + ":" + p;
        }
        if (!reached) out += " листанием не дойти до последней строки «" + (last.slider() != null ? last.slider().label() : last.text()) + "»;";
        for (int guard = 0; guard < 200; guard++) {
            var up = s.button("rows.up");
            if (up == null || !up.active) break;
            up.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
        }
        if (!m.shownRows().get(0).equals(m.rows().get(0))) out += " ▲ не вернула к первой строке;";
        scrolls += presses;
        m.first = first;
        s.refresh();
        return out;
    }

    // every action of the model has its button, as active as the action; all widgets on screen, none overlapping,
    // no label cut: here and on the player's screens
    private static void layout(String gun) {
        var s = screen;
        ClientSmoke.check(Minecraft.getInstance().gui.screen() == s, gun + ": открыт экран рук");
        String here = problems(s);
        ClientSmoke.check(here.isEmpty(), gun + ": раскладка на " + s.width + "×" + s.height + " цела" + here);
        int w0 = s.width, h0 = s.height;
        String other = "";
        for (int[] r : SCREENS)
            for (int k = 1; k <= maxScale(r[0], r[1]); k++) {
                int gw = (r[0] + k - 1) / k, gh = (r[1] + k - 1) / k;
                s.init(gw, gh);
                String p = problems(s) + scrolled(s);
                if (!p.isEmpty()) other += " " + r[0] + "×" + r[1] + " масштаб " + k + " (" + gw + "×" + gh + "):" + p;
                if (s.need() > widest) {
                    widest = s.need();
                    widestWhere = gun;
                }
            }
        s.init(w0, h0);
        ClientSmoke.check(other.isEmpty(), gun + ": раскладка цела на 1280×720 и 1920×1080 при любом масштабе" + other);
        int missing = 0;
        var hidden = screen.model().hiddenIds();
        for (var a : screen.model().actions()) {
            if (hidden.contains(a.id())) continue;
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
        // the box is for what the editor shows now: playing stops, and once the time moves the box closes, taking nothing
        screen.refresh();
        m.playing = true;
        for (var x : screen.sliders()) if (x.setting.id().equals("grip.0")) sl = x;
        screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(sl.getX() + sl.getWidth() / 2, sl.getY() + sl.getHeight() / 2,
                new net.minecraft.client.input.MouseButtonInfo(1, 0)), false);
        ClientSmoke.check(screen.typing() != null && !m.playing, gun + ": поле для числа остановило анимацию");
        var shown = m.state();
        if (screen.typing() != null) screen.typing().setValue("0,5");
        m.seek(m.time + ArmEditor.STEP);
        screen.tick();
        ClientSmoke.check(screen.typing() == null && m.state().data().equals(shown.data()), gun + ": время сдвинулось — поле закрылось, ничего не записав");
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
        ClientSmoke.check(widest > 0, "самой широкой раскладке нужно " + widest + " (" + widestWhere + ")");
        ClientSmoke.check(scrolls > 0, "строки настроек пролистаны на узких экранах: нажато ▼ " + scrolls);
        ClientSmoke.finish();
    }
}
