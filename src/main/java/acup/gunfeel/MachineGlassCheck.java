package acup.gunfeel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3d;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PmachineGlass: real NTM machines (chemical plant, chemical factory) in the test world. Once the
// client has read their shapes (MeshShapes), a ray at a clear pixel of each must meet a window (ModelSurface.GlassHit)
// and pierce it as glass; with bulletMachineGlass off the same ray flies through as before. A real NTM bullet fired on
// the server at the window must lose damage in it. The windows found go to the log, by texture.
final class MachineGlassCheck {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.machineGlassCheck");
    private static final String[] MACHINES = {"hbm:machine_chemical_plant", "hbm:machine_chemical_factory"};
    // the chemical factory's clear pixels are only the empty part of its texture: no window on it
    private static final boolean[] WINDOWS = {true, false};
    private static final List<BlockPos> CORES = new ArrayList<>();
    private static volatile boolean placed;
    private static volatile String failure;
    private static long readyAt;
    private static int step;

    private MachineGlassCheck() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var server = mc.getSingleplayerServer();
                var level = server.overworld();
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                com.hbm.blocks.multiblock.BlockMultiblockCore.withoutTeardown(() -> {
                    for (int x = -8; x <= 28; x++) for (int z = -8; z <= 24; z++) for (int y = 99; y <= 112; y++)
                        level.setBlock(new BlockPos(x, y, z), (y == 99 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 3);
                });
                player.teleportTo(level, 10.5, 100, -6.5, Set.of(), 0, 0, false);
                for (int i = 0; i < MACHINES.length; i++) {
                    var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(MACHINES[i]));
                    ItemStack stack = new ItemStack(item);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    BlockPos floor = new BlockPos(2 + 14 * i, 99, 8);
                    var before = new java.util.HashSet<>(level.getChunkAt(floor).getBlockEntities().keySet());
                    var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(new Vec3(floor.getX() + .5, 100, floor.getZ() + .5), Direction.UP, floor, false)));
                    BlockPos core = null;
                    for (BlockPos p : level.getChunkAt(floor).getBlockEntities().keySet())
                        if (!before.contains(p) && Structures.taken(level.getBlockState(p))) core = p.immutable();
                    if (core == null) for (int dx = -4; dx <= 4 && core == null; dx++) for (int dz = -4; dz <= 4 && core == null; dz++)
                        for (int dy = 0; dy <= 4 && core == null; dy++) {
                            BlockPos p = floor.offset(dx, 1 + dy, dz);
                            if (level.getBlockEntity(p) != null && Structures.taken(level.getBlockState(p))) core = p.immutable();
                        }
                    if (core == null) throw new IllegalStateException(MACHINES[i] + " не поставлен: " + result);
                    CORES.add(core);
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            } catch (Throwable t) {
                failure = t.toString();
                GunFeel.LOGGER.error("[machineglass]", t);
            }
            placed = true;
        });
    }

    static void frame() {
        if (!placed) return;
        if (failure != null) {
            ClientSmoke.check(false, "стекло машин: " + failure);
            ClientSmoke.finish();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        var level = mc.level;
        if (readyAt == 0) readyAt = System.nanoTime();
        double waited = (System.nanoTime() - readyAt) / 1e9;
        // the client must have read every machine's shape, windows included
        boolean all = true;
        for (int i = 0; i < CORES.size(); i++) all &= !WINDOWS[i] || windowFaces(level, CORES.get(i)) > 0;
        // every block of every machine has its cells (from the jar's table, ShapePack, or taken whole by MeshShapes)
        for (BlockPos core : CORES)
            for (BlockPos p : com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level, core))
                all &= Structures.cells(level, p, level.getBlockState(p)) != null;
        if (!all && waited < 90) return;
        if (step++ < 40) return; // a few more frames: the capture is published whole
        for (int i = 0; i < CORES.size(); i++) check(level, CORES.get(i), MACHINES[i], WINDOWS[i]);
        ClientSmoke.finish();
    }

    // glass faces in the shapes of the machine's blocks, as bullets meet them
    private static int windowFaces(net.minecraft.client.multiplayer.ClientLevel level, BlockPos core) {
        var state = level.getBlockState(core);
        if (!Structures.taken(state)) return 0;
        int n = 0;
        for (BlockPos p : com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level, core)) {
            long[] cells = Structures.cells(level, p, level.getBlockState(p));
            if (cells == null) continue;
            for (ModelSurface.Face f : ModelSurface.faces(cells)) if (f.glass()) n++;
        }
        return n;
    }

    private static void check(net.minecraft.client.multiplayer.ClientLevel level, BlockPos core, String name, boolean hasWindows) {
        var be = level.getBlockEntity(core);
        if (be == null) {
            ClientSmoke.check(false, name + ": нет блок-сущности на клиенте");
            return;
        }
        RenderedMesh.dropped = new ArrayList<>();
        List<RenderedMesh.Tri> tris = new ArrayList<>(RenderedMesh.of(be));
        // and its body baked into the chunk (the block model of its core)
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(core);
        for (BlockPos p : com.hbm.blocks.multiblock.MultiblockSurface.rigidStructureBlocks(level, core)) box = box.minmax(new net.minecraft.world.phys.AABB(p));
        int ber = tris.size();
        tris.addAll(RenderedMesh.models(level, box.inflate(.5), core, new net.minecraft.world.phys.AABB(core), new boolean[1],
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())));
        GunFeel.LOGGER.info("[machineglass] {}: {} triangles by the renderer, {} of block models", name, ber, tris.size() - ber);
        Map<String, Integer> drop = new java.util.TreeMap<>();
        for (String d : RenderedMesh.dropped) drop.merge(d, 1, Integer::sum);
        RenderedMesh.dropped = null;
        GunFeel.LOGGER.info("[machineglass] {}: dropped as light {}", name, drop);
        Map<String, int[]> byTex = new LinkedHashMap<>();
        List<RenderedMesh.Tri> windows = new ArrayList<>();
        for (RenderedMesh.Tri t : tris) {
            if (t.tex() instanceof RenderedMesh.Window) {
                windows.add(t);
                byTex.computeIfAbsent(RenderedMesh.texName(t.tex()), k -> new int[1])[0]++;
            }
        }
        GunFeel.LOGGER.info("[machineglass] {}: {} triangles, {} windows {}", name, tris.size(), windows.size(),
                byTex.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0]).toList());
        if (!hasWindows) {
            ClientSmoke.check(windows.isEmpty() && windowFaces(level, core) == 0, name + ": окон по альфе нет (прозрачное — пустое место текстуры): "
                    + windows.size() + " треугольников, " + windowFaces(level, core) + " граней");
            return;
        }
        ClientSmoke.check(!windows.isEmpty(), name + ": окна на модели найдены: " + windows.size() + " треугольников " + byTex.keySet());
        ClientSmoke.check(windowFaces(level, core) > 0, name + ": стеклянные грани в форме для пуль: " + windowFaces(level, core));
        // rays from outside at clear pixels: the first thing met is that window
        int tried = 0, glass = 0, wasAir = 0, blocked = 0;
        Vec3 shotFrom = null, shotAt = null;
        for (RenderedMesh.Tri t : windows) {
            Vector3d n = t.normal();
            if (n == null) continue;
            for (int a = 1; a < 24 && tried < 2000; a++) for (int b = 1; a + b < 24 && tried < 2000; b++) {
                double[] w = {a / 24.0, b / 24.0, 1 - (a + b) / 24.0};
                if (!t.opaqueAt(w)) continue;
                Vec3 pt = new Vec3(w[0] * t.p()[0] + w[1] * t.p()[3] + w[2] * t.p()[6], w[0] * t.p()[1] + w[1] * t.p()[4] + w[2] * t.p()[7],
                        w[0] * t.p()[2] + w[1] * t.p()[5] + w[2] * t.p()[8]);
                Vec3 nv = new Vec3(n.x, n.y, n.z);
                Vec3 from = pt.add(nv.scale(4)), to = pt.subtract(nv.scale(.5));
                // only where nothing of the machine stands in front of the window
                BlockHitResult hit = PassableClip.clip(level, from, to);
                tried++;
                if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(pt) > 2e-3) {
                    if (blocked++ < 5) GunFeel.LOGGER.info("[machineglass] {}: window at {} n={} hidden: met {} at {} ({})", name, pt, nv,
                            hit.getType(), hit.getLocation(), hit.getClass().getSimpleName());
                    continue;
                }
                if (!(hit instanceof ModelSurface.GlassHit)) continue;
                glass++;
                BlockHitResult out = PassableClip.exit(level, hit, nv.scale(-1));
                float left = out == null ? -1F : PassableClip.pierced(level, hit, out, 30F, null);
                if (!(left >= 1F && left < 30F)) {
                    ClientSmoke.check(false, name + ": окно не пробивается как стекло: осталось " + left + " из 30 у " + pt);
                    return;
                }
                MachineGlass.setting = () -> false;
                try {
                    BlockHitResult before = PassableClip.clip(level, from, to);
                    if (before.getType() == HitResult.Type.MISS || before.getLocation().distanceTo(pt) > 1e-2) wasAir++;
                } finally {
                    MachineGlass.setting = () -> ReaimTimes.flag(MachineGlass.KEY, true);
                }
                if (shotFrom == null) {
                    shotFrom = from;
                    shotAt = pt;
                }
            }
        }
        ClientSmoke.check(glass > 0, name + ": луч в прозрачный пиксель встречает окно (стекло): " + glass + " из " + tried + " проб");
        ClientSmoke.check(glass > 0 && wasAir == glass, name + ": с bulletMachineGlass=false тот же луч пролетает: " + wasAir + " из " + glass);
        if (shotFrom != null) bullet(name, shotFrom, shotAt);
    }

    // a real NTM bullet on the server, at the window: it loses damage in the glass
    private static void bullet(String name, Vec3 from, Vec3 at) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        float[] got = {Float.NaN};
        boolean[] alive = {false};
        Vec3 dir = at.subtract(from).normalize();
        var done = server.submit(() -> {
            BulletConfig round = BulletCheck.pick();
            EntityBulletBaseMK4 b = new EntityBulletBaseMK4(server.overworld(), round, 1F, 0F, 0F, 0F);
            Vec3 start = at.subtract(dir.scale(1.5));
            b.setPos(start.x, start.y, start.z);
            b.setThrowableHeading(dir.x, dir.y, dir.z, round.velocity, 0F);
            b.damage = 30F;
            for (int i = 0; i < 3 && !b.isRemoved(); i++) b.tick();
            got[0] = b.damage;
            alive[0] = !b.isRemoved();
        });
        try {
            done.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            ClientSmoke.check(false, name + ": пуля на сервере: " + e);
            return;
        }
        ClientSmoke.check(got[0] < 30F, name + ": пуля NTM на сервере теряет урон в окне: 30 -> " + got[0] + (alive[0] ? ", летит дальше" : ", остановлена корпусом за окном"));
    }
}
