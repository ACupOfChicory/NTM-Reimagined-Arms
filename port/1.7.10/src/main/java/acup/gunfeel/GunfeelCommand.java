package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

// port: 26.2's GripCommand (GripCommand.java, e284339) for 1.7.10's client command /gunfeel (CompatClient.Status hands
// every subcommand but status here): words instead of brigadier, the gun's id in 26.2's form (GunFeelClient.gunId).
// Not ported: the debug ones (meshdump, armdebug, sounddebug, holedebug, testlog).
// /gunfeel grip right|left <x> <y> <z>   hand position for the held NTM gun (fractions of the model size)
// /gunfeel grip rightAt|leftAt <x> <y> <z>   the point in model units, as grip show prints it
// /gunfeel grip show | reset
// /gunfeel set|unset|get <setting> [value]   per-gun override of any setting for the held gun; /gunfeel settings
// /gunfeel quality [low|medium|high|realism], /gunfeel hitvolume [0..4], /gunfeel clearholes
public final class GunfeelCommand {
    public static final String[] WORDS = {"status", "set", "unset", "get", "settings", "quality", "hitvolume", "clearholes", "grip"};

    private GunfeelCommand() {
    }

    public static String usage() {
        return "/gunfeel status | set|unset|get <setting> [value] | settings | quality [preset] | hitvolume [0..4] | clearholes"
                + " | grip show|reset|right|left|rightAt|leftAt <x> <y> <z>";
    }

    // false: not one of these (the caller shows the usage)
    public static boolean run(ICommandSender s, String[] a) {
        switch (a[0]) {
            case "set":
                if (a.length < 2) return false;
                return set(s, a);
            case "unset":
            case "get":
                if (a.length != 2) return false;
                String id = held(s), key = a[1];
                if (id == null || !checkKey(s, key)) return true;
                if (a[0].equals("unset")) {
                    Grips.put(id, key, null);
                    settings(s);
                } else {
                    String own = Grips.overrides(id).get(key);
                    say(s, key + " = " + (own != null ? own + "  (эта пушка)" : Config.globalValue(key) + "  (общее)"));
                }
                return true;
            case "settings":
                settings(s);
                return true;
            case "hitvolume":
                return hitVolume(s, a);
            case "quality":
                return quality(s, a);
            case "clearholes":
                say(s, "Дырки убраны: " + Tracers.clearHoles());
                return true;
            case "grip":
                return grip(s, a);
            default:
                return false;
        }
    }

    public static List<String> complete(String[] a) {
        List<String> out = new ArrayList<>();
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        Iterable<String> from;
        if (a.length == 1) from = java.util.Arrays.asList(WORDS);
        else if (a.length == 2 && (a[0].equals("set") || a[0].equals("unset") || a[0].equals("get"))) {
            TreeSet<String> keys = new TreeSet<>(Config.keys());
            keys.remove("enabled");
            from = keys;
        } else if (a.length == 2 && a[0].equals("quality")) from = new TreeSet<>(Config.PRESETS.keySet());
        else if (a.length == 2 && a[0].equals("grip")) from = java.util.Arrays.asList("show", "reset", "right", "left", "rightAt", "leftAt");
        else return null;
        for (String w : from) if (w.toLowerCase(Locale.ROOT).startsWith(last)) out.add(w);
        return out;
    }

    private static boolean set(ICommandSender s, String[] a) {
        String id = held(s), key = a[1];
        if (id == null || !checkKey(s, key)) return true;
        if (a.length == 2) {
            describe(s, id, key);
            return true;
        }
        StringBuilder value = new StringBuilder();
        for (int i = 2; i < a.length; i++) value.append(i > 2 ? " " : "").append(a[i]);
        Grips.put(id, key, value.toString().trim());
        settings(s);
        return true;
    }

    private static boolean hitVolume(ICommandSender s, String[] a) {
        if (a.length == 1) {
            say(s, "Громкость попаданий: " + Config.hitVolume + " (1 = как в правилах, 0 = тихо, до 4; у взрывных не больше 1). /gunfeel hitvolume <число>");
            return true;
        }
        if (a.length != 2) return false;
        float v;
        try {
            v = Float.parseFloat(a[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (!(v >= 0F && v <= 4F)) return false;
        Config.hitVolume = v;
        try {
            Config.setInFile("hitVolume", Float.toString(v));
        } catch (java.io.IOException e) {
            fail(s, "Не записалось в конфиг: " + e.getMessage());
            return true;
        }
        say(s, "Громкость попаданий: " + v + (v > 1F ? " (звук накладывается " + (int) Math.ceil(v - 1e-3F) + " раза)" : ""));
        return true;
    }

    private static boolean quality(ICommandSender s, String[] a) {
        if (a.length == 1) {
            say(s, "Качество: " + Config.quality + " (low, medium, high, realism). /gunfeel quality <пресет>");
            return true;
        }
        if (a.length != 2) return false;
        String q = a[1].toLowerCase(Locale.ROOT);
        if (!Config.PRESETS.containsKey(q)) {
            fail(s, "Нет такого пресета: " + q + " (low, medium, high, realism)");
            return true;
        }
        try {
            Config.setInFile("quality", q);
        } catch (java.io.IOException e) {
            fail(s, "Не записалось в конфиг: " + e.getMessage());
            return true;
        }
        say(s, "Качество: " + q + " (дырки, их время и частицы; настройки, изменённые вручную, остаются)");
        return true;
    }

    private static boolean grip(ICommandSender s, String[] a) {
        if (a.length == 2 && a[1].equals("show")) {
            show(s);
            return true;
        }
        if (a.length == 2 && a[1].equals("reset")) {
            String id = held(s);
            if (id == null) return true;
            Grips.reset(id + missing());
            show(s);
            return true;
        }
        if (a.length != 5) return false;
        boolean at = a[1].equals("rightAt") || a[1].equals("leftAt");
        boolean right = a[1].equals("right") || a[1].equals("rightAt");
        if (!at && !right && !a[1].equals("left")) return false;
        float[] v = new float[3];
        try {
            for (int i = 0; i < 3; i++) v[i] = Float.parseFloat(a[2 + i]);
        } catch (NumberFormatException e) {
            return false;
        }
        // 26.2: right/left are fractions in -1..2, rightAt/leftAt any model point
        if (!at) for (float f : v) if (!(f >= -1F && f <= 2F)) return false;
        String id = held(s);
        if (id == null) return true;
        if (at) Grips.setAt(id + missing(), right, v[0], v[1], v[2]);
        else Grips.set(id + missing(), right, v[0], v[1], v[2]);
        show(s);
        return true;
    }

    // /gunfeel set <key> with no value: what the held gun uses now and the defaults under it
    private static void describe(ICommandSender s, String gun, String key) {
        String own = Grips.own(gun, key), shipped = Grips.shipped(gun, key);
        String global = Config.globalValue(key), code = Config.codeValue(key);
        String now, from;
        if (own != null && !own.isEmpty()) {
            now = own;
            from = "твоя настройка этой пушки";
        } else if (shipped != null && own == null) {
            now = shipped;
            from = "по умолчанию для этой пушки в моде";
        } else {
            now = global;
            from = global != null && !global.equals(code) ? "твоя общая настройка" : "общее по умолчанию";
        }
        say(s, key + " (" + gun + ")");
        say(s, "  сейчас: " + show(now) + "  — " + from);
        say(s, "  по умолчанию для этой пушки: " + (shipped != null ? shipped : "нет, берётся общее"));
        say(s, "  общее по умолчанию: " + show(code) + (global != null && !global.equals(code) ? " (у тебя в общем конфиге: " + show(global) + ")" : ""));
    }

    private static String show(String v) {
        return v == null ? "?" : v.isEmpty() ? "(пусто)" : v;
    }

    private static boolean checkKey(ICommandSender s, String key) {
        if (Config.isKey(key) && !key.equals("enabled")) return true;
        fail(s, "Нет такой настройки: " + key);
        return false;
    }

    private static void settings(ICommandSender s) {
        String id = held(s);
        if (id == null) return;
        Map<String, String> own = Grips.overrides(id);
        say(s, id + (own.isEmpty() ? ": своих настроек нет, всё из общего конфига" : ": " + own.toString().replaceAll("[{}]", "")));
    }

    private static String held(ICommandSender s) {
        ItemStack stack = Minecraft.getMinecraft().thePlayer == null ? null : Minecraft.getMinecraft().thePlayer.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemGunBaseNT)) {
            fail(s, "Возьми пушку NTM в основную руку");
            return null;
        }
        return GunFeelClient.gunId(stack.getItem());
    }

    private static void show(ICommandSender s) {
        String id = held(s);
        if (id == null) return;
        String missing = missing();
        Grips.Grip g = Grips.get(id, missing);
        say(s, id + missing + "  right" + (g.rightAt() != null ? "At " + fmt(g.rightAt()) : " " + fmt(g.right()))
                + "  left" + (g.leftAt() != null ? "At " + fmt(g.leftAt()) : " " + fmt(g.left())));
    }

    // model parts the held gun's variant does not draw ("-Stock-Barrel" for a sawn-off), "" = the full gun:
    // hand positions set while holding a variant go to that variant only
    private static String missing() {
        ItemStack stack = Minecraft.getMinecraft().thePlayer == null ? null : Minecraft.getMinecraft().thePlayer.getHeldItem();
        GunGeometry g = stack == null ? null : Arms.geometry(stack.getItem());
        return g == null ? "" : g.missing;
    }

    private static String fmt(float[] v) {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", v[0], v[1], v[2]);
    }

    private static void say(ICommandSender s, String text) {
        s.addChatMessage(new ChatComponentText(text));
    }

    private static void fail(ICommandSender s, String text) {
        s.addChatMessage(new ChatComponentText(text).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
    }
}
