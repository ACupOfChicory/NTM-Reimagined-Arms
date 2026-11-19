package acup.gunfeel.smoke.client;

import java.nio.ByteBuffer;

import acup.gunfeel.smoke.SmokeGrid;

/**
 * The texels of the smoke's atlas, the numbers only: a cloud's layers (y) side by side in a row of tiles, one texel a
 * cell, a cloud a row ("slot"); each tile has a border texel of open, empty air, so a bilinear filter never reads the
 * next tile. The version's adapter makes the texture, looks the light up and sends the rows (26.2: {@code SmokeAtlas},
 * Blaze3D having no 3D textures).
 *
 * <p>A texel: R the density a tick ago and G now (the shader blends them by the partial tick, so the spreading is smooth
 * at 20 Hz), both times "open" and scaled by 1/{@link #SCALE}; B how lit the cell is; A 255 open, 0 closed. The shader
 * divides the filtered density by the filtered openness: a sample next to a wall takes only the open cells' smoke, so
 * there is no clear strip along a wall and no film through one.
 */
public final class SmokeAtlasTexels {
    public static final int TILE_W = SmokeGrid.SX + 2, TILE_H = SmokeGrid.SZ + 2;
    public static final int WIDTH = TILE_W * SmokeGrid.SY;
    /** Densities up to this go in a byte. */
    public static final float SCALE = 1.25F;

    private SmokeAtlasTexels() {
    }

    /** A cell's bytes dx, dz cells back (the box went dx, dz on); the cells that came in get {@code empty}. */
    public static void move(byte[] a, int dx, int dz, byte empty) {
        byte[] was = a.clone();
        for (int y = 0; y < SmokeGrid.SY; y++) for (int z = 0; z < SmokeGrid.SZ; z++) for (int x = 0; x < SmokeGrid.SX; x++) {
            int fx = x + dx, fz = z + dz;
            a[SmokeGrid.index(x, y, z)] = SmokeGrid.inside(fx, y, fz) ? was[SmokeGrid.index(fx, y, fz)] : empty;
        }
    }

    /**
     * A slot's tile rows {@code lo} to {@code hi} into {@code out} (cleared first; texel bytes at their place in the
     * slot): {@code now}, the densities last sent, becomes "a tick ago" and takes the grid's; {@code light} by cell.
     */
    public static void fill(SmokeGrid g, byte[] now, byte[] light, boolean first, int lo, int hi, ByteBuffer out) {
        out.clear();
        for (int ty = lo; ty <= hi; ty++) {
            int z = ty - 1;
            for (int y = 0; y < SmokeGrid.SY; y++) {
                for (int tx = 0; tx < TILE_W; tx++) {
                    int x = tx - 1, at = (ty * WIDTH + y * TILE_W + tx) * 4;
                    if (x < 0 || z < 0 || x >= SmokeGrid.SX || z >= SmokeGrid.SZ) {
                        out.put(at, (byte) 0).put(at + 1, (byte) 0).put(at + 2, (byte) 255).put(at + 3, (byte) 255);
                        continue;
                    }
                    int i = SmokeGrid.index(x, y, z);
                    boolean closed = g.closedAt(i);
                    int v = closed ? 0 : Math.min(255, Math.round(g.density(i) / SCALE * 255F));
                    byte before = first ? (byte) v : now[i];
                    now[i] = (byte) v;
                    out.put(at, closed ? 0 : before).put(at + 1, (byte) v).put(at + 2, light[i]).put(at + 3, closed ? 0 : (byte) 255);
                }
            }
        }
    }
}
