package acup.gunfeel;

// 4x4 float matrices as GL keeps them: 16 floats, column by column (26.2: JOML Matrix4f).
final class Mat4 {
    private Mat4() {
    }

    // a * b
    static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float s = 0F;
                for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
                r[c * 4 + row] = s;
            }
        }
        return r;
    }

    // m * (x, y, z, 1), the first three
    static float[] point(float[] m, float x, float y, float z) {
        return new float[] {m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14]};
    }

    // the inverse of an affine matrix (last row 0 0 0 1); null when it has none
    static float[] invertAffine(float[] m) {
        float a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
        float co0 = e * i - f * h, co1 = f * g - d * i, co2 = d * h - e * g;
        float det = a * co0 + b * co1 + c * co2;
        if (Math.abs(det) < 1e-12F) return null;
        float s = 1F / det;
        float[] r = new float[16];
        r[0] = co0 * s;
        r[4] = (c * h - b * i) * s;
        r[8] = (b * f - c * e) * s;
        r[1] = co1 * s;
        r[5] = (a * i - c * g) * s;
        r[9] = (c * d - a * f) * s;
        r[2] = co2 * s;
        r[6] = (b * g - a * h) * s;
        r[10] = (a * e - b * d) * s;
        float tx = m[12], ty = m[13], tz = m[14];
        r[12] = -(r[0] * tx + r[4] * ty + r[8] * tz);
        r[13] = -(r[1] * tx + r[5] * ty + r[9] * tz);
        r[14] = -(r[2] * tx + r[6] * ty + r[10] * tz);
        r[15] = 1F;
        return r;
    }

    // column c of the upper 3x3
    static float[] column(float[] m, int c) {
        return new float[] {m[c * 4], m[c * 4 + 1], m[c * 4 + 2]};
    }
}
