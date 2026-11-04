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
        GRIP("Хват", "Где рука держит оружие и с какой деталью двигается."),
        ARM("Рука", "Плечо, поворот и толщина выбранной руки."),
        BOTH("Обе руки", "Настройки обеих рук этого оружия: толщина, длина, следование, скольжение.");

        final String label, hint;

        Page(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }
    }

    // a number shown as a slider; why != null = it does not act now, and why
    record Setting(String id, String label, String hint, String unit, float value, float min, float max, float step, String why) {
        String text() {
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
        for (Page p : Page.values())
            out.add(new Action("page." + p.name().toLowerCase(Locale.ROOT), p.label, p.hint, "", p == page ? "эта страница уже открыта" : null, () -> page = p));
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
        if (page == Page.TIME) timeActions(out, none);
        if (page != Page.BOTH) {
            out.add(new Action("hand.right", "Правая рука", "Показать и править правую руку.", "", hand == 0 ? "правая рука уже выбрана" : null, () -> {
                hand = 0;
                if (trackWhy(track) != null) track = Track.PATH;
            }));
            out.add(new Action("hand.left", "Левая рука", "Показать и править левую руку.", "", hand == 1 ? "левая рука уже выбрана" : null, () -> hand = 1));
        }
        if (page != Page.TIME) settingActions(out);
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

        Setting setting() {
            return new Setting(id, label, hint, unit, get(), lo(), hi(), step, why());
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
            return new Setting(id, label, hint, gripPoint() ? "ед. модели" : "", get(), lo(), hi(), st, null);
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

    // the knobs of a page, in the order shown
    private List<Knob> knobs(Page p) {
        var out = new ArrayList<Knob>();
        String s = side();
        switch (p) {
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
                out.add(new ConfigKnob("armBase" + s, -1, "Основа толщины",
                        "Толщина руки до общего множителя, одна на всех оружиях. 0 — толщина растёт с длиной руки.", "", 0F, 5F, 0.01F));
                boolean right = hand == 0;
                out.add(new ConfigKnob("armReach" + s, -1, "Плечо до", "Рука идёт назад до этой глубины перед глазом (минус — за глазом).", "", -2F, 2F, 0.01F) {
                    @Override
                    String why() {
                        return base(right) > 0F && Config.armLength > 0F
                                ? "действует, только когда «Основа толщины» этой руки 0 или «Длина руки» 0" : null;
                    }
                });
                if (hand == 1) out.add(new ConfigKnob("armInspectDrop", -1, "Опускание при осмотре",
                        "На сколько опускается левая рука при осмотре, когда оружие лежит на боку, в пикселях руки.", "", 0F, 4F, 0.1F));
            }
            case BOTH -> {
                out.add(new ConfigKnob("armScale", -1, "Толщина обеих", "Множитель толщины обеих рук.", "×", 0.2F, 2F, 0.01F));
                out.add(new ConfigKnob("armLength", -1, "Длина руки", "Длина руки в ванильных руках её толщины. 0 — рука тянется до «Плечо до».", "", 0F, 2F, 0.01F));
                out.add(new ConfigKnob("armMaxScale", -1, "Предел толщины", "Наибольшая толщина, которую рука набирает с длиной.", "", 0.5F, 8F, 0.05F) {
                    @Override
                    String why() {
                        return base(true) > 0F && base(false) > 0F ? "действует, только когда «Основа толщины» хоть одной руки 0" : null;
                    }
                });
                out.add(new ConfigKnob("armFollow", -1, "Руки за оружием", "Насколько руки поворачиваются вместе с оружием в анимациях: 0 — нет, 1 — полностью.", "", 0F, 1F, 0.01F));
                out.add(new ConfigKnob("armFollowSupport", -1, "Левая при стрельбе", "То же для левой руки при стрельбе и передёргивании: оружие поворачивается в ладони.", "", 0F, 1F, 0.01F));
                String noSlide = "действует, только когда включено «Скольжение рук»";
                out.add(new ConfigKnob("armStretch", -1, "Растяжение", "На сколько рука может растянуться, прежде чем кисть заскользит по оружию: 0,1 — на 10 %.", "", 0F, 1F, 0.01F) {
                    @Override
                    String why() {
                        Config.select(gun);
                        return Config.armSlide ? null : noSlide;
                    }
                });
                out.add(new ConfigKnob("armSlideTo", -1, "Скольжение до", "Докуда кисть может скользить к прикладу: 0 — до конца приклада, 1 — до дула.", "", 0F, 1F, 0.01F) {
                    @Override
                    String why() {
                        Config.select(gun);
                        return Config.armSlide ? null : noSlide;
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
            {"armSlide", "Скольжение рук", "В анимациях кисть скользит по оружию к прикладу, а не рука растягивается."},
            {"armSlideNearest", "Ближайшая точка", "Скользящая кисть останавливается в ближайшей к хвату точке, до которой достаёт рука."}};

    private boolean switchOn(String key) {
        return (Boolean) config(key);
    }

    private String switchWhy(String key) {
        return key.equals("armSlideNearest") && !switchOn("armSlide") ? "действует, только когда включено «Скольжение рук»" : null;
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

    List<Row> rows() {
        var out = new ArrayList<Row>();
        if (page == Page.TIME) return out;
        if (page == Page.GRIP) {
            out.add(new Row("Хват " + (hand == 0 ? "правой" : "левой") + (gripPoint() ? ": точкой модели" : ": в долях модели"),
                    "Точка не сдвигается, когда обвесы меняют размер модели; доли идут за ними.", null, List.of("grip.mode", "grip.reset")));
        }
        for (Knob k : knobs(page))
            out.add(new Row(null, k.hint, k.setting(), k instanceof GripKnob ? List.of("set." + k.id + ".minus", "set." + k.id + ".plus")
                    : List.of("set." + k.id + ".minus", "set." + k.id + ".plus", "set." + k.id + ".reset")));
        if (page == Page.GRIP) {
            out.add(new Row("Деталь: " + partName((String) config(partKey())), "Деталь модели, с которой двигается кисть (помпа, рычаг). Корпус — всё оружие.", null,
                    List.of("grip.part.prev", "grip.part.next", "grip.part.reset")));
            if (!variantParts().isEmpty())
                out.add(new Row("Хват вида: " + (Grips.variantHand(gunBase() + clips.variant(), hand == 0, false) ? "свой" : "общий"),
                        "Оружие сейчас без деталей " + variantParts() + " (например, обрез). Такой вид может держаться иначе."
                                + " Общий хват — такой же, как у всего оружия.", null,
                        List.of("grip.variant.own", "grip.variant.common")));
        }
        if (page == Page.BOTH)
            for (String[] sw : SWITCHES) out.add(new Row(sw[1] + ": " + (switchOn(sw[0]) ? "да" : "нет"), sw[2], null, List.of("set." + sw[0] + ".toggle", "set." + sw[0] + ".reset")));
        return out;
    }

    private void settingActions(List<Action> out) {
        for (Knob k : knobs(page)) {
            var st = k.setting();
            float eps = st.step() * 1e-3F;
            String own = k instanceof ConfigKnob ck && Grips.own(gun, ck.key) != null ? null : "у этого оружия нет своего значения";
            String why = st.why();
            out.add(new Action("set." + k.id + ".minus", "−", "Уменьшить «" + k.label + "» на " + number(st.step(), st.step()) + ".", "",
                    why != null ? why : st.value() <= st.min() + eps ? "уже наименьшее значение" : null,
                    () -> change(() -> k.put(snap(Math.max(st.min(), st.value() - st.step()), st.step())))));
            out.add(new Action("set." + k.id + ".plus", "+", "Увеличить «" + k.label + "» на " + number(st.step(), st.step()) + ".", "",
                    why != null ? why : st.value() >= st.max() - eps ? "уже наибольшее значение" : null,
                    () -> change(() -> k.put(snap(Math.min(st.max(), st.value() + st.step()), st.step())))));
            if (k instanceof ConfigKnob ck)
                out.add(new Action("set." + k.id + ".reset", "↺", "Вернуть «" + k.label + "», как задано в моде для этого оружия или для всех.", "", own,
                        () -> change(() -> {
                            Grips.put(gun, ck.key, null, false);
                            Config.select(gun);
                        })));
        }
        if (page == Page.GRIP) {
            var sh = clips.shape();
            boolean point = gripPoint();
            out.add(new Action("grip.mode", point ? "В доли" : "В точку",
                    point ? "Задать хват долями модели: он пойдёт за обвесами, когда они меняют размер модели."
                            : "Задать хват точкой модели: он не сдвинется от обвесов.", "",
                    sh == null ? "модель оружия ещё не измерена: посмотри на него секунду" : null, () -> change(() -> {
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
        if (page == Page.BOTH)
            for (String[] sw : SWITCHES) {
                String key = sw[0];
                out.add(new Action("set." + key + ".toggle", switchOn(key) ? "Выключить" : "Включить", sw[2], "", switchWhy(key), () -> change(() -> {
                    Grips.put(gun, key, String.valueOf(!switchOn(key)), false);
                    Config.select(gun);
                })));
                out.add(new Action("set." + key + ".reset", "↺", "Вернуть «" + sw[1] + "», как задано в моде.", "",
                        Grips.own(gun, key) != null ? null : "у этого оружия нет своего значения", () -> change(() -> {
                            Grips.put(gun, key, null, false);
                            Config.select(gun);
                        })));
            }
    }

    private void setPart(String part) {
        Grips.put(gun, partKey(), part, false);
        Config.select(gun);
    }

    // a slider dragged: in effect at once; one step of undo for the whole drag, taken when it is let go
    private Map<String, String> dragFrom;

    void slide(String id, float value, boolean last) {
        if (dragFrom == null) dragFrom = Grips.snapshot();
        for (Knob k : knobs(page))
            if (k.id.equals(id) && k.why() == null) {
                var st = k.setting();
                k.put(snap(Math.clamp(value, st.min(), st.max()), st.step()));
            }
        if (last) {
            var from = dragFrom;
            dragFrom = null;
            if (!Grips.snapshot().equals(from)) {
                undo.push(from);
                if (undo.size() > 200) undo.removeLast();
                redo.clear();
            }
        }
        Config.select(gun);
    }

    private Object config(String key) {
        Config.select(gun);
        try {
            return Config.class.getField(key).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no setting " + key, e);
        }
    }

    static float snap(float v, float step) {
        return Math.round(v / step) * step;
    }

    // a number as shown: as many decimals as the step needs, with a comma
    static String number(float v, float step) {
        String f = step >= 1F ? "%.0f" : step >= 0.1F ? "%.1f" : step >= 0.01F ? "%.2f" : "%.3f";
        String out = String.format(Locale.ROOT, f, v);
        if (out.matches("-0[.,]?0*")) out = out.substring(1);
        return out.replace('.', ',');
    }

    private static String plain(float v) {
        String s = String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
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

    record State(int clip, int hand, float time, boolean playing, double speed, Track track, Page page, boolean closed,
            Map<String, String> data, int undo, int redo) {
    }

    State state() {
        return new State(clip, hand, time, playing, speed, track, page, closed, Grips.snapshot(), undo.size(), redo.size());
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
