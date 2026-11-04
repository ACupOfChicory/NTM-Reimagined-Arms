package acup.gunfeel;

import java.util.*;

/**
 * Модель экрана «Руки пушки»: что выбрано, дорожки рук и действия. Без отрисовки: экран только показывает
 * {@link #actions()}, а тест нажимает каждое действие во всех состояниях.
 */
final class ArmEditor {
    // an animation the gun has, named as the game names it ("RELOAD"), and its length
    record Clip(String name, int ms) {
    }

    // where the gun's animations come from: NTM in the game, a list in tests
    interface Clips {
        List<Clip> list();

        default ArmRig.Buses buses(String clip) {
            return ArmRig.Buses.NONE;
        }

        // when NTM's buses move in a clip: bus name -> {from, to, from, to, ...} ms
        default Map<String, int[]> moves(String clip) {
            return Map.of();
        }

        // a gun's name as the player sees it
        default String gunName(String gun) {
            return gun;
        }
    }

    // one control of the screen: why == null means it can be pressed now; else why says why not
    record Action(String id, String label, String hint, String hotkey, String why, Runnable run) {
        boolean active() {
            return why == null;
        }
    }

    // what moves a hand, one row each on the screen, in the order the game tries them
    enum Track {
        HIDE("Скрыта", "Когда руку не видно.", ArmRig.Driver.HIDDEN),
        PATH("По деталям", "Рука идёт от детали к детали: к рычагу, магазину, патрону и обратно к хвату.", ArmRig.Driver.PATH),
        CARRY("Несёт", "Рука берёт то, что двигает анимация (патрон, бутылку), несёт и возвращается к хвату.", ArmRig.Driver.CARRY),
        RELEASE("Отпускает", "Рука отпускает оружие, пока оно вращается, и берёт снова, когда оборот закончен.", ArmRig.Driver.RELEASE),
        AROUND("Обход детали", "Левая рука обходит деталь, которую держит, на заданный угол.", ArmRig.Driver.AROUND),
        ADJUST("Поправка", "Сдвиг и поворот кисти поверх того, что делает рука.", null);

        final String label, hint;
        final ArmRig.Driver driver;

        Track(String label, String hint, ArmRig.Driver driver) {
            this.label = label;
            this.hint = hint;
            this.driver = driver;
        }
    }

    static final int STEP = 10;
    static final double[] SPEEDS = {.05, .1, .25, .5, 1};

    final String gun;
    final Clips clips;
    private final List<Clip> list;
    private final Map<String, String> opened;
    private final Deque<Map<String, String>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    int clip, hand = 1;
    float time;
    boolean playing, closed;
    double speed = .25;
    Track track = Track.PATH;
    private int version = Integer.MIN_VALUE;
    private ArmRig.Drive[] drives;

    ArmEditor(String gun, Clips clips) {
        this.gun = gun;
        this.clips = clips;
        this.list = List.copyOf(clips.list());
        this.opened = Grips.snapshot();
        Config.select(gun);
        int reload = indexOf("RELOAD");
        if (reload >= 0) clip = reload;
        track = firstTrackWithKeys();
    }

    // ---------------------------------------------------------------- what is shown

    List<Clip> clipList() {
        return list;
    }

    Clip clip() {
        return list.isEmpty() ? null : list.get(clip);
    }

    int duration() {
        return list.isEmpty() ? 0 : Math.max(1, list.get(clip).ms());
    }

    static String clipLabel(String name) {
        return switch (name) {
            case "RELOAD" -> "Перезарядка";
            case "RELOAD_CYCLE" -> "Цикл зарядки";
            case "RELOAD_END" -> "Конец зарядки";
            case "INSPECT" -> "Осмотр";
            case "EQUIP" -> "Доставание";
            case "CYCLE" -> "Передёргивание";
            case "CYCLE_EMPTY" -> "Передёргивание пустого";
            case "CYCLE_DRY" -> "Холостой цикл";
            case "ALT_CYCLE" -> "Другой цикл";
            case "SPINUP" -> "Раскрутка";
            case "SPINDOWN" -> "Остановка";
            case "JAMMED" -> "Осечка";
            default -> name;
        };
    }

    static String seconds(int ms) {
        return String.format(Locale.ROOT, "%.2f", ms / 1000F).replace('.', ',') + " с";
    }

    String handName() {
        return hand == 0 ? "правая" : "левая";
    }

    private ArmRig.Drive drive() {
        Config.select(gun);
        if (drives == null || version != Grips.version()) {
            version = Grips.version();
            drives = new ArmRig.Drive[] {new ArmRig.Drive(true), new ArmRig.Drive(false)};
        }
        return drives[hand];
    }

    ArmRig.Buses buses() {
        return list.isEmpty() ? ArmRig.Buses.NONE : clips.buses(clip().name());
    }

    // what moves the selected hand at a time of the selected animation
    ArmRig.Driver driver(float t) {
        if (list.isEmpty()) return ArmRig.Driver.HOLD;
        return drive().at(clip().name(), t, buses());
    }

    // what the screen draws every frame, worked out again only when the settings, the animation or the hand change
    private record View(int version, int clip, int hand, ArmRig.Driver[] drivers, Map<Track, List<Float>> keys) {
    }

    private View view;

    private View view() {
        drive();
        if (view == null || view.version != Grips.version() || view.clip != clip || view.hand != hand) {
            var d = new ArmRig.Driver[duration() / STEP + 1];
            for (int i = 0; i < d.length; i++) d[i] = driver(Math.min(i * STEP, duration()));
            var k = new EnumMap<Track, List<Float>>(Track.class);
            for (Track t : Track.values()) k.put(t, List.copyOf(readKeys(t)));
            view = new View(Grips.version(), clip, hand, d, k);
        }
        return view;
    }

    // the driver along the whole animation, one entry per STEP ms
    ArmRig.Driver[] drivers() {
        return view().drivers;
    }

    private String settingSource(String right, String left) {
        Config.select(gun);
        return hand == 0 ? right : left;
    }

    // the times of a track's keys in the selected animation (sorted, as the game reads them)
    List<Float> keys(Track t) {
        return view().keys.get(t);
    }

    private List<Float> readKeys(Track t) {
        var out = new ArrayList<Float>();
        if (list.isEmpty()) return out;
        String name = clip().name();
        var buses = buses();
        switch (t) {
            case HIDE -> {
                float[] r = ArmRig.forAnimation(ArmRig.ranges(settingSource(Config.armRightHide, Config.armLeftHide)), name, buses);
                if (r != null) for (float v : r) out.add(v);
            }
            case PATH -> {
                var k = ArmRig.forAnimation(ArmRig.pathKeys(settingSource(Config.armRightPath, Config.armLeftPath), Config.armPathTimes), name, buses);
                if (k != null) for (var key : k) out.add(key.ms());
            }
            case CARRY -> {
                var k = ArmRig.forAnimation(ArmRig.carryKeys(settingSource(Config.armRightCarryKeys, Config.armLeftCarryKeys)), name, buses);
                if (k != null) for (var key : k) out.add(key[0]);
            }
            case AROUND -> {
                if (hand == 1) {
                    float[] k = ArmRig.angleKeys(settingSource("", Config.armLeftKeys)).get(name);
                    if (k != null) for (int j = 0; j < k.length; j += 2) out.add(k[j]);
                }
            }
            case RELEASE -> {
            }
            case ADJUST -> {
                var k = ArmRig.adjustments(settingSource(Config.armRightAdjustments, Config.armLeftAdjustments)).get(name);
                if (k != null) out.addAll(k.keySet());
            }
        }
        Collections.sort(out);
        return out;
    }

    // a track that cannot act on this hand at all, and why; null = it can
    String trackWhy(Track t) {
        if (t == Track.AROUND && hand == 0) return "обходит деталь только левая рука";
        if (t == Track.RELEASE && settingSource(Config.armReleaseRight, Config.armReleaseLeft).isEmpty())
            return "этой руке не задано, при вращении какой детали отпускать оружие";
        return null;
    }

    // what takes the hand at time t before this track can (a track higher in the game's order); null = nothing
    ArmRig.Driver coveredBy(Track t, float time) {
        return coveredBy(t, driver(time));
    }

    // the same with the driver known
    static ArmRig.Driver coveredBy(Track t, ArmRig.Driver d) {
        if (t.driver == null) return d == ArmRig.Driver.HIDDEN ? d : null;
        return d.ordinal() < t.driver.ordinal() ? d : null;
    }

    // the selected track's note: keys that the game never plays because something else takes the hand then
    String trackNote(Track t) {
        String why = trackWhy(t);
        if (why != null) return why;
        var k = keys(t);
        if (k.isEmpty()) return null;
        long late = k.stream().filter(x -> x > duration()).count();
        if (late > 0) return "Ключей после конца анимации: " + late + ". Игра до них не доходит.";
        ArmRig.Driver by = null;
        float from = k.get(0), to = k.get(k.size() - 1);
        boolean all = true;
        var drivers = drivers();
        for (float x = from; x <= to; x += STEP) {
            var c = coveredBy(t, drivers[Math.clamp(Math.round(x / STEP), 0, drivers.length - 1)]);
            if (c == null) all = false;
            else by = c;
        }
        if (by == null) return null;
        return (all ? "Всю эту дорожку перекрывает: " : "Часть дорожки перекрывает: ") + by.label + ".";
    }

    // the shared gun and its name, for "these arms are shared with ..."
    String sharedWith() {
        return Grips.sharedWith(base());
    }

    private String base() {
        int dash = gun.indexOf('-');
        return dash < 0 ? gun : gun.substring(0, dash);
    }

    boolean changed() {
        return !Grips.snapshot().equals(opened);
    }

    int undoSize() {
        return undo.size();
    }

    int redoSize() {
        return redo.size();
    }

    // ---------------------------------------------------------------- actions

    private void change(Runnable edit) {
        var before = Grips.snapshot();
        edit.run();
        if (!Grips.snapshot().equals(before)) {
            undo.push(before);
            if (undo.size() > 200) undo.removeLast();
            redo.clear();
        }
        Config.select(gun);
    }

    void seek(float t) {
        time = Math.clamp(t, 0, duration());
    }

    private void selectClip(int index) {
        clip = Math.floorMod(index, list.size());
        time = 0;
        track = firstTrackWithKeys();
    }

    private Track firstTrackWithKeys() {
        for (Track t : Track.values()) if (t != Track.HIDE && trackWhy(t) == null && !keys(t).isEmpty()) return t;
        return Track.PATH;
    }

    private Float keyBefore() {
        Float best = null;
        for (float k : keys(track)) if (k < time - .5F && k >= 0) best = k;
        return best;
    }

    private Float keyAfter() {
        for (float k : keys(track)) if (k > time + .5F && k <= duration()) return k;
        return null;
    }

    private String noClip() {
        return list.isEmpty() ? "у этого оружия нет анимаций" : null;
    }

    List<Action> actions() {
        var out = new ArrayList<Action>();
        String none = noClip();
        String one = none != null ? none : list.size() < 2 ? "у этого оружия одна анимация" : null;
        out.add(new Action("clip.prev", "← Анимация", "Предыдущая анимация этого оружия.", "PgUp", one, () -> selectClip(clip - 1)));
        out.add(new Action("clip.next", "Анимация →", "Следующая анимация этого оружия.", "PgDn", one, () -> selectClip(clip + 1)));
        out.add(new Action("play", playing ? "Пауза" : "Играть", playing ? "Остановить анимацию на этом кадре." : "Играть анимацию по кругу.",
                "Пробел", none, () -> playing = !playing));
        out.add(new Action("speed", "Скорость ×" + String.valueOf(speed).replace('.', ','),
                "Скорость показа, по кругу: ×0,05, ×0,1, ×0,25, ×0,5, ×1.", "", none, () -> {
                    int i = 0;
                    for (int j = 0; j < SPEEDS.length; j++) if (SPEEDS[j] == speed) i = j;
                    speed = SPEEDS[(i + 1) % SPEEDS.length];
                }));
        String atStart = none != null ? none : time <= 0 ? "уже начало анимации" : null;
        String atEnd = none != null ? none : time >= duration() ? "уже конец анимации" : null;
        out.add(new Action("time.start", "⏮", "В начало анимации.", "Home", atStart, () -> {
            playing = false;
            seek(0);
        }));
        out.add(new Action("time.back", "◀ " + STEP, "На " + STEP + " мс назад.", "←", atStart, () -> {
            playing = false;
            seek(time - STEP);
        }));
        out.add(new Action("time.forward", STEP + " ▶", "На " + STEP + " мс вперёд.", "→", atEnd, () -> {
            playing = false;
            seek(time + STEP);
        }));
        out.add(new Action("time.end", "⏭", "В конец анимации.", "End", atEnd, () -> {
            playing = false;
            seek(duration());
        }));
        out.add(new Action("hand.right", "Правая рука", "Показать и править правую руку.", "", hand == 0 ? "правая рука уже выбрана" : null, () -> {
            hand = 0;
            if (trackWhy(track) != null) track = Track.PATH;
        }));
        out.add(new Action("hand.left", "Левая рука", "Показать и править левую руку.", "", hand == 1 ? "левая рука уже выбрана" : null, () -> hand = 1));
        for (Track t : Track.values()) {
            String why = t == track ? "эта дорожка уже выбрана" : trackWhy(t);
            out.add(new Action("track." + t.name().toLowerCase(Locale.ROOT), t.label, t.hint, "", why, () -> track = t));
        }
        Float before = none != null ? null : keyBefore(), after = none != null ? null : keyAfter();
        String empty = none != null ? none : keys(track).isEmpty() ? "на дорожке «" + track.label + "» нет ключей в этой анимации" : null;
        out.add(new Action("key.prev", "← Ключ", "Перейти к предыдущему ключу выбранной дорожки.", "[",
                empty != null ? empty : before == null ? "раньше ключей нет" : null, () -> {
                    playing = false;
                    seek(before);
                }));
        out.add(new Action("key.next", "Ключ →", "Перейти к следующему ключу выбранной дорожки.", "]",
                empty != null ? empty : after == null ? "позже ключей нет" : null, () -> {
                    playing = false;
                    seek(after);
                }));
        String shared = sharedWith();
        if (shared != null) {
            String name = clips.gunName(shared);
            boolean own = Grips.ownArms(base());
            out.add(new Action("arms.own", "Свои руки",
                    "Руки этого оружия общие с «" + name + "»: правка меняет обе. Скопировать их этому оружию, чтобы править отдельно.", "",
                    own ? "у этого оружия уже свои руки" : Grips.armsToCopy(base()) == 0 ? "у «" + name + "» нет настроек рук, копировать нечего" : null,
                    () -> change(() -> Grips.copyArms(base()))));
            out.add(new Action("arms.shared", "Общие руки",
                    "Убрать свои руки этого оружия и снова брать руки «" + name + "».", "",
                    own ? null : "руки и так общие с «" + name + "»", () -> change(() -> Grips.shareArms(base()))));
        }
        out.add(new Action("undo", "Отменить", "Отменить последнюю правку.", "Ctrl+Z", undo.isEmpty() ? "нечего отменять" : null, () -> {
            redo.push(Grips.snapshot());
            Grips.restoreUnsaved(undo.pop());
            Config.select(gun);
        }));
        out.add(new Action("redo", "Повторить", "Вернуть отменённую правку.", "Ctrl+Y", redo.isEmpty() ? "нечего повторять" : null, () -> {
            undo.push(Grips.snapshot());
            Grips.restoreUnsaved(redo.pop());
            Config.select(gun);
        }));
        out.add(new Action("reset", "Вернуть всё", "Вернуть все правки, сделанные с открытия редактора. Это тоже можно отменить.", "",
                changed() ? null : "правок с открытия не было", () -> change(() -> Grips.restoreUnsaved(opened))));
        out.add(new Action("close", "Готово", "Закрыть редактор. Правки уже действуют и сохраняются при закрытии.", "Esc", null, () -> {
            playing = false;
            closed = true;
        }));
        return out;
    }

    Action action(String id) {
        for (var a : actions()) if (a.id().equals(id)) return a;
        return null;
    }

    // for the screen's hotkeys: presses the action when it can be pressed now
    boolean press(String id) {
        var a = action(id);
        if (a == null || !a.active()) return false;
        a.run().run();
        return true;
    }

    private int indexOf(String name) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).name().equals(name)) return i;
        return -1;
    }

    // ---------------------------------------------------------------- for the test: everything a press can change

    record State(int clip, int hand, float time, boolean playing, double speed, Track track, boolean closed,
            Map<String, String> data, int undo, int redo) {
    }

    State state() {
        return new State(clip, hand, time, playing, speed, track, closed, Grips.snapshot(), undo.size(), redo.size());
    }

    // back to a state taken before (the test tries every action from the same state)
    void restore(State s, Deque<Map<String, String>> undoCopy, Deque<Map<String, String>> redoCopy) {
        clip = s.clip;
        hand = s.hand;
        time = s.time;
        playing = s.playing;
        speed = s.speed;
        track = s.track;
        closed = s.closed;
        Grips.restoreUnsaved(s.data);
        undo.clear();
        undo.addAll(undoCopy);
        redo.clear();
        redo.addAll(redoCopy);
        Config.select(gun);
    }

    Deque<Map<String, String>> undoCopy() {
        return new ArrayDeque<>(undo);
    }

    Deque<Map<String, String>> redoCopy() {
        return new ArrayDeque<>(redo);
    }
}
