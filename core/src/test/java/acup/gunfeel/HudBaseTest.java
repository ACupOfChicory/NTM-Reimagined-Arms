package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.Random;

import org.junit.jupiter.api.Test;

import acup.gunfeel.gl54.Gl54Hud;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;

// HudBase's arithmetic against Hud.render's and Hud.block's as they were before the port (copied below), on random
// numbers through a port that answers hudBlocks only
class HudBaseTest {
    private static final int WHITE = 0xFFFFFFFF, DIM = 0x66FFFFFF, LOW = 0xFFFF5555, INFINITE = -1;

    // what a block was asked to hold: the port's answer
    private record Given(int kind, int amount, int capacity, int reserve, boolean locked, float wear, float dura, Object icon,
            Gl54Hud.Model gl, String text) {
    }

    private static NtmGuns port(Given[] blocks, int[] asked) {
        return (NtmGuns) Proxy.newProxyInstance(NtmGuns.class.getClassLoader(), new Class<?>[] {NtmGuns.class}, (p, m, a) -> {
            if (!m.getName().equals("hudBlocks")) throw new UnsupportedOperationException(m.getName());
            asked[0]++;
            int[] ints = (int[]) a[2];
            float[] floats = (float[]) a[3];
            Object[] objects = (Object[]) a[4];
            String[] texts = (String[]) a[5];
            for (int i = 0; i < blocks.length && i < texts.length; i++) {
                Given g = blocks[i];
                int k = i * NtmGuns.H_INTS, f = i * NtmGuns.H_FLOATS, o = i * NtmGuns.H_OBJECTS;
                ints[k + NtmGuns.H_KIND] = g.kind;
                ints[k + NtmGuns.H_LOCKED] = g.locked ? 1 : 0;
                floats[f + NtmGuns.H_WEAR] = g.wear;
                floats[f + NtmGuns.H_DURABILITY] = g.dura;
                objects[o + NtmGuns.H_ICON] = g.icon;
                objects[o + NtmGuns.H_GL54] = g.gl;
                texts[i] = g.text;
                if (g.kind == NtmGuns.HUD_SINGLE) {
                    ints[k + NtmGuns.H_AMOUNT] = g.amount;
                    ints[k + NtmGuns.H_CAPACITY] = g.capacity;
                    ints[k + NtmGuns.H_RESERVE] = g.reserve;
                }
            }
            return blocks.length;
        });
    }

    private static float someFloat(Random r) {
        return switch (r.nextInt(8)) {
            case 0 -> 0F;
            case 1 -> -r.nextFloat() * 10F;
            case 2 -> Float.NaN;
            case 3 -> r.nextInt(5000);
            default -> r.nextFloat() * 3000F;
        };
    }

    private static int someInt(Random r) {
        return switch (r.nextInt(6)) {
            case 0 -> 0;
            case 1 -> r.nextInt(3);
            case 2 -> 990 + r.nextInt(20);
            case 3 -> 9990 + r.nextInt(20);
            case 4 -> -r.nextInt(3);
            default -> r.nextInt(400);
        };
    }

    private static Given given(Random r) {
        int kind = r.nextInt(4);
        Gl54Hud.Model gl = null;
        String text = null;
        if (kind == NtmGuns.HUD_GL54) {
            int[] dots = new int[r.nextInt(3)];
            gl = new Gl54Hud.Model(String.format("%03d", r.nextInt(12)), String.format("%04d", r.nextInt(40)), r.nextInt(),
                    r.nextBoolean(), r.nextBoolean() ? -1F : r.nextFloat(), dots, r.nextBoolean());
        } else if (kind == NtmGuns.HUD_OTHER) {
            text = switch (r.nextInt(4)) {
                case 0 -> "0";
                case 1 -> "00012";
                case 2 -> "12 / 30";
                default -> "";
            };
        }
        int reserve = r.nextInt(5) == 0 ? INFINITE : someInt(r);
        return new Given(kind, someInt(r), someInt(r), reserve, r.nextBoolean(), someFloat(r), someFloat(r),
                r.nextBoolean() ? new Object() : null, gl, text);
    }

    @Test
    void blocksAreTheOldArithmetic() {
        Random r = new Random(20261120L);
        for (int run = 0; run < 20000; run++) {
            Given[] blocks = new Given[1 + r.nextInt(r.nextInt(10) == 0 ? 12 : 4)];
            for (int i = 0; i < blocks.length; i++) blocks[i] = given(r);
            int[] asked = {0};
            int n = HudBase.read(port(blocks, asked), null, Body.MAIN_HAND);
            assertEquals(blocks.length, n);
            for (int i = 0; i < n; i++) {
                Given g = blocks[i];
                HudBase.Block b = HudBase.block(i);
                String at = "run " + run + " block " + i + " " + g;
                // render
                float health = g.dura > 0 ? clamp(1F - g.wear / g.dura, 0F, 1F) : 1F;
                assertEquals(Float.floatToRawIntBits(health), Float.floatToRawIntBits(b.health), at);
                assertEquals(i == 0, b.drawGun, at);
                // block, as it was
                String big, small = null;
                boolean low;
                if (g.kind == NtmGuns.HUD_GL54) {
                    big = g.gl.big();
                    small = g.gl.small();
                    low = false;
                } else if (g.kind == NtmGuns.HUD_SINGLE) {
                    int amount = g.amount, capacity = g.capacity;
                    big = String.format("%03d", Math.min(amount, 999));
                    int reserve = g.reserve;
                    small = reserve == INFINITE ? "∞" : String.format("%04d", Math.min(reserve, 9999));
                    low = capacity > 0 && amount <= Math.max(1, capacity / 4);
                } else {
                    big = g.kind == NtmGuns.HUD_BELT_INFINITE ? "∞" : g.text;
                    low = false;
                }
                int lead = 0;
                if (small != null) {
                    while (lead < big.length() - 1 && big.charAt(lead) == '0') lead++;
                }
                Gl54Hud.Model gl = g.kind == NtmGuns.HUD_GL54 ? g.gl : null;
                int color = gl != null ? gl.color() : low ? LOW : WHITE;
                int dim = DIM;
                if (gl != null && gl.dim()) {
                    color = HudColors.faint(color);
                    dim = HudColors.faint(dim);
                }
                assertEquals(big, b.big, at);
                assertEquals(small, b.small, at);
                assertEquals(low, b.low, at);
                assertEquals(lead, b.lead, at);
                assertEquals(color, b.color, at);
                assertEquals(dim, b.dim, at);
                assertEquals(g.locked, b.locked, at);
                assertSame(g.icon, b.icon, at);
                if (gl == null) assertNull(b.gl, at);
                else assertSame(gl, b.gl, at);
            }
            // more than the room: asked once more with room for all, never again after
            assertEquals(blocks.length > HudBase.ROOM && blocks.length > seen ? 2 : 1, asked[0], "run " + run);
            seen = Math.max(seen, blocks.length);
        }
    }

    private static int seen = HudBase.ROOM;

    // Mth.clamp(float, float, float) of 26.2, as its bytecode has it
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    @Test
    void noGunNoBlocks() {
        assertEquals(0, HudBase.read(NtmGuns.NONE, null, Body.MAIN_HAND));
    }
}
