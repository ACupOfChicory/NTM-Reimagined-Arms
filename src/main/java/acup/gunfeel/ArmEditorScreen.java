package acup.gunfeel;

import java.util.*;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.BusAnimation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Экран «Руки пушки»: показывает {@link ArmEditor}. Каждая кнопка — действие модели с подсказкой. */
public final class ArmEditorScreen extends Screen {
    private static final int W = 244, H = 14, GAP = 2, LABEL = 84, ROW = 10, BUS = 4, SET_H = 12, SET_STEP = 13, SMALL = 16;
    private static final int[] COLOR = {0xFF555566, 0xFFB07CFF, 0xFF4FC3F7, 0xFFFFB74D, 0xFFE57373, 0xFF81C784, 0xFF9E9E9E};
    private final Screen parent;
    private final Item item;
    private final ArmEditor model;
    private final Map<String, int[]> moves = new LinkedHashMap<>();
    private int previewClip = -1;
    private String actionsShown = "";
    private final Map<String, Button> buttons = new LinkedHashMap<>();
    // texts of the settings pages: where they stand, how wide they may be, and their hint
    record Text(String text, String hint, int x, int y, int w) {
    }

    private final List<Text> texts = new ArrayList<>();
    private Slider held;

    public ArmEditorScreen(Screen parent, Item item, String gun) {
        super(Component.literal("Руки пушки"));
        this.parent = parent;
        this.item = item;
        this.model = new ArmEditor(gun, new GameClips());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    ArmEditor model() {
        return model;
    }

    // ---------------------------------------------------------------- the gun's animations from NTM

    private final class GameClips implements ArmEditor.Clips {
        private final Map<String, BusAnimation> animations = new LinkedHashMap<>();

        GameClips() {
            var player = Minecraft.getInstance().player;
            if (player == null || !(player.getMainHandItem().getItem() instanceof ItemGunBaseNT gun)) return;
            var stack = player.getMainHandItem().copy();
            var factory = gun.getConfig(stack, 0).getAnims(stack);
            if (factory == null) return;
            for (GunAnimation cue : GunAnimation.values()) {
                try {
                    BusAnimation a = factory.apply(stack, cue);
                    if (a != null && a.getDuration() > 0) animations.put(cue.name(), a);
                } catch (RuntimeException e) {
                    GunFeel.LOGGER.debug("No {} animation for {}", cue, stack, e);
                }
            }
        }

        @Override
        public List<ArmEditor.Clip> list() {
            var out = new ArrayList<ArmEditor.Clip>();
            animations.forEach((name, a) -> out.add(new ArmEditor.Clip(name, a.getDuration())));
            return out;
        }

        @Override
        public ArmRig.Buses buses(String clip) {
            BusAnimation a = animations.get(clip);
            if (a == null) return ArmRig.Buses.NONE;
            return new ArmRig.Buses() {
                public boolean has(String bus) {
                    return a.getBus(bus) != null;
                }

                public double[] at(String bus, float ms) {
                    var s = a.getBus(bus);
                    return s == null ? null : s.getTransformation((int) ms);
                }

                public boolean drawn(String part, int nth) {
                    return Arms.editorPartDrawn(part, nth);
                }
            };
        }

        // the buses that move in a clip, from the model's part names and the buses the settings name
        @Override
        public Map<String, int[]> moves(String clip) {
            BusAnimation a = animations.get(clip);
            if (a == null) return Map.of();
            var names = new LinkedHashSet<String>(Arms.editorParts());
            for (String s : new String[] {Config.armReleaseLeft, Config.armReleaseRight}) if (!s.isEmpty()) names.add(s);
            for (String s : new String[] {Config.armLeftCarry, Config.armRightCarry}) {
                var c = ArmRig.carry(s);
                if (c != null) names.addAll(List.of(c.buses()));
            }
            Map<String, int[]> out = new LinkedHashMap<>();
            int end = a.getDuration();
            for (String bus : names) {
                var s = a.getBus(bus);
                if (s == null) continue;
                var spans = new ArrayList<Integer>();
                double[] last = s.getTransformation(0);
                boolean moving = false;
                for (int t = ArmEditor.STEP; t <= end; t += ArmEditor.STEP) {
                    double[] now = s.getTransformation(t);
                    boolean m = !Arrays.equals(now, last);
                    if (m != moving) spans.add(t - ArmEditor.STEP);
                    moving = m;
                    last = now;
                }
                if (moving) spans.add(end);
                if (spans.isEmpty()) continue;
                out.put(bus, spans.stream().mapToInt(Integer::intValue).toArray());
            }
            return out;
        }

        @Override
        public ArmEditor.Shape shape() {
            GunGeometry g = Arms.geometry(item);
            if (g == null) return null;
            return new ArmEditor.Shape() {
                public float[] min() {
                    return new float[] {g.minX, g.minY, g.minZ};
                }

                public float[] max() {
                    return new float[] {g.maxX, g.maxY, g.maxZ};
                }

                public float[] fractions(float[] point) {
                    return g.fractions(point);
                }

                public float[] point(float[] fractions) {
                    return g.grip(fractions);
                }
            };
        }

        @Override
        public String variant() {
            GunGeometry g = Arms.geometry(item);
            return g == null ? "" : g.missing;
        }

        @Override
        public List<String> parts() {
            return Arms.editorParts();
        }

        @Override
        public String gunName(String gun) {
            try {
                Item i = BuiltInRegistries.ITEM.getValue(Identifier.parse(gun.contains("-") ? gun.substring(0, gun.indexOf('-')) : gun));
                return new ItemStack(i).getHoverName().getString();
            } catch (RuntimeException e) {
                return gun;
            }
        }
    }

    // ---------------------------------------------------------------- preview: NTM's animation on its own clock

    private void syncPreview() {
        var clip = model.clip();
        if (clip == null) {
            ArmAnimationPreview.clear();
            previewClip = -1;
            return;
        }
        if (previewClip != model.clip || ArmAnimationPreview.animation() == null) {
            previewClip = model.clip;
            ArmAnimationPreview.start(GunAnimation.valueOf(clip.name()));
            moves.clear();
            moves.putAll(model.clips.moves(clip.name()));
        }
        ArmAnimationPreview.speed(model.speed);
        if (model.playing) {
            ArmAnimationPreview.play(true);
            model.time = ArmAnimationPreview.millis();
        } else {
            ArmAnimationPreview.play(false);
            if (Math.round(model.time) != ArmAnimationPreview.millis()) ArmAnimationPreview.seek(model.time);
        }
    }

    // ---------------------------------------------------------------- widgets

    private Button button(ArmEditor.Action a, int x, int y, int w) {
        String tip = a.hint() + (a.hotkey().isEmpty() ? "" : " Клавиша: " + a.hotkey() + ".") + (a.active() ? "" : "\nСейчас нельзя: " + a.why() + ".");
        Button b = Button.builder(Component.literal(a.label()), p -> press(a.id())).bounds(x, y, w, H).build();
        b.setTooltip(Tooltip.create(Component.literal(tip)));
        b.active = a.active();
        buttons.put(a.id(), b);
        return addRenderableWidget(b);
    }

    // the button of an action now on screen, null = none
    Button button(String id) {
        return buttons.get(id);
    }

    List<Text> texts() {
        return texts;
    }

    List<Slider> sliders() {
        var out = new ArrayList<Slider>();
        for (var w : children()) if (w instanceof Slider sl) out.add(sl);
        return out;
    }

    // after the model was changed from outside (the stand check)
    void refresh() {
        syncPreview();
        rebuildWidgets();
    }

    void press(String id) {
        model.press(id);
        if (model.closed) {
            onClose();
            return;
        }
        syncPreview();
        rebuildWidgets();
    }

    private int timelineY, timelineH;

    // a row of buttons across the column: each as wide as its label needs, the rest of the row shared out
    private void row(Map<String, ArmEditor.Action> a, int y, String... ids) {
        var list = new ArrayList<ArmEditor.Action>();
        for (String id : ids) if (a.containsKey(id)) list.add(a.get(id));
        int[] w = new int[list.size()];
        int used = GAP * (list.size() - 1);
        for (int i = 0; i < w.length; i++) used += w[i] = font.width(list.get(i).label()) + 8;
        int spare = Math.max(0, W - used), x = 4;
        for (int i = 0; i < w.length; i++) {
            int width = w[i] + spare / w.length + (i == w.length - 1 ? spare % w.length : 0);
            button(list.get(i), x, y, width);
            x += width + GAP;
        }
    }

    @Override
    protected void init() {
        syncPreview();
        buttons.clear();
        texts.clear();
        held = null;
        Map<String, ArmEditor.Action> a = new LinkedHashMap<>();
        for (var x : model.actions()) a.put(x.id(), x);
        actionsShown = signature();
        int y = 14;
        row(a, y, "page.time", "page.key", "page.grip", "page.arm", "page.both");
        y += H + GAP;
        if (model.page != ArmEditor.Page.TIME) {
            settingsPage(a, y);
            return;
        }
        row(a, y, "clip.prev", "clip.next", "hand.right", "hand.left");
        y += H + GAP;
        row(a, y, "time.start", "time.back", "play", "time.forward", "time.end", "speed");
        y += H + GAP;
        row(a, y, "key.prev", "key.next", "undo", "redo");
        y += H + GAP;
        row(a, y, "reset", "arms.own", "arms.shared", "close");
        y += H + GAP;
        textY = y + 1;
        // two lines of text, then the time lane: ruler and "what moves the hand", the tracks, the buses
        timelineY = y + 32;
        int rows = ArmEditor.Track.values().length;
        int fixed = 16 + rows * ROW + 2;
        int busRows = Math.clamp((height - 4 - timelineY - fixed) / (BUS + 1), 0, Math.min(moves.size(), 8));
        timelineH = fixed + busRows * (BUS + 1);
        for (int i = 0; i < rows; i++) {
            var t = ArmEditor.Track.values()[i];
            var b = button(a.get("track." + t.name().toLowerCase(Locale.ROOT)), 4, timelineY + 16 + i * ROW, LABEL);
            b.setHeight(ROW - 1);
        }
        addRenderableWidget(new Lane(4 + LABEL + 2, timelineY, W - LABEL - 2, timelineH));
    }

    private int textY;

    // a settings page: the animation still plays above; then one line per setting, its buttons at the right
    private void settingsPage(Map<String, ArmEditor.Action> a, int y) {
        row(a, y, "clip.prev", "play", "clip.next", "speed");
        y += H + GAP;
        row(a, y, "hand.right", "hand.left", "undo", "redo");
        y += H + GAP;
        row(a, y, "reset", "arms.own", "arms.shared", "close");
        y += H + GAP;
        if (model.page == ArmEditor.Page.KEY) {
            row(a, y, "key.prev", "key.next", "key.add", "key.delete");
            y += H + GAP;
            row(a, y, "key.view.where", "key.view.approach", "key.view.track", "key.view.hold");
            y += H + GAP;
        }
        textY = y + 1;
        y += 12;
        for (var r : model.rows()) {
            int right = 4 + W;
            for (int i = r.ids().size() - 1; i >= 0; i--) {
                var act = a.get(r.ids().get(i));
                if (act == null) continue;
                int w = act.label().length() <= 1 ? SMALL : font.width(act.label()) + 8;
                right -= w;
                button(act, right, y, w).setHeight(SET_H);
                right -= GAP;
            }
            if (r.slider() != null) addRenderableWidget(new Slider(r.slider(), 4, y, right - 4, SET_H, r.hint()));
            else texts.add(new Text(r.text(), r.hint(), 4, y + 2, right - 4));
            y += SET_STEP;
        }
    }

    // what the buttons show: rebuilt when it changes (a key passed while playing, the end reached)
    private String signature() {
        var sb = new StringBuilder();
        for (var a : model.actions()) sb.append(a.id()).append(a.label()).append(a.why()).append(';');
        return sb.toString();
    }

    @Override
    public void tick() {
        var player = Minecraft.getInstance().player;
        if (player == null || player.getMainHandItem().getItem() != item) {
            onClose();
            return;
        }
        syncPreview();
        // not while a slider is dragged: it would be made anew under the mouse
        if (held == null && !signature().equals(actionsShown)) rebuildWidgets();
    }

    @Override
    public void onClose() {
        model.playing = false;
        Grips.flush();
        ArmAnimationPreview.clear();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void removed() {
        ArmAnimationPreview.clear();
        Grips.flush();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int x, int y, float a) {
        g.fill(0, 0, W + 8, height, 0x90101018);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
        if (model.playing) model.time = ArmAnimationPreview.millis();
        super.extractRenderState(g, mx, my, a);
        var clip = model.clip();
        String title = "Руки пушки · " + (clip == null ? "нет анимаций" : ArmEditor.clipLabel(clip.name()) + " · " + ArmEditor.seconds(clip.ms()));
        String shared = model.sharedWith();
        if (shared != null) title += Grips.ownArms(baseGun()) ? " · свои руки" : " · руки общие с «" + model.clips.gunName(shared) + "»";
        g.text(font, title, 4, 4, 0xFFFFCC55);
        var d = model.driver(model.time);
        g.text(font, "Сейчас (" + Math.round(model.time) + " мс): " + d.label, 4, textY, COLOR[d.ordinal()] | 0xFF000000);
        if (model.page != ArmEditor.Page.TIME) {
            for (var t : texts) {
                g.text(font, t.text(), t.x(), t.y(), 0xFFE0E0E0);
                if (mx >= t.x() && mx < t.x() + t.w() && my >= t.y() - 2 && my < t.y() + SET_H - 2)
                    g.setTooltipForNextFrame(font, Component.literal(t.hint()), mx, my);
            }
            return;
        }
        String note = model.trackNote(model.track);
        if (note == null) note = "Дорожка «" + model.track.label + "»: ключей " + model.keys(model.track).size() + ".";
        g.textWithWordWrap(font, Component.literal(note), 4, textY + 10, W, 0xFFAAAAAA);
    }

    private String baseGun() {
        int dash = model.gun.indexOf('-');
        return dash < 0 ? model.gun : model.gun.substring(0, dash);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean ctrl = (event.modifiers() & 2) != 0;
        String id = switch (event.key()) {
            case 32 -> "play";
            case 263 -> "time.back";
            case 262 -> "time.forward";
            case 268 -> "time.start";
            case 269 -> "time.end";
            case 266 -> "clip.prev";
            case 267 -> "clip.next";
            case 91 -> "key.prev";
            case 93 -> "key.next";
            case 260 -> "key.add";
            case 261 -> "key.delete";
            case 90 -> ctrl ? "undo" : null;
            case 89 -> ctrl ? "redo" : null;
            default -> null;
        };
        if (id != null) {
            press(id);
            return true;
        }
        return super.keyPressed(event);
    }

    // ---------------------------------------------------------------- a setting's slider

    final class Slider extends AbstractSliderButton {
        final ArmEditor.Setting setting;

        Slider(ArmEditor.Setting s, int x, int y, int w, int h, String hint) {
            super(x, y, w, h, Component.literal(s.text()), Math.clamp((s.value() - s.min()) / (s.max() - s.min()), 0.0, 1.0));
            this.setting = s;
            active = s.why() == null;
            setTooltip(Tooltip.create(Component.literal(hint + " Тяни или щёлкни." + (active ? "" : "\nСейчас нельзя: " + s.why() + "."))));
        }

        private float target() {
            return setting.min() + (float) value * (setting.max() - setting.min());
        }

        @Override
        protected void updateMessage() {
            float v = ArmEditor.snap(Math.clamp(target(), setting.min(), setting.max()), setting.step());
            setMessage(Component.literal(new ArmEditor.Setting(setting.id(), setting.label(), setting.hint(), setting.unit(), v, setting.min(),
                    setting.max(), setting.step(), setting.why()).text()));
        }

        @Override
        protected void applyValue() {
            held = this;
            model.slide(setting.id(), target(), false);
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            release();
        }

        // the drag is over: one step of undo, buttons as the new value makes them
        void release() {
            if (held != this) return;
            held = null;
            model.slide(setting.id(), target(), true);
            rebuildWidgets();
        }

        // for the stand: as a click at this share of the width and a let-go
        void clickAt(double share) {
            value = Math.clamp(share, 0.0, 1.0);
            applyValue();
            updateMessage();
            release();
        }
    }

    // ---------------------------------------------------------------- the time lane: ruler, driver, keys, buses

    private final class Lane extends AbstractWidget {
        Lane(int x, int y, int w, int h) {
            super(x, y, w, h, Component.literal("Время анимации"));
            setTooltip(Tooltip.create(Component.literal(
                    "Время анимации. Щёлкни или тяни, чтобы выбрать момент. Верхняя полоса — что ведёт руку; ромбы — ключи дорожек; серое — дорожку перекрывает другая; внизу — когда двигаются детали.")));
        }

        private float timeAt(double mx) {
            return (float) Math.clamp((mx - getX()) / getWidth(), 0, 1) * model.duration();
        }

        private int xAt(float t) {
            return getX() + Math.round(t / Math.max(1, model.duration()) * (getWidth() - 1));
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            seekTo(event.x());
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            seekTo(event.x());
        }

        private void seekTo(double mx) {
            model.playing = false;
            model.seek(timeAt(mx));
            syncPreview();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
            int x = getX(), y = getY(), w = getWidth();
            g.fill(x, y, x + w, y + getHeight(), 0xC0181820);
            int duration = model.duration();
            if (duration <= 0) return;
            // ruler: a tick each 100 ms, a long one each second
            for (int t = 0; t <= duration; t += 100) {
                int tx = xAt(t);
                g.fill(tx, y, tx + 1, y + (t % 1000 == 0 ? 6 : 3), 0xFF777777);
            }
            // what moves the hand along the animation
            var drivers = model.drivers();
            for (int i = 0; i < drivers.length; i++) {
                int x0 = xAt(i * ArmEditor.STEP), x1 = Math.max(x0 + 1, xAt(Math.min((i + 1) * ArmEditor.STEP, duration)));
                g.fill(x0, y + 8, x1, y + 14, COLOR[drivers[i].ordinal()]);
            }
            var tracks = ArmEditor.Track.values();
            for (int r = 0; r < tracks.length; r++) {
                var t = tracks[r];
                int ry = y + 16 + r * ROW;
                if (t == model.track) g.fill(x, ry, x + w, ry + ROW - 1, 0x40FFFFFF);
                if (model.trackWhy(t) != null) {
                    g.fill(x, ry, x + w, ry + ROW - 1, 0x60000000);
                    continue;
                }
                for (int i = 0; i < drivers.length; i++) {
                    if (ArmEditor.coveredBy(t, drivers[i]) == null) continue;
                    int x0 = xAt(i * ArmEditor.STEP);
                    g.fill(x0, ry + 4, x0 + 1, ry + ROW - 5, 0x80888888);
                }
                for (float k : model.keys(t)) {
                    if (k < 0 || k > duration) continue;
                    int kx = xAt(k), cy = ry + ROW / 2 - 1;
                    int c = t == model.track ? 0xFFFFFFFF : 0xFFB0B0B0;
                    g.fill(kx - 1, cy - 2, kx + 2, cy + 3, c);
                    g.fill(kx - 2, cy - 1, kx + 3, cy + 2, c);
                }
            }
            int by = y + 16 + tracks.length * ROW + 2, n = 0;
            for (var e : moves.entrySet()) {
                if (n++ >= 8) break;
                int[] s = e.getValue();
                for (int j = 0; j + 1 < s.length; j += 2) g.fill(xAt(s[j]), by, Math.max(xAt(s[j]) + 1, xAt(s[j + 1])), by + BUS, 0xFF607080);
                if (my >= by && my < by + BUS + 1 && mx >= x && mx < x + w)
                    g.setTooltipForNextFrame(font, Component.literal("Двигается деталь " + e.getKey()), mx, my);
                by += BUS + 1;
            }
            int px = xAt(model.time);
            g.fill(px, y, px + 1, y + getHeight(), 0xFFFF5555);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
