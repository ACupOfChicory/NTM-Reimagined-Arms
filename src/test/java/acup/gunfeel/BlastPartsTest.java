package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.client.BotModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.phys.Vec3;

// A blast by the body's parts (BlastParts): which boxes count, their shares and multipliers, a part's nearest point and
// the points its rays start from.
class BlastPartsTest {
    /** the FBI agent standing, its boxes marked by its profile as ModelHits.standParts marks them */
    static List<ModelHits.StandPart> agent() {
        var model = new HumanoidModel<HumanoidRenderState>(BotModel.split(BotModelTest.vanilla(), ModelPoseCaptureTest.PARTS));
        model.setupAnim(new HumanoidRenderState());
        var boxes = BotModelTest.worldBoxes(model);
        var heads = ModelHeads.resolve("hbm:entity_fbi", boxes, 0F).zones();
        var limbs = ModelHeads.limbs("hbm:entity_fbi", boxes, 0F).zones();
        List<ModelHits.StandPart> out = new ArrayList<>();
        for (var b : boxes) out.add(new ModelHits.StandPart(b, zoned(heads, b), zoned(limbs, b)));
        return out;
    }

    private static boolean zoned(List<HeadZone> zones, ModelBox box) {
        for (var zone : zones) if (zone.box().equals(box) || zone.pieces().contains(box)) return true;
        return false;
    }

    @Test
    void agentHasTenPartsSharedBySurface() {
        var parts = BlastParts.of(agent());
        // the hat over the head and the forearms' and shins' overlaps inside the upper halves are not counted
        assertEquals(10, parts.size(), parts.toString());
        double sum = 0, mean = 0, head = 0, limbs = 0;
        for (var p : parts) {
            sum += p.share();
            mean += p.share() * p.multiplier();
            if (p.multiplier() > 1) head += p.share();
            if (p.multiplier() < 1) limbs += p.share();
        }
        assertEquals(1, sum, 1e-9);
        // px²: the head 8³ cube 384, the body 8 x 12 x 4 352, each half limb 4 x 6 x 4 128 (eight of them), 1760 in all
        assertEquals(384 / 1760.0, head, 1e-6);
        assertEquals(1024 / 1760.0, limbs, 1e-6);
        // in the open the parts' mean multiplier: 0.218 x 1.75 + 0.2 + 0.582 x 0.5
        assertEquals((384 * 1.75 + 352 + 1024 * 0.5) / 1760, mean, 1e-6);
    }

    @Test
    void noHeadNoLimbIsNtms() {
        var plain = new ArrayList<ModelHits.StandPart>();
        for (var p : agent()) plain.add(new ModelHits.StandPart(p.box(), false, false));
        assertTrue(BlastParts.of(plain).isEmpty());
        assertTrue(BlastParts.of(List.of()).isEmpty());
    }

    @Test
    void nearestPointAndRayPoints() {
        var box = new ModelBox("b", 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0.5);
        assertEquals(new Vec3(1, 1, 0.5), BlastParts.nearest(box, new Vec3(3, 1, 4)));
        assertEquals(new Vec3(0.5, 1, 0.25), BlastParts.nearest(box, new Vec3(0.5, 1, 0.25)));
        assertEquals(new Vec3(0, 0, 0), BlastParts.nearest(box, new Vec3(-1, -1, -1)));
        assertEquals(2 * (2 + 1 + 0.5), BlastParts.surface(box), 1e-9);
        var three = BlastParts.points(box, 3);
        // the middle, then 0.15 and 0.85 along the longest edge (y)
        var want = List.of(new Vec3(0.5, 1, 0.25), new Vec3(0.5, 0.3, 0.25), new Vec3(0.5, 1.7, 0.25));
        assertEquals(3, three.size());
        for (int i = 0; i < 3; i++) assertTrue(three.get(i).distanceTo(want.get(i)) < 1e-9, three.toString());
        assertEquals(1, BlastParts.points(box, 1).size());
        assertEquals(9, BlastParts.points(box, 9).size());
    }

    @Test
    void aBoxOffTheMobIsNotTaken() {
        var near = new net.minecraft.world.phys.AABB(0, 0, 0, 1, 2, 1).inflate(BlastParts.REACH);
        assertTrue(BlastParts.inside(new ModelBox("b", 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0.5), near));
        // a client's box 14 blocks away, or one with NaN in it
        assertTrue(!BlastParts.inside(new ModelBox("b", 14, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0.5), near));
        assertTrue(!BlastParts.inside(new ModelBox("b", Double.NaN, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0.5), near));
    }
}
