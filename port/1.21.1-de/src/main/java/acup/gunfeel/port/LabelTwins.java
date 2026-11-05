package acup.gunfeel.port;

import acup.gunfeel.ModelBox;
import java.util.ArrayList;
import java.util.List;

/**
 * Boxes 1.21.1 draws in one place under one name, a layer that renders the model again (rewrite.txt, HeadProfiles).
 * The frame alone makes them twins: each may carry its own mesh (its texture's mask), so a zone goes on all of them.
 */
public final class LabelTwins {
    private LabelTwins() {}
    private static final double EPS = 1e-4;

    public static boolean same(ModelBox a, ModelBox b) {
        return a.part().equals(b.part())
                && near(a.x(), b.x()) && near(a.y(), b.y()) && near(a.z(), b.z())
                && near(a.ux(), b.ux()) && near(a.uy(), b.uy()) && near(a.uz(), b.uz())
                && near(a.vx(), b.vx()) && near(a.vy(), b.vy()) && near(a.vz(), b.vz())
                && near(a.wx(), b.wx()) && near(a.wy(), b.wy()) && near(a.wz(), b.wz());
    }

    /** The box and its twins, the box first. */
    public static List<Integer> of(List<ModelBox> boxes, int index) {
        var out = new ArrayList<Integer>();
        out.add(index);
        for (int i = 0; i < boxes.size(); i++) if (i != index && same(boxes.get(index), boxes.get(i))) out.add(i);
        return out;
    }

    private static boolean near(double a, double b) { return Math.abs(a - b) < EPS; }
}
