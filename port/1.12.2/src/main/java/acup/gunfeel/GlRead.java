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
// pixels copied out on a thread of their own (a pack's block atlas, 4096 x 4096: its array alone took the game's
// thread 2.7 ms), then it is let go. Without pixel buffers (GL under 2.1) it reads at once.
final class GlRead {
    private static final java.util.concurrent.ExecutorService COPY = java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ntm-gunfeel pixels");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    });

    // the mapped buffer's pixels into an array (a class, not a lambda: MachineScan loads it beforehand)
    private static final class Copy implements java.util.concurrent.Callable<int[]> {
        private final ByteBuffer from;
        private final int n;

        Copy(ByteBuffer from, int n) {
            this.from = from;
            this.n = n;
        }

        @Override
        public int[] call() {
            int[] out = new int[n];
            from.asIntBuffer().get(out);
            return out;
        }
    }

    private int w, h, pbo;
    private GLSync fence;
    private int waited;
    private ByteBuffer mapped;
    private java.util.concurrent.Future<int[]> copy;
    private int[] px;
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

    // true: all is read; the pixels are copied out on their own thread, the game's only maps and lets go of the buffer
    boolean step(long deadline) throws Exception {
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
            copy = COPY.submit(new Copy(mapped, w * h));
            return false;
        }
        if (!copy.isDone()) return false;
        px = copy.get();
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
        // not unmapped under the copy
        if (copy != null && !copy.isDone()) {
            try {
                copy.get();
            } catch (Exception e) {
                // let go all the same
            }
        }
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
