package acup.gunfeel;

import acup.gunfeel.gl54.Gl54Hud;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;

/**
 * The corner HUD's blocks worked out from NTM's numbers (NtmGuns.hudBlocks), without drawing: per block the big number
 * and the small, how many of the big one's leading zeros are dim, their colours, low, the durability, whether the gun
 * is drawn beside it (the first only), its icon and the GL-54's model. Hud (26.2) draws them, block i 34 px above
 * block i - 1. The client's thread only: the arrays and the blocks are kept and written anew each frame.
 */
public final class HudBase {
    public static final int WHITE = 0xFFFFFFFF, DIM = 0x66FFFFFF, LOW = 0xFFFF5555;
    public static final String INFINITE = "∞";
    /** blocks room is kept for; a gun with more is asked again with room for all */
    static final int ROOM = 8;

    private static final NtmGuns GUNS = CoreServices.load(NtmGuns.class, NtmGuns.NONE);
    private static int[] ints = new int[ROOM * NtmGuns.H_INTS];
    private static float[] floats = new float[ROOM * NtmGuns.H_FLOATS];
    private static Object[] objects = new Object[ROOM * NtmGuns.H_OBJECTS];
    private static String[] texts = new String[ROOM];
    private static Block[] blocks = blocks(ROOM);

    /** one block, as drawn: small null for none; the GL-54's gl null for another gun */
    public static final class Block {
        public String big, small;
        public boolean low, drawGun, locked;
        public float health;
        public int lead, color, dim;
        public Object icon;
        public Gl54Hud.Model gl;
    }

    private HudBase() {
    }

    /** The blocks of the gun in the holder's hand: how many there are (then {@link #block}) */
    public static int read(Body holder, int hand) {
        return read(GUNS, holder, hand);
    }

    static int read(NtmGuns guns, Body holder, int hand) {
        int n = guns.hudBlocks(holder, hand, ints, floats, objects, texts);
        if (n > texts.length) {
            ints = new int[n * NtmGuns.H_INTS];
            floats = new float[n * NtmGuns.H_FLOATS];
            objects = new Object[n * NtmGuns.H_OBJECTS];
            texts = new String[n];
            blocks = blocks(n);
            n = guns.hudBlocks(holder, hand, ints, floats, objects, texts);
        }
        for (int i = 0; i < n; i++) model(i, blocks[i]);
        return n;
    }

    public static Block block(int i) {
        return blocks[i];
    }

    private static Block[] blocks(int n) {
        Block[] out = new Block[n];
        for (int i = 0; i < n; i++) out[i] = new Block();
        return out;
    }

    // Hud.render's and Hud.block's arithmetic as it was, between their draws
    private static void model(int i, Block b) {
        int k = i * NtmGuns.H_INTS, f = i * NtmGuns.H_FLOATS, o = i * NtmGuns.H_OBJECTS;
        float wear = floats[f + NtmGuns.H_WEAR], dura = floats[f + NtmGuns.H_DURABILITY];
        b.health = dura > 0 ? GameMath.clamp(1F - wear / dura, 0F, 1F) : 1F;
        b.drawGun = i == 0;
        b.icon = objects[o + NtmGuns.H_ICON];
        b.locked = ints[k + NtmGuns.H_LOCKED] != 0;
        b.gl = null;
        switch (ints[k + NtmGuns.H_KIND]) {
            case NtmGuns.HUD_GL54 -> {
                b.gl = (Gl54Hud.Model) objects[o + NtmGuns.H_GL54];
                b.big = b.gl.big();
                b.small = b.gl.small();
                b.low = false;
            }
            case NtmGuns.HUD_SINGLE -> {
                int amount = ints[k + NtmGuns.H_AMOUNT], capacity = ints[k + NtmGuns.H_CAPACITY], reserve = ints[k + NtmGuns.H_RESERVE];
                b.big = String.format("%03d", Math.min(amount, 999));
                b.small = reserve == NtmGuns.INFINITE_ROUNDS ? INFINITE : String.format("%04d", Math.min(reserve, 9999));
                b.low = capacity > 0 && amount <= Math.max(1, capacity / 4);
            }
            case NtmGuns.HUD_BELT_INFINITE -> {
                b.big = INFINITE;
                b.small = null;
                b.low = false;
            }
            default -> {
                b.big = texts[i];
                b.small = null;
                b.low = false;
            }
        }
        int lead = 0;
        if (b.small != null) {
            while (lead < b.big.length() - 1 && b.big.charAt(lead) == '0') lead++;
        }
        b.lead = lead;
        int color = b.gl != null ? b.gl.color() : b.low ? LOW : WHITE;
        int dim = DIM;
        if (b.gl != null && b.gl.dim()) { // a reload is on: the number is faint
            color = HudColors.faint(color);
            dim = HudColors.faint(dim);
        }
        b.color = color;
        b.dim = dim;
    }
}
