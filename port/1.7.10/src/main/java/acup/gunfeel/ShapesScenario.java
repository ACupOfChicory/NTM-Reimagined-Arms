package acup.gunfeel;

import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

// A dev scenario that makes ModelCells' table (gradle runClient -Pscenario=shapes): in ShootScenario's flat world it
// sets each listed block in each meta (and, for blocks joining their neighbours, each of the 16 joins, made by the
// same block beside it) up in the air, then draws each one with RenderBlocks into the Tessellator (the way the game
// draws it in a chunk, Forge's render handlers too) and keeps the triangles. A triangle fills the cells where its
// pixels show (alpha over 0.1, as 1.7.10's alpha test) in the block atlas, read back from the graphics card. The table
// goes to run/client/ntmgunfeel-bullet-shapes-1710.pack (ShapePack), to be copied to src/main/resources; the game quits.
// 26.2 makes the same on each client from its block models (MeshShapes.cells).
final class ShapesScenario {
    private ShapesScenario() {
    }

    // blocks met by their model: inside their hitbox (scaffold, poles, a ladder) or with see-through pixels (bars,
    // grates, NTM's chain-link fence, trapdoors, doors with windows), as 26.2's "generate" list and masks. Not glass panes: their collision
    // boxes are their model (a lone pane a cross), and their clear pixels are still glass
    static final String[] BLOCKS = {"minecraft:iron_bars", "minecraft:ladder", "minecraft:trapdoor", "hbm:tile.steel_scaffold",
            "hbm:tile.steel_poles", "hbm:tile.steel_grate", "hbm:tile.steel_grate_wide", "hbm:tile.fence_metal",
            "minecraft:wooden_door", "minecraft:iron_door", "hbm:tile.door_metal", "hbm:tile.door_office", "hbm:tile.door_bunker", "hbm:tile.door_red",
            "hbm:tile.trapdoor_steel", "hbm:tile.wood_barrier", "hbm:tile.wood_structure"};

    private static final int BUILD = 5, CAPTURE = 80, MACHINES = 100, SPACING = 3, UP = 12, ROW = 24;

    private static final class Spot {
        final Block block;
        final int meta, x, y, z;
        final String joins; // null: the block does not join its neighbours

        Spot(Block block, int meta, int x, int y, int z, String joins) {
            this.block = block;
            this.meta = meta;
            this.x = x;
            this.y = y;
            this.z = z;
            this.joins = joins;
        }
    }

    private static final List<Spot> SPOTS = new ArrayList<Spot>();

    static void step(Minecraft mc, int t) {
        if (t == BUILD) {
            final int px = (int) Math.floor(mc.thePlayer.posX), py = (int) Math.floor(mc.thePlayer.boundingBox.minY) + UP,
                    pz = (int) Math.floor(mc.thePlayer.posZ);
            ShootScenario.onServer(() -> build(px, py, pz));
        } else if (t == CAPTURE) {
            capture(mc);
        } else if (t >= MACHINES) {
            if (!machine(mc, t - MACHINES)) {
                finish(mc);
                mc.shutdown();
            }
        }
    }

    // ---- NTM's machines (MachineCells): one by one at one spot, in each of the four facings, drawn and taken away

    private static final List<Block> MACHINE_BLOCKS = new ArrayList<Block>();
    private static int[] machineSpot;
    private static volatile int[] machineCore;
    private static volatile boolean machinePlaced;
    // the facing being made (machine * 4 + facing), its step (0 set it up, 1 the server at it, 2 the client waits for
    // it) and the tick that step began
    private static int machineNext, machineState, machineSince;
    private static int machinesMade, machinesNone;
    private static final Map<Integer, int[][]> MACHINE_TEXTURES = new HashMap<Integer, int[][]>();

    // a tick of the machine phase (t from its start); false: done
    private static boolean machine(Minecraft mc, int t) {
        if (t == 0) {
            // -Dntmgunfeel.machines=hbm:tile.machine_chemical_plant,...: only these (dev)
            String only = System.getProperty("ntmgunfeel.machines", "");
            java.util.List<String> want = only.isEmpty() ? null : java.util.Arrays.asList(only.split(","));
            for (Object o : Block.blockRegistry)
                if (o instanceof Block && MachineCells.machine((Block) o) && (want == null || want.contains(Block.blockRegistry.getNameForObject(o))))
                    MACHINE_BLOCKS.add((Block) o);
            machineSpot = new int[] {(int) Math.floor(mc.thePlayer.posX), (int) Math.floor(mc.thePlayer.boundingBox.minY) + UP + 20, (int) Math.floor(mc.thePlayer.posZ) + 40};
            GunFeel.LOGGER.info("Scenario shapes: {} machines to draw", MACHINE_BLOCKS.size());
        }
        int n = machineNext;
        if (n >= MACHINE_BLOCKS.size() * 4) {
            final int[] last = machineSpot;
            ShootScenario.onServer(() -> clearMachine(last));
            GunFeel.LOGGER.info("Scenario shapes: {} machine facings made, {} drew nothing", machinesMade, machinesNone);
            return false;
        }
        final Block b = MACHINE_BLOCKS.get(n / 4);
        final int facing = n % 4;
        final int[] spot = machineSpot;
        String id = Block.blockRegistry.getNameForObject(b);
        if (machineState == 0) {
            // the last one taken away and this one set in one go on the server (it keeps one task at a time)
            machineCore = null;
            machinePlaced = false;
            machineSince = t;
            machineState = 1;
            ShootScenario.onServer(() -> {
                clearMachine(spot);
                machineCore = placeMachine(b, facing, spot);
                machinePlaced = true;
            });
            return true;
        }
        if (machineState == 1) {
            // a big machine takes the server a while: wait for it
            if (machinePlaced) {
                machineSince = t;
                machineState = 2;
            } else if (t - machineSince > 400) {
                GunFeel.LOGGER.warn("Scenario shapes: machine {} facing {}: the server did not set it up in time", id, facing);
                machineNext++;
                machineState = 0;
            }
            return true;
        }
        // the client has the machine and its tile entity (a few ticks after the server), or gives up after 2 s
        int[] c = machineCore;
        boolean there = c != null && mc.theWorld.getBlock(c[0], c[1], c[2]) == b && mc.theWorld.getTileEntity(c[0], c[1], c[2]) != null;
        if (c != null && !there && t - machineSince < 40 || t - machineSince < 4) return true;
        machineNext++;
        machineState = 0;
        if (c == null) {
            GunFeel.LOGGER.info("Scenario shapes: machine {} facing {}: not set up", id, facing);
            return true;
        }
        Map<String, long[]> cells = null;
        MachineCapture.last = null;
        try {
            if (machineAtlas == null) machineAtlas = Atlas.read(mc);
            cells = MachineCapture.capture(mc, c[0], c[1], c[2], MACHINE_TEXTURES, machineAtlas);
        } catch (Throwable e) {
            GunFeel.LOGGER.warn("Scenario shapes: machine {} could not be drawn: {}", id, e.toString());
        }
        if (cells == null) {
            machinesNone++;
            GunFeel.LOGGER.info("Scenario shapes: machine {} facing {}: nothing drawn, keeps its box", id, facing);
        } else {
            MADE.putAll(cells);
            if (MachineCapture.last != null) MACHINE_FACES.put(id + "/" + mc.theWorld.getBlockMetadata(c[0], c[1], c[2]), MachineCapture.last);
            machinesMade++;
            int glass = 0;
            for (String k : cells.keySet()) if (k.endsWith("/glass")) glass++;
            GunFeel.LOGGER.info("Scenario shapes: machine {} meta {} ({}): {} blocks, {} with glass", id, mc.theWorld.getBlockMetadata(c[0], c[1], c[2]),
                    MachineCapture.how, cells.size() - glass, glass);
        }
        return true;
    }

    // the integrated server: the machine placed at the spot as a player facing that way would (BlockDummyable builds
    // it); its core, or null
    static int[] placeMachine(Block b, int facing, int[] spot) {
        EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        World w = player.worldObj;
        float yaw = player.rotationYaw;
        player.rotationYaw = facing * 90F;
        // the machine in hand, as a player places it (NTM's soyuz_launcher takes one off the held stack)
        int slot = player.inventory.currentItem;
        net.minecraft.item.ItemStack held = player.inventory.mainInventory[slot];
        player.inventory.mainInventory[slot] = new net.minecraft.item.ItemStack(b, 64);
        try {
            w.setBlock(spot[0], spot[1], spot[2], b, 0, 3);
            b.onBlockPlacedBy(w, spot[0], spot[1], spot[2], player, new net.minecraft.item.ItemStack(b));
        } catch (RuntimeException e) {
            GunFeel.LOGGER.info("Scenario shapes: machine {} could not be placed: {}", Block.blockRegistry.getNameForObject(b), e.toString());
            return null;
        } finally {
            player.rotationYaw = yaw;
            player.inventory.mainInventory[slot] = held;
        }
        int r = MachineCapture.REACH;
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            if (w.getBlock(spot[0] + dx, spot[1] + dy, spot[2] + dz) != b) continue;
            int[] c = ((com.hbm.blocks.BlockDummyable) b).findCore(w, spot[0] + dx, spot[1] + dy, spot[2] + dz);
            if (c != null && w.getBlock(c[0], c[1], c[2]) == b) return c;
        }
        // what stood in its way
        StringBuilder in = new StringBuilder();
        for (int dx = -r; dx <= r && in.length() < 300; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++)
            if (!w.isAirBlock(spot[0] + dx, spot[1] + dy, spot[2] + dz)) in.append(' ').append(dx).append(',').append(dy).append(',').append(dz).append('=')
                    .append(Block.blockRegistry.getNameForObject(w.getBlock(spot[0] + dx, spot[1] + dy, spot[2] + dz)));
        GunFeel.LOGGER.info("Scenario shapes: machine {} facing {}: blocks around the spot:{}", Block.blockRegistry.getNameForObject(b), facing,
                in.length() == 0 ? " none" : in);
        return null;
    }

    // the integrated server: every block around the spot gone, and what fell out of it
    static void clearMachine(int[] spot) {
        World w = MinecraftServer.getServer().worldServerForDimension(0);
        int r = MachineCapture.REACH + 2;
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++)
            if (!w.isAirBlock(spot[0] + dx, spot[1] + dy, spot[2] + dz)) w.setBlock(spot[0] + dx, spot[1] + dy, spot[2] + dz, Blocks.air, 0, 2);
        for (Object o : w.getEntitiesWithinAABB(net.minecraft.entity.Entity.class, net.minecraft.util.AxisAlignedBB.getBoundingBox(spot[0] - r, spot[1] - r - 40,
                spot[2] - r, spot[0] + r, spot[1] + r, spot[2] + r)))
            if (!(o instanceof EntityPlayerMP)) ((net.minecraft.entity.Entity) o).setDead();
    }

    // the integrated server: every spot and its joined neighbours on a square around the player, SPACING apart (a
    // neighbour of one spot never touches another spot)
    private static void build(int px, int py, int pz) {
        EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        World w = player.worldObj;
        int i = 0;
        for (String id : BLOCKS) {
            Block b = Block.getBlockFromName(id);
            if (b == null || b == Blocks.air) {
                GunFeel.LOGGER.warn("Scenario shapes: no block {}", id);
                continue;
            }
            boolean joining = b instanceof net.minecraft.block.BlockPane || b instanceof net.minecraft.block.BlockFence;
            if (ModelCells.door(b)) {
                // the lower half's facing and open (0-7) with the upper's hinge (8 | 0, 1): both halves taken
                for (int meta = 0; meta < 16; meta++) {
                    int x = px + (i % ROW - ROW / 2) * SPACING, z = pz + (i / ROW - ROW / 2) * SPACING;
                    i++;
                    w.setBlock(x, py, z, b, meta & 7, 2);
                    w.setBlock(x, py + 1, z, b, 8 | meta >> 3, 2);
                    SPOTS.add(new Spot(b, meta & 7, x, py, z, null));
                    SPOTS.add(new Spot(b, 8 | meta >> 3, x, py + 1, z, null));
                }
                continue;
            }
            for (int meta = 0; meta < 16; meta++) {
                for (int j = 0; j < (joining ? 16 : 1); j++) {
                    int x = px + (i % ROW - ROW / 2) * SPACING, z = pz + (i / ROW - ROW / 2) * SPACING;
                    i++;
                    // the joins as ModelCells keys them: north, east, south, west
                    boolean[] side = {(j & 8) != 0, (j & 4) != 0, (j & 2) != 0, (j & 1) != 0};
                    w.setBlock(x, py, z, b, meta, 2);
                    if (w.getBlock(x, py, z) != b || w.getBlockMetadata(x, py, z) != meta) continue;
                    if (joining) {
                        if (side[0]) w.setBlock(x, py, z - 1, b, meta, 2);
                        if (side[1]) w.setBlock(x + 1, py, z, b, meta, 2);
                        if (side[2]) w.setBlock(x, py, z + 1, b, meta, 2);
                        if (side[3]) w.setBlock(x - 1, py, z, b, meta, 2);
                    }
                    SPOTS.add(new Spot(b, meta, x, py, z, joining ? (side[0] ? "1" : "0") + (side[1] ? "1" : "0") + (side[2] ? "1" : "0") + (side[3] ? "1" : "0") : null));
                }
            }
        }
        GunFeel.LOGGER.info("Scenario shapes: {} spots set", SPOTS.size());
        buildDoors(player, px, py, pz);
    }

    // ---- NTM's big doors (DoorCells): each set up once in the air, its core found, drawn shut and open

    private static final List<int[]> DOORS = new ArrayList<int[]>();
    static final int DOOR_SPACING = 14;

    private static void buildDoors(EntityPlayerMP player, int px, int py, int pz) {
        World w = player.worldObj;
        float yaw = player.rotationYaw;
        player.rotationYaw = 0F; // facing south: BlockDummyable turns the door to it
        int i = 0;
        try {
            for (Object o : Block.blockRegistry) {
                if (!(o instanceof com.hbm.blocks.generic.BlockDoorGeneric)) continue;
                Block b = (Block) o;
                int x = px + (i % 6 - 3) * DOOR_SPACING, z = pz - 60 - (i / 6) * DOOR_SPACING;
                i++;
                w.setBlock(x, py, z, b, 0, 3);
                b.onBlockPlacedBy(w, x, py, z, player, new net.minecraft.item.ItemStack(b));
                int[] core = null;
                for (int dx = -8; dx <= 8 && core == null; dx++) for (int dy = -8; dy <= 8 && core == null; dy++)
                    for (int dz = -8; dz <= 8 && core == null; dz++)
                        if (w.getTileEntity(x + dx, py + dy, z + dz) instanceof com.hbm.tileentity.TileEntityDoorGeneric) core = new int[] {x + dx, py + dy, z + dz};
                if (core == null) {
                    GunFeel.LOGGER.warn("Scenario shapes: door {} was not set up", Block.blockRegistry.getNameForObject(b));
                    continue;
                }
                DOORS.add(core);
            }
        } finally {
            player.rotationYaw = yaw;
        }
        GunFeel.LOGGER.info("Scenario shapes: {} big doors set", DOORS.size());
    }

    private static void captureDoors(Minecraft mc, Map<String, long[]> made) {
        Map<Integer, int[][]> textures = new HashMap<Integer, int[][]>();
        int doors = 0;
        for (int[] c : DOORS) {
            Block b = mc.theWorld.getBlock(c[0], c[1], c[2]);
            String id = Block.blockRegistry.getNameForObject(b);
            for (boolean open : new boolean[] {false, true}) {
                Map<String, long[]> cells;
                try {
                    cells = DoorCapture.capture(mc, c[0], c[1], c[2], open, textures);
                } catch (Throwable e) {
                    GunFeel.LOGGER.warn("Scenario shapes: door {} could not be drawn: {}", id, e.toString());
                    continue;
                }
                if (cells == null) {
                    GunFeel.LOGGER.info("Scenario shapes: door {} {}: none of NTM's models drawn, keeps its boxes", id, open ? "open" : "shut");
                    continue;
                }
                made.putAll(cells);
                if (DoorCapture.lastDoor != null) MACHINE_FACES.put(MachineFaces.doorKey(id, open), DoorCapture.lastDoor);
                doors++;
                GunFeel.LOGGER.info("Scenario shapes: door {} {}: {} blocks, {}", id, open ? "open" : "shut", cells.size(), gaps(mc.theWorld, b, c, open, cells));
            }
        }
        GunFeel.LOGGER.info("Scenario shapes: {} door states made", doors);
    }

    // where a bullet now flies through the door: along each axis, the lines of cells through its collision boxes that
    // meet none of the model's cells (in the door's blocks, across them)
    private static String gaps(World w, Block b, int[] c, boolean open, Map<String, long[]> cells) {
        com.hbm.tileentity.TileEntityDoorGeneric te = (com.hbm.tileentity.TileEntityDoorGeneric) w.getTileEntity(c[0], c[1], c[2]);
        byte state = te.state;
        te.state = open ? com.hbm.tileentity.TileEntityDoorGeneric.STATE_OPEN : com.hbm.tileentity.TileEntityDoorGeneric.STATE_CLOSED;
        StringBuilder out = new StringBuilder();
        try {
            // the door's cells and box cells in one grid over its blocks
            List<int[]> blocks = DoorCapture.footprint(w, b, c[0], c[1], c[2]);
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int[] o : blocks) for (int a = 0; a < 3; a++) {
                lo[a] = Math.min(lo[a], o[a]);
                hi[a] = Math.max(hi[a], o[a]);
            }
            int sx = (hi[0] - lo[0] + 1) * 16, sy = (hi[1] - lo[1] + 1) * 16, sz = (hi[2] - lo[2] + 1) * 16;
            boolean[] model = new boolean[sx * sy * sz], box = new boolean[sx * sy * sz];
            double[] boxes = new double[6 * 16];
            for (int[] o : blocks) {
                long[] bits = DoorCells.of(cells, w, b, o[0], o[1], o[2]);
                int n = PassableClip.collision(w, b, o[0], o[1], o[2], boxes);
                for (int k = 0; k < 4096; k++) {
                    int gx = (o[0] - lo[0]) * 16 + (k & 15), gy = (o[1] - lo[1]) * 16 + (k >> 4 & 15), gz = (o[2] - lo[2]) * 16 + (k >> 8);
                    int g = gx + sx * (gy + sy * gz);
                    if (bits != null && (bits[k >> 6] >>> (k & 63) & 1L) != 0) model[g] = true;
                    double cx = o[0] + ((k & 15) + .5) / 16, cy = o[1] + ((k >> 4 & 15) + .5) / 16, cz = o[2] + ((k >> 8) + .5) / 16;
                    for (int i = 0; i < n && !box[g]; i++)
                        box[g] = cx >= boxes[i * 6] && cx <= boxes[i * 6 + 3] && cy >= boxes[i * 6 + 1] && cy <= boxes[i * 6 + 4] && cz >= boxes[i * 6 + 2]
                                && cz <= boxes[i * 6 + 5];
                }
            }
            int[] size = {sx, sy, sz};
            for (int ax = 0; ax < 3; ax++) {
                int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3, lines = 0, clear = 0;
                for (int i = 0; i < size[a1]; i++) for (int j = 0; j < size[a2]; j++) {
                    boolean inBox = false, hit = false;
                    for (int t = 0; t < size[ax]; t++) {
                        int[] p = new int[3];
                        p[ax] = t;
                        p[a1] = i;
                        p[a2] = j;
                        int g = p[0] + sx * (p[1] + sy * p[2]);
                        inBox |= box[g];
                        hit |= model[g];
                    }
                    if (!inBox) continue;
                    lines++;
                    if (!hit) clear++;
                }
                out.append("xyz".charAt(ax)).append(": ").append(clear).append('/').append(lines).append(" lines clear  ");
            }
        } finally {
            te.state = state;
        }
        return out.toString();
    }

    private static void capture(Minecraft mc) {
        Atlas atlas = Atlas.read(mc);
        RenderBlocks rb = new RenderBlocks(mc.theWorld);
        Map<String, long[]> made = new TreeMap<String, long[]>();
        Set<String> wrong = new HashSet<String>();
        for (Spot s : SPOTS) {
            String id = Block.blockRegistry.getNameForObject(s.block);
            Block there = mc.theWorld.getBlock(s.x, s.y, s.z);
            if (there != s.block || mc.theWorld.getBlockMetadata(s.x, s.y, s.z) != s.meta) {
                wrong.add(id + "#" + s.meta);
                continue;
            }
            // the joins the block itself sees: a meta that does not join (a fence's other kind) is keyed as it is drawn
            String joins = ModelCells.joins(mc.theWorld, s.block, s.x, s.y, s.z);
            float[] v;
            try {
                v = draw(rb, s.block, s.x, s.y, s.z);
            } catch (Throwable e) {
                GunFeel.LOGGER.warn("Scenario shapes: {}#{} could not be drawn: {}", id, s.meta, e.toString());
                continue;
            }
            List<ModelSurface.Face> faces = new ArrayList<ModelSurface.Face>();
            long[] bits = cells(v, atlas, faces);
            if (ModelCells.empty(bits)) continue;
            double[] boxes = new double[6 * 16];
            int nb = PassableClip.collision(mc.theWorld, s.block, s.x, s.y, s.z, boxes);
            for (int k = 0; k < nb; k++) for (int a = 0; a < 6; a++) boxes[k * 6 + a] -= a % 3 == 0 ? s.x : a % 3 == 1 ? s.y : s.z;
            if (shell(v, boxes, nb)) bits[64] = 1L;
            // exact faces as 26.2's (MeshShapes.cells): not on a shell, decided where the bullet comes into its box
            else bits = ModelSurface.attach(bits, faces);
            made.put(ModelCells.key(id, ModelCells.fullMeta(mc.theWorld, s.block, s.meta, s.x, s.y, s.z), joins), bits);
        }
        if (!wrong.isEmpty()) GunFeel.LOGGER.warn("Scenario shapes: not in the client's world (an unused meta?): {}", wrong);
        captureDoors(mc, made);
        MADE.putAll(made);
    }

    // the table made: blocks, doors, then machines
    private static final Map<String, long[]> MADE = new TreeMap<String, long[]>();
    private static Atlas machineAtlas;
    // the machines' triangles by "<id>/<core meta>" (MachineFaces)
    private static final Map<String, MachineFaces.Model> MACHINE_FACES = new TreeMap<String, MachineFaces.Model>();

    private static void finish(Minecraft mc) {
        Map<String, long[]> made = MADE;
        // -Dntmgunfeel.machines: only those machines drawn again, the others kept from the jar's tables
        String only = System.getProperty("ntmgunfeel.machines", "");
        if (!only.isEmpty()) {
            java.util.Set<String> again = new java.util.HashSet<String>(java.util.Arrays.asList(only.split(",")));
            Map<String, long[]> merged = new TreeMap<String, long[]>();
            for (Map.Entry<String, long[]> e : ModelCells.jarTable().entrySet()) {
                int m = e.getKey().indexOf("#machine/");
                if (m > 0 && !again.contains(e.getKey().substring(0, m))) merged.put(e.getKey(), e.getValue());
            }
            merged.putAll(made);
            made = merged;
            List<MachineFaces.Tex> tex = new ArrayList<MachineFaces.Tex>();
            Map<String, MachineFaces.Model> models = new TreeMap<String, MachineFaces.Model>();
            MachineFaces.readJar(tex, models);
            int shift = tex.size();
            for (String k : new ArrayList<String>(models.keySet())) if (again.contains(k.substring(0, k.lastIndexOf('/')))) models.remove(k);
            tex.addAll(MachineCapture.TEXTURES);
            for (Map.Entry<String, MachineFaces.Model> e : MACHINE_FACES.entrySet()) models.put(e.getKey(), e.getValue().shifted(shift));
            MachineCapture.TEXTURES.clear();
            MachineCapture.TEXTURES.addAll(tex);
            MACHINE_FACES.clear();
            MACHINE_FACES.putAll(models);
            GunFeel.LOGGER.info("Scenario shapes: {} machines drawn again, the rest kept from the jar", again.size());
        }
        ModelCells.put(made);
        File out = new File(mc.mcDataDir, ModelCells.FILE);
        try {
            write(out, made);
            GunFeel.LOGGER.info("Scenario shapes: {} shapes written to {}", made.size(), out);
        } catch (IOException e) {
            GunFeel.LOGGER.warn("Scenario shapes: could not write " + out, e);
        }
        if (MACHINE_FACES.isEmpty()) return;
        MachineFaces.put(MachineCapture.TEXTURES, MACHINE_FACES);
        File faces = new File(mc.mcDataDir, MachineFaces.FILE);
        try (java.io.OutputStream o = new java.io.FileOutputStream(faces)) {
            MachineFaces.write(o, MachineCapture.TEXTURES, MACHINE_FACES);
            GunFeel.LOGGER.info("Scenario shapes: {} machine facings' faces ({} textures) written to {}, {} bytes", MACHINE_FACES.size(),
                    MachineCapture.TEXTURES.size(), faces, faces.length());
        } catch (IOException e) {
            GunFeel.LOGGER.warn("Scenario shapes: could not write " + faces, e);
        }
    }

    // the block drawn as in a chunk: its quads, 5 floats a corner (x, y, z in the block, u, v in the atlas)
    static float[] draw(RenderBlocks rb, Block b, int x, int y, int z) {
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setTranslation(-x, -y, -z);
        float[] out;
        try {
            rb.renderBlockByRenderType(b, x, y, z);
            int[] raw = ReflectionHelper.getPrivateValue(Tessellator.class, t, "rawBuffer", "field_78405_h");
            int n = ReflectionHelper.<Integer, Tessellator> getPrivateValue(Tessellator.class, t, "vertexCount", "field_78406_i");
            out = new float[n / 4 * 4 * 5];
            for (int i = 0; i < out.length / 5; i++) for (int k = 0; k < 5; k++) out[i * 5 + k] = Float.intBitsToFloat(raw[i * 8 + k]);
        } finally {
            t.setTranslation(0, 0, 0);
            // the drawn quads go nowhere
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(false);
            t.draw();
            GL11.glDepthMask(true);
            GL11.glColorMask(true, true, true, true);
        }
        return out;
    }

    // 26.2's MeshShapes.cells for the drawn quads: each quad two triangles, a face of a double-sided plane not pushed
    // behind
    static long[] cells(float[] v, Atlas atlas) {
        return cells(v, atlas, null);
    }

    // faces (not null): each triangle's exact face too (ModelSurface: its pixels on the atlas' grid)
    static long[] cells(float[] v, Atlas atlas, List<ModelSurface.Face> faces) {
        long[] bits = new long[65];
        int quads = v.length / 20;
        Set<String> planes = new HashSet<String>();
        for (int q = 0; q < quads; q++) {
            String k = ModelCells.planeKey(tri(v, q, 0, 1, 2), false);
            if (k != null) planes.add(k);
        }
        for (int q = 0; q < quads; q++) {
            String twin = ModelCells.planeKey(tri(v, q, 0, 1, 2), true);
            boolean behind = twin == null || !planes.contains(twin);
            for (int t = 0; t < 2; t++) {
                final int a = 0, b = t == 0 ? 1 : 2, c = t == 0 ? 2 : 3;
                final float[] u = {v[(q * 4 + a) * 5 + 3], v[(q * 4 + b) * 5 + 3], v[(q * 4 + c) * 5 + 3]};
                final float[] w = {v[(q * 4 + a) * 5 + 4], v[(q * 4 + b) * 5 + 4], v[(q * 4 + c) * 5 + 4]};
                ModelCells.fill(bits, tri(v, q, a, b, c), behind,
                        (wa, wb, wc) -> atlas.shown(wa * u[0] + wb * u[1] + wc * u[2], wa * w[0] + wb * w[1] + wc * w[2]));
                if (faces != null) {
                    ModelSurface.Face f = MachineFaces.face(tri(v, q, a, b, c), atlas.tex(), new double[] {u[0], u[1], u[2]}, new double[] {w[0], w[1], w[2]}, false);
                    if (f != null) faces.add(f);
                }
            }
        }
        return bits;
    }

    // 26.2's MeshShapes.shell: the model's quads (shown pixels or not) cover every face of the block's collision boxes
    // (boxes: n of them, {min x, y, z, max x, y, z} in the block): a closed box such as a door or a trapdoor, entered only
    // through its outer faces. Not: bars, a ladder, scaffold poles, whose model lies inside the hitbox
    static boolean shell(float[] v, double[] boxes, int n) {
        if (n == 0) return false;
        int points = 0, covered = 0;
        for (int b = 0; b < n; b++) {
            double[] lo = {boxes[b * 6], boxes[b * 6 + 1], boxes[b * 6 + 2]}, hi = {boxes[b * 6 + 3], boxes[b * 6 + 4], boxes[b * 6 + 5]};
            for (int ax = 0; ax < 3; ax++) {
                int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
                for (double plane : new double[] {lo[ax], hi[ax]}) {
                    int facePoints = 0, faceCovered = 0;
                    for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
                        double[] p = new double[3];
                        p[ax] = plane;
                        p[a1] = lo[a1] + (hi[a1] - lo[a1]) * (i + 0.5) / 4;
                        p[a2] = lo[a2] + (hi[a2] - lo[a2]) * (j + 0.5) / 4;
                        // a face shared with another of the boxes is inside the hitbox
                        if (inOther(boxes, n, b, p)) continue;
                        facePoints++;
                        if (onAnyQuad(v, p, ax)) faceCovered++;
                    }
                    // a bare face on the block's edge is a seam to the other half (door halves have no face where they meet)
                    boolean edge = Math.abs(plane) < 1e-4 || Math.abs(plane - 1) < 1e-4;
                    if (edge && faceCovered == 0) continue;
                    points += facePoints;
                    covered += faceCovered;
                }
            }
        }
        return points > 0 && covered >= points * 0.95;
    }

    private static boolean inOther(double[] boxes, int n, int self, double[] p) {
        for (int o = 0; o < n; o++) {
            if (o == self) continue;
            boolean in = true;
            for (int i = 0; i < 3; i++) in &= p[i] >= boxes[o * 6 + i] - 1e-4 && p[i] <= boxes[o * 6 + 3 + i] + 1e-4;
            if (in) return true;
        }
        return false;
    }

    // an axis-aligned quad of the model lies in that plane over the point
    private static boolean onAnyQuad(float[] v, double[] p, int ax) {
        for (int q = 0; q < v.length / 20; q++) {
            double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int k = 0; k < 4; k++) for (int i = 0; i < 3; i++) {
                double c = v[(q * 4 + k) * 5 + i];
                mn[i] = Math.min(mn[i], c);
                mx[i] = Math.max(mx[i], c);
            }
            if (mx[ax] - mn[ax] > 1e-4 || Math.abs(mn[ax] - p[ax]) > 1e-3) continue;
            boolean in = true;
            for (int i = 0; i < 3; i++) if (i != ax) in &= p[i] >= mn[i] - 1e-4 && p[i] <= mx[i] + 1e-4;
            if (in) return true;
        }
        return false;
    }

    private static double[] tri(float[] v, int q, int a, int b, int c) {
        int[] k = {a, b, c};
        double[] p = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) p[i * 3 + j] = v[(q * 4 + k[i]) * 5 + j];
        return p;
    }

    // the block atlas's pixels as the graphics card has them (mip level 0)
    static final class Atlas {
        final int w, h;
        final int[] argb;

        private Atlas(int w, int h, int[] argb) {
            this.w = w;
            this.h = h;
            this.argb = argb;
        }

        static Atlas read(Minecraft mc) {
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            IntBuffer buf = BufferUtils.createIntBuffer(w * h);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
            int[] px = new int[w * h];
            buf.get(px);
            GunFeel.LOGGER.info("Scenario shapes: block atlas {} x {}", w, h);
            return new Atlas(w, h, px);
        }

        private MachineFaces.Tex tex;

        // the atlas as MachineFaces' texture (its shown pixels)
        MachineFaces.Tex tex() {
            if (tex == null) tex = MachineFaces.Tex.of(null, new int[][] {{w, h}, argb});
            return tex;
        }

        // the pixel at (u, v) passes 1.7.10's alpha test (alpha over 0.1)
        boolean shown(double u, double v) {
            int x = (int) Math.floor(u * w), y = (int) Math.floor(v * h);
            x = Math.max(0, Math.min(w - 1, x));
            y = Math.max(0, Math.min(h - 1, y));
            return (argb[x + w * y] >>> 24) > 25;
        }
    }

    private static void write(File out, Map<String, long[]> made) throws IOException {
        java.nio.file.Files.write(out.toPath(), ShapePack.wrap(ShapePack.shapes(made), PortPaths.hbmVersion(), ShapePack.CODEC));
    }
}
