package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.BulletBlocks;
import acup.gunfeel.host.WorldEdit;
import acup.gunfeel.host.WorldView;

final class BulletBlockFake implements WorldEdit, BulletBlocks {
    record Position(int x, int y, int z) {}
    static final Object LEVEL = new Object(), TOKEN = new Object(), OTHER = new Object(), BULLET = new Object(), HIT = new Object();
    static final Object[] STATES = {"air", "a[0]", "a[1]", "b"};
    final List<List<Object>> calls = new ArrayList<>();
    int state = 1, permission, parsed = 2, failAt = -1;
    int shooterRequests, shootersCreated;
    float hardness = 1F, damage = 6F, k = 1F;
    long now;
    boolean gone;
    BulletRuleText.Break rule = new BulletRuleText.Break(4, 12F, true, true, false, null, 0F, 1F, 0F);
    final WorldView view = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "state" -> { call("state", args[0], args[1], args[2]); yield state; }
                case "air" -> { call("air", args[0]); yield (int) args[0] == 0; }
                case "destroySpeed" -> { call("destroySpeed", args[0], args[1], args[2], args[3]); yield hardness; }
                case "gameTime" -> { call("time"); yield now; }
                default -> throw new AssertionError(method);
            });

    void call(Object... args) {
        calls.add(Arrays.asList(args));
        if (calls.size() == failAt) throw new IllegalStateException("port failed");
    }
    @Override public int mayBreak(Object level, Body owner, Object pos, int state) { call("mayBreak", owner, pos, state); return permission; }
    @Override public int parseState(Object level, String text) { call("parseState", text); return parsed; }
    @Override public Object blockToken(int state) { call("blockToken", state); return state == 3 ? OTHER : TOKEN; }
    @Override public boolean gone(Object level, long pos, Object token) { call("gone", pos, token); return gone; }
    @Override public long time(Object level) { call("time"); return now; }
    @Override public Object position(long pos) { call("position", pos); return new Position(PosLong.blockX(pos), PosLong.blockY(pos), PosLong.blockZ(pos)); }
    @Override public Object stateObject(int state) { call("stateObject", state); return STATES[state]; }
    @Override public void cracks(Object level, int breaker, Object pos, int stage) { call("cracks", breaker, pos, stage); }
    @Override public void destroy(Object level, Object pos, boolean drop, Body by) { call("destroy", pos, drop, by); state = 0; }
    @Override public void set(Object level, Object pos, int state, int flags) { call("set", pos, state, flags); assertTrue(state >= 0); this.state = state; }
    @Override public BulletRuleText.Break rule(int state) { call("rule", state); return rule; }
    @Override public float water(Object bullet, Object hit) { assertSame(BULLET, bullet); assertSame(HIT, hit); call("water"); return k; }
    @Override public BulletServerBase.Shooter shooter(Object bullet) {
        assertSame(BULLET, bullet);
        shooterRequests++;
        call("shooter");
        shootersCreated++;
        return new BulletServerBase.Shooter(17, null, damage);
    }
    @Override public int shotId(Object bullet) { assertSame(BULLET, bullet); call("id"); return 17; }
    @Override public Object location(Object hit) { assertSame(HIT, hit); call("location"); return "hit point"; }
}
