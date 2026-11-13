package acup.gunfeel.ai.client;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiBlockKinds;
import acup.gunfeel.ai.AiRoomsPayload;
import acup.gunfeel.ai.Dirs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws what /armsai debug rooms sends: each room's floor in its own colour with its number, kind and size over its
 * middle; a way's cells orange (red with a door, bright blue over a sill: a window climbed through) with a frame; the cells beside a way yellow outside and pink inside;
 * stairs as white bars; windows as light blue panes in the wall (paler when glazed); cells under a roof that are no
 * building's as grey crosses; and, when asked for, the blocks of the rooms' shells by their kind (brown: the ground,
 * blue-grey: put there, green: someone lives there). Per-tick gizmos, with or without F3+B.
 */
public final class AiRoomsClient {
    private AiRoomsClient() {
    }

    private static @Nullable AiRoomsPayload rooms;
    /** a room's colour by its number */
    static final int[] PALETTE = {0x4040A0FF, 0x4040D060, 0x40C060E0, 0x4030C8C8, 0x40E0A030, 0x408080FF, 0x4090D030, 0x40E070A0, 0x4060B0A0, 0x40B09060};
    static final int WAY = 0x80FF8020, DOOR = 0x80FF3030, SILL = 0x8030D0FF, OUT = 0x70FFE020, IN = 0x70FF70C0, STAIR = 0xFFF8F8F8, WILD = 0x90707070, WINDOW = 0x7040C8FF, GLAZED = 0x70B0E8FF;
    static final int[] KINDS = {0x50808080, 0x709A6030, 0x705878A0, 0x7030C050};

    public static void receive(AiRoomsPayload p) {
        rooms = p.count() == 0 && p.labels().isEmpty() ? null : p;
    }

    static void draw() {
        AiRoomsPayload p = rooms;
        if (p == null) return;
        BlockPos o = p.origin();
        byte[] m = p.marks();
        for (int i = 0; i < m.length; i += AiRoomsPayload.MARK) {
            int x = o.getX() + m[i], y = o.getY() + m[i + 1], z = o.getZ() + m[i + 2], value = m[i + 4] & 0xFF;
            switch (m[i + 3]) {
                case AiRoomsPayload.ROOM -> floor(x, y, z, 0.04, PALETTE[value % PALETTE.length], 0.03);
                case AiRoomsPayload.WAY -> {
                    boolean door = (value & 1) != 0, sill = (value & 4) != 0;
                    floor(x, y, z, 0.02, door ? DOOR : sill ? SILL : WAY, 0.05);
                    int c = door ? 0xFFFF3030 : sill ? 0xFF30D0FF : 0xFFFF8020;
                    Vec3 a = new Vec3(x + 0.05, y + 0.06, z + 0.05), b = new Vec3(x + 0.95, y + 0.06, z + 0.05), cc = new Vec3(x + 0.95, y + 0.06, z + 0.95),
                            d = new Vec3(x + 0.05, y + 0.06, z + 0.95);
                    Gizmos.line(a, b, c, 3F);
                    Gizmos.line(b, cc, c, 3F);
                    Gizmos.line(cc, d, c, 3F);
                    Gizmos.line(d, a, c, 3F);
                    // a way out from under the roof: a mark standing in it
                    if ((value & 2) != 0) Gizmos.line(new Vec3(x + 0.5, y + 0.06, z + 0.5), new Vec3(x + 0.5, y + 1.0, z + 0.5), c, 3F);
                }
                case AiRoomsPayload.BESIDE -> Gizmos.cuboid(new AABB(x + 0.25, y + 0.05, z + 0.25, x + 0.75, y + 0.2, z + 0.75), GizmoStyle.fill(value == 0 ? OUT : IN));
                case AiRoomsPayload.STAIRS -> {
                    for (double t = 0.25; t < 1; t += 0.25) {
                        if (value == 0) Gizmos.line(new Vec3(x + 0.15, y + 0.08, z + t), new Vec3(x + 0.85, y + 0.08, z + t), STAIR, 2F);
                        else Gizmos.line(new Vec3(x + 0.3, y + t * 1.6, z + 0.5), new Vec3(x + 0.7, y + t * 1.6, z + 0.5), STAIR, 2F);
                    }
                }
                case AiRoomsPayload.WILD -> {
                    Gizmos.line(new Vec3(x + 0.25, y + 0.04, z + 0.25), new Vec3(x + 0.75, y + 0.04, z + 0.75), WILD, 2F);
                    Gizmos.line(new Vec3(x + 0.75, y + 0.04, z + 0.25), new Vec3(x + 0.25, y + 0.04, z + 0.75), WILD, 2F);
                }
                case AiRoomsPayload.WINDOW -> {
                    // a pane across the wall's block, facing the room
                    int dir = value & 7;
                    boolean alongX = Dirs.DX[dir] == 0;
                    double t = 0.42;
                    AABB box = alongX ? new AABB(x + 0.05, y + 0.05, z + t, x + 0.95, y + 0.95, z + 1 - t) : new AABB(x + t, y + 0.05, z + 0.05, x + 1 - t, y + 0.95, z + 0.95);
                    Gizmos.cuboid(box, GizmoStyle.fill((value & 8) != 0 ? GLAZED : WINDOW));
                }
                case AiRoomsPayload.WALL -> {
                    if (value != AiBlockKinds.NEUTRAL && value < KINDS.length) Gizmos.cuboid(new AABB(x + 0.3, y + 0.3, z + 0.3, x + 0.7, y + 0.7, z + 0.7).inflate(0.21), GizmoStyle.stroke(KINDS[value] | 0xFF000000, 2F));
                }
                default -> {
                }
            }
        }
        for (AiRoomsPayload.Label l : p.labels()) Gizmos.billboardTextOverBlock(l.text(), l.at(), 0, l.colour(), 0.5F);
    }

    private static void floor(int x, int y, int z, double inset, int colour, double up) {
        Gizmos.rect(new Vec3(x + inset, y + up, z + inset), new Vec3(x + 1 - inset, y + up + 0.01, z + 1 - inset), Direction.UP, GizmoStyle.fill(colour));
    }
}
