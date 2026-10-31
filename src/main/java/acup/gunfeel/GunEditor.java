package acup.gunfeel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

// The gun editor (key O, "NTM Reimagined Arms" in Controls): sliders for the held gun's hands, arms, recoil and
// position, in effect while you drag, written to config/ntm-gunfeel-guns.properties for this gun only when you let go.
// The game goes on behind it (no pause, no blur), so the gun and the arms are seen as they change. A slider's wheel
// moves it by one step (Shift: a tenth); ↺ takes the gun's value back to the mod's own (or the global one).
public final class GunEditor extends Screen {
    public static final KeyMapping KEY = new KeyMapping("key.ntmgunfeel.editor", 79 /* GLFW_KEY_O */, AmmoLockKey.CATEGORY);
    // a test shot is being fed to CameraRecoil (not a real kick to remember)
    static boolean replaying;

    private static final int PANEL_W = 214, ROW_H = 18, GAP = 2, RESET_W = 16;
    private static final String[] TABS = {"Правая", "Левая", "Руки", "Отдача", "Положение"};
    private static int tab;
    private static int autoFire;
    private static final int[] AUTO_MS = {0, 1000, 333};
    private static final String[] AUTO_NAME = {"выкл", "1/с", "3/с"};
    // the recoil tab's second page: presets and the movements, with their graph
    private static boolean curves;
    private static int channel;
    private static final String[] CHANNELS = {"kickPitch", "kickYaw", "kickRoll", "kickPosY", "kickPosZ", "cameraPitch", "cameraYaw", "cameraRoll"};
    private static final String[] CHANNEL_NAME = {"пушка: подъём", "пушка: вбок", "пушка: крен", "пушка: вверх", "пушка: назад",
            "камера: подъём", "камера: вбок", "камера: крен"};
    private static final String[] PARAM_NAME = {"сила", "резкость, с", "затухание, с", "отскок", "задержка, с", "частота, Гц"};
    private static final int GRAPH_H = 100;

    private final Item item;
    private final String gun;
    private final Map<String, String> before = Grips.snapshot();
    private final List<Row> rows = new ArrayList<>();
    private long nextShot;
    private Component status = Component.empty();
    private EditBox presetName;

    private GunEditor(Item item) {
        super(Component.literal("Редактор пушки"));
        this.item = item;
        this.gun = BuiltInRegistries.ITEM.getKey(item).toString();
    }

    static void register(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            // the ammo key registers the category when it is on
            if (!acup.gunfeel.compat.Feature.AMMO_CHOICE.on()) e.registerCategory(AmmoLockKey.CATEGORY);
            e.register(KEY);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            Minecraft mc = Minecraft.getInstance();
            while (KEY.consumeClick()) {
                if (mc.gui.screen() != null || mc.player == null) continue;
                ItemStack held = mc.player.getMainHandItem();
                if (held.getItem() instanceof ItemGunBaseNT) mc.gui.setScreen(new GunEditor(held.getItem()));
                else mc.player.sendOverlayMessage(Component.literal("Возьми пушку NTM в основную руку"));
            }
        });
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // no dark or blurred backdrop: the gun behind is what is being tuned
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, PANEL_W + 8, height, 0x90101010);
    }

    @Override
    protected void init() {
        rows.clear();
        int x = 4, y = 4;
        int tw = (PANEL_W - (TABS.length - 1) * GAP) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            Button b = addRenderableWidget(Button.builder(Component.literal(TABS[i]), p -> {
                tab = t;
                rebuildWidgets();
            }).bounds(x + i * (tw + GAP), y, tw, ROW_H).build());
            b.active = i != tab;
        }
        y += ROW_H + 6;
        addRenderableWidget(Button.builder(Component.literal("Анимации · движения рук"), p -> {
            autoFire = 0;
            Minecraft.getInstance().gui.setScreen(new ArmAnimationEditor(this, item, gun));
        }).bounds(x, y, PANEL_W, ROW_H).build());
        y += ROW_H + GAP;
        int half = (PANEL_W - GAP) / 2;
        if (tab <= 2) {
            String label = switch (Config.armModel) {
                case "classic" -> "обычные";
                case "slim" -> "skinny";
                default -> "по скину";
            };
            addRenderableWidget(Button.builder(Component.literal("Руки: " + label), p -> {
                String next = switch (Config.armModel) {
                    case "classic" -> "slim";
                    case "slim" -> "auto";
                    default -> "classic";
                };
                Grips.put(gun, "armModel", next);
                Config.select(gun);
                rebuildWidgets();
            }).bounds(x, y, PANEL_W, ROW_H).build());
            y += ROW_H + GAP;
        }
        if (tab == 3) {
            Button general = addRenderableWidget(Button.builder(Component.literal("Общее"), p -> {
                curves = false;
                rebuildWidgets();
            }).bounds(x, y, half, ROW_H).build());
            Button shapes = addRenderableWidget(Button.builder(Component.literal("Кривые"), p -> {
                curves = true;
                rebuildWidgets();
            }).bounds(x + half + GAP, y, half, ROW_H).build());
            general.active = curves;
            shapes.active = !curves;
            y += ROW_H + GAP;
            if (curves) {
                addRenderableWidget(Button.builder(Component.literal("Пушка: " + Config.kickPreset), p -> cyclePreset(false))
                        .bounds(x, y, half, ROW_H).build());
                addRenderableWidget(Button.builder(Component.literal("Камера: " + (Config.cameraPreset.isBlank() ? "как пушка" : Config.cameraPreset)),
                        p -> cyclePreset(true)).bounds(x + half + GAP, y, half, ROW_H).build());
                y += ROW_H + GAP;
                addRenderableWidget(Button.builder(Component.literal("Движение: " + CHANNEL_NAME[channel]), p -> {
                    channel = (channel + (Minecraft.getInstance().hasShiftDown() ? CHANNELS.length - 1 : 1)) % CHANNELS.length;
                    rebuildWidgets();
                }).bounds(x, y, PANEL_W, ROW_H).build());
                y += ROW_H + GAP;
            }
        }
        for (Row r : rowsFor(tab)) {
            r.setRectangle(PANEL_W - RESET_W - GAP, ROW_H, x, y);
            r.sync();
            rows.add(addRenderableWidget(r));
            addRenderableWidget(Button.builder(Component.literal("↺"), p -> r.reset()).bounds(x + PANEL_W - RESET_W, y, RESET_W, ROW_H).build());
            y += ROW_H + GAP;
        }
        if (tab == 3 && curves) {
            presetName = addRenderableWidget(new EditBox(font, x, y, half, ROW_H, Component.literal("имя пресета")));
            presetName.setMaxLength(32);
            presetName.setHint(Component.literal("имя пресета"));
            addRenderableWidget(Button.builder(Component.literal("Сохранить пресет"), p -> savePreset())
                    .bounds(x + half + GAP, y, half, ROW_H).build());
            y += ROW_H + GAP;
        }
        y += 6;
        addRenderableWidget(Button.builder(Component.literal("Выстрел"), p -> testShot()).bounds(x, y, half, ROW_H).build());
        addRenderableWidget(Button.builder(Component.literal("Очередь: " + AUTO_NAME[autoFire]), p -> {
            autoFire = (autoFire + 1) % AUTO_MS.length;
            p.setMessage(Component.literal("Очередь: " + AUTO_NAME[autoFire]));
        }).bounds(x + half + GAP, y, half, ROW_H).build());
        y += ROW_H + GAP;
        if (tab <= 1) {
            boolean right = tab == 0;
            addRenderableWidget(Button.builder(Component.literal(handAt(right) ? "Точка → доли модели" : "Доли → точка модели"), p -> convertHand(right))
                    .bounds(x, y, PANEL_W, ROW_H).build());
            y += ROW_H + GAP;
        }
        addRenderableWidget(Button.builder(Component.literal("Отменить всё"), p -> {
            Grips.restore(before);
            status = Component.literal("Вернул, как было при открытии");
        }).bounds(x, y, half, ROW_H).build());
        addRenderableWidget(Button.builder(Component.literal("Готово"), p -> onClose()).bounds(x + half + GAP, y, half, ROW_H).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        String missing = missing();
        int y = height - 34;
        g.text(font, gun + missing, 4, y, 0xFFFFFFFF);
        g.text(font, "● = своё у этой пушки   колесо: шаг, Shift+колесо: мельче", 4, y + 10, 0xFFA0A0A0);
        g.text(font, status, 4, y + 20, 0xFFFFC040);
        if (tab == 3 && curves) graph(g);
    }

    // the movements of the chosen one's group (gun or camera) over time, each to its own peak; the chosen one bright
    private void graph(GuiGraphicsExtractor g) {
        int x0 = PANEL_W + 16, y0 = 28, w = Math.min(280, width - x0 - 8), h = GRAPH_H;
        if (w < 80) return;
        String chosen = CHANNELS[channel];
        String[] group = RecoilPresets.camera(chosen) ? RecoilPresets.CAMERA : RecoilPresets.KICK;
        float len = 0.1F;
        Recoil[] rs = new Recoil[group.length];
        for (int i = 0; i < group.length; i++) {
            rs[i] = RecoilPresets.channel(group[i]);
            len = Math.max(len, rs[i].length());
        }
        g.fill(x0 - 4, y0 - 4, x0 + w + 4, y0 + h + 26, 0xB0101010);
        int mid = y0 + h / 2;
        g.fill(x0, mid, x0 + w, mid + 1, 0x60FFFFFF);
        float chosenPeak = 0F;
        for (int i = 0; i < group.length; i++) {
            boolean me = group[i].equals(chosen);
            float peak = 0F;
            for (int px = 0; px < w; px++) peak = Math.max(peak, Math.abs(rs[i].at(len * px / (w - 1))));
            if (me) chosenPeak = peak;
            if (peak <= 0F) continue;
            int color = me ? 0xFFFFC040 : 0x7090B0FF;
            int last = mid;
            for (int px = 0; px < w; px++) {
                float v = rs[i].at(len * px / (w - 1)) / peak;
                int y = mid - Math.round(v * (h / 2 - 2));
                int a = Math.min(last, y), b = Math.max(last, y);
                g.fill(x0 + px, a, x0 + px + 1, b + 1, color);
                last = y;
            }
        }
        g.text(font, CHANNEL_NAME[channel] + ": пик " + String.format(Locale.ROOT, "%.2f", chosenPeak)
                + (RecoilPresets.camera(chosen) && !chosen.equals("cameraRoll") ? " × отдача NTM" : ""), x0, y0 + h + 4, 0xFFFFC040);
        g.text(font, "0 … " + Math.round(len * 1000F) + " мс" + (rs[java.util.Arrays.asList(group).indexOf(chosen)] instanceof Curve ? "   своя кривая t:v" : ""),
                x0, y0 + h + 14, 0xFFA0A0A0);
    }

    // the next preset (Shift: the one before); the camera's list starts with "as the gun"
    private void cyclePreset(boolean camera) {
        List<String> names = new ArrayList<>(RecoilPresets.names());
        if (camera) names.add(0, "");
        String now = camera ? Config.cameraPreset : Config.kickPreset;
        int i = names.indexOf(now);
        int step = Minecraft.getInstance().hasShiftDown() ? names.size() - 1 : 1;
        String next = names.get(((i < 0 ? 0 : i) + step) % names.size());
        Grips.put(gun, camera ? "cameraPreset" : "kickPreset", next, true);
        Config.select(gun);
        rebuildWidgets();
    }

    // the chosen group's movements as they are now, as a preset of the player's; the gun then takes it, with none
    // of its own movements on top
    private void savePreset() {
        String name = presetName == null ? "" : presetName.getValue().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (name.isEmpty()) {
            status = Component.literal("Имя пресета: латиница, цифры, - и _");
            return;
        }
        boolean camera = RecoilPresets.camera(CHANNELS[channel]);
        String[] group = camera ? RecoilPresets.CAMERA : RecoilPresets.KICK;
        Map<String, Spring> out = new java.util.LinkedHashMap<>();
        for (String c : group) out.put(c, RecoilPresets.spring(c));
        RecoilPresets.save(name, out);
        for (String c : group) Grips.put(gun, c, null, false);
        Grips.put(gun, camera ? "cameraPreset" : "kickPreset", name, true);
        Config.select(gun);
        status = Component.literal("Пресет " + name + " (" + (camera ? "камера" : "пушка") + ") сохранён в config/" + RecoilPresets.FILE);
        rebuildWidgets();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.getMainHandItem().getItem() != item) {
            onClose();
            return;
        }
        long now = GunClock.millis();
        if (autoFire > 0 && now >= nextShot) {
            nextShot = now + AUTO_MS[autoFire];
            testShot();
        }
    }

    @Override
    public void removed() {
        Grips.flush();
    }

    // the gun's last real kick again, camera and model (NTM's own recoil animation does not play)
    private void testShot() {
        float[] k = CameraRecoil.LAST.get(item);
        if (k == null) {
            k = new float[] {5F, 0F};
            status = Component.literal("Пушка ещё не стреляла: отдача взята условная, выстрели раз по-настоящему");
        }
        replaying = true;
        try {
            CameraRecoil.addImpulse(k[0], k[1]);
        } finally {
            replaying = false;
        }
        var player = Minecraft.getInstance().player;
        ModelRecoil.shot(player != null && ItemGunBaseNT.getIsAiming(player.getMainHandItem()));
    }

    private static String missing() {
        GunGeometry g = Arms.geometry(Minecraft.getInstance().player.getMainHandItem().getItem());
        return g == null ? "" : g.missing;
    }

    private GunGeometry geo() {
        return Arms.geometry(item);
    }

    private boolean handAt(boolean right) {
        Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(item), missing());
        return (right ? g.rightAt() : g.leftAt()) != null;
    }

    // switch a hand between a point in model units and fractions of the model, keeping where it is now
    private void convertHand(boolean right) {
        GunGeometry geo = geo();
        if (geo == null) {
            status = Component.literal("Модель пушки ещё не измерена: посмотри на неё секунду");
            return;
        }
        Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(item), missing());
        float[] at = right ? g.rightAt() : g.leftAt();
        if (at != null) {
            float[] f = geo.fractions(at);
            Grips.set(gun + missing(), right, f[0], f[1], f[2]);
            status = Component.literal("Рука в долях модели: идёт за обвесами");
        } else {
            float[] p = geo.grip(right ? g.right() : g.left());
            Grips.setAt(gun + missing(), right, p[0], p[1], p[2]);
            status = Component.literal("Рука в точке модели: не сдвигается от обвесов");
        }
        rebuildWidgets();
    }

    private List<Row> rowsFor(int t) {
        List<Row> out = new ArrayList<>();
        switch (t) {
            case 0, 1 -> {
                boolean right = t == 0;
                String side = right ? "Right" : "Left";
                for (int c = 0; c < 3; c++) out.add(new HandRow(right, c));
                String[] dir = {"плечо ← →", "плечо ↓ ↑", "плечо назад/вперёд"};
                for (int c = 0; c < 3; c++) out.add(new ConfigRow("armDir" + side, c, dir[c], -1.5F, 1.5F, 0.01F));
                out.add(new ConfigRow("armRoll" + side, -1, "поворот руки", -180F, 180F, 1F));
                out.add(new ConfigRow("armScale" + side, -1, "толщина", 0.3F, 2.5F, 0.01F));
                out.add(new ConfigRow("armBase" + side, -1, "толщина основа", 0F, 5F, 0.01F));
            }
            case 2 -> {
                out.add(new ConfigRow("armScale", -1, "толщина обеих", 0.2F, 2F, 0.01F));
                out.add(new ConfigRow("armLength", -1, "длина руки", 0F, 2F, 0.01F));
                out.add(new ConfigRow("armMaxScale", -1, "предел толщины", 0.5F, 8F, 0.05F));
                out.add(new ConfigRow("armFollow", -1, "руки за пушкой", 0F, 1F, 0.01F));
                out.add(new ConfigRow("armFollowSupport", -1, "левая при стрельбе", 0F, 1F, 0.01F));
                out.add(new ConfigRow("armStretch", -1, "растяжение", 0F, 1F, 0.01F));
                out.add(new ConfigRow("armSlideTo", -1, "скольжение до", 0F, 1F, 0.01F));
            }
            case 3 -> {
                if (curves) {
                    for (int i = 0; i < PARAM_NAME.length; i++) out.add(new SpringRow(CHANNELS[channel], i));
                    break;
                }
                out.add(new ConfigRow("cameraScale", -1, "камера: сила", 0F, 2F, 0.01F));
                out.add(new ConfigRow("cameraTime", -1, "камера: время", 0.05F, 3F, 0.01F));
                out.add(new ConfigRow("cameraShake", -1, "камера: крен", 0F, 3F, 0.01F));
                out.add(new ConfigRow("modelKick", -1, "пушка: сила", 0F, 3F, 0.01F));
                out.add(new ConfigRow("modelRotate", -1, "пушка: поворот", 0F, 3F, 0.01F));
                out.add(new ConfigRow("modelSpeed", -1, "пушка: скорость", 0.05F, 3F, 0.01F));
                out.add(new ConfigRow("ntmRecoilAnim", -1, "анимация NTM", 0F, 1F, 0.01F));
                out.add(new ConfigRow("adsRecoil", -1, "в прицеле: множитель", 0F, 1F, 0.01F));
            }
            default -> {
                out.add(new ConfigRow("hipX", -1, "от бедра ← →", -0.5F, 1.5F, 0.01F));
                out.add(new ConfigRow("hipY", -1, "от бедра ↓ ↑", 0F, 2F, 0.01F));
                out.add(new ConfigRow("hipZ", -1, "от бедра даль", 0.3F, 1.5F, 0.01F));
                String[] tilt = {"наклон: нос", "наклон: вбок", "наклон: крен"};
                for (int c = 0; c < 3; c++) out.add(new ConfigRow("modelTilt", c, tilt[c], -45F, 45F, 0.5F));
                out.add(new ConfigRow("smokeWidth", -1, "ширина дыма", 0F, 4F, 0.05F));
            }
        }
        return out;
    }

    // one slider: a value with its range and step; the wheel moves it by a step
    private abstract class Row extends AbstractSliderButton {
        final float min, max, step;
        private boolean held;

        Row(float min, float max, float step) {
            super(0, 0, 10, ROW_H, Component.empty(), 0);
            this.min = min;
            this.max = max;
            this.step = step;
        }

        abstract float get();

        // save false while dragging
        abstract void set(float v, boolean save);

        abstract void reset();

        abstract String name();

        abstract boolean own();

        void sync() {
            value = Math.clamp((get() - min) / (max - min), 0.0, 1.0);
            updateMessage();
        }

        float snap(float v) {
            return Math.round(v / step) * step;
        }

        @Override
        protected void updateMessage() {
            float v = get();
            String n = step >= 1F ? String.format(Locale.ROOT, "%.0f", v) : step >= 0.1F ? String.format(Locale.ROOT, "%.1f", v)
                    : step >= 0.01F ? String.format(Locale.ROOT, "%.2f", v) : String.format(Locale.ROOT, "%.3f", v);
            setMessage(Component.literal((own() ? "● " : "") + name() + ": " + n));
        }

        @Override
        protected void applyValue() {
            set(snap(min + (float) value * (max - min)), false);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            held = true;
            super.onClick(event, doubleClick);
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            held = false;
            super.onRelease(event);
            Grips.flush();
        }

        @Override
        public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
            if (scrollY == 0) return false;
            float s = Minecraft.getInstance().hasShiftDown() ? step / 10F : step;
            set(Math.clamp(get() + (scrollY > 0 ? s : -s), Math.min(min, get()), Math.max(max, get())), true);
            sync();
            return true;
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            // follows what is in effect (another slider, ↺, the file changed) unless it is being dragged
            if (!held) sync();
            super.extractWidgetRenderState(g, mouseX, mouseY, a);
        }
    }

    // a setting of ntm-gunfeel.properties for this gun; c >= 0 = one part of a three-number value
    private final class ConfigRow extends Row {
        final String key, label;
        final int c;

        ConfigRow(String key, int c, String label, float min, float max, float step) {
            super(min, max, step);
            this.key = key;
            this.c = c;
            this.label = label;
        }

        @Override
        String name() {
            return label;
        }

        @Override
        boolean own() {
            return Grips.gunValue(gun, key) != null;
        }

        @Override
        float get() {
            return c < 0 ? (float) field() : vector()[c];
        }

        @Override
        void set(float v, boolean save) {
            String text;
            if (c < 0) text = trim(v);
            else {
                float[] now = vector().clone();
                now[c] = v;
                text = trim(now[0]) + "," + trim(now[1]) + "," + trim(now[2]);
            }
            Grips.put(gun, key, text, save);
            Config.select(gun);
        }

        @Override
        void reset() {
            Grips.put(gun, key, null, true);
            Config.select(gun);
        }

        private float[] vector() {
            if (key.equals("modelTilt")) {
                String[] p = Config.modelTilt.split(",");
                float[] out = new float[3];
                if (p.length == 3) for (int i = 0; i < 3; i++) {
                    try {
                        out[i] = Float.parseFloat(p[i].trim());
                    } catch (NumberFormatException e) {
                        out[i] = 0F;
                    }
                }
                return out;
            }
            return (float[]) value(key);
        }

        private float field() {
            return ((Number) value(key)).floatValue();
        }
    }

    // one number of one recoil movement (Spring) of this gun; ↺ = the preset's movement again
    private final class SpringRow extends Row {
        final String key;
        final int i;

        SpringRow(String key, int i) {
            super(lo(key, i), hi(key, i), stepOf(key, i));
            this.key = key;
            this.i = i;
        }

        private static float lo(String key, int i) {
            return i == 0 ? -hi(key, 0) : i == 1 ? Spring.MIN_ATTACK : i == 2 ? Spring.MIN_SETTLE : 0F;
        }

        private static float hi(String key, int i) {
            return switch (i) {
                case 0 -> key.equals("cameraPitch") || key.equals("cameraYaw") ? 3F : key.startsWith("kickPos") ? 10F : key.equals("cameraRoll") ? 10F : 30F;
                case 1 -> 0.3F;
                case 2 -> 2F;
                case 3 -> 0.9F;
                case 4 -> 0.3F;
                default -> 40F;
            };
        }

        private static float stepOf(String key, int i) {
            return switch (i) {
                case 0 -> key.equals("cameraPitch") || key.equals("cameraYaw") ? 0.01F : key.startsWith("kickPos") ? 0.05F : 0.1F;
                case 1, 4 -> 0.005F;
                case 3, 2 -> 0.01F;
                default -> 0.5F;
            };
        }

        @Override
        String name() {
            return PARAM_NAME[i];
        }

        @Override
        boolean own() {
            String v = Grips.gunValue(gun, key);
            return v != null && !v.isBlank();
        }

        @Override
        float get() {
            return RecoilPresets.spring(key).get(i);
        }

        @Override
        void set(float v, boolean save) {
            Grips.put(gun, key, RecoilPresets.spring(key).with(i, v).toString(), save);
            Config.select(gun);
        }

        @Override
        void reset() {
            Grips.put(gun, key, null, true);
            Config.select(gun);
        }
    }

    // where one hand holds the gun: fractions of the model, or a point in model units when the gun has one
    private final class HandRow extends Row {
        final boolean right;
        final int c;

        HandRow(boolean right, int c) {
            super(GunEditor.this.lo(right, c), GunEditor.this.hi(right, c), GunEditor.this.stepOf(right));
            this.right = right;
            this.c = c;
        }

        @Override
        String name() {
            String[] n = {"рука ← →", "рука ↓ ↑", "рука вдоль"};
            return n[c] + (handAt(right) ? " (точка)" : "");
        }

        @Override
        boolean own() {
            String h = right ? "right" : "left", m = missing();
            return Grips.own(gun + m, h) != null || Grips.own(gun + m, h + "At") != null;
        }

        private float[] now() {
            Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(item), missing());
            float[] at = right ? g.rightAt() : g.leftAt();
            return (at != null ? at : right ? g.right() : g.left()).clone();
        }

        @Override
        float get() {
            return now()[c];
        }

        @Override
        void set(float v, boolean save) {
            float[] p = now();
            p[c] = v;
            if (handAt(right)) Grips.setAt(gun + missing(), right, p[0], p[1], p[2], save);
            else Grips.set(gun + missing(), right, p[0], p[1], p[2], save);
        }

        @Override
        void reset() {
            Grips.resetHand(gun + missing(), right, true);
        }
    }

    // ranges of a hand slider: fractions go a bit past the model; a point goes 30% of the gun's length past its box
    private float lo(boolean right, int c) {
        GunGeometry g = geo();
        if (!handAt(right) || g == null) return c == 2 ? -0.2F : -0.5F;
        float pad = g.length() * 0.3F;
        return (c == 0 ? g.minX : c == 1 ? g.minY : g.minZ) - pad;
    }

    private float hi(boolean right, int c) {
        GunGeometry g = geo();
        if (!handAt(right) || g == null) return c == 2 ? 1.2F : 1.5F;
        float pad = g.length() * 0.3F;
        return (c == 0 ? g.maxX : c == 1 ? g.maxY : g.maxZ) + pad;
    }

    private float stepOf(boolean right) {
        GunGeometry g = geo();
        return handAt(right) && g != null ? Math.max(1e-3F, g.length() / 400F) : 0.005F;
    }

    private static Object value(String key) {
        try {
            Field f = Config.class.getField(key);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no setting " + key, e);
        }
    }

    private static String trim(float v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
