package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Mat4Test {
    @Test
    void inverseUndoesAnAffineMatrix() {
        // a turn about a skew axis, a scale and a move, column by column
        double a = 0.7, c = Math.cos(a), s = Math.sin(a);
        float[] m = {(float) c * 2F, (float) s * 2F, 0F, 0F, (float) -s * 2F, (float) c * 2F, 0F, 0F, 0F, 0F, 0.5F, 0F, 1F, -2F, 3F, 1F};
        float[] r = Mat4.mul(Mat4.invertAffine(m), m);
        for (int i = 0; i < 16; i++) assertEquals(i % 5 == 0 ? 1F : 0F, r[i], 1e-6F, "element " + i);
        float[] p = Mat4.point(m, 1F, 0F, 0F);
        assertEquals(1F + 2F * (float) c, p[0], 1e-6F);
        assertEquals(-2F + 2F * (float) s, p[1], 1e-6F);
        assertEquals(3F, p[2], 1e-6F);
    }
}
