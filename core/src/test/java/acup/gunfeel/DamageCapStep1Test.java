package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;

// b657a52's settings arithmetic, each settings read recorded with its default and position.
class DamageCapStep1Test {
    private static final float[] VALUES = {
            Float.intBitsToFloat(0x7fc00001), Float.intBitsToFloat(0xffc00002), Float.NaN,
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -Float.MAX_VALUE, -1.5F, -1F,
            -Float.MIN_VALUE, -0F, 0F, Float.MIN_VALUE, Math.nextDown(0.5F), 0.5F,
            Math.nextUp(0.5F), Math.nextDown(1F), 1F, Math.nextUp(1F), Math.nextDown(1.5F),
            1.5F, Math.nextUp(1.5F), Math.nextDown(14.5F), 14.5F, Math.nextUp(14.5F),
            Math.nextDown(25.5F), 25.5F, Math.nextUp(25.5F), 0x1p24F, 0x1p31F, Float.MAX_VALUE
    };
    private static final String[] KEYS = {"damageCapClick", "damageCapHead", "damageCapWindow",
            "damageCapWindowTicks", "damageCapSniperHead", "damageCapSniperDelay"};

    private static final class Settings implements DamageCapBase.Settings {
        final List<String> calls = new ArrayList<>();
        final Map<String, Float> values;
        final float[] sequence;
        int at;
        boolean enabled;

        Settings(Map<String, Float> values, float... sequence) {
            this.values = values;
            this.sequence = sequence;
        }

        @Override
        public boolean flag(String key, boolean def) {
            calls.add("flag:" + key + ":" + def);
            return enabled;
        }

        @Override
        public float number(String key, float def) {
            calls.add("number:" + key + ":" + Float.floatToRawIntBits(def));
            return sequence.length == 0 ? values.getOrDefault(key, def) : sequence[at++ % sequence.length];
        }
    }

    // The old methods, only ReaimTimes replaced by the fake settings.
    private static boolean on(Settings settings) {
        return settings.flag("damageCap", true);
    }

    private static DamageCapRule.Limits limits(Settings settings) {
        var d = DamageCapRule.Limits.DEFAULT;
        return new DamageCapRule.Limits(share(settings, "damageCapClick", d.click()), share(settings, "damageCapHead", d.head()), share(settings, "damageCapWindow", d.window()),
                ticks(settings, "damageCapWindowTicks", d.windowTicks()), share(settings, "damageCapSniperHead", d.sniperHead()), ticks(settings, "damageCapSniperDelay", d.sniperDelay()));
    }

    private static float share(Settings settings, String key, float def) {
        float v = settings.number(key, def);
        return Float.isFinite(v) && v > 0F ? v : def;
    }

    private static int ticks(Settings settings, String key, int def) {
        float v = settings.number(key, def);
        return Float.isFinite(v) && v >= 1F ? Math.round(v) : def;
    }

    private static void bits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    private static void same(DamageCapRule.Limits expected, DamageCapRule.Limits actual) {
        bits(expected.click(), actual.click());
        bits(expected.head(), actual.head());
        bits(expected.window(), actual.window());
        assertEquals(expected.windowTicks(), actual.windowTicks());
        bits(expected.sniperHead(), actual.sniperHead());
        assertEquals(expected.sniperDelay(), actual.sniperDelay());
    }

    @Test
    void onReadsEveryTime() {
        Settings old = new Settings(Map.of()), core = new Settings(Map.of());
        for (boolean value : new boolean[] {false, true, false, true}) {
            old.enabled = core.enabled = value;
            assertEquals(on(old), DamageCapBase.onOf(core));
            assertEquals(old.calls, core.calls);
        }
        assertEquals(4, core.calls.size());
    }

    @Test
    void limitsBitsAndReadOrder() {
        Settings old = new Settings(Map.of()), core = new Settings(Map.of());
        same(limits(old), DamageCapBase.limitsOf(core));
        assertEquals(old.calls, core.calls);
        for (String key : KEYS) for (float value : VALUES) {
            old = new Settings(Map.of(key, value));
            core = new Settings(Map.of(key, value));
            same(limits(old), DamageCapBase.limitsOf(core));
            assertEquals(old.calls, core.calls, key + " bits=" + Float.floatToRawIntBits(value));
            assertEquals(6, core.calls.size());
        }
        // Different answers within and between calls also expose reordered reads and caching.
        old = new Settings(Map.of(), VALUES);
        core = new Settings(Map.of(), VALUES);
        for (int i = 0; i < VALUES.length; i++) {
            same(limits(old), DamageCapBase.limitsOf(core));
            assertEquals(old.calls, core.calls);
            assertEquals((i + 1) * 6, core.calls.size());
        }
    }

    @Test
    void shareAndTicksBitsAndReadOrder() {
        for (float value : VALUES) for (float def : VALUES) {
            Settings old = new Settings(Map.of("share", value)), core = new Settings(Map.of("share", value));
            bits(share(old, "share", def), DamageCapBase.shareOf(core, "share", def));
            assertEquals(old.calls, core.calls);
        }
        for (float value : VALUES) for (int def : new int[] {Integer.MIN_VALUE, -1, 0, 1, 14, 25, Integer.MAX_VALUE}) {
            Settings old = new Settings(Map.of("ticks", value)), core = new Settings(Map.of("ticks", value));
            assertEquals(ticks(old, "ticks", def), DamageCapBase.ticksOf(core, "ticks", def));
            assertEquals(old.calls, core.calls);
        }
    }

    @Test
    void liveSettingsAreNotCached() {
        try {
            for (String value : new String[] {"0", "0.5", "1.5", "25.5", "-1", "0"}) {
                ServerSettings.useRemote(new ServerSettings.Snapshot("", Map.of("damageCap", value, "damageCapClick", value,
                        "damageCapWindowTicks", value), Map.of(), ""));
                assertEquals(ReaimTimes.flag("damageCap", true), DamageCapBase.onOf());
                float v = ReaimTimes.number("damageCapClick", 0.6F);
                bits(Float.isFinite(v) && v > 0F ? v : 0.6F, DamageCapBase.limitsOf().click());
                float t = ReaimTimes.number("damageCapWindowTicks", 14);
                assertEquals(Float.isFinite(t) && t >= 1F ? Math.round(t) : 14, DamageCapBase.limitsOf().windowTicks());
                bits(DamageCapBase.limitsOf().click(), DamageCapBase.shareOf("damageCapClick", 0.6F));
                assertEquals(DamageCapBase.limitsOf().windowTicks(), DamageCapBase.ticksOf("damageCapWindowTicks", 14));
            }
        } finally {
            ServerSettings.useRemote(null);
        }
    }

    @Test
    void noGunDelayInEitherHand() {
        Body unread = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class},
                (proxy, method, args) -> { throw new AssertionError("unexpected read: " + method); });
        for (int hand : new int[] {Body.MAIN_HAND, Body.OFF_HAND}) {
            assertEquals(0, NtmGuns.NONE.maxDelay(unread, hand));
            assertEquals(0, NtmGuns.NONE.maxDelay(null, hand));
        }
    }
}
