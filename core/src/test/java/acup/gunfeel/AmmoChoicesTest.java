package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;

// AmmoChoices on NtmGuns' reads picks and figures as 26.2's RadialMenu.choices and statsFor did on NTM's own (copied
// here as they were, a round being its number): the same choices in the same order, the same figures bit for bit, and
// NTM asked for a round's reserve no more often
class AmmoChoicesTest {
    private static final int MAX = RadialDial.MAX;

    private record Gun(int[] rounds, int gl, int[] reserve, boolean stats, float[] gun, float[][] round) {
    }

    private static NtmGuns port(Gun g, int[] asked) {
        return (NtmGuns) Proxy.newProxyInstance(NtmGuns.class.getClassLoader(), new Class<?>[] {NtmGuns.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "menuRounds" -> {
                    if (g.gl != 0) return g.gl;
                    int[] out = (int[]) a[2];
                    for (int i = 0; i < g.rounds.length && i < out.length; i++) out[i] = g.rounds[i];
                    return g.rounds.length;
                }
                case "reserve" -> {
                    asked[0]++;
                    return g.reserve[(Integer) a[1]];
                }
                case "choiceStats" -> {
                    if (!g.stats) return false;
                    System.arraycopy(g.gun, 0, (float[]) a[2], 0, NtmGuns.C_COUNT);
                    return true;
                }
                case "roundStats" -> {
                    System.arraycopy(g.round[(Integer) a[0]], 0, (float[]) a[1], 0, NtmGuns.S_COUNT);
                    return null;
                }
                default -> throw new AssertionError(m.getName());
            }
        });
    }

    private static float pick(SplittableRandom r) {
        return switch (r.nextInt(6)) {
            case 0 -> new float[] {0F, -0F, 1F, 0.5F, Float.MIN_VALUE, 1e-30F, 3e30F, Float.NaN, -1F, 100F}[r.nextInt(10)];
            case 1 -> r.nextInt(-3, 40);
            default -> r.nextFloat() * r.nextInt(1, 200);
        };
    }

    @Test
    void choicesAndFiguresAreTheMenus() {
        SplittableRandom r = new SplittableRandom(20261120);
        for (int t = 0; t < 30_000; t++) {
            int kinds = r.nextInt(1, 30);
            int[] rounds = new int[r.nextInt(0, t % 50 == 0 ? 90 : 25)];
            for (int i = 0; i < rounds.length; i++) rounds[i] = r.nextInt(1, kinds + 1);
            int[] reserve = new int[kinds + 1];
            for (int i = 1; i <= kinds; i++) reserve[i] = switch (r.nextInt(4)) {
                case 0 -> 0;
                case 1 -> NtmGuns.INFINITE_ROUNDS;
                default -> r.nextInt(1, 500);
            };
            float[] gun = new float[NtmGuns.C_COUNT];
            for (int i = 0; i < gun.length; i++) gun[i] = pick(r);
            float[][] round = new float[kinds + 1][NtmGuns.S_COUNT];
            for (float[] s : round) {
                for (int i = 0; i < s.length; i++) s[i] = pick(r);
                s[NtmGuns.S_PROJECTILES_MIN] = r.nextInt(0, 30);
                s[NtmGuns.S_PROJECTILES_MAX] = r.nextInt(0, 30);
            }
            Gun g = new Gun(rounds, t % 97 == 0 ? NtmGuns.ROUNDS_GL54 : t % 89 == 0 ? -1 : 0, reserve, r.nextInt(8) != 0, gun, round);
            int[] asked = {0};
            NtmGuns port = port(g, asked);

            List<Integer> want = oldChoices(g, asked);
            int oldAsked = asked[0];
            asked[0] = 0;
            int[] out = new int[MAX];
            int n = AmmoChoices.choices(port, null, out);
            String at = "case " + t;
            if (g.gl == NtmGuns.ROUNDS_GL54) {
                assertEquals(NtmGuns.ROUNDS_GL54, n, at);
            } else {
                List<Integer> got = new ArrayList<>();
                for (int i = 0; i < n; i++) got.add(out[i]);
                assertEquals(want, got, at);
                assertEquals(oldAsked, asked[0], at);
            }
            boolean gl = g.gl == NtmGuns.ROUNDS_GL54;
            int count = gl ? r.nextInt(0, 5) : n;
            assertArrayEquals(oldStatsFor(g, want, gl, count), AmmoChoices.stats(port, null, out, count, gl), at);
        }
    }

    // RadialMenu.choices as it was: accepted, each once, held (count != 0), at most MAX
    private static List<Integer> oldChoices(Gun g, int[] asked) {
        List<Integer> out = new ArrayList<>();
        if (g.gl != 0) return out;
        for (int c : g.rounds) {
            if (out.size() < MAX && !out.contains(c) && reserve(g, c, asked) != 0) out.add(c);
        }
        return out;
    }

    private static int reserve(Gun g, int c, int[] asked) {
        asked[0]++;
        return g.reserve[c];
    }

    // RadialMenu.statsFor as it was (a GL-54 entry has no figures)
    private static AmmoStats[] oldStatsFor(Gun g, List<Integer> list, boolean gl, int count) {
        AmmoStats[] out = new AmmoStats[count];
        if (!g.stats) return out;
        float wear = AmmoStats.wear(g.gun[NtmGuns.C_WEAR], g.gun[NtmGuns.C_DURABILITY]);
        float base = g.gun[NtmGuns.C_BASE_DAMAGE], split = g.gun[NtmGuns.C_SPLIT];
        for (int i = 0; i < out.length; i++) {
            if (gl) continue;
            float[] b = g.round[list.get(i)];
            out[i] = AmmoStats.of(base, wear, split, b[NtmGuns.S_DAMAGE_MULT], (int) b[NtmGuns.S_PROJECTILES_MIN], (int) b[NtmGuns.S_PROJECTILES_MAX],
                    b[NtmGuns.S_HEADSHOT_MULT], b[NtmGuns.S_THRESHOLD_NEGATION], b[NtmGuns.S_ARMOR_PIERCING]);
        }
        return out;
    }
}
