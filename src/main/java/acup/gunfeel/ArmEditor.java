package acup.gunfeel;

import java.util.*;

/**
 * Модель экрана «Руки пушки»: что выбрано, дорожки рук и действия. Без отрисовки: экран только показывает
 * {@link #actions()}, а тест нажимает каждое действие во всех состояниях.
 */
final class ArmEditor {
    // an animation the gun has, named as the game names it ("RELOAD"), its length, and which of the animations NTM
    // plays under that name it is ("RELOAD", or "RELOAD#empty" from an empty magazine)
    record Clip(String name, int ms, String id) {
        static final String EMPTY = "#empty";

        Clip(String name, int ms) {
            this(name, ms, name);
        }

        boolean empty() {
            return id.endsWith(EMPTY);
        }

        String label() {
            return clipLabel(name) + (empty() ? " из пустого" : "");
        }
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

        // the gun's model as measured in the game, null = not measured yet (or none in a test)
        default Shape shape() {
            return null;
        }

        // the parts the held gun does not draw ("-Stock-Barrel" for a sawn-off), "" = the whole gun
        default String variant() {
            return "";
        }

        // the names of the model's parts a hand can move with
        default List<String> parts() {
            return List.of();
        }

        // the held gun is aimed through a scope (PistolHold.scoped): NTM keeps its own hold then
        default boolean scoped() {
            return false;
        }
    }

    // the gun's model box and the two ways of giving a grip: a point in model units, or fractions of the model
    interface Shape {
        float[] min();

        float[] max();

        float[] fractions(float[] point);

        float[] point(float[] fractions);
    }

    // the screen's pages: the animation lane, then the settings that hold for the whole animation
    enum Page {
        TIME("Анимации", "Дорожки выбранной руки по времени анимации."),
        KEY("Ключ", "Ключ выбранной дорожки в этот момент: когда он, где рука и как она подходит."),
        GRIP("Хват", "Где рука держит оружие и с какой деталью двигается."),
        ARM("Рука", "Плечо, поворот и толщина выбранной руки."),
        BOTH("Обе руки", "Настройки обеих рук этого оружия: толщина, длина, следование, скольжение."),
        FILE("Файл", "Взять руки с другого оружия, выгрузить руки этого оружия в файл и загрузить их из файла.");

        final String label, hint;

        Page(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }
    }

    // a number shown as a slider; why != null = it does not act now, and why; off = that reason in a few words, shown on
    // the slider in place of its number (the full one is in its hint)
    record Setting(String id, String label, String hint, String unit, float value, float min, float max, float step, String why, String off) {
        String text() {
            if (why != null && off != null) return label + ": " + off;
            return label + ": " + number(value, step) + (unit.isEmpty() ? "" : " " + unit);
        }
    }

    // one line of a settings page: a slider or a text, and the buttons at its right
    record Row(String text, String hint, Setting slider, List<String> ids) {
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

    // the views of the key page
    enum KeyView {
        WHERE("Ключ", "Ключ выбранной дорожки в этот момент: когда он и где рука."),
        APPROACH("Подход", "Как рука подходит к детали в этом ключе: сторона подхода и сдвиг плеча."),
        TRACK("Дорожка", "Настройки всей дорожки в этой анимации: моменты, что рука несёт, когда отпускает оружие."),
        HOLD("Как держит", "Как рука держит то, что несёт: с какой стороны, поворот, угол руки.");

        final String label, hint;

        KeyView(String label, String hint) {
            this.label = label;
            this.hint = hint;
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
    Page page = Page.TIME;
    // what the key page shows: the key, how a path key's hand comes, or the settings of the whole track
    KeyView keyView = KeyView.WHERE;
    // the lines of settings the screen has room for (the screen sets it), and the first line shown when not all fit
    int room = Integer.MAX_VALUE, first;
    // the File page: the gun to take arms from and the file to load (as indexes of their lists), the last export
    int from, file;
    private int exports;
    private java.nio.file.Path exported;
    private String exportError;
    // text the screen puts on the clipboard (the last export), taken once
    private String clipboard;
    // the key copied with "Копировать": it can be pasted to any hand, animation or gun
    static ArmKeys.Copy copied;
    // the mod's version in an export's head (the screen sets it), and the clock its date comes from
    static String modVersion = "X0-dev";
    static java.util.function.Supplier<java.time.LocalDateTime> clock = java.time.LocalDateTime::now;
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
        return list.isEmpty() ? ArmRig.Buses.NONE : clips.buses(clip().id());
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
        if (t == Track.RELEASE && settingSource(Config.armReleaseRight, Config.armReleaseLeft).isEmpty())
            return "Этой руке не задано, при вращении какой детали отпускать оружие. Задать: «Ключ», вид «Дорожка».";
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
        for (Page p : Page.values())
            out.add(new Action("page." + p.name().toLowerCase(Locale.ROOT), p.label, p.hint, "", p == page ? "эта страница уже открыта" : null, () -> {
                page = p;
                first = 0;
            }));
        String none = noClip();
        String one = none != null ? none : list.size() < 2 ? "у этого оружия одна анимация" : null;
        out.add(new Action("clip.prev", "← Анимация", "Предыдущая анимация этого оружия.", "PgUp", one, () -> selectClip(clip - 1)));
        out.add(new Action("clip.next", "Анимация →", "Следующая анимация этого оружия.", "PgDn", one, () -> selectClip(clip + 1)));
        out.add(new Action("play", playing ? "Пауза" : "Играть", playing ? "Остановить анимацию на этом кадре." : "Играть анимацию по кругу.",
                "Space", none, () -> playing = !playing));
        out.add(new Action("speed", "Скорость ×" + String.valueOf(speed).replace('.', ','),
                "Скорость показа, по кругу: ×0,05, ×0,1, ×0,25, ×0,5, ×1.", "", none, () -> {
                    int i = 0;
                    for (int j = 0; j < SPEEDS.length; j++) if (SPEEDS[j] == speed) i = j;
                    speed = SPEEDS[(i + 1) % SPEEDS.length];
                }));
        if (page == Page.TIME) timeActions(out, none);
        if (page == Page.TIME || page == Page.KEY) keyActions(out, none);
        if (page != Page.BOTH && page != Page.FILE) {
            out.add(new Action("hand.right", "Правая рука", "Показать и править правую руку.", "", hand == 0 ? "правая рука уже выбрана" : null, () -> {
                hand = 0;
                if (trackWhy(track) != null) track = Track.PATH;
            }));
            out.add(new Action("hand.left", "Левая рука", "Показать и править левую руку.", "", hand == 1 ? "левая рука уже выбрана" : null, () -> hand = 1));
        }
        if (page != Page.TIME) settingActions(out);
        if (page == Page.FILE) fileActions(out);
        commonActions(out);
        return out;
    }

    // the animation lane's controls: time, tracks, keys
    private void timeActions(List<Action> out, String none) {
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
        for (Track t : Track.values()) {
            String why = t == track ? "эта дорожка уже выбрана" : trackWhy(t);
            out.add(new Action("track." + t.name().toLowerCase(Locale.ROOT), t.label, t.hint, "", why, () -> track = t));
        }
    }

    // going from key to key of the selected track, adding and deleting one
    private void keyActions(List<Action> out, String none) {
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
        if (page != Page.KEY) return;
        var k = none != null ? null : keysOf(track);
        var key = k == null ? null : k.at(time);
        String cannot = none != null ? none : keyless();
        out.add(new Action("key.add", "+ Ключ", track == Track.HIDE ? "Скрыть руку с этого момента (на полсекунды; длину можно поменять на странице «Ключ»)."
                : "Добавить ключ выбранной дорожки в этот момент. Он повторяет ключ перед ним, править его — на странице «Ключ».", "Insert",
                cannot != null ? cannot : key != null ? (track == Track.HIDE ? "рука в этот момент уже скрыта" : "в этот момент ключ уже есть")
                        : track == Track.HIDE && time >= duration() ? "в конце анимации скрывать уже нечего" : null,
                () -> change(() -> editKey((ks, x) -> ks.add(time, duration())))));
        out.add(new Action("key.delete", "− Ключ", track == Track.HIDE ? "Больше не скрывать руку в этом отрезке." : "Удалить ключ выбранной дорожки в этот момент.",
                "Delete", cannot != null ? cannot : key == null ? (track == Track.HIDE ? "рука в этот момент не скрыта" : "в этот момент ключа нет") : null,
                () -> change(() -> editKey((ks, x) -> ks.delete(x)))));
        if (!trackView()) copyActions(out, k, key, cannot);
        viewActions(out);
    }

    // copying the key here, and pasting the copied one here (over the key here, which keeps its time, or as a new one)
    private void copyActions(List<Action> out, ArmKeys k, ArmKeys.Key key, String cannot) {
        // no keys here at all: the page says why, and there is nothing to copy or paste
        if (cannot != null) return;
        var mine = key == null ? null : k.copy(key);
        out.add(new Action("key.copy", "Копировать", "Запомнить этот ключ, чтобы вставить его в другой момент, другой руке, в другую анимацию или другому оружию.",
                "Ctrl+C", cannot != null ? cannot : key == null ? "в этот момент ключа нет" : mine.equals(copied) ? "этот ключ уже скопирован" : null,
                () -> copied = mine));
        String paste = cannot != null ? cannot : copied == null ? "сначала скопируй ключ («Копировать»)"
                : copied.track() != track ? "скопирован ключ дорожки «" + copied.track().label + "», а выбрана «" + track.label + "»"
                : track == Track.HIDE && key == null && time >= duration() ? "в конце анимации скрывать уже нечего" : null;
        if (paste == null) {
            var trial = keysOf(track);
            String before = trial.write();
            trial.paste(copied, time, duration());
            if (trial.write().equals(before)) paste = "здесь уже такой ключ";
        }
        out.add(new Action("key.paste", "Вставить", key != null ? "Заменить ключ в этот момент скопированным (время ключа остаётся)."
                : "Вставить скопированный ключ в этот момент.", "Ctrl+V", paste, () -> change(() -> editKey((ks, x) -> ks.paste(copied, time, duration())))));
    }

    // why the selected track has no keys to add or delete here; null = it has
    private String keyless() {
        String why = trackWhy(track);
        if (why != null) return why;
        if (track == Track.RELEASE) return "у дорожки «Отпускает» нет ключей: рука отпускает оружие, пока деталь вращается";
        var k = keysOf(track);
        return k != null && k.broken ? "в записи поправок этой руки ошибка: игра их не читает" : null;
    }

    // ---------------------------------------------------------------- keys of the selected track

    // the gun's setting that holds a track of the selected hand; null = no keys
    private String trackSetting(Track t) {
        return switch (t) {
            case HIDE -> "arm" + side() + "Hide";
            case PATH -> "arm" + side() + "Path";
            case CARRY -> "arm" + side() + "CarryKeys";
            case AROUND -> hand == 1 ? "armLeftKeys" : null;
            case ADJUST -> "arm" + side() + "Adjustments";
            case RELEASE -> null;
        };
    }

    ArmKeys keysOf(Track t) {
        String key = trackSetting(t);
        if (list.isEmpty() || key == null) return null;
        String src = (String) config(key);
        return new ArmKeys(t, src, clip().name(), buses(), ArmRig.pathTimes(Config.armPathTimes));
    }

    // an edit of the selected track at the current time (the key there, or null), written back to the gun
    private void editKey(java.util.function.BiConsumer<ArmKeys, ArmKeys.Key> edit) {
        var k = keysOf(track);
        edit.accept(k, k.at(time));
        String key = trackSetting(track), text = k.write();
        // the text the gun has anyway (the jar's, the shared gun's, none) is not written as its own
        Grips.put(gun, key, null, false);
        if (!text.equals(config(key))) Grips.put(gun, key, text, false);
        Config.select(gun);
    }

    // ---------------------------------------------------------------- the File page

    // where the gun's arms are read and written: the gun, or the gun it shares them with
    private String target() {
        return Grips.armsGun(base());
    }

    // the guns whose arms can be taken: those with arm settings of their own, other than this one
    List<String> sources() {
        String t = target();
        var out = new ArrayList<String>();
        for (String g : Grips.gunsWithArms()) if (!g.equals(t) && !g.equals(base())) out.add(g);
        return out;
    }

    // another gun's arms as keys of this one
    private Map<String, String> armsFrom(String src) {
        String s = Grips.armsGun(src), t = target();
        Map<String, String> out = new TreeMap<>();
        for (var e : Grips.armsOf(src).entrySet()) out.put(t + e.getKey().substring(s.length()), e.getValue());
        return out;
    }

    // the arms are already these (what hides the shared gun's values follows from the rest)
    private boolean sameArms(Map<String, String> arms) {
        String t = target();
        Map<String, String> now = new TreeMap<>(Grips.armsOf(base())), want = new TreeMap<>();
        for (var e : arms.entrySet())
            if (!e.getValue().isEmpty() && (e.getKey().startsWith(t + ".") || e.getKey().startsWith(t + "-"))) want.put(e.getKey(), e.getValue());
        now.values().removeIf(String::isEmpty);
        return now.equals(want);
    }

    private void fileActions(List<Action> out) {
        var src = sources();
        String none = src.isEmpty() ? "у других оружий нет своих настроек рук" : null;
        String one = none != null ? none : src.size() < 2 ? "взять руки можно только у одного оружия" : null;
        out.add(new Action("copy.from.prev", "◀", "Предыдущее оружие, у которого есть свои руки.", "", one, () -> from = Math.floorMod(from - 1, src.size())));
        out.add(new Action("copy.from.next", "▶", "Следующее оружие, у которого есть свои руки.", "", one, () -> from = Math.floorMod(from + 1, src.size())));
        String s = none == null ? src.get(Math.floorMod(from, src.size())) : null;
        var arms = s == null ? null : armsFrom(s);
        out.add(new Action("copy.from.take", "Взять", "Сделать руки этого оружия такими же, как у выбранного: хват, дорожки и все настройки рук. Это можно отменить.",
                "", none != null ? none : sameArms(arms) ? "руки уже такие же, как у «" + clips.gunName(s) + "»" : null, () -> change(() -> Grips.putArms(base(), arms))));
        out.add(new Action("export.write", "Выгрузить", "Записать руки этого оружия в файл в папке игры config/" + ArmFiles.FOLDER
                + " и скопировать его текст в буфер обмена: его можно сразу вставить в сообщение.", "", null, this::export));
        var files = ArmFiles.files(target());
        String noFile = files.isEmpty() ? "выгруженных файлов этого оружия нет" : null;
        String oneFile = noFile != null ? noFile : files.size() < 2 ? "файл этого оружия один" : null;
        out.add(new Action("load.prev", "◀", "Файл новее.", "", oneFile, () -> file = Math.floorMod(file - 1, files.size())));
        out.add(new Action("load.next", "▶", "Файл старее.", "", oneFile, () -> file = Math.floorMod(file + 1, files.size())));
        var r = noFile != null ? null : ArmFiles.read(files.get(Math.floorMod(file, files.size())).path());
        String why = noFile != null ? noFile : r.problem() != null ? "в файле ошибка, " + r.problem() : !r.gun().equals(target()) ? "файл выгружен для другого оружия"
                : sameArms(r.arms()) ? "руки уже как в этом файле" : null;
        out.add(new Action("load.file", "Загрузить", "Сделать руки этого оружия такими, как в выбранном файле. Это можно отменить.", "", why,
                () -> change(() -> Grips.putArms(base(), r.arms()))));
    }

    private void export() {
        String t = target();
        var when = clock.get();
        String text = ArmFiles.text(t, clips.gunName(t), base(), modVersion, when, Grips.armsOf(base()));
        try {
            exported = ArmFiles.write(t, text, when);
            exportError = null;
            clipboard = text;
        } catch (java.io.IOException | RuntimeException e) {
            exported = null;
            exportError = String.valueOf(e.getMessage());
        }
        exports++;
        // the files go newest first: the one just written
        file = 0;
    }

    // the text to put on the clipboard now, once
    String takeClipboard() {
        String c = clipboard;
        clipboard = null;
        return c;
    }

    private List<Row> fileRows() {
        var out = new ArrayList<Row>();
        var src = sources();
        String t = target();
        String shared = t.equals(base()) ? "" : " Руки этого оружия общие с «" + clips.gunName(t) + "»: правка меняет обе.";
        out.add(new Row("Взять руки с: " + (src.isEmpty() ? "не у кого" : clips.gunName(src.get(Math.floorMod(from, src.size())))),
                "Оружие, руки которого можно взять этому: ◀ ▶ выбирают, «Взять» копирует." + shared, null, List.of("copy.from.prev", "copy.from.next", "copy.from.take")));
        String folder = "config/" + ArmFiles.FOLDER;
        out.add(new Row(exportError != null ? "Не удалось выгрузить" : exported == null ? "Выгрузить руки в файл" : "Выгружено, текст в буфере",
                exportError != null ? "Файл не записался: " + exportError + "."
                        : exported == null ? "Файл ложится в папку игры " + folder + ", его текст — в буфер обмена."
                        : "Файл " + folder + "/" + exported.getFileName() + ". Его текст уже в буфере обмена: вставь его в сообщение.",
                null, List.of("export.write")));
        var files = ArmFiles.files(t);
        int i = files.isEmpty() ? 0 : Math.floorMod(file, files.size());
        out.add(new Row(files.isEmpty() ? "Файлов нет" : files.get(i).label(),
                "Выгруженные файлы этого оружия из папки " + folder + ", новые первыми" + (files.size() > 1 ? " (этот " + (i + 1) + " из " + files.size() + ")" : "")
                        + ". Чужой файл можно положить туда же.", null, List.of("load.prev", "load.next", "load.file")));
        return out;
    }

    // own or shared arms, undo, redo, back to how it was, close: on every page
    private void commonActions(List<Action> out) {
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
                    !own ? "руки и так общие с «" + name + "»" : Grips.ownArmsShipped(base()) ? "свои руки этого оружия заданы в самом моде" : null,
                    () -> change(() -> Grips.shareArms(base()))));
        }
        int n = page == Page.TIME ? 0 : rows().size();
        if (n > room) {
            int f = firstShown(n), c = shownCount(n);
            out.add(new Action("rows.up", "▲", "Показать строку выше. Листать можно и колесом мыши.", "", f == 0 ? "выше строк нет" : null, () -> first = f - 1));
            out.add(new Action("rows.down", "▼", "Показать строку ниже. Листать можно и колесом мыши.", "", f + c >= n ? "ниже строк нет" : null,
                    () -> first = f + 1));
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
    }

    // ---------------------------------------------------------------- settings pages

    // a number of the gun's settings: what it is, how to read and write it, and when it does not act
    private abstract class Knob {
        final String id, label, hint, unit;
        final float step;

        Knob(String id, String label, String hint, String unit, float step) {
            this.id = id;
            this.label = label;
            this.hint = hint;
            this.unit = unit;
            this.step = step;
        }

        abstract float get();

        abstract void put(float v);

        abstract float lo();

        abstract float hi();

        String why() {
            return null;
        }

        String off() {
            return null;
        }

        // the game takes only whole numbers here (milliseconds the carry is taken at)
        boolean whole() {
            return false;
        }

        Setting setting() {
            return new Setting(id, label, hint, unit, get(), lo(), hi(), step, why(), off());
        }
    }

    // a setting of the gun read through Config; c >= 0 = one number of three
    private class ConfigKnob extends Knob {
        final String key;
        final int c;
        final float lo, hi;

        ConfigKnob(String key, int c, String label, String hint, String unit, float lo, float hi, float step) {
            super(c < 0 ? key : key + "." + c, label, hint, unit, step);
            this.key = key;
            this.c = c;
            this.lo = lo;
            this.hi = hi;
        }

        float get() {
            Object v = config(key);
            return c < 0 ? ((Number) v).floatValue() : ((float[]) v)[c];
        }

        void put(float v) {
            String text;
            if (c < 0) text = plain(v);
            else {
                float[] now = ((float[]) config(key)).clone();
                now[c] = v;
                text = plain(now[0]) + "," + plain(now[1]) + "," + plain(now[2]);
            }
            Grips.put(gun, key, text, false);
            Config.select(gun);
        }

        float lo() {
            return lo;
        }

        float hi() {
            return hi;
        }
    }

    // one number of where the selected hand holds the gun: fractions of the model, or a point in model units
    private class GripKnob extends Knob {
        final int c;

        GripKnob(int c) {
            super("grip." + c, new String[] {"Хват ← →", "Хват ↓ ↑", "Хват вдоль"}[c], gripHint(c), "", 0.005F);
            this.c = c;
        }

        float get() {
            return grip()[c];
        }

        void put(float v) {
            float[] p = grip().clone();
            p[c] = v;
            if (gripPoint()) Grips.setAt(gripTarget(), hand == 0, p[0], p[1], p[2], false);
            else Grips.set(gripTarget(), hand == 0, p[0], p[1], p[2], false);
            Config.select(gun);
        }

        float lo() {
            if (!gripPoint()) return c == 2 ? -0.2F : -0.5F;
            var sh = clips.shape();
            if (sh == null) return -16F;
            return sh.min()[c] - (sh.max()[2] - sh.min()[2]) * 0.3F;
        }

        float hi() {
            if (!gripPoint()) return c == 2 ? 1.2F : 1.5F;
            var sh = clips.shape();
            if (sh == null) return 16F;
            return sh.max()[c] + (sh.max()[2] - sh.min()[2]) * 0.3F;
        }

        @Override
        Setting setting() {
            float st = step;
            var sh = clips.shape();
            if (gripPoint()) st = sh == null ? 0.05F : Math.max(1e-3F, (sh.max()[2] - sh.min()[2]) / 400F);
            return new Setting(id, label, hint, gripPoint() ? "ед. модели" : "", get(), lo(), hi(), st, null, null);
        }
    }

    private static String gripHint(int c) {
        return switch (c) {
            case 0 -> "Хват поперёк оружия. В долях: 0 — один бок, 1 — другой. Точкой — в единицах модели.";
            case 1 -> "Хват по высоте. В долях: 0 — низ оружия, 1 — верх. Точкой — в единицах модели.";
            default -> "Хват вдоль оружия. В долях: 0 — конец приклада, 1 — дуло. Точкой — в единицах модели.";
        };
    }

    private String side() {
        return hand == 0 ? "Right" : "Left";
    }

    private float base(boolean right) {
        Config.select(gun);
        return right ? Config.armBaseRight : Config.armBaseLeft;
    }

    // how much a pistol's arms take their thickness from their hands' depth and turn on this gun (armPistolScale,
    // Arms.pistolTarget): the weight the hold gives it, 0 = not at all
    private float pistolDepth() {
        Config.select(gun);
        return Config.armPistolScale && PistolHold.on() && !clips.scoped() ? Math.min(1F, Config.pistolHold) : 0F;
    }

    // the arm runs from the hand back toward the eye, so how far back it goes is read (Arms.restArm)
    private boolean back(boolean right) {
        Config.select(gun);
        float[] d = right ? Config.armDirRight : Config.armDirLeft;
        float sq = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
        return sq >= 1e-6F && d[2] / (float) Math.sqrt(sq) < -1e-3F;
    }

    // the hand has keys of a path along the parts or of carrying
    private boolean loads(boolean right) {
        Config.select(gun);
        return !(right ? Config.armRightPath : Config.armLeftPath).isEmpty() || !(right ? Config.armRightCarryKeys : Config.armLeftCarryKeys).isEmpty();
    }

    // the knobs of a page, in the order shown
    private List<Knob> knobs(Page p) {
        var out = new ArrayList<Knob>();
        String s = side();
        switch (p) {
            case KEY -> out.addAll(trackView() ? trackKnobs() : keyKnobs());
            case GRIP -> {
                for (int c = 0; c < 3; c++) out.add(new GripKnob(c));
            }
            case ARM -> {
                String[] dir = {"Плечо ← →", "Плечо ↓ ↑", "Плечо назад/вперёд"};
                String[] dirHint = {"Куда от кисти идёт рука к плечу: влево (+) или вправо (−).", "Куда от кисти идёт рука к плечу: вверх (+) или вниз (−).",
                        "Куда от кисти идёт рука к плечу: вперёд (+) или назад (−)."};
                for (int c = 0; c < 3; c++) out.add(new ConfigKnob("armDir" + s, c, dir[c], dirHint[c], "", -1.5F, 1.5F, 0.01F));
                out.add(new ConfigKnob("armRoll" + s, -1, "Поворот руки", "Поворот руки вокруг своей оси.", "°", -180F, 180F, 1F));
                out.add(new ConfigKnob("armScale" + s, -1, "Толщина", "Толщина этой руки, когда она держит оружие, поверх общей толщины обеих рук.", "×", 0.3F, 2.5F, 0.01F));
                out.add(new ConfigKnob("armAimScale" + s, -1, "Толщина в прицеле",
                        "Толщина этой руки, когда оружие в прицеле; между бедром и прицелом переходит плавно. 0 — как «Толщина».",
                        "×", 0F, 2.5F, 0.01F));
                out.add(new ConfigKnob("armBase" + s, -1, "Основа толщины",
                        "Толщина руки до общего множителя, одна на всех оружиях. 0 — толщина растёт с длиной руки.", "", 0F, 5F, 0.01F) {
                    // an arm sized by its hand's depth alone reads its base only for its length (Arms.restArm)
                    @Override
                    String why() {
                        return pistolDepth() >= 1F && !(Config.armLength > 0F)
                                ? "не действует, пока толщину рук задаёт глубина кисти («Руки пистолета по глубине»), а «Длина руки» 0" : null;
                    }

                    @Override
                    String off() {
                        return "по глубине кисти";
                    }
                });
                boolean right = hand == 0;
                out.add(new ConfigKnob("armReach" + s, -1, "Плечо до", "Рука идёт назад до этой глубины перед глазом (минус — за глазом).", "", -2F, 2F, 0.01F) {
                    @Override
                    String why() {
                        if (base(right) > 0F && Config.armLength > 0F) return "действует, только когда «Основа толщины» этой руки 0 или «Длина руки» 0";
                        return back(right) ? null : "действует, только когда рука идёт от кисти к плечу назад: «Плечо назад/вперёд» меньше 0";
                    }

                    @Override
                    String off() {
                        return base(right) > 0F && Config.armLength > 0F ? "при основе или длине 0" : "при плече назад";
                    }
                });
                // read only while the hand goes along its path or carries (Arms.pathState, carryState)
                String noLoad = "действует, только когда у этой руки есть ключи пути по деталям или переноса";
                out.add(new FallbackKnob("armLoadRoll" + s, "armRoll" + s, "Поворот на зарядке",
                        "Поворот руки вокруг своей оси, пока она идёт по деталям или несёт. Если не задан, как «Поворот руки».", "°", -180F, 180F, 1F) {
                    @Override
                    String why() {
                        return loads(right) ? null : noLoad;
                    }

                    @Override
                    String off() {
                        return "нет ключей";
                    }
                });
                out.add(new ConfigKnob("armLoadScale" + s, -1, "Толщина на зарядке", "Толщина руки, пока она идёт по деталям или несёт, поверх обычной.", "×",
                        0.2F, 2.5F, 0.01F) {
                    @Override
                    String why() {
                        return loads(right) ? null : noLoad;
                    }

                    @Override
                    String off() {
                        return "нет ключей";
                    }
                });
                if (hand == 1) out.add(new ConfigKnob("armInspectDrop", -1, "Опускание при осмотре",
                        "На сколько опускается левая рука при осмотре, когда оружие лежит на боку, в пикселях руки.", "", 0F, 4F, 0.1F));
                if (hand == 1) out.add(new ConfigKnob("armInspectReleaseMs", -1, "Магазин до",
                        "При осмотре левая рука держит вынутый магазин до этого времени.", "мс", 0F, 5000F, 10F) {
                    @Override
                    String why() {
                        return get() < 0F ? "левая рука не держит магазин при осмотре: «Вкл» справа" : null;
                    }

                    @Override
                    String off() {
                        return "выключено";
                    }
                });
            }
            case BOTH -> {
                out.add(new ConfigKnob("armScale", -1, "Толщина обеих", "Множитель толщины обеих рук.", "×", 0.2F, 2F, 0.01F));
                out.add(new ConfigKnob("armLength", -1, "Длина руки", "Длина руки в ванильных руках её толщины. 0 — рука тянется до «Плечо до».", "", 0F, 2F, 0.01F) {
                    // with both bases 0 it only lets an arm drawn shorter than 11.5 of its pixels be cut (Arms.local), and
                    // such an arm is at least 18 / (armScale x its thickest hold/load/aim scale
                    // x min(1, armMaxScale)) pixels long; an arm sized by its hand's depth has no such bound
                    @Override
                    String why() {
                        if (pistolDepth() > 0F) return null;
                        Config.select(gun);
                        float aimRight = Config.armAimScaleRight > 0F ? Config.armAimScaleRight : 0F;
                        float aimLeft = Config.armAimScaleLeft > 0F ? Config.armAimScaleLeft : 0F;
                        float thickest = Config.armScale * Math.min(1F, Config.armMaxScale) * Math.max(
                                Math.max(Math.max(Config.armScaleRight, Config.armScaleLeft), Math.max(Config.armLoadScaleRight, Config.armLoadScaleLeft)),
                                Math.max(aimRight, aimLeft));
                        return base(true) <= 0F && base(false) <= 0F && 18F / thickest >= 11.5F
                                ? "действует, только когда «Основа толщины» хоть одной руки больше 0 или рука вместе с «Толщиной обеих» толще "
                                        + number(18F / 11.5F, 0.01F) : null;
                    }

                    @Override
                    String off() {
                        return "при основе больше 0";
                    }
                });
                out.add(new ConfigKnob("armMaxScale", -1, "Предел толщины", "Наибольшая толщина, которую рука набирает с длиной.", "", 0.5F, 8F, 0.05F) {
                    @Override
                    String why() {
                        if (base(true) > 0F && base(false) > 0F) return "действует, только когда «Основа толщины» хоть одной руки 0";
                        // the hold's full weight takes the thickness from the hands' depth alone
                        return pistolDepth() >= 1F ? "не действует, пока толщину рук задаёт глубина кисти: «Руки пистолета по глубине»" : null;
                    }

                    @Override
                    String off() {
                        return base(true) > 0F && base(false) > 0F ? "при основе 0" : "по глубине кисти";
                    }
                });
                out.add(new ConfigKnob("armFollow", -1, "Руки за оружием", "Насколько руки поворачиваются вместе с оружием в анимациях: 0 — нет, 1 — полностью.", "", 0F, 1F, 0.01F));
                out.add(new ConfigKnob("armFollowSupport", -1, "Левая при стрельбе", "То же для левой руки при стрельбе и передёргивании: оружие поворачивается в ладони.", "", 0F, 1F, 0.01F));
                String noSlide = "действует, только когда включено «Скольжение рук»";
                out.add(new ConfigKnob("armStretch", -1, "Растяжение", "На сколько рука может растянуться: 0,1 — на 10 %. Со «Скольжением рук» дальше кисть скользит по оружию.", "", 0F, 1F, 0.01F));
                out.add(new ConfigKnob("armSlideTo", -1, "Скольжение до", "Докуда кисть может скользить к прикладу: 0 — до конца приклада, 1 — до дула.", "", 0F, 1F, 0.01F) {
                    @Override
                    String why() {
                        Config.select(gun);
                        return Config.armSlide ? null : noSlide;
                    }

                    @Override
                    String off() {
                        return "при скольжении рук";
                    }
                });
                out.add(new ConfigKnob("armSwitchMs", -1, "Переход анимаций", "За сколько миллисекунд рука переходит на новое место, когда анимация начинается или кончается. 0 — сразу.", "мс", 0F, 300F, 10F));
            }
            default -> {
            }
        }
        return out;
    }

    // the switches of the BOTH page: key, label, hint
    private static final String[][] SWITCHES = {
            {"armPistolScale", "Руки пистолета по глубине", "У пистолета, когда включён «пистолет у бедра», рука тем толще, чем дальше кисть от глаза, и тем тоньше, чем больше она видна с ребра: обе руки на экране одного размера у бедра и в прицеле. «Толщина» и «Толщина в прицеле» действуют поверх."},
            {"armSlide", "Скольжение рук", "В анимациях кисть скользит по оружию к прикладу, а не рука растягивается."},
            {"armSlideNearest", "Ближайшая точка", "Скользящая кисть останавливается в ближайшей к хвату точке, до которой достаёт рука."}};

    private boolean switchOn(String key) {
        return (Boolean) config(key);
    }

    private String switchWhy(String key) {
        if (key.equals("armPistolScale") && !PistolHold.on()) return "действует только у пистолета, когда включён «пистолет у бедра»";
        if (key.equals("armPistolScale") && clips.scoped()) return "не действует у оружия с оптическим прицелом";
        return key.equals("armSlideNearest") && !switchOn("armSlide") ? "действует, только когда включено «Скольжение рук»" : null;
    }

    // ---- the key page: the selected track's key at the current time

    // a number of the key at the current time
    private interface KeyGet {
        float get(ArmKeys k, ArmKeys.Key key);
    }

    private interface KeyPut {
        void put(ArmKeys k, ArmKeys.Key key, float v);
    }

    private final class KeyKnob extends Knob {
        final float lo, hi;
        final KeyGet get;
        final KeyPut put;

        KeyKnob(String id, String label, String hint, String unit, float lo, float hi, float step, KeyGet get, KeyPut put) {
            super("key." + id, label, hint, unit, step);
            this.lo = lo;
            this.hi = hi;
            this.get = get;
            this.put = put;
        }

        float get() {
            var k = keysOf(track);
            return get.get(k, k.at(time));
        }

        void put(float v) {
            float c = Math.clamp(v, lo, hi);
            editKey((k, key) -> put.put(k, key, c));
        }

        float lo() {
            return lo;
        }

        float hi() {
            return hi;
        }
    }

    // where a number may go: at least what it is now (data written by hand may sit past its neighbours)
    private static float[] bounds(float lo, float hi, float now) {
        return new float[] {Math.min(lo, now), Math.max(hi, now)};
    }

    private static final String[] SIDES = {"сзади", "спереди", "снаружи", "изнутри", "снизу", "сверху"};
    private static final float[][] SIDE = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, -1, 0}, {0, 1, 0}};

    private static int sideIndex(float[] side) {
        if (side == null) return 0;
        for (int i = 0; i < SIDE.length; i++) if (Arrays.equals(side, SIDE[i])) return i;
        return -1;
    }

    static String contactName(int id) {
        return switch (id) {
            case 1 -> "угол внутри спереди";
            case 2 -> "угол внутри сзади";
            case 3 -> "угол снаружи спереди";
            case 4 -> "угол снаружи сзади";
            case 5 -> "грань внутри";
            case 6 -> "грань снаружи";
            case 7 -> "грань спереди";
            case 8 -> "грань сзади";
            case 9 -> "торец кисти";
            case 10 -> "ребро внутри";
            case 11 -> "ребро снаружи";
            case 12 -> "ребро спереди";
            case 13 -> "ребро сзади";
            default -> "как в анимации";
        };
    }

    private static final String[] CARRY_KINDS = {"идёт с анимацией", "замерла в момент", "в своей точке"};

    // the part a path key holds, as shown
    static String keyPart(String part) {
        return part.equalsIgnoreCase("grip") ? "хват" : part;
    }

    // the parts a path key can hold: the grip, the model's parts, the parts the gun's paths name
    private List<String> pathParts(String now) {
        var out = new ArrayList<String>();
        out.add("grip");
        for (String p : clips.parts()) if (!out.contains(p)) out.add(p);
        for (String src : new String[] {(String) config("armRightPath"), (String) config("armLeftPath")})
            for (var seg : ArmRig.path(src).segments)
                for (var e : seg.entries) if (e.valid() && !e.grip() && !out.contains(e.part)) out.add(e.part);
        if (!out.contains(now)) out.add(now);
        return out;
    }

    private float defaultRoll() {
        Config.select(gun);
        float load = hand == 0 ? Config.armLoadRollRight : Config.armLoadRollLeft;
        return Float.isFinite(load) ? load - (hand == 0 ? Config.armRollRight : Config.armRollLeft) : 0F;
    }

    private List<Knob> keyKnobs() {
        var out = new ArrayList<Knob>();
        var k = page == Page.KEY ? keysOf(track) : null;
        var key = k == null ? null : k.at(time);
        if (key == null) return out;
        var prev = k.before(key);
        var next = k.after(key);
        float end = Math.max(duration(), key.to());
        if (track == Track.HIDE) {
            float[] a = bounds(prev == null ? 0F : prev.to(), key.to() - 1F, key.ms());
            out.add(new KeyKnob("from", "Скрыта с", "Когда рука пропадает.", "мс", a[0], a[1], STEP, (x, y) -> y.ms(), (x, y, v) -> {
                x.range(y, v, y.to());
                time = Math.clamp(time, v, y.to());
            }));
            float[] b = bounds(key.ms() + 1F, next == null ? end : next.ms(), key.to());
            out.add(new KeyKnob("to", "Скрыта до", "Когда рука появляется снова.", "мс", b[0], b[1], STEP, (x, y) -> y.to(), (x, y, v) -> {
                x.range(y, y.ms(), v);
                time = Math.clamp(time, y.ms(), v);
            }));
            return out;
        }
        String moment = k.moment(key);
        float[] t = bounds(prev == null ? 0F : prev.ms() + 1F, next == null ? duration() : next.ms() - 1F, key.ms());
        out.add(new KeyKnob("time", "Время", moment == null ? "Когда рука в этом ключе. Ключ не заходит за соседние."
                : "Когда рука в этом ключе. Он записан от момента «" + moment + "» и сдвигается вместе с ним. Ключ не заходит за соседние.",
                "мс", t[0], t[1], STEP, (x, y) -> y.ms(), (x, y, v) -> {
                    x.retime(y, v);
                    time = v;
                }));
        switch (track) {
            case PATH -> {
                var e = k.pathEntry(key);
                if (e.grip()) break;
                if (shownView() != KeyView.APPROACH) {
                    String[] l = {"Точка ← →", "Точка ↓ ↑", "Точка вдоль"};
                    for (int c = 0; c < 3; c++) {
                        int ci = c;
                        float[] r = bounds(-16F, 16F, e.point[c]);
                        out.add(new KeyKnob("point." + c, l[c], "Точка детали, к которой идёт рука, в единицах модели этой детали.", "", r[0], r[1], 0.025F,
                                (x, y) -> x.pathEntry(y).point[ci], (x, y, v) -> {
                                    x.pathEntry(y).point[ci] = v;
                                    x.touched();
                                }));
                    }
                    float[] r = bounds(-180F, 180F, Float.isNaN(e.roll) ? defaultRoll() : e.roll);
                    out.add(new KeyKnob("roll", "Поворот кисти", "Поворот кисти у детали. Если его не задать, он как обычно у этой руки на зарядке.", "°",
                            r[0], r[1], 1F, (x, y) -> Float.isNaN(x.pathEntry(y).roll) ? defaultRoll() : x.pathEntry(y).roll, (x, y, v) -> {
                                x.pathEntry(y).roll = v;
                                x.touched();
                            }));
                } else {
                    if (e.side != null) {
                        String[] sl = {"Подход ← →", "Подход ↓ ↑", "Подход вдоль"};
                        for (int c = 0; c < 3; c++) {
                            int ci = c;
                            float[] r = bounds(-1F, 1F, e.side[c]);
                            out.add(new KeyKnob("side." + c, sl[c], "Откуда кисть подходит к детали, точнее стрелок: направление по осям модели детали.", "",
                                    r[0], r[1], 0.05F, (x, y) -> x.pathEntry(y).side == null ? 0F : x.pathEntry(y).side[ci], (x, y, v) -> {
                                        var pe = x.pathEntry(y);
                                        if (pe.side == null) pe.side = SIDE[0].clone();
                                        pe.side[ci] = v;
                                        x.touched();
                                    }));
                        }
                    }
                    String[] l = {"Плечо ← →", "Плечо ↓ ↑", "Плечо вперёд-назад"};
                    for (int c = 0; c < 3; c++) {
                        int ci = c;
                        float now = e.shoulder == null ? 0F : e.shoulder[c];
                        float[] r = bounds(-2F, 2F, now);
                        out.add(new KeyKnob("shoulder." + c, l[c], "Сдвиг плеча в этом ключе, чтобы рука дотянулась. 0 — плечо на месте.", "", r[0], r[1], 0.01F,
                                (x, y) -> x.pathEntry(y).shoulder == null ? 0F : x.pathEntry(y).shoulder[ci], (x, y, v) -> {
                                    var pe = x.pathEntry(y);
                                    if (pe.shoulder == null) pe.shoulder = new float[3];
                                    pe.shoulder[ci] = v;
                                    x.touched();
                                }));
                    }
                }
            }
            case CARRY -> {
                float[] v = k.value(key);
                out.add(new KeyKnob("weight", "Рука на детали", "0 — рука на оружии, 1 — на детали, которую несёт; между ними — на пути.", "", 0F,
                        Math.max(1F, v[1]), 0.05F, (x, y) -> x.value(y)[1], (x, y, w) -> {
                            float[] n = x.value(y);
                            n[1] = w;
                            x.values(y, n);
                        }));
                if ((int) v[2] == 1) {
                    float[] r = bounds(0F, duration(), v[3]);
                    out.add(new KeyKnob("bus", "Деталь как в", "Деталь стоит там, где была в этот момент анимации.", "мс", r[0], r[1], STEP,
                            (x, y) -> x.value(y)[3], (x, y, b) -> {
                                float[] n = x.value(y);
                                n[3] = b;
                                x.values(y, n);
                            }));
                }
                if ((int) v[2] == 2) {
                    String[] l = {"Точка ← →", "Точка ↓ ↑", "Точка вдоль"};
                    for (int c = 0; c < 3; c++) {
                        int ci = c;
                        float[] r = bounds(-16F, 16F, v[4 + c]);
                        out.add(new KeyKnob("carry." + c, l[c], "Где стоит деталь, в единицах модели оружия.", "", r[0], r[1], 0.025F,
                                (x, y) -> x.value(y)[4 + ci], (x, y, p) -> {
                                    float[] n = x.value(y);
                                    n[4 + ci] = p;
                                    x.values(y, n);
                                }));
                    }
                }
            }
            case AROUND -> {
                float[] r = bounds(-180F, 180F, k.value(key)[1]);
                out.add(new KeyKnob("angle", "Угол обхода", "На сколько градусов левая рука обходит деталь вокруг оси ствола.", "°", r[0], r[1], 1F,
                        (x, y) -> x.value(y)[1], (x, y, a) -> {
                            float[] n = x.value(y);
                            n[1] = a;
                            x.values(y, n);
                        }));
            }
            case ADJUST -> {
                String[] l = {"Сдвиг вправо", "Сдвиг вверх", "Сдвиг назад", "Наклон", "Поворот", "Крен"};
                String[] h = {"Сдвиг кисти вправо (минус — влево), в пикселях руки.", "Сдвиг кисти вверх (минус — вниз), в пикселях руки.",
                        "Сдвиг кисти назад (минус — вперёд), в пикселях руки.", "Наклон кисти вперёд-назад.", "Поворот кисти влево-вправо.",
                        "Крен кисти вокруг руки."};
                float[] v = k.value(key);
                for (int c = 0; c < 6; c++) {
                    int ci = c;
                    float lim = c < 3 ? 8F : 90F;
                    float[] r = bounds(-lim, lim, v[1 + c]);
                    out.add(new KeyKnob("adjust." + c, l[c], h[c], c < 3 ? "пикс." : "°", r[0], r[1], c < 3 ? 0.1F : 1F,
                            (x, y) -> x.value(y)[1 + ci], (x, y, a) -> {
                                float[] n = x.value(y);
                                n[1 + ci] = a;
                                x.values(y, n);
                            }));
                }
            }
            default -> {
            }
        }
        return out;
    }

    private List<Row> keyRows() {
        var out = new ArrayList<Row>();
        String why = noClip() != null ? noClip() : keyless();
        if (why != null) {
            out.add(new Row("Ключей нет: «" + track.label + "»", "Ключей здесь нет, потому что " + why + ".", null, List.of()));
            return out;
        }
        var k = keysOf(track);
        var key = k.at(time);
        if (key == null) {
            out.add(new Row("Здесь ключа нет", "На дорожке «" + track.label + "» в этот момент ключа нет. Перейди к ключу («← Ключ», «Ключ →»), добавь его («+ Ключ») или вставь скопированный."
                    + " Дорожку выбирают на странице «Анимации».", null, List.of("key.copy", "key.paste")));
            return out;
        }
        boolean pathPart = track == Track.PATH && !k.pathEntry(key).grip();
        boolean approach = shownView() == KeyView.APPROACH;
        out.add(new Row("Ключ " + (k.indexOf(key) + 1) + " из " + k.keys().size(),
                "Ключ " + (k.indexOf(key) + 1) + " из " + k.keys().size() + " дорожки «" + track.label + "». Дорожку выбирают на странице «Анимации». «Копировать» запоминает"
                        + " ключ, «Вставить» ставит запомненный на его место.", null, List.of("key.copy", "key.paste")));
        var knobs = keyKnobs();
        Runnable slider = () -> {
            var kn = knobs.remove(0);
            out.add(new Row(null, kn.hint, kn.setting(), List.of("set." + kn.id + ".minus", "set." + kn.id + ".plus")));
        };
        slider.run(); // time, or where the hand hides from
        if (track == Track.HIDE) {
            slider.run();
            return out;
        }
        if (track == Track.PATH) {
            var e = k.pathEntry(key);
            if (!approach || !pathPart)
                out.add(new Row("Деталь: " + keyPart(e.part), "Деталь модели, к которой идёт рука в этом ключе. «Хват» — рука на своём обычном месте.", null,
                        List.of("key.part.prev", "key.part.next")));
            if (!pathPart) return out;
            if (!approach) {
                out.add(new Row("Копия детали: " + (e.nth == 0 ? "первая" : e.nth < 0 ? "последняя" : "№" + (e.nth + 1)),
                        "Если модель рисует эту деталь несколько раз (патроны в ряд), какую из них брать.", null, List.of("key.nth")));
                for (int c = 0; c < 3; c++) slider.run();
                out.add(new Row("Касание: " + contactName(e.contact), "Какой частью кисти рука касается детали.", null,
                        List.of("key.contact.prev", "key.contact.next")));
                var roll = knobs.remove(0);
                out.add(new Row(null, roll.hint, roll.setting(), List.of("set." + roll.id + ".minus", "set." + roll.id + ".plus", "key.roll.reset")));
            } else {
                int si = sideIndex(e.side);
                out.add(new Row("Подход: " + (si < 0 ? "свой" : SIDES[si]) + (e.side == null ? " (обычно)" : ""),
                        "С какой стороны детали кисть к ней подходит.", null, List.of("key.side.prev", "key.side.next", "key.side.reset")));
                if (e.side != null) for (int c = 0; c < 3; c++) slider.run();
                // the shoulder's sliders, the first with the switch "follows the point" (a row less: the side's sliders fit)
                for (int c = 0; c < 3; c++) {
                    var kn = knobs.remove(0);
                    var ids = new ArrayList<>(List.of("set." + kn.id + ".minus", "set." + kn.id + ".plus"));
                    if (c == 0) ids.add("key.follow");
                    out.add(new Row(null, kn.hint, kn.setting(), ids));
                }
            }
            return out;
        }
        if (track == Track.CARRY) {
            slider.run();
            float[] v = k.value(key);
            out.add(new Row("Деталь: " + CARRY_KINDS[Math.clamp((int) v[2], 0, 2)], "Где деталь, которую несёт рука: там, где её двигает анимация, там, где она была в "
                    + "заданный момент, или в своей точке.", null, List.of("key.kind.prev", "key.kind.next")));
        }
        while (!knobs.isEmpty()) slider.run();
        return out;
    }

    private void keySettingActions(List<Action> out) {
        var k = noClip() != null || keyless() != null ? null : keysOf(track);
        var key = k == null ? null : k.at(time);
        if (key == null) return;
        if (track == Track.CARRY) {
            int kind = Math.clamp((int) k.value(key)[2], 0, 2);
            for (int d : new int[] {-1, 1})
                out.add(new Action(d < 0 ? "key.kind.prev" : "key.kind.next", d < 0 ? "◀" : "▶", "Где деталь: " + String.join(", ", CARRY_KINDS) + ".", "", null,
                        () -> change(() -> editKey((x, y) -> {
                            float[] n = x.value(y);
                            int to = Math.floorMod(kind + d, 3);
                            n[2] = to;
                            if (to == 1) n[3] = y.ms();
                            x.values(y, n);
                        }))));
        }
        if (track != Track.PATH) return;
        var e = k.pathEntry(key);
        boolean approach = shownView() == KeyView.APPROACH;
        var parts = pathParts(e.grip() ? "grip" : e.part);
        int at = parts.indexOf(e.grip() ? "grip" : e.part);
        String one = parts.size() < 2 ? "у модели этого оружия нет отдельных деталей" : null;
        // the view of how the hand comes has no row of the part
        if (!approach || e.grip()) for (int d : new int[] {-1, 1})
            out.add(new Action(d < 0 ? "key.part.prev" : "key.part.next", d < 0 ? "◀" : "▶", d < 0 ? "Предыдущая деталь." : "Следующая деталь.", "", one,
                    () -> change(() -> editKey((x, y) -> {
                        var pe = x.pathEntry(y);
                        String to = parts.get(Math.floorMod(at + d, parts.size()));
                        pe.attach(to, pe.point != null ? pe.point : new float[3]);
                        x.touched();
                    }))));
        if (e.grip()) return;
        if (!approach) {
            out.add(new Action("key.nth", e.nth == 0 ? "Последняя" : "Первая", "Брать первую или последнюю копию детали, если модель рисует её несколько раз.", "", null,
                    () -> change(() -> editKey((x, y) -> {
                        x.pathEntry(y).nth = x.pathEntry(y).nth == 0 ? -1 : 0;
                        x.touched();
                    }))));
            for (int d : new int[] {-1, 1})
                out.add(new Action(d < 0 ? "key.contact.prev" : "key.contact.next", d < 0 ? "◀" : "▶", "Другая часть кисти, которой рука касается детали.", "", null,
                        () -> change(() -> editKey((x, y) -> {
                            x.pathEntry(y).contact = Math.floorMod(x.pathEntry(y).contact + d, 14);
                            x.touched();
                        }))));
            out.add(new Action("key.roll.reset", "↺", "Поворот кисти как обычно у этой руки на зарядке.", "",
                    Float.isNaN(e.roll) ? "поворот кисти и так обычный" : null, () -> change(() -> editKey((x, y) -> {
                        x.pathEntry(y).roll = Float.NaN;
                        x.touched();
                    }))));
        } else {
            int si = sideIndex(e.side);
            for (int d : new int[] {-1, 1})
                out.add(new Action(d < 0 ? "key.side.prev" : "key.side.next", d < 0 ? "◀" : "▶", "Подход: " + String.join(", ", SIDES) + ".", "", null,
                        () -> change(() -> editKey((x, y) -> {
                            int to = si < 0 ? 0 : Math.floorMod(si + d, SIDE.length);
                            x.pathEntry(y).side = SIDE[to].clone();
                            x.touched();
                        }))));
            out.add(new Action("key.side.reset", "↺", "Подход как обычно: сзади.", "", e.side == null ? "подход и так обычный" : null,
                    () -> change(() -> editKey((x, y) -> {
                        x.pathEntry(y).side = null;
                        x.touched();
                    }))));
            out.add(new Action("key.follow", e.follow ? "На месте" : "За точкой", e.follow
                    ? "Сейчас плечо идёт вслед за точкой детали. Нажми — плечо будет стоять на месте, сдвинутое ползунками «Плечо»."
                    : "Сейчас плечо стоит на месте. Нажми — плечо пойдёт вслед за точкой детали, со сдвигом ползунков «Плечо».", "", null,
                    () -> change(() -> editKey((x, y) -> {
                        var pe = x.pathEntry(y);
                        pe.follow = !pe.follow;
                        if (pe.shoulder == null) pe.shoulder = new float[3];
                        x.touched();
                    }))));
        }
    }

    // ---- the key page: its views, and the settings of the whole track

    // why the key page cannot show a view of the selected track now; null = it can
    String viewWhy(KeyView v) {
        if (noClip() != null) return noClip();
        return switch (v) {
            case WHERE -> track == Track.RELEASE ? "у дорожки «Отпускает» нет ключей, только настройки всей дорожки" : null;
            case APPROACH -> {
                if (track != Track.PATH) yield "подход есть только у ключей дорожки «По деталям»";
                String why = keyless();
                if (why != null) yield why;
                var key = keysOf(track).at(time);
                if (key == null) yield "в этот момент ключа нет";
                yield keysOf(track).pathEntry(key).grip() ? "ключ на хвате: рука на своём месте, подходить не к чему" : null;
            }
            case TRACK -> track == Track.ADJUST ? "у дорожки «Поправка» нет настроек всей дорожки, только ключи" : trackWhy(track);
            case HOLD -> track != Track.CARRY ? "это есть только у дорожки «Несёт»" : null;
        };
    }

    // the view the key page shows: the one chosen when it can be shown, else the key (a track without keys: the track)
    KeyView shownView() {
        if (noClip() != null) return KeyView.WHERE;
        if (viewWhy(keyView) == null) return keyView;
        return viewWhy(KeyView.WHERE) == null ? KeyView.WHERE : KeyView.TRACK;
    }

    boolean trackView() {
        var v = shownView();
        return v == KeyView.TRACK || v == KeyView.HOLD;
    }

    private void viewActions(List<Action> out) {
        var shown = shownView();
        for (var v : KeyView.values())
            out.add(new Action("key.view." + v.name().toLowerCase(Locale.ROOT), v.label, v.hint, "", v == shown ? "это уже показано" : viewWhy(v), () -> {
                keyView = v;
                first = 0;
            }));
    }

    // a text setting of the gun: the gun's own value only when it differs from what the gun has anyway
    private void putText(String key, String text) {
        Grips.put(gun, key, null, false);
        if (!text.equals(config(key))) Grips.put(gun, key, text, false);
        Config.select(gun);
    }

    // a number that is NaN when unset and then acts as another setting (armLoadRollLeft: as armRollLeft)
    private class FallbackKnob extends ConfigKnob {
        final String fallback;

        FallbackKnob(String key, String fallback, String label, String hint, String unit, float lo, float hi, float step) {
            super(key, -1, label, hint, unit, lo, hi, step);
            this.fallback = fallback;
        }

        @Override
        float get() {
            float v = super.get();
            return Float.isNaN(v) ? ((Number) config(fallback)).floatValue() : v;
        }
    }

    // a switch of the gun: key, label, hint
    private Row switchRow(String[] sw) {
        return new Row(sw[1] + ": " + (switchOn(sw[0]) ? "да" : "нет"), sw[2], null, List.of("set." + sw[0] + ".toggle", "set." + sw[0] + ".reset"));
    }

    private void switchActions(List<Action> out, String[] sw, String why) {
        String key = sw[0];
        out.add(new Action("set." + key + ".toggle", switchOn(key) ? "Выключить" : "Включить", sw[2], "", why, () -> change(() -> {
            Grips.put(gun, key, String.valueOf(!switchOn(key)), false);
            Config.select(gun);
        })));
        String resetWhy = Grips.own(gun, key) != null ? null : "у этого оружия нет своего значения";
        if (key.equals("armPistolScale") && why != null) resetWhy = why;
        out.add(new Action("set." + key + ".reset", "↺", "Вернуть «" + sw[1] + "», как задано в моде.", "",
                resetWhy, () -> change(() -> {
                    Grips.put(gun, key, null, false);
                    Config.select(gun);
                })));
    }

    private static final String[] PATH_BLEND = {"armPathBlend", "Плавно к хвату", "Рядом с хватом рука на пути плавно переходит в обычный хват (толщина, поворот)."};
    private static final String[] KEEP_ANGLE = {"armCarryKeepAngle", "Рука как на хвате",
            "Рука, которая несёт, не меняет угол и длину, а только сдвигается (как бутылка в осмотре дробовика). Для обеих рук."};
    static final int MOMENTS = 4;

    // ---- named moments (armPathTimes): "name=ms" entries, the game reads each name (lower case), the last one wins

    private record Moment(String name, float ms, int entry) {
    }

    private List<Moment> moments() {
        String[] entries = ((String) config("armPathTimes")).split(",");
        Map<String, Moment> byName = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i++) {
            String t = entries[i];
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            try {
                String name = t.substring(0, eq).trim();
                float ms = Float.parseFloat(t.substring(eq + 1).trim());
                byName.remove(name.toLowerCase(Locale.ROOT));
                byName.put(name.toLowerCase(Locale.ROOT), new Moment(name, ms, i));
            } catch (NumberFormatException e) {
                // the game skips it too
            }
        }
        var out = new ArrayList<>(byName.values());
        out.sort(Comparator.comparingInt(Moment::entry));
        return out;
    }

    private void putMoment(Moment m, Float ms) {
        var entries = new ArrayList<>(List.of(((String) config("armPathTimes")).split(",")));
        if (ms != null) entries.set(m.entry, m.name + "=" + ArmRig.number(ms));
        else entries.removeIf(t -> t.indexOf('=') > 0 && t.substring(0, t.indexOf('=')).trim().equalsIgnoreCase(m.name));
        entries.removeIf(String::isBlank);
        putText("armPathTimes", String.join(",", entries));
    }

    // the path keys of both hands, in every animation, written from a moment
    private int keysFrom(String moment) {
        var times = ArmRig.pathTimes((String) config("armPathTimes"));
        int n = 0;
        for (String src : new String[] {(String) config("armRightPath"), (String) config("armLeftPath")})
            for (var seg : ArmRig.path(src).segments)
                for (var e : seg.entries) {
                    String base = e.valid() ? ArmRig.momentOf(e.time, times) : null;
                    if (base != null && base.trim().equalsIgnoreCase(moment)) n++;
                }
        return n;
    }

    private int longest() {
        int out = 0;
        for (var c : list) out = Math.max(out, c.ms());
        return out;
    }

    private final class MomentKnob extends Knob {
        final int i;

        MomentKnob(int i, Moment m) {
            super("moment." + i, "Момент «" + m.name + "»",
                    "Время с именем. Ключи, записанные от него, сдвигаются вместе с ним: у обеих рук, во всех анимациях.", "мс", STEP);
            this.i = i;
        }

        float get() {
            return moments().get(i).ms;
        }

        void put(float v) {
            putMoment(moments().get(i), v);
        }

        float lo() {
            return Math.min(0F, get());
        }

        float hi() {
            return Math.max(longest(), get());
        }
    }

    // ---- what a hand carries (armLeftCarry, or armLeftCarryReload on a reload that has it)

    private static boolean reloadClip(String name) {
        return name.equals("RELOAD") || name.equals("RELOAD_CYCLE") || name.equals("RELOAD_END");
    }

    private boolean onReload() {
        return !list.isEmpty() && reloadClip(clip().name());
    }

    private String reloadKey() {
        return "arm" + side() + "CarryReload";
    }

    // the setting the game reads for what this hand carries in this animation
    private String carryKey() {
        return onReload() && !((String) config(reloadKey())).isEmpty() ? reloadKey() : "arm" + side() + "Carry";
    }

    // what is carried, as an edit: part, buses with a point each, grab time, side (null = from behind)
    private static final class Carried {
        String part;
        final List<String> buses = new ArrayList<>();
        final List<float[]> ats = new ArrayList<>();
        int grab;
        float[] dir;

        static Carried of(ArmRig.CarrySpec c) {
            var out = new Carried();
            out.part = c.part();
            out.buses.addAll(List.of(c.buses()));
            for (var a : c.ats()) out.ats.add(a.clone());
            // as the game: a bus without its own point takes the last one
            while (out.ats.size() < out.buses.size()) out.ats.add(out.ats.get(out.ats.size() - 1).clone());
            out.grab = c.grab();
            out.dir = c.dir() == null ? null : c.dir().clone();
            return out;
        }

        String write() {
            var pts = new ArrayList<String>();
            for (var a : ats) pts.add(ArmRig.vector(a));
            return part + " " + String.join("|", buses) + " " + grab + " " + String.join("|", pts) + (dir == null ? "" : " " + ArmRig.vector(dir));
        }
    }

    private Carried carried() {
        var c = ArmRig.carry((String) config(carryKey()));
        return c == null ? null : Carried.of(c);
    }

    private void putCarried(Carried c) {
        putText(carryKey(), c.write());
    }

    // the buses that move in this animation, as the game names them
    private List<String> moving() {
        return list.isEmpty() ? List.of() : new ArrayList<>(new TreeSet<>(clips.moves(clip().id()).keySet()));
    }

    // the bus the rows show and the sliders change: the one the animation moves now, else the last one it moved by now,
    // else the first one it moves (several shells, one by one: the one in the hand at this time)
    private int activeBus(Carried c) {
        var m = list.isEmpty() ? Map.<String, int[]>of() : clips.moves(clip().id());
        int last = -1, first = -1;
        float lastFrom = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < c.buses.size(); i++) {
            int[] w = m.get(c.buses.get(i));
            if (w == null) continue;
            if (first < 0) first = i;
            for (int j = 0; j + 1 < w.length; j += 2) {
                if (time >= w[j] && time <= w[j + 1]) return i;
                if (w[j] <= time && w[j] > lastFrom) {
                    lastFrom = w[j];
                    last = i;
                }
            }
        }
        return last >= 0 ? last : Math.max(first, 0);
    }

    // something new to carry: the first part that moves in this animation, within the gun's first part
    private Carried newCarried() {
        var m = moving();
        if (m.isEmpty()) return null;
        var c = new Carried();
        c.part = clips.parts().isEmpty() ? "Gun" : clips.parts().get(0);
        c.buses.add(m.get(0));
        c.ats.add(new float[3]);
        return c;
    }

    private interface CarryGet {
        float get(Carried c, int bus);
    }

    private interface CarryPut {
        void put(Carried c, int bus, float v);
    }

    private class CarryKnob extends Knob {
        final float lo, hi;
        final CarryGet get;
        final CarryPut put;

        CarryKnob(String id, String label, String hint, String unit, float lo, float hi, float step, CarryGet get, CarryPut put) {
            super("carry." + id, label, hint, unit, step);
            this.lo = lo;
            this.hi = hi;
            this.get = get;
            this.put = put;
        }

        float get() {
            var c = carried();
            return get.get(c, activeBus(c));
        }

        void put(float v) {
            var c = carried();
            put.put(c, activeBus(c), Math.clamp(v, lo(), hi()));
            putCarried(c);
        }

        float lo() {
            return Math.min(lo, get());
        }

        float hi() {
            return Math.max(hi, get());
        }
    }

    private static final String[] XYZ = {"← →", "↓ ↑", "вдоль"};

    private String releaseKey() {
        return "arm" + "Release" + side();
    }

    private String releaseBus() {
        return (String) config(releaseKey());
    }

    // the knobs of the selected track's settings
    private List<Knob> trackKnobs() {
        var out = new ArrayList<Knob>();
        var v = shownView();
        if (v != KeyView.TRACK && v != KeyView.HOLD || noClip() != null) return out;
        String s = side();
        switch (track) {
            case PATH -> {
                var ms = moments();
                for (int i = 0; i < Math.min(ms.size(), MOMENTS); i++) out.add(new MomentKnob(i, ms.get(i)));
                out.add(new ConfigKnob("armPathMinLength", -1, "Рука видна хотя бы",
                        "Рука на пути у самого глаза показывает хотя бы столько пикселей руки. 0 — сколько отрежет плоскость. Для обеих рук.", "пикс.", 0F, 32F, 1F));
            }
            case CARRY -> {
                if (carried() == null) break;
                if (v == KeyView.TRACK) {
                    out.add(new CarryKnob("grab", "Взять в", "До этого момента рука тянется туда, где деталь тогда будет; дальше несёт её.", "мс", 0F,
                            duration(), STEP, (c, b) -> c.grab, (c, b, x) -> c.grab = Math.round(x)) {
                        @Override
                        boolean whole() {
                            return true;
                        }
                    });
                    for (int i = 0; i < 3; i++) {
                        int ci = i;
                        out.add(new CarryKnob("at." + i, "Точка " + XYZ[i], "Какую точку детали держит рука, в единицах модели.", "", -16F, 16F, 0.025F,
                                (c, b) -> c.ats.get(b)[ci], (c, b, x) -> c.ats.get(b)[ci] = x));
                    }
                } else {
                    for (int i = 0; i < 3; i++) {
                        int ci = i;
                        out.add(new CarryKnob("dir." + i, "Подход " + XYZ[i],
                                "С какой стороны и как далеко рука от точки: длина 1 — кисть касается её. Обычно сзади: 0, 0, −1.", "", -3F, 3F, 0.05F,
                                (c, b) -> (c.dir == null ? ArmRig.DEFAULT_SIDE : c.dir)[ci], (c, b, x) -> {
                                    if (c.dir == null) c.dir = ArmRig.DEFAULT_SIDE.clone();
                                    c.dir[ci] = x;
                                }));
                    }
                    out.add(new ConfigKnob("armCarryTurn" + s, -1, "Поворот у патрона",
                            "Рука, которая несёт, поворачивается вокруг точки по оси патрона, чтобы не уходить в оружие. Плюс — плечом вправо.", "°", -90F, 90F, 1F));
                }
            }
            case RELEASE -> {
                String key = hand == 0 ? "armReleaseMoveRight" : "armReleaseMove";
                String[] l = {"Отходит ← →", "Отходит ↓ ↑", "Отходит вперёд-назад"};
                for (int i = 0; i < 3; i++)
                    out.add(new ConfigKnob(key, i, l[i], "Куда рука отходит, пока оружие вращается, и возвращается, в пикселях руки.", "пикс.", -8F, 8F, 0.1F) {
                        @Override
                        String why() {
                            return releaseBus().isEmpty() ? "рука не отпускает оружие: выбери деталь выше" : null;
                        }

                        @Override
                        String off() {
                            return "нет детали";
                        }
                    });
            }
            case AROUND -> {
                out.add(new ConfigKnob("armAroundShift", -1, "Сдвиг наружу", "Когда рука обходит деталь, она стоит на столько левее, снаружи детали. Под деталью сдвига нет.",
                        "пикс.", -4F, 4F, 0.1F));
                out.add(new ConfigKnob("armAroundTopDrop", -1, "Ниже сверху", "Когда рука над деталью, она опускается к ней на столько. Сбоку — меньше, до нуля.",
                        "пикс.", -4F, 4F, 0.1F));
                out.add(new FallbackKnob("armAroundShiftReload", "armAroundShift", "Сдвиг на зарядке", "То же на перезарядке. Если не задано, как «Сдвиг наружу».",
                        "пикс.", -4F, 4F, 0.1F));
                out.add(new FallbackKnob("armAroundTopDropReload", "armAroundTopDrop", "Ниже на зарядке", "То же на перезарядке. Если не задано, как «Ниже сверху».",
                        "пикс.", -4F, 4F, 0.1F));
            }
            default -> {
            }
        }
        return out;
    }

    private Row knobRow(Knob k) {
        return new Row(null, k.hint, k.setting(), k instanceof ConfigKnob ? List.of("set." + k.id + ".minus", "set." + k.id + ".plus", "set." + k.id + ".reset")
                : List.of("set." + k.id + ".minus", "set." + k.id + ".plus"));
    }

    // ---- keys written for one variant of an animation ("RELOAD/PumpGrip": the SPAS-12's reload from empty)

    private static boolean byBus(Track t) {
        return t == Track.HIDE || t == Track.PATH || t == Track.CARRY;
    }

    // the parts that move in this animation and that another animation of the same name lacks
    private List<String> ownBuses() {
        var out = new ArrayList<String>();
        if (list.isEmpty()) return out;
        var c = clip();
        for (String b : moving())
            for (var o : list)
                if (o.name().equals(c.name()) && !o.id().equals(c.id()) && !clips.buses(o.id()).has(b) && !clips.moves(o.id()).containsKey(b)
                        && !out.contains(b)) out.add(b);
        return out;
    }

    private Row onlyRow() {
        var k = keysOf(track);
        String bus = k == null ? null : k.onlyWith();
        return new Row("Ключи: " + (bus == null ? "общие" : "только при " + bus),
                "У анимации может быть вариант, где движется своя деталь (перезарядка из пустого). Ключи можно записать только для него; "
                        + "общие — те же, что у обычной анимации.", null, List.of("key.only.prev", "key.only.next"));
    }

    private void onlyActions(List<Action> out) {
        var k = keysOf(track);
        String now = k == null ? null : k.onlyWith();
        var options = new ArrayList<String>();
        options.add(null);
        options.addAll(ownBuses());
        if (now != null && !options.contains(now)) options.add(now);
        int at = options.indexOf(now);
        String why = keyless() != null ? keyless() : k == null || k.keys().isEmpty() ? "ключей этой дорожки здесь нет"
                : options.size() < 2 ? "у этой анимации нет варианта со своей деталью: ключи общие" : null;
        for (int d : new int[] {-1, 1})
            out.add(new Action(d < 0 ? "key.only.prev" : "key.only.next", d < 0 ? "◀" : "▶",
                    "«Только при …»: этот вариант анимации получает свои ключи, сначала такие же, как сейчас. «Общие»: свои ключи варианта"
                            + " убираются, он играет ключи обычной анимации (нет их — на этой дорожке ничего). Отменить — «Отменить».", "", why,
                    () -> change(() -> editKey((x, y) -> x.onlyWith(options.get(Math.floorMod(at + d, options.size())))))));
    }

    private List<Row> trackRows() {
        var out = new ArrayList<Row>();
        var knobs = new ArrayDeque<>(trackKnobs());
        var v = shownView();
        if (v == KeyView.TRACK && byBus(track)) out.add(onlyRow());
        switch (track) {
            case PATH -> {
                var ms = moments();
                out.add(new Row("Моменты: " + ms.size(), "Время с именем: от него можно записать ключи («Время ключа» ниже), и они сдвигаются вместе с ним.", null,
                        List.of("moment.add")));
                var k = keysOf(track);
                var key = k == null ? null : k.at(time);
                String from = key == null ? null : k.moment(key);
                out.add(new Row("Время ключа: " + (key == null ? "здесь ключа нет" : from == null ? "числом" : "от «" + from.trim() + "»"),
                        "Записать время ключа в этот момент числом или от момента: тогда ключ сдвигается вместе с моментом.", null,
                        List.of("key.moment.prev", "key.moment.next")));
                for (int i = 0; i < Math.min(ms.size(), MOMENTS); i++) {
                    var kn = knobs.poll();
                    out.add(new Row(null, kn.hint, kn.setting(), List.of("set." + kn.id + ".minus", "set." + kn.id + ".plus", "moment." + i + ".delete")));
                }
                out.add(knobRow(knobs.poll()));
                out.add(switchRow(PATH_BLEND));
            }
            case CARRY -> {
                var c = carried();
                String what = c == null ? (((String) config(carryKey())).isEmpty() ? "ничего" : "запись с ошибкой") : c.buses.get(activeBus(c));
                String reload = carryKey().equals(reloadKey()) ? "На зарядке несёт: " : "Несёт: ";
                if (v == KeyView.TRACK) {
                    out.add(new Row(reload + what, "Что рука берёт: деталь, которую двигает анимация. Когда она её несёт — ключи этой дорожки.", null,
                            List.of("carry.add", "carry.delete")));
                    if (onReload())
                        out.add(new Row("На зарядке: " + (carryKey().equals(reloadKey()) ? "своё" : "как всегда"),
                                "На перезарядке рука может нести другое, чем в остальных анимациях.", null, List.of("carry.reload.own", "carry.reload.common")));
                    if (c == null) break;
                    out.add(new Row("Внутри: " + partName(c.part), "Деталь оружия, внутри которой анимация двигает то, что несёт рука.", null,
                            List.of("carry.part.prev", "carry.part.next")));
                    out.add(new Row("Деталь: " + c.buses.get(activeBus(c)) + (c.buses.size() > 1 ? ", " + (activeBus(c) + 1) + " из " + c.buses.size() : ""),
                            "Что несёт рука: деталь, которую двигает анимация. Если их несколько (патроны по одному), рука берёт ту, что движется сейчас.", null,
                            List.of("carry.bus.prev", "carry.bus.next", "carry.bus.add", "carry.bus.delete")));
                } else {
                    if (c == null) {
                        out.add(new Row(reload + what, "Сначала задай, что рука несёт: вид «Дорожка».", null, List.of()));
                        break;
                    }
                    out.add(new Row("Подход: " + (c.dir == null ? "обычный, сзади" : "свой"), "С какой стороны рука держит то, что несёт.", null,
                            List.of("carry.dir.reset")));
                }
                while (knobs.size() > (v == KeyView.HOLD ? 1 : 0)) out.add(knobRow(knobs.poll()));
                if (v == KeyView.HOLD) {
                    out.add(knobRow(knobs.poll()));
                    out.add(switchRow(KEEP_ANGLE));
                }
            }
            case RELEASE -> {
                String bus = releaseBus();
                out.add(new Row(bus.isEmpty() ? "Отпускает: никогда" : "Отпускает, пока вращается: " + bus,
                        "Рука отпускает оружие, пока эта деталь делает оборот, и берёт снова, когда оборот закончен.", null,
                        List.of("release.prev", "release.next", "release.reset")));
                while (!knobs.isEmpty()) out.add(knobRow(knobs.poll()));
            }
            case AROUND -> {
                out.add(new Row("«Обход детали»: где рука", "Как стоит левая рука, когда обходит деталь. Углы обхода — ключи этой дорожки.", null, List.of()));
                while (!knobs.isEmpty()) out.add(knobRow(knobs.poll()));
            }
            default -> {
            }
        }
        return out;
    }

    private void trackActions(List<Action> out) {
        if (shownView() == KeyView.TRACK && byBus(track)) onlyActions(out);
        switch (track) {
            case PATH -> {
                var ms = moments();
                out.add(new Action("moment.add", "+ Момент", "Добавить момент с именем на этом времени.", "",
                        ms.size() >= MOMENTS ? "больше " + MOMENTS + " моментов здесь не помещается" : null, () -> change(() -> {
                            var names = ArmRig.pathTimes((String) config("armPathTimes")).keySet();
                            int n = 1;
                            while (names.contains("m" + n)) n++;
                            String src = (String) config("armPathTimes");
                            putText("armPathTimes", (src.isBlank() ? "" : src + ",") + "m" + n + "=" + ArmRig.number(Math.round(time)));
                        })));
                for (int i = 0; i < Math.min(ms.size(), MOMENTS); i++) {
                    var m = ms.get(i);
                    int used = keysFrom(m.name);
                    out.add(new Action("moment." + i + ".delete", "✕", "Удалить момент «" + m.name + "».", "",
                            used > 0 ? "от него записаны ключи (" + used + "): сначала запиши их числом" : null, () -> change(() -> putMoment(m, null))));
                }
                var k = keysOf(track);
                var key = k == null ? null : k.at(time);
                // in milliseconds, or from each moment the key's time adds up to exactly as the game reads it
                String now = key == null ? null : k.moment(key);
                var names = new ArrayList<String>();
                names.add(null);
                for (var m : ms)
                    if (key != null && (now != null && m.name.equalsIgnoreCase(now.trim()) || ArmRig.fromMoment(m.name, m.ms, key.ms()) != null))
                        names.add(m.name);
                String cannot = keyless() != null ? keyless() : key == null ? "в этот момент ключа нет" : ms.isEmpty() ? "моментов нет: добавь «+ Момент»"
                        : names.size() < 2 ? "время ключа не записать от момента точно: оно остаётся числом" : null;
                for (int d : new int[] {-1, 1})
                    out.add(new Action(d < 0 ? "key.moment.prev" : "key.moment.next", d < 0 ? "◀" : "▶", "Записать время ключа числом или от момента: "
                            + "ключ остаётся на месте.", "", cannot, () -> change(() -> editKey((x, y) -> {
                                var times = ArmRig.pathTimes((String) config("armPathTimes"));
                                var pe = x.pathEntry(y);
                                int at = 0;
                                for (int j = 1; j < names.size(); j++) if (now != null && names.get(j).equalsIgnoreCase(now.trim())) at = j;
                                String to = names.get(Math.floorMod(at + d, names.size()));
                                String from = to == null ? null : ArmRig.fromMoment(to, times.get(to.toLowerCase(Locale.ROOT)), y.ms());
                                pe.time = from != null ? from : ArmRig.number(y.ms());
                                x.touched();
                            }))));
                switchActions(out, PATH_BLEND, null);
            }
            case CARRY -> {
                var c = carried();
                String text = (String) config(carryKey());
                boolean reload = carryKey().equals(reloadKey());
                var fresh = newCarried();
                String nothing = "в этой анимации ничего не движется: нести нечего";
                if (shownView() == KeyView.TRACK) {
                out.add(new Action("carry.add", "+ Нести", "Рука несёт деталь, которую двигает анимация. Когда — задают ключи этой дорожки.", "",
                        c != null ? "рука уже несёт: правь ниже" : fresh == null ? nothing : null, () -> change(() -> putText(carryKey(), fresh.write()))));
                out.add(new Action("carry.delete", "Не нести", "Рука ничего не несёт.", "",
                        reload ? "на зарядке «Как всегда» вернёт то, что рука несёт обычно" : text.isEmpty() ? "рука и так ничего не несёт" : null,
                        () -> change(() -> putText(carryKey(), ""))));
                if (onReload()) {
                    String main = (String) config("arm" + side() + "Carry");
                    out.add(new Action("carry.reload.own", "Своё", "Дать перезарядке своё: сперва как всегда, дальше правь.", "",
                            reload ? "на зарядке уже своё" : main.isEmpty() && fresh == null ? nothing : null,
                            () -> change(() -> putText(reloadKey(), main.isEmpty() ? fresh.write() : main))));
                    out.add(new Action("carry.reload.common", "Как всегда", "На перезарядке рука несёт то же, что всегда.", "",
                            reload ? null : "на зарядке и так как всегда", () -> change(() -> putText(reloadKey(), ""))));
                }
                }
                if (c == null) return;
                if (shownView() == KeyView.TRACK) {
                    var parts = new ArrayList<String>(clips.parts());
                    if (!parts.contains(c.part)) parts.add(c.part);
                    int at = parts.indexOf(c.part);
                    for (int d : new int[] {-1, 1})
                        out.add(new Action(d < 0 ? "carry.part.prev" : "carry.part.next", d < 0 ? "◀" : "▶", "Другая деталь оружия.", "",
                                parts.size() < 2 ? "у модели этого оружия нет отдельных деталей" : null, () -> change(() -> {
                                    c.part = parts.get(Math.floorMod(at + d, parts.size()));
                                    putCarried(c);
                                })));
                    int b = activeBus(c);
                    var buses = new ArrayList<>(moving());
                    if (!buses.contains(c.buses.get(b))) buses.add(c.buses.get(b));
                    int bi = buses.indexOf(c.buses.get(b));
                    for (int d : new int[] {-1, 1})
                        out.add(new Action(d < 0 ? "carry.bus.prev" : "carry.bus.next", d < 0 ? "◀" : "▶", "Другая деталь, которую двигает эта анимация.", "",
                                buses.size() < 2 ? "в этой анимации больше ничего не движется" : null, () -> change(() -> {
                                    c.buses.set(b, buses.get(Math.floorMod(bi + d, buses.size())));
                                    putCarried(c);
                                })));
                    var more = new ArrayList<>(moving());
                    more.removeAll(c.buses);
                    out.add(new Action("carry.bus.add", "+", "Ещё одна деталь: рука берёт ту, что движется сейчас (патроны по одному).", "",
                            more.isEmpty() ? "всё, что движется в этой анимации, уже в списке" : null, () -> change(() -> {
                                c.buses.add(more.get(0));
                                c.ats.add(c.ats.get(b).clone());
                                putCarried(c);
                            })));
                    out.add(new Action("carry.bus.delete", "−", "Убрать эту деталь из списка.", "", c.buses.size() < 2 ? "деталь одна: «Не нести» уберёт её" : null,
                            () -> change(() -> {
                                c.buses.remove(b);
                                c.ats.remove(b);
                                putCarried(c);
                            })));
                } else {
                    out.add(new Action("carry.dir.reset", "↺", "Подход как обычно: сзади.", "", c.dir == null ? "подход и так обычный" : null, () -> change(() -> {
                        c.dir = null;
                        putCarried(c);
                    })));
                    switchActions(out, KEEP_ANGLE, null);
                }
            }
            case RELEASE -> {
                String bus = releaseBus();
                var choices = new ArrayList<String>();
                choices.add("");
                choices.addAll(moving());
                if (!choices.contains(bus)) choices.add(bus);
                int at = choices.indexOf(bus);
                for (int d : new int[] {-1, 1})
                    out.add(new Action(d < 0 ? "release.prev" : "release.next", d < 0 ? "◀" : "▶", "При вращении какой детали рука отпускает оружие, или никогда.", "",
                            choices.size() < 2 ? "в этой анимации ничего не движется" : null,
                            () -> change(() -> putText(releaseKey(), choices.get(Math.floorMod(at + d, choices.size()))))));
                out.add(new Action("release.reset", "↺", "Вернуть, как задано в моде.", "", Grips.own(gun, releaseKey()) != null ? null : "у этого оружия нет своего значения",
                        () -> change(() -> {
                            Grips.put(gun, releaseKey(), null, false);
                            Config.select(gun);
                        })));
            }
            default -> {
            }
        }
    }

    // ---- the grip of the selected hand

    private String gunBase() {
        return base();
    }

    String variantParts() {
        String v = clips.variant();
        return v == null || v.isEmpty() ? "" : String.join(", ", v.substring(1).split("-"));
    }

    // where the selected hand's grip is written: the variant when it has a grip of its own, else the gun
    private String gripTarget() {
        String v = clips.variant();
        if (v == null || v.isEmpty()) return gunBase();
        return Grips.variantHand(gunBase() + v, hand == 0, false) ? gunBase() + v : gunBase();
    }

    private Grips.Grip gripNow() {
        String v = clips.variant();
        return Grips.get(net.minecraft.resources.Identifier.parse(gunBase()), v == null ? "" : v);
    }

    private boolean gripPoint() {
        var g = gripNow();
        return (hand == 0 ? g.rightAt() : g.leftAt()) != null;
    }

    private float[] grip() {
        var g = gripNow();
        float[] at = hand == 0 ? g.rightAt() : g.leftAt();
        return at != null ? at : hand == 0 ? g.right() : g.left();
    }

    private boolean gripOwn() {
        String t = gripTarget(), h = hand == 0 ? "right" : "left";
        return Grips.own(t, h) != null || Grips.own(t, h + "At") != null;
    }

    private String partKey() {
        return "armPart" + side();
    }

    private List<String> partChoices() {
        var out = new ArrayList<String>();
        out.add("");
        for (String p : clips.parts()) if (!out.contains(p)) out.add(p);
        String now = (String) config(partKey());
        if (!out.contains(now)) out.add(now);
        return out;
    }

    static String partName(String part) {
        return part.isEmpty() ? "корпус" : part;
    }

    // ---- rows and actions of the settings pages

    // how many of n lines of settings are shown: all when they fit, else as many as fit over the line «Строки … из …»
    private int shownCount(int n) {
        return n <= room ? n : Math.max(1, room - 1);
    }

    private int firstShown(int n) {
        return Math.clamp(first, 0, Math.max(0, n - shownCount(n)));
    }

    // the lines of settings on screen: those that fit from the first shown, and when not all fit, a line to scroll them
    List<Row> shownRows() {
        var all = rows();
        int n = all.size();
        if (n <= room) return all;
        int c = shownCount(n), f = firstShown(n);
        var out = new ArrayList<>(all.subList(f, f + c));
        out.add(new Row("Строки " + (f + 1) + "–" + (f + c) + " из " + n,
                "Все настройки страницы на экран не помещаются. Листай кнопками ▲ ▼ справа или колесом мыши.", null, List.of("rows.up", "rows.down")));
        return out;
    }

    // the actions of the lines scrolled away: they have no button now
    Set<String> hiddenIds() {
        var out = new HashSet<String>();
        for (var r : rows()) out.addAll(r.ids());
        for (var r : shownRows()) out.removeAll(r.ids());
        return out;
    }

    List<Row> rows() {
        var out = new ArrayList<Row>();
        if (page == Page.TIME) return out;
        if (page == Page.KEY) return trackView() ? trackRows() : keyRows();
        if (page == Page.FILE) return fileRows();
        if (page == Page.GRIP) {
            out.add(new Row("Хват " + (hand == 0 ? "правой" : "левой") + (gripPoint() ? ": точкой модели" : ": в долях модели"),
                    "Точка не сдвигается, когда обвесы меняют размер модели; доли идут за ними.", null, List.of("grip.mode", "grip.reset")));
        }
        for (Knob k : knobs(page)) {
            var r = knobRow(k);
            if (k.id.equals("armInspectReleaseMs")) r = new Row(null, r.hint(), r.slider(), List.of("set.armInspectReleaseMs.minus", "set.armInspectReleaseMs.plus",
                    "set.armInspectReleaseMs.toggle", "set.armInspectReleaseMs.reset"));
            out.add(r);
        }
        if (page == Page.GRIP) {
            out.add(new Row("Деталь: " + partName((String) config(partKey())), "Деталь модели, с которой двигается кисть (помпа, рычаг). Корпус — всё оружие.", null,
                    List.of("grip.part.prev", "grip.part.next", "grip.part.reset")));
            if (!variantParts().isEmpty())
                out.add(new Row("Хват вида: " + (Grips.variantHand(gunBase() + clips.variant(), hand == 0, false) ? "свой" : "общий"),
                        "Оружие сейчас без деталей " + variantParts() + " (например, обрез). Такой вид может держаться иначе."
                                + " Общий хват — такой же, как у всего оружия.", null,
                        List.of("grip.variant.own", "grip.variant.common")));
        }
        if (page == Page.BOTH) for (String[] sw : SWITCHES) out.add(switchRow(sw));
        return out;
    }

    private void settingActions(List<Action> out) {
        if (page == Page.KEY) {
            if (trackView()) trackActions(out);
            else keySettingActions(out);
        }
        for (Knob k : knobs(page)) {
            var st = k.setting();
            float eps = st.step() * 1e-3F;
            String own = k instanceof ConfigKnob ck && Grips.own(gun, ck.key) != null ? null : "у этого оружия нет своего значения";
            String why = st.why();
            out.add(new Action("set." + k.id + ".minus", "−", "Уменьшить «" + k.label + "» на " + number(st.step(), st.step()) + ".", "",
                    why != null ? why : st.value() <= st.min() + eps ? "уже наименьшее значение" : null,
                    () -> change(() -> k.put(stepTo(st, -1)))));
            out.add(new Action("set." + k.id + ".plus", "+", "Увеличить «" + k.label + "» на " + number(st.step(), st.step()) + ".", "",
                    why != null ? why : st.value() >= st.max() - eps ? "уже наибольшее значение" : null,
                    () -> change(() -> k.put(stepTo(st, 1)))));
            if (k instanceof ConfigKnob ck)
                out.add(new Action("set." + k.id + ".reset", "↺", "Вернуть «" + k.label + "», как задано в моде для этого оружия или для всех.", "", own,
                        () -> change(() -> {
                            Grips.put(gun, ck.key, null, false);
                            Config.select(gun);
                        })));
        }
        if (page == Page.ARM && hand == 1) {
            boolean on = (Float) config("armInspectReleaseMs") >= 0F;
            out.add(new Action("set.armInspectReleaseMs.toggle", on ? "Выкл" : "Вкл", on ? "Левая рука не держит магазин при осмотре."
                    : "При осмотре левая рука держит вынутый магазин (до середины осмотра, время правится ползунком).", "",
                    on || indexOf("INSPECT") >= 0 ? null : "у этого оружия нет осмотра", () -> change(() -> {
                        int inspect = indexOf("INSPECT");
                        Grips.put(gun, "armInspectReleaseMs", on ? "-1" : ArmRig.number(inspect < 0 ? 500 : list.get(inspect).ms() / 2 / STEP * STEP), false);
                        Config.select(gun);
                    })));
        }
        if (page == Page.GRIP) {
            var sh = clips.shape();
            boolean point = gripPoint();
            out.add(new Action("grip.mode", point ? "В доли" : "В точку",
                    point ? "Задать хват долями модели: он пойдёт за обвесами, когда они меняют размер модели."
                            : "Задать хват точкой модели: он не сдвинется от обвесов.", "",
                    sh == null ? "модель оружия ещё не измерена: посмотри на него секунду"
                            : !sameWay(sh, point) ? "хват за краем модели: в " + (point ? "долях" : "точке") + " рука бы сдвинулась" : null,
                    () -> change(() -> {
                        float[] g = grip();
                        if (point) {
                            float[] f = sh.fractions(g);
                            Grips.set(gripTarget(), hand == 0, f[0], f[1], f[2], false);
                        } else {
                            float[] at = sh.point(g);
                            Grips.setAt(gripTarget(), hand == 0, at[0], at[1], at[2], false);
                        }
                        Config.select(gun);
                    })));
            out.add(new Action("grip.reset", "↺", "Вернуть хват этой руки, как задано в моде (или как мод находит его по модели).", "",
                    gripOwn() ? null : "у этой руки нет своего хвата", () -> change(() -> {
                        Grips.resetHand(gripTarget(), hand == 0, false);
                        Config.select(gun);
                    })));
            var parts = partChoices();
            String one = parts.size() < 2 ? "у модели этого оружия нет отдельных деталей" : null;
            int at = parts.indexOf((String) config(partKey()));
            out.add(new Action("grip.part.prev", "◀", "Предыдущая деталь модели.", "", one, () -> change(() -> setPart(parts.get(Math.floorMod(at - 1, parts.size()))))));
            out.add(new Action("grip.part.next", "▶", "Следующая деталь модели.", "", one, () -> change(() -> setPart(parts.get(Math.floorMod(at + 1, parts.size()))))));
            out.add(new Action("grip.part.reset", "↺", "Вернуть деталь, как задано в моде.", "",
                    Grips.own(gun, partKey()) != null ? null : "у этого оружия нет своей детали для этой руки", () -> change(() -> {
                        Grips.put(gun, partKey(), null, false);
                        Config.select(gun);
                    })));
            if (!variantParts().isEmpty()) {
                String variant = gunBase() + clips.variant();
                boolean own = Grips.variantHand(variant, hand == 0, false);
                out.add(new Action("grip.variant.own", "Свой хват",
                        "Дать оружию без деталей " + variantParts() + " свой хват этой руки. Остальные виды не изменятся.", "",
                        own ? "у этого вида уже свой хват" : null, () -> change(() -> {
                            float[] g = grip();
                            if (gripPoint()) Grips.setAt(variant, hand == 0, g[0], g[1], g[2], false);
                            else Grips.set(variant, hand == 0, g[0], g[1], g[2], false);
                            Config.select(gun);
                        })));
                out.add(new Action("grip.variant.common", "Как у оружия", "Убрать свой хват этого вида: он будет держаться, как всё оружие.", "",
                        !own ? "этот вид и так держится, как всё оружие"
                                : Grips.variantHand(variant, hand == 0, true) ? "свой хват этого вида задан в моде: его можно править, но не убрать" : null,
                        () -> change(() -> {
                            Grips.resetHand(variant, hand == 0, false);
                            Config.select(gun);
                        })));
            }
        }
        if (page == Page.BOTH) for (String[] sw : SWITCHES) switchActions(out, sw, switchWhy(sw[0]));
    }

    // the grip changed way and back lands where it is: off the model's ends there is no section to take shares of
    private boolean sameWay(Shape sh, boolean point) {
        float[] g = grip(), back = point ? sh.point(sh.fractions(g)) : sh.fractions(sh.point(g));
        float tol = point ? Math.max(0.01F, (sh.max()[2] - sh.min()[2]) / 2000F) : 0.001F;
        for (int i = 0; i < 3; i++) if (!(Math.abs(back[i] - g[i]) <= tol)) return false;
        return true;
    }

    private void setPart(String part) {
        Grips.put(gun, partKey(), part, false);
        Config.select(gun);
    }

    // a slider dragged: in effect at once; one step of undo for the whole drag, taken when it is let go
    private Map<String, String> dragFrom;

    void slide(String id, float value, boolean last) {
        if (dragFrom == null) dragFrom = Grips.snapshot();
        try {
            drag(id, value);
        } finally {
            if (last) endDrag();
            Config.select(gun);
        }
    }

    private void drag(String id, float value) {
        for (Knob k : knobs(page))
            if (k.id.equals(id) && k.why() == null) {
                k.put(dragTo(k.setting(), value));
            }
    }

    // the drag let go, or cut short by a key or a button pressed while the mouse is still down: what it changed so far
    // is its own step of undo, and the next drag starts from where things are now
    private void endDrag() {
        var from = dragFrom;
        dragFrom = null;
        if (from == null || Grips.snapshot().equals(from)) return;
        undo.push(from);
        if (undo.size() > 200) undo.removeLast();
        redo.clear();
    }

    private Object config(String key) {
        Config.select(gun);
        try {
            return Config.class.getField(key).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no setting " + key, e);
        }
    }

    // the grid's number nearest to v, as the float nearest to that decimal (0.35, not 7 * 0.05F = 0.35000002)
    static float snap(float v, float step) {
        return new java.math.BigDecimal(Float.toString(step)).multiply(java.math.BigDecimal.valueOf(Math.round(v / step))).floatValue();
    }

    // a step up or down on the step's grid; a limit off the grid (a key 3.935 ms from the next) is reached itself
    static float stepTo(Setting st, int d) {
        float v = snap(st.value() + d * st.step(), st.step());
        if (d > 0 && (v > st.max() || v <= st.value())) v = st.max();
        if (d < 0 && (v < st.min() || v >= st.value())) v = st.min();
        return v;
    }

    // a dragged value on the step's grid, the limits themselves at the ends
    static float dragTo(Setting st, float value) {
        float v = Math.clamp(value, st.min(), st.max()), s = snap(v, st.step());
        if (v >= st.max() || s > st.max()) s = st.max();
        if (v <= st.min() || s < st.min()) s = st.min();
        return s;
    }

    // a number as shown: as many decimals as the step needs, with a comma
    static String number(float v, float step) {
        // a number off the slider's grid (typed, or from the mod: 3.935 ms, -66.25°) is shown as it is
        if (snap(v, step) != v && Float.isFinite(v)) return ArmRig.number(v).replace('.', ',');
        String f = step >= 1F ? "%.0f" : step >= 0.1F ? "%.1f" : step >= 0.01F ? "%.2f" : "%.3f";
        String out = String.format(Locale.ROOT, f, v);
        if (out.matches("-0[.,]?0*")) out = out.substring(1);
        return out.replace('.', ',');
    }

    // a number as written into the settings: four decimals at most when they read back as the same number, else every
    // digit it needs (a typed 0.42999998 stays itself)
    private static String plain(float v) {
        String s = String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.equals("-0")) s = "0";
        return Float.parseFloat(s) == v || v == 0F ? s : ArmRig.number(v);
    }

    // a number typed for a slider ("-0,44", "865"): the value of the setting, in one step of undo; null = done, else
    // why it was not taken
    String type(String id, String text) {
        endDrag();
        for (Knob k : knobs(page)) {
            if (!k.id.equals(id)) continue;
            if (k.why() != null) return k.why();
            String t = text.trim().replace('−', '-').replace(',', '.');
            if (!t.matches("-?(\\d+\\.?\\d*|\\.\\d+)")) return "введи число, например 0,5 или -12";
            float v = Float.parseFloat(t), lo = k.lo(), hi = k.hi();
            if (v < lo || v > hi) return "можно от " + number(lo, k.step) + " до " + number(hi, k.step);
            if (k.whole() && v != Math.round(v)) return "здесь только целое число, например " + Math.round(v);
            change(() -> k.put(v));
            return null;
        }
        return "этого ползунка сейчас нет";
    }

    Action action(String id) {
        for (var a : actions()) if (a.id().equals(id)) return a;
        return null;
    }

    // for the screen's hotkeys: presses the action when it can be pressed now
    boolean press(String id) {
        endDrag();
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

    record State(int clip, int hand, float time, boolean playing, double speed, Track track, Page page, KeyView keyView, int first, boolean closed,
            int from, int file, int exports, ArmKeys.Copy copied, Map<String, String> data, int undo, int redo) {
    }

    State state() {
        return new State(clip, hand, time, playing, speed, track, page, keyView, first, closed, from, file, exports, copied, Grips.snapshot(), undo.size(),
                redo.size());
    }

    // back to a state taken before (the test tries every action from the same state)
    void restore(State s, Deque<Map<String, String>> undoCopy, Deque<Map<String, String>> redoCopy) {
        clip = s.clip;
        hand = s.hand;
        time = s.time;
        playing = s.playing;
        speed = s.speed;
        track = s.track;
        page = s.page;
        keyView = s.keyView;
        first = s.first;
        closed = s.closed;
        from = s.from;
        file = s.file;
        exports = s.exports;
        copied = s.copied;
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
