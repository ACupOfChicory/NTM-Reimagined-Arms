package acup.gunfeel.mixins.early;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.MachineMesh;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

// Client: what a machine's renderer draws straight with the Tessellator, for the holes on it (MachineMesh). Only while
// a machine is being read; otherwise nothing.
// port: 1.12.2 keeps the vertices in the BufferBuilder, in its vertex format (NTM CE's pumpjack cables and BAT9000
// gauge are POSITION_COLOR, the crucible's metal POSITION_TEX); they are read into 1.7.10's raw layout that
// MachineMesh.tess takes (8 ints a vertex: x, y, z, u, v as float bits; the translation is already added by pos())
@Mixin(Tessellator.class)
public abstract class MixinTessellator {
    @Inject(method = "draw", at = @At("HEAD"))
    private void gunfeel$draw(CallbackInfo ci) {
        if (!MachineMesh.active()) return;
        BufferBuilder b = ((Tessellator) (Object) this).getBuffer();
        int count = b.getVertexCount();
        VertexFormat f = b.getVertexFormat();
        if (count < 3 || f == null) return;
        int pos = -1, uv = -1;
        for (int i = 0; i < f.getElementCount(); i++) {
            VertexFormatElement e = f.getElement(i);
            if (e.getType() != VertexFormatElement.EnumType.FLOAT) continue;
            if (e.getUsage() == VertexFormatElement.EnumUsage.POSITION && pos < 0) pos = f.getOffset(i);
            if (e.getUsage() == VertexFormatElement.EnumUsage.UV && e.getIndex() == 0 && uv < 0) uv = f.getOffset(i);
        }
        if (pos < 0) return;
        ByteBuffer data = b.getByteBuffer();
        int size = f.getSize();
        int[] raw = new int[count * 8];
        for (int v = 0; v < count; v++) {
            int at = v * size;
            for (int k = 0; k < 3; k++) raw[v * 8 + k] = data.getInt(at + pos + k * 4);
            if (uv >= 0) for (int k = 0; k < 2; k++) raw[v * 8 + 3 + k] = data.getInt(at + uv + k * 4);
        }
        MachineMesh.tess(raw, count, b.getDrawMode(), uv >= 0);
    }
}
