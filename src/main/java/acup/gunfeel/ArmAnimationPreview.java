package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.HbmAnimations;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

/** Изолированные часы предпросмотра: без пакетов, патронов и настоящей перезарядки. */
public final class ArmAnimationPreview {
    private static Item item;
    private static HbmAnimations.Animation animation;
    private static GunAnimation type;
    private static double position, speed = .25;
    private static long sampled;
    private static boolean playing;
    private ArmAnimationPreview() {}
    public static boolean active() {
        var mc = Minecraft.getInstance();
        return animation != null && mc.gui.screen() instanceof ArmAnimationEditor
                && mc.player != null && mc.player.getMainHandItem().getItem() == item;
    }
    static void start(GunAnimation cue) {
        clear();
        var player = Minecraft.getInstance().player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof ItemGunBaseNT gun)) return;
        var stack = player.getMainHandItem().copy();
        var factory = gun.getConfig(stack,0).getAnims(stack);
        if (factory == null) return;
        var buses = factory.apply(stack,cue);
        if (buses == null) return;
        item = gun; type = cue;
        animation = new HbmAnimations.Animation(stack.getItem().getDescriptionId(), com.hbm.util.GameTime.now(), buses, true);
        sampled = System.nanoTime();
    }
    public static HbmAnimations.Animation animation() { return animation; }
    public static GunAnimation type() { return type; }
    static int duration() { return animation == null ? 0 : Math.max(1,animation.animation.getDuration()); }
    public static int millis() {
        long now = System.nanoTime();
        if (playing && sampled != 0) {
            position += (now-sampled)/1e6*speed;
            if (position > duration()) position %= duration();
        }
        sampled = now;
        return (int)Math.round(position);
    }
    static void seek(double time) { position = Math.clamp(time,0,duration()); sampled=System.nanoTime(); }
    static void play(boolean value) { millis(); playing=value; }
    static boolean playing() { return playing; }
    static void speed(double value) { millis(); speed=value; }
    static double speed() { return speed; }
    public static double[] transform(String bus) {
        var sequence = animation.animation.getBus(bus);
        var result = sequence == null ? null : sequence.getTransformation(millis());
        return result == null ? HbmAnimations.identity() : result;
    }
    static void clear() { animation=null; item=null; playing=false; position=0; sampled=0; }
}
