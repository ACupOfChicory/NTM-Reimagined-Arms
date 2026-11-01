package acup.gunfeel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

// A GL texture's pixels read without stalling the game (MachineScan): glGetTexImage into a pixel pack buffer, so the
// GPU copies while the game draws on; once it has (a fence, or three frames without one) the buffer is mapped and its
// pixels taken out a slice at a time until the frame's deadline. Without pixel buffers (GL under 2.1) it reads at once.
final class GlRead {
    private static final int SLICE = 1 << 16;

    private int w, h, pbo;
    private GLSync fence;
    private int waited;
    private ByteBuffer mapped;
    private int[] px;
    private int at;
    private boolean done;

    static boolean async() {
        ContextCapabilities c = GLContext.getCapabilities();
        return c.OpenGL21;
    }

    // the read of texture `id` (level 0) starts
    GlRead(int id) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) {
            px = new int[0];
            done = true;
            return;
        }
        if (!async()) {
            IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(w * h);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
            px = new int[w * h];
            buf.get(px);
            done = true;
            return;
        }
        pbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        if (GLContext.getCapabilities().OpenGL32) fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    // takes out what it can until `deadline` (System.nanoTime); true: all is read
    boolean step(long deadline) {
        if (done) return true;
        if (mapped == null) {
            if (fence != null) {
                int s = GL32.glClientWaitSync(fence, 0, 0L);
                if (s != GL32.GL_ALREADY_SIGNALED && s != GL32.GL_CONDITION_SATISFIED) return false;
            } else if (++waited < 3) {
                return false;
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
            ByteBuffer b = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, (long) w * h * 4, null);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            if (b == null) throw new IllegalStateException("pixel buffer not mapped");
            mapped = b.order(ByteOrder.nativeOrder());
            px = new int[w * h];
            if (System.nanoTime() > deadline) return false;
        }
        IntBuffer ints = mapped.asIntBuffer();
        while (at < px.length) {
            int n = Math.min(SLICE, px.length - at);
            ints.position(at);
            ints.get(px, at, n);
            at += n;
            if (System.nanoTime() > deadline) break;
        }
        if (at < px.length) return false;
        free();
        done = true;
        return true;
    }

    // {{w, h}, argb}, as DoorCapture.texture
    int[][] pixels() {
        return new int[][] {{Math.max(w, 0), Math.max(h, 0)}, px};
    }

    int width() {
        return w;
    }

    int height() {
        return h;
    }

    // the buffer and the fence given back (done, or left unfinished)
    void free() {
        if (pbo != 0) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
            if (mapped != null) GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glDeleteBuffers(pbo);
            pbo = 0;
        }
        mapped = null;
        if (fence != null) {
            GL32.glDeleteSync(fence);
            fence = null;
        }
    }
}
