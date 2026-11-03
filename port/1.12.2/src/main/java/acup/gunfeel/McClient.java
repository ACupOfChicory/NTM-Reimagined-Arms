package acup.gunfeel;

import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.IBlockAccess;

// port: new for 1.12.2. Mc's client half: what only the client has (block models, sounds played here).
public final class McClient {
    private McClient() {}

    // the texture on the block's face (1.7.10: Block.getIcon(world, x, y, z, side)): its model's first quad on that
    // face, else the model's particle texture
    public static net.minecraft.client.renderer.texture.TextureAtlasSprite icon(IBlockAccess w, int x, int y, int z, int face) {
        BlockPos p = new BlockPos(x, y, z);
        IBlockState s = w.getBlockState(p).getActualState(w, p);
        net.minecraft.client.renderer.block.model.IBakedModel m = Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(s);
        java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> q = m.getQuads(s, net.minecraft.util.EnumFacing.byIndex(face), 0L);
        return q.isEmpty() ? m.getParticleTexture() : q.get(0).getSprite();
    }

    // the sound on this client only, at x, y, z (1.7.10: World.playSound with distance delay off)
    public static void playLocal(double x, double y, double z, String id, float volume, float pitch) {
        SoundEvent s = Mc.sound(id);
        Minecraft mc = Minecraft.getMinecraft();
        if (s != null && mc.world != null) mc.world.playSound(x, y, z, s, SoundCategory.PLAYERS, volume, pitch, false);
    }
}
