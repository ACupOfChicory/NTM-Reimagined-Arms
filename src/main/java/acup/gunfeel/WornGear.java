package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

// What a mob wears, on its hitbox (modelHitboxesWorn, on by default): the model parts under worn armor or a head
// item grow by what vanilla draws over them.
// - Armor (HumanoidArmorLayer; 1.12.2 LayerBipedArmor) is the player's cubes inflated: helmet, chestplate and boots by
//   1 pixel a side, leggings by 0.5. Drawn on humanoid models only (head, body and two arms; the legs may be hidden,
//   as NTM's full Digamma hides them), and on a model of its own: a skeleton's 2-pixel limbs wear 4-pixel armor. Both
//   platforms catch that drawn armor as geometry of its layer, so a part here only grows by the side, about its middle:
//   armor is solid, a part taken as a mesh of its opaque pixels (a skeleton's ribs) is a whole cube under it.
// - A skull on the head (CustomHeadLayer; 1.12.2 LayerCustomHead) is the 8-pixel skull drawn 1.1875 times larger,
//   9.5 pixels, standing on the base of the head (1.7.10's RenderBiped draws it 1.0625 times larger, 8.5: OLD_SKULL);
//   a dragon's head is its 16-pixel cube drawn at 0.75 of that, 14.25, standing the same way (its snout sticks out
//   further and is not counted); a block (a carved pumpkin) is drawn at 0.625 of a block, 10 pixels, about the head's
//   middle. The head grows to at least that on each axis. On any model
//   with a head part. The base is the head's lower face, found by the world's up.
// The model's pixel is read off the part itself (a head is 8 pixels across, a body, arm or leg 12 tall), so a baby or
// a giant gets its own. A limb in two parts (the mob AI's bots: an upper arm and a forearm, a thigh and a shin, each
// about half of it) takes it off its thickness, 4 pixels, and both halves grow as the whole limb does. The outer
// layers (hat, jacket, sleeves, trousers) already stand off their part by 0.5 or 0.25 and grow by the rest.
// Only the model's own parts grow: the armor (both platforms) and head items (26.2) caught as geometry of their layers
// are left as they are drawn.
// Pure: the platforms say what is worn (ModelHits.worn); the boxes grow for the hitbox, the head and limb zones after
// ModelHeads matched them on the bare model (its sizes pick the profile).
public final class WornGear {
    public enum Head { NONE, ARMOR, SKULL, OLD_SKULL, DRAGON, BLOCK }

    public record Worn(Head head, boolean chest, boolean legs, boolean feet) {
        public static final Worn NOTHING = new Worn(Head.NONE, false, false, false);

        public boolean any() {
            return head != Head.NONE || chest || legs || feet;
        }
    }

    static final double SKULL_PIXELS = 8 * 1.1875, OLD_SKULL_PIXELS = 8 * 1.0625, DRAGON_PIXELS = 16 * 0.75 * 1.1875, BLOCK_PIXELS = 16 * 0.625;
    private static final String[] HUMANOID = {"head", "body", "right_arm", "left_arm"};
    /** the lower halves of a limb in two parts (BotModel.FOREARM, SHIN) */
    static final String FOREARM = "forearm", SHIN = "shin";

    private WornGear() {
    }

    // a part path's last name: "root/head/hat" -> "hat"
    static String name(String part) {
        int at = part.lastIndexOf('/');
        return at < 0 ? part : part.substring(at + 1);
    }

    public static boolean humanoid(List<ModelBox> boxes) {
        for (String want : HUMANOID) {
            boolean found = false;
            for (ModelBox b : boxes) if (name(b.part()).equals(want)) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    public static List<ModelBox> boxes(List<ModelBox> boxes, Worn worn) {
        if (!worn.any()) return boxes;
        boolean humanoid = humanoid(boxes);
        List<ModelBox> out = new ArrayList<>(boxes.size());
        for (ModelBox b : boxes) out.add(grow(b, worn, humanoid));
        return out;
    }

    /** the model's arms and legs are in two parts each (a forearm, a shin) */
    public static boolean split(List<ModelBox> boxes) {
        for (ModelBox b : boxes) {
            String name = name(b.part());
            if (name.equals(FOREARM) || name.equals(SHIN)) return true;
        }
        return false;
    }

    // humanoid: of the model the zones were found on (humanoid(its boxes))
    public static List<HeadZone> zones(List<HeadZone> zones, Worn worn, boolean humanoid) {
        return zones(zones, worn, humanoid, false);
    }

    // split: the model's limbs are in two parts (split(its boxes))
    public static List<HeadZone> zones(List<HeadZone> zones, Worn worn, boolean humanoid, boolean split) {
        if (!worn.any()) return zones;
        List<HeadZone> out = new ArrayList<>(zones.size());
        for (HeadZone z : zones) {
            ModelBox grown = grow(z.box(), worn, humanoid, split, true);
            if (grown == z.box()) {
                out.add(z);
                continue;
            }
            // the zone's cuts stay where they were on the part, in its own frame; the cubes of a part without
            // Flywheel (HeadZone.pieces) do not: what is worn covers the whole grown box, as Flywheel's grown box
            // drops the part's mesh
            List<HeadZone.Plane> local = new ArrayList<>(z.planes().size());
            for (HeadZone.Plane p : z.planes()) local.add(HeadZone.localPlane(z.box(), p));
            out.add(HeadZone.local(grown, local));
        }
        return out;
    }

    // the part grown by what is worn over it; the same box when nothing is
    static ModelBox grow(ModelBox b, Worn worn, boolean humanoid) {
        return grow(b, worn, humanoid, false);
    }

    static ModelBox grow(ModelBox b, Worn worn, boolean humanoid, boolean split) {
        return grow(b, worn, humanoid, split, false);
    }

    // zone: a head zone under a helmet covers its hat as well, 1.5 pixels out of the head (vanilla's armor head has
    // it, drawn where its texture's hat is opaque: SECURITY's is); the hitbox's boxes take the helmet's 1. A zone grows
    // ZONE_MARGIN past the armor: the armor's faces, drawn in floats, lie on the grown face, and a shot on them came
    // out of the zone by up to a quarter of a pixel (the bots' sleeves on the stand)
    static final double ZONE_MARGIN = 0.1;

    static ModelBox grow(ModelBox b, Worn worn, boolean humanoid, boolean split, boolean zone) {
        // a layer's own draw (26.2 names it ".../draws/<n>/tree/<part>"): the armor or head item caught as geometry
        // is already the size it is drawn at
        if (b.part().contains("/draws/")) return b;
        String name = name(b.part());
        double side = 0, outer = 0, least = 0, base;
        boolean byLeast, standing = false;
        switch (name) {
            case "head", "hat" -> {
                outer = name.equals("hat") ? 0.5 : 0;
                base = 8 + 2 * outer;
                byLeast = true;
                if (worn.head() == Head.ARMOR && humanoid) side = zone ? 1.5 : 1;
                else if (worn.head() == Head.SKULL || worn.head() == Head.OLD_SKULL || worn.head() == Head.DRAGON) {
                    least = worn.head() == Head.SKULL ? SKULL_PIXELS : worn.head() == Head.OLD_SKULL ? OLD_SKULL_PIXELS : DRAGON_PIXELS;
                    standing = true;
                } else if (worn.head() == Head.BLOCK) least = BLOCK_PIXELS;
            }
            case "body", "jacket" -> {
                outer = name.equals("jacket") ? 0.25 : 0;
                base = 12 + 2 * outer;
                byLeast = false;
                if (humanoid) side = worn.chest() ? 1 : worn.legs() ? 0.5 : 0;
            }
            case "right_arm", "left_arm", "right_sleeve", "left_sleeve", FOREARM -> {
                outer = name.endsWith("sleeve") ? 0.25 : 0;
                base = split ? 4 : 12 + 2 * outer;
                byLeast = split;
                if (humanoid && worn.chest()) side = 1;
            }
            case "right_leg", "left_leg", "right_pants", "left_pants", SHIN -> {
                outer = name.endsWith("pants") ? 0.25 : 0;
                base = split ? 4 : 12 + 2 * outer;
                byLeast = split;
                if (humanoid) side = worn.feet() ? 1 : worn.legs() ? 0.5 : 0;
            }
            default -> {
                return b;
            }
        }
        if (zone && side > 0) side += ZONE_MARGIN;
        double[][] e = {{b.ux(), b.uy(), b.uz()}, {b.vx(), b.vy(), b.vz()}, {b.wx(), b.wy(), b.wz()}};
        double[] len = new double[3];
        for (int i = 0; i < 3; i++) len[i] = Math.sqrt(e[i][0] * e[i][0] + e[i][1] * e[i][1] + e[i][2] * e[i][2]);
        if (len[0] < 1e-9 || len[1] < 1e-9 || len[2] < 1e-9) return b;
        // one of the part's pixels, in the box's units
        double pixel = (byLeast ? Math.min(len[0], Math.min(len[1], len[2])) : Math.max(len[0], Math.max(len[1], len[2]))) / base;
        if (side == 0 && least == 0) return b;
        double[] grown = new double[3];
        for (int i = 0; i < 3; i++) {
            grown[i] = len[i];
            if (side > outer) grown[i] += 2 * (side - outer) * pixel;
            if (least > 0) grown[i] = Math.max(grown[i], least * pixel);
        }
        if (grown[0] == len[0] && grown[1] == len[1] && grown[2] == len[2]) return b;
        // a standing item keeps the head's lower face: the edge nearest the world's up grows from its lower end
        int up = -1;
        if (standing) {
            up = 0;
            for (int i = 1; i < 3; i++) if (Math.abs(e[i][1]) / len[i] > Math.abs(e[up][1]) / len[up]) up = i;
        }
        double x = b.x(), y = b.y(), z = b.z();
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            double k = grown[i] / len[i], back = (grown[i] - len[i]) / 2 / len[i];
            if (i == up) back = e[i][1] > 0 ? 0 : 2 * back;
            for (int j = 0; j < 3; j++) out[i][j] = e[i][j] * k;
            x -= e[i][0] * back;
            y -= e[i][1] * back;
            z -= e[i][2] * back;
        }
        // armor is solid; a skull or a block is drawn over the whole head as well
        return new ModelBox(b.part(), x, y, z, out[0][0], out[0][1], out[0][2], out[1][0], out[1][1], out[1][2],
                out[2][0], out[2][1], out[2][2]);
    }
}
