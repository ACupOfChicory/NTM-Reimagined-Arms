package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// Stand (mobCases "<mob>#armor_<set>"): how far the hitbox of a mob in armor stands off the armor drawn on it. For
// every box of the caught pose: the bare part, the part grown by WornGear, the armor caught as its layer's geometry.
// Each grown part is measured in the frame of the armor box over it: how far each of its six faces stands out of that
// box, in the model's pixels (1/16 block). Lines go to armor.tsv beside the stand's result.
final class ArmorFloatStand {
    private ArmorFloatStand() {}

    static boolean wanted(List<String> variants) {
        return variants.stream().anyMatch(v -> v.startsWith("armor_") || v.startsWith("head_"));
    }

    static void report(String label, LivingEntity target, List<ModelBox> boxes, java.util.function.BiConsumer<Boolean,String> check) {
        var worn = ModelHits.worn(target);
        // as the hitbox takes it (ModelHits.wornBoxes)
        var grown = WornGear.boxes(boxes, ModelHits.drawn(worn, boxes));
        var lines = new ArrayList<String>();
        lines.add("# " + label + " worn=" + worn + " boxes=" + boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            var b = boxes.get(i);
            var g = grown.get(i);
            lines.add(String.join("\t", label, b.part().contains("/draws/") ? "layer" : "part", b.part(), describe(b),
                    g == b ? "-" : describe(g), b.mesh() == 0 ? "solid" : "mesh"));
        }
        double worst = 0;
        String worstText = "none";
        int matched = 0;
        for (int i = 0; i < boxes.size(); i++) {
            var b = boxes.get(i);
            if (b.part().contains("/draws/")) continue;
            var g = grown.get(i);
            var center = ModelContours.point(b, .5, .5, .5);
            for (var a : boxes) {
                if (!a.part().contains("/draws/") || !inside(a, center)) continue;
                matched++;
                double[] bare = faces(a, b), out = faces(a, g);
                lines.add(String.join("\t", label, "gap", b.part(), a.part(), "bare=" + fmt(bare), "grown=" + fmt(out)));
                for (double d : out) if (d > worst) { worst = d; worstText = b.part() + " in " + a.part() + " grown=" + fmt(out); }
            }
        }
        lines.add("# " + label + " matched=" + matched + " worst_out_px=" + String.format(java.util.Locale.ROOT, "%.3f", worst) + " " + worstText);
        try {
            var file = java.nio.file.Path.of(System.getProperty("ntmgunfeel.checkClient")).resolveSibling("armor.tsv");
            java.nio.file.Files.write(file, lines, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) {
            check.accept(false, "armor.tsv: " + ex);
        }
        int layers = 0, grew = 0;
        for (int i = 0; i < boxes.size(); i++) {
            var part = boxes.get(i).part();
            if (part.contains("/draws/") || part.startsWith("mesh/") && !part.startsWith("mesh/root")) layers++;
            if (grown.get(i) != boxes.get(i)) grew++;
        }
        boolean armorOnly = worn.head() != WornGear.Head.SKULL && worn.head() != WornGear.Head.OLD_SKULL
                && worn.head() != WornGear.Head.DRAGON && worn.head() != WornGear.Head.BLOCK;
        // a block on the head, and a skull without Flywheel, are not caught: the head grows to their size instead
        if (armorOnly) {
            check.accept(layers > 0, "надетое снято как геометрия слоя: частей=" + layers);
            check.accept(grew == 0, "под бронёй хитбокс не растёт: выросших частей=" + grew);
        } else check.accept(true, "предмет на голове: частей слоя=" + layers + ", выросших частей=" + grew);
        check.accept(true, String.format(java.util.Locale.ROOT, "броня: частей под слоем=%d, наибольший выступ грани=%.3f px (%s)", matched, worst, worstText));
    }

    // the box's corner and its three edges' lengths, in pixels
    private static String describe(ModelBox b) {
        var c = ModelContours.point(b, .5, .5, .5);
        return String.format(java.util.Locale.ROOT, "c=%.3f,%.3f,%.3f len=%.3f,%.3f,%.3f", c.x * 16, c.y * 16, c.z * 16,
                16 * Math.sqrt(b.ux() * b.ux() + b.uy() * b.uy() + b.uz() * b.uz()),
                16 * Math.sqrt(b.vx() * b.vx() + b.vy() * b.vy() + b.vz() * b.vz()),
                16 * Math.sqrt(b.wx() * b.wx() + b.wy() * b.wy() + b.wz() * b.wz()));
    }

    private static String fmt(double[] d) {
        var s = new StringBuilder();
        for (double v : d) s.append(s.isEmpty() ? "" : ",").append(String.format(java.util.Locale.ROOT, "%.3f", v));
        return s.toString();
    }

    private static boolean inside(ModelBox a, Vec3 p) {
        double[] f = local(a, p);
        return f[0] >= 0 && f[0] <= 1 && f[1] >= 0 && f[1] <= 1 && f[2] >= 0 && f[2] <= 1;
    }

    // a point in the box's own frame (0..1 on each edge)
    private static double[] local(ModelBox a, Vec3 p) {
        double[][] m = {{a.ux(), a.vx(), a.wx()}, {a.uy(), a.vy(), a.wy()}, {a.uz(), a.vz(), a.wz()}};
        double dx = p.x - a.x(), dy = p.y - a.y(), dz = p.z - a.z();
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        double[] r = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] k = {m[0].clone(), m[1].clone(), m[2].clone()};
            k[0][c] = dx; k[1][c] = dy; k[2][c] = dz;
            r[c] = (k[0][0] * (k[1][1] * k[2][2] - k[1][2] * k[2][1]) - k[0][1] * (k[1][0] * k[2][2] - k[1][2] * k[2][0])
                    + k[0][2] * (k[1][0] * k[2][1] - k[1][1] * k[2][0])) / det;
        }
        return r;
    }

    // how far the box b stands out of the box a, face by face (-u,+u,-v,+v,-w,+w), in pixels; negative: inside
    private static double[] faces(ModelBox a, ModelBox b) {
        double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int k = 0; k < 8; k++) {
            var p = ModelContours.point(b, k & 1, (k >> 1) & 1, (k >> 2) & 1);
            double[] f = local(a, p);
            for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], f[i]); hi[i] = Math.max(hi[i], f[i]); }
        }
        double[] len = {Math.sqrt(a.ux() * a.ux() + a.uy() * a.uy() + a.uz() * a.uz()),
                Math.sqrt(a.vx() * a.vx() + a.vy() * a.vy() + a.vz() * a.vz()),
                Math.sqrt(a.wx() * a.wx() + a.wy() * a.wy() + a.wz() * a.wz())};
        double[] out = new double[6];
        for (int i = 0; i < 3; i++) {
            out[2 * i] = -lo[i] * len[i] * 16;
            out[2 * i + 1] = (hi[i] - 1) * len[i] * 16;
        }
        return out;
    }
}
