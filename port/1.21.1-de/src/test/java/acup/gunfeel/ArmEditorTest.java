package acup.gunfeel;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

// The port's copy of the root's ArmEditorTest (notes/ports/de-1.21.1.md, "Копии"): the arm editor is not ported
// (skip.txt), so neither are its tests; the other arm tests use its helpers, kept here as they are at the root.
class ArmEditorTest {
    // the guns of the jar with any arm setting, and the animations their keys name
    static Map<String, Set<String>> jarGuns() throws Exception {
        var p = new Properties();
        try (var in = ArmEditorTest.class.getResourceAsStream("/ntmgunfeel-guns.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Map<String, Set<String>> out = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            int dot = key.lastIndexOf('.');
            String gun = key.substring(0, dot), k = key.substring(dot + 1);
            if (gun.contains("-") || !(Grips.armKey(k) || k.equals("sameAs"))) continue;
            var names = out.computeIfAbsent(gun, g -> new TreeSet<>(List.of("RELOAD", "INSPECT")));
            if (k.endsWith("Path") || k.endsWith("CarryKeys") || k.endsWith("Hide") || k.endsWith("Adjustments") || k.equals("armLeftKeys"))
                for (String s : p.getProperty(key).split(";")) {
                    String head = s.trim().split("\\s+")[0];
                    if (head.isEmpty()) continue;
                    int slash = head.indexOf('/');
                    names.add((slash < 0 ? head : head.substring(0, slash)).toUpperCase(Locale.ROOT));
                }
        }
        return out;
    }

    // animations long enough for every key the gun has
    static ArmEditor.Clips clips(Collection<String> names) {
        return clips(names, "", true);
    }

    // a stand-in model: a box 4 x 4 x 20, fractions as shares of it; as the real model, it has no section beyond its ends
    // (GunGeometry.fractions then gives the middle and the bottom); two parts a hand can move with
    static final Map<String, int[]> MOVES = Map.of("SHELL", new int[] {200, 800}, "ROLL", new int[] {1000, 2000});

    static ArmEditor.Clips clips(Collection<String> names, String variant, boolean measured) {
        return clips(names, variant, measured, false);
    }

    // emptyReload: a second RELOAD, as NTM plays it from an empty magazine, where PumpGrip moves too (SPAS-12)
    static ArmEditor.Clips clips(Collection<String> names, String variant, boolean measured, boolean emptyReload) {
        var list = new ArrayList<>(names.stream().map(n -> new ArmEditor.Clip(n, 4000)).toList());
        if (emptyReload) list.add(new ArmEditor.Clip("RELOAD", 4000, "RELOAD" + ArmEditor.Clip.EMPTY));
        ArmEditor.Shape box = new ArmEditor.Shape() {
            public float[] min() {
                return new float[] {-2, -2, -10};
            }

            public float[] max() {
                return new float[] {2, 2, 10};
            }

            public float[] fractions(float[] p) {
                float z = (p[2] + 10) / 20;
                return z < 0 || z > 1 ? new float[] {0.5F, 0F, z} : new float[] {(p[0] + 2) / 4, (p[1] + 2) / 4, z};
            }

            public float[] point(float[] f) {
                return new float[] {f[0] * 4 - 2, f[1] * 4 - 2, f[2] * 20 - 10};
            }
        };
        return new ArmEditor.Clips() {
            public List<ArmEditor.Clip> list() {
                return list;
            }

            public ArmEditor.Shape shape() {
                return measured ? box : null;
            }

            public String variant() {
                return variant;
            }

            public List<String> parts() {
                return List.of("Pump", "Lever");
            }

            // two parts the animation moves: a shell early, a turn of the gun later
            public Map<String, int[]> moves(String clip) {
                if (!clip.endsWith(ArmEditor.Clip.EMPTY)) return MOVES;
                var out = new TreeMap<>(MOVES);
                out.put("PumpGrip", new int[] {300, 600});
                return out;
            }

            public ArmRig.Buses buses(String clip) {
                if (!clip.endsWith(ArmEditor.Clip.EMPTY)) return ArmRig.Buses.NONE;
                return new ArmRig.Buses() {
                    public boolean has(String bus) {
                        return bus.equals("PumpGrip") || MOVES.containsKey(bus);
                    }

                    public double[] at(String bus, float ms) {
                        return null;
                    }
                };
            }
        };
    }

    // guns seen as a variant too (the parts they do not draw), and without a measured model
    static final Map<String, String> VARIANTS = Map.of("hbm:gun_double_barrel", "-Barrel", "hbm:gun_maresleg", "-Stock-Barrel",
            "hbm:gun_autoshotgun_heretic", "-Barrel", "hbm:gun_g3", "-Mag_Paddle-Scope-Silencer");
}
