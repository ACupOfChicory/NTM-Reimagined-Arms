package acup.gunfeel;

// A bullet hole as core draws it (HoleDecals), in numbers: the version makes it (Tracers) and keeps what it alone
// knows (the block, its state, the part a renderer moves) in `host`.
// pos = x, y, z; the face: its axis (0 x, 1 y, 2 z) and direction (step, +1 or -1); rect = the area of the face the
// hole may cover, {uMin, uMax, vMin, vMax} on the face's two axes; the mask: which 1/16-block cells of the face show
// the block's pixels (solid null: all), maskW x maskH cells from cell (maskU0, maskV0) on the face's two axes;
// plane = where on the face's axis it lies (NaN: the hit point's); mesh: a hole cut from a drawn model, {normal xyz,
// then per polygon its count and per vertex x, y, z (from pos), u, v, alpha} (null: a flat hole).
final class HoleDecal {
    final double x, y, z;
    final int axis, step;
    final int turn;
    final long born;
    final int light, kind;
    final float size;
    final double[] rect;
    final int maskU0, maskV0, maskW, maskH;
    final boolean[] solid;
    final double plane;
    final boolean late, cull;
    final float[] mesh;
    final boolean front, drawn, machine;
    final Object host;
    // on a part a renderer moves: where it is this frame, as the version puts it (movedMesh null: cut away whole)
    final boolean moving;
    double mx, my, mz;
    float[] movedMesh;
    int movedLight;
    // the one batch this hole is drawn in now (null: none), kept with the hole instead of looked up in a map
    HoleDecals.Batch batch;

    HoleDecal(double x, double y, double z, int axis, int step, int turn, long born, int light, int kind, float size, double[] rect,
            int maskU0, int maskV0, int maskW, int maskH, boolean[] solid, double plane, boolean late, boolean cull, float[] mesh,
            boolean front, boolean drawn, boolean machine, boolean moving, Object host) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.axis = axis;
        this.step = step;
        this.turn = turn;
        this.born = born;
        this.light = light;
        this.kind = kind;
        this.size = size;
        this.rect = rect;
        this.maskU0 = maskU0;
        this.maskV0 = maskV0;
        this.maskW = maskW;
        this.maskH = maskH;
        this.solid = solid;
        this.plane = plane;
        this.late = late;
        this.cull = cull;
        this.mesh = mesh;
        this.front = front;
        this.drawn = drawn;
        this.machine = machine;
        this.moving = moving;
        this.host = host;
    }

    // the moving part's place this frame
    void moved(double x, double y, double z, float[] mesh, int light) {
        mx = x;
        my = y;
        mz = z;
        movedMesh = mesh;
        movedLight = light;
    }
}
