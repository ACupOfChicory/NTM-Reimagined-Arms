package acup.gunfeel.gl54;

import java.util.HashMap;

import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.AnimationLoader;
import com.hbm.render.anim.BusAnimation;
import com.hbm.render.anim.BusAnimationKeyframe;
import com.hbm.render.anim.BusAnimationKeyframe.IType;
import com.hbm.render.anim.BusAnimationSequence;
import com.hbm.render.anim.BusAnimationSequence.Dimension;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

// What the GL-54 plays: the model's own Fire (models/weapons/animations/gl54.json, from the .blend) for a shot, and a
// TEMPORARY lift for drawing it (the model has none: the owner decides on it and on reloading). Reloading and the
// rest play nothing yet. Loaded when first asked on the client and again after every resource reload.
public final class Gl54Anim {
    public static final Identifier FILE = Identifier.fromNamespaceAndPath("ntmgunfeel", "models/weapons/animations/gl54.json");
    public static final String FIRE = "Fire";
    private static volatile HashMap<String, BusAnimation> loaded;

    private Gl54Anim() {
    }

    public static void reset() {
        loaded = null;
    }

    private static HashMap<String, BusAnimation> animations() {
        HashMap<String, BusAnimation> a = loaded;
        if (a == null) {
            a = AnimationLoader.load(FILE);
            if (a == null) a = new HashMap<>();
            loaded = a;
        }
        return a;
    }

    public static BusAnimation get(ItemStack stack, GunAnimation type) {
        switch (type) {
            case CYCLE:
                return animations().get(FIRE);
            case EQUIP:
                return equip();
            default:
                return null;
        }
    }

    // the gun comes up from below, muzzle first, in the 7 ticks of the draw
    static BusAnimation equip() {
        BusAnimationSequence seq = new BusAnimationSequence();
        seq.addKeyframe(Dimension.TY, new BusAnimationKeyframe(-2.5, 0, IType.LINEAR));
        seq.addKeyframe(Dimension.TY, new BusAnimationKeyframe(0, 350, IType.SIN_DOWN));
        seq.addKeyframe(Dimension.TZ, new BusAnimationKeyframe(-1.5, 0, IType.LINEAR));
        seq.addKeyframe(Dimension.TZ, new BusAnimationKeyframe(0, 350, IType.SIN_DOWN));
        seq.addKeyframe(Dimension.RX, new BusAnimationKeyframe(45, 0, IType.LINEAR));
        seq.addKeyframe(Dimension.RX, new BusAnimationKeyframe(0, 350, IType.SIN_DOWN));
        return new BusAnimation().addBus("Gun", seq);
    }
}
