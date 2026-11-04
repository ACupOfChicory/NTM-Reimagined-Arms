package acup.gunfeel;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.play.server.SPacketSoundEffect;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

// port: new for 1.12.2. The 1.7.10 code reads the world by block, metadata and x, y, z; Minecraft 1.12.2 keeps a block
// state at a BlockPos. These turn one into the other, so the 1.7.10 code reads as it did (its calls are rewritten to
// these: world.getBlock(x, y, z) -> Mc.block(world, x, y, z)). Also sounds and particles by name, as 1.7.10 has them.
public final class Mc {
    private Mc() {}

    // the side a ray hit a block on, as 1.7.10 numbers it (0 down ... 5 east, the same as EnumFacing's index), -1 none
    public static int side(net.minecraft.util.math.RayTraceResult r) {
        return r == null || r.sideHit == null ? -1 : r.sideHit.getIndex();
    }

    // a ray's hit on a block's side (1.7.10: new MovingObjectPosition(x, y, z, side, at)); side -1: none (a hit inside)
    public static net.minecraft.util.math.RayTraceResult blockHit(int x, int y, int z, int side, net.minecraft.util.math.Vec3d at) {
        net.minecraft.util.EnumFacing f = side < 0 || side > 5 ? net.minecraft.util.EnumFacing.UP : net.minecraft.util.EnumFacing.byIndex(side);
        return new net.minecraft.util.math.RayTraceResult(at, f, new BlockPos(x, y, z));
    }

    public static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    public static IBlockState state(IBlockAccess w, int x, int y, int z) {
        return w.getBlockState(new BlockPos(x, y, z));
    }

    public static Block block(IBlockAccess w, int x, int y, int z) {
        return state(w, x, y, z).getBlock();
    }

    // the block's metadata as 1.7.10 kept it (what its state saves)
    public static int meta(IBlockAccess w, int x, int y, int z) {
        IBlockState s = state(w, x, y, z);
        return s.getBlock().getMetaFromState(s);
    }

    public static TileEntity te(IBlockAccess w, int x, int y, int z) {
        return w.getTileEntity(new BlockPos(x, y, z));
    }

    public static boolean air(IBlockAccess w, int x, int y, int z) {
        return w.isAirBlock(new BlockPos(x, y, z));
    }

    // the state a meta stands for; port: a block whose properties have fewer values than 16 metas (NTM CE's grates:
    // height 0-9) throws on the others: its default state then, as the world reads such a meta
    @SuppressWarnings("deprecation")
    public static IBlockState state(Block b, int meta) {
        try {
            return b.getStateFromMeta(meta);
        } catch (RuntimeException e) {
            return b.getDefaultState();
        }
    }

    // the metas a block's states save as, in order (1.12.2: its valid states; 1.7.10 scenarios went through 0-15)
    public static int[] metas(Block b) {
        java.util.TreeSet<Integer> m = new java.util.TreeSet<Integer>();
        for (IBlockState st : b.getBlockState().getValidStates()) m.add(b.getMetaFromState(st));
        int[] out = new int[m.size()];
        int i = 0;
        for (int v : m) out[i++] = v;
        return out;
    }

    public static boolean setBlock(World w, int x, int y, int z, Block b) {
        return w.setBlockState(new BlockPos(x, y, z), b.getDefaultState(), 3);
    }

    public static boolean setBlock(World w, int x, int y, int z, Block b, int meta, int flags) {
        return w.setBlockState(new BlockPos(x, y, z), state(b, meta), flags);
    }

    // a block's registry name, "minecraft:stone" (1.7.10: Block.blockRegistry.getNameForObject)
    public static String name(Block b) {
        ResourceLocation r = b == null ? null : b.getRegistryName();
        return r == null ? null : r.toString();
    }

    // the same, null when there is none
    public static Block blockOrNull(String name) {
        int at = name.indexOf(":tile.");
        if (at >= 0) name = name.substring(0, at + 1) + name.substring(at + ":tile.".length());
        return Block.getBlockFromName(name);
    }

    // a block by its id; port: an NTM block by its 1.7.10 name ("hbm:tile.steel_grate") too, as 1.12.2 names it
    // without the "tile." (the scenarios' and checks' lists are 1.7.10's)
    public static Block block(String name) {
        int at = name.indexOf(":tile.");
        if (at >= 0) name = name.substring(0, at + 1) + name.substring(at + ":tile.".length());
        return Block.REGISTRY.getObject(new ResourceLocation(name));
    }

    // a sound by its id ("block.stone.hit", "minecraft:block.stone.hit", "hbm:weapon.hit"), null if none
    public static SoundEvent sound(String id) {
        if (id == null || id.isEmpty()) return null;
        ResourceLocation r = new ResourceLocation(id);
        return SoundEvent.REGISTRY.containsKey(r) ? SoundEvent.REGISTRY.getObject(r) : null;
    }

    // the sound heard by all near x, y, z (1.7.10: World.playSoundEffect)
    public static void playSound(World w, double x, double y, double z, String id, float volume, float pitch) {
        SoundEvent s = sound(id);
        if (s != null) w.playSound(null, x, y, z, s, SoundCategory.PLAYERS, volume, pitch);
    }

    // port: a sound everyone within `range` blocks hears, fading out over 26.2's distance for it (SoundFade), as 26.2's
    // playSound(null, ...) of a sound with its own attenuation_distance: each player gets it set on the line from his
    // ears (World.playSound would fade it over 16 and send it only within 16)
    public static void playSoundFaded(World w, double x, double y, double z, String id, float volume, float pitch, double range) {
        SoundEvent s = sound(id);
        if (s == null) return;
        float fade = SoundFade.of(id);
        for (net.minecraft.entity.player.EntityPlayer p : w.playerEntities) {
            if (!(p instanceof EntityPlayerMP) || ((EntityPlayerMP) p).connection == null) continue;
            // the range from the player's position, as Minecraft's own (PlayerList.sendToAllNearExcept)
            if (p.getDistanceSq(x, y, z) >= range * range) continue;
            double[] at = SoundFade.at(new double[] {p.posX, p.posY + p.getEyeHeight(), p.posZ}, new double[] {x, y, z}, fade);
            ((EntityPlayerMP) p).connection.sendPacket(new SPacketSoundEffect(s, SoundCategory.PLAYERS, at[0], at[1], at[2], volume, pitch));
        }
    }

    // the sound heard by one player only (1.7.10: S29PacketSoundEffect)
    public static void playSoundTo(EntityPlayerMP p, double x, double y, double z, String id, float volume, float pitch) {
        SoundEvent s = sound(id);
        // a FakePlayer (another mod's machine holding a gun) has no connection
        if (s != null && p.connection != null) p.connection.sendPacket(new SPacketSoundEffect(s, SoundCategory.PLAYERS, x, y, z, volume, pitch));
    }


    // A particle as 1.7.10 names it: a name ("smoke", "largesmoke", "reddust"), or "blockcrack_<id>_<meta>" /
    // "iconcrack_<id>_<meta>" with its block or item. 1.12.2 keeps the names (EnumParticleTypes) and takes the block
    // as its state id.
    public static final class Particle {
        public final EnumParticleTypes type;
        public final int[] args;

        Particle(EnumParticleTypes type, int... args) {
            this.type = type;
            this.args = args;
        }
    }

    public static Particle particle(String name) {
        if (name == null) return null;
        if (name.startsWith("blockcrack_") || name.startsWith("blockdust_")) {
            String[] p = name.split("_");
            if (p.length < 3) return null;
            try {
                Block b = Block.getBlockById(Integer.parseInt(p[1]));
                int state = Block.getStateId(state(b, Integer.parseInt(p[2])));
                return new Particle(name.startsWith("blockcrack_") ? EnumParticleTypes.BLOCK_CRACK : EnumParticleTypes.BLOCK_DUST, state);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (name.startsWith("iconcrack_")) {
            String[] p = name.split("_");
            if (p.length < 3) return null;
            try {
                return new Particle(EnumParticleTypes.ITEM_CRACK, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        EnumParticleTypes t = EnumParticleTypes.getByName(name);
        if (t == null) t = EnumParticleTypes.getByName(MODERN.getOrDefault(name, name));
        return t == null ? null : new Particle(t);
    }

    // 26.2's particle names that 1.12.2 calls otherwise
    private static final java.util.Map<String, String> MODERN = new java.util.HashMap<>();
    static {
        MODERN.put("poof", "explode");
        MODERN.put("large_smoke", "largesmoke");
        MODERN.put("dust", "reddust");
        MODERN.put("effect", "spell");
        MODERN.put("entity_effect", "mobSpell");
        MODERN.put("witch", "witchMagic");
        MODERN.put("explosion", "largeexplode");
        MODERN.put("explosion_emitter", "hugeexplosion");
        MODERN.put("item_snowball", "snowballpoof");
        MODERN.put("dripping_water", "dripWater");
        MODERN.put("dripping_lava", "dripLava");
        MODERN.put("splash", "splash");
        MODERN.put("bubble", "bubble");
        MODERN.put("damage_indicator", "damageIndicator");
        MODERN.put("sweep_attack", "sweepAttack");
        MODERN.put("totem_of_undying", "totem");
        MODERN.put("happy_villager", "happyVillager");
        MODERN.put("angry_villager", "angryVillager");
        MODERN.put("enchanted_hit", "magicCrit");
        MODERN.put("instant_effect", "instantSpell");
        MODERN.put("falling_dust", "fallingdust");
    }

    public static void spawn(World w, String name, double x, double y, double z, double vx, double vy, double vz) {
        Particle p = particle(name);
        if (p != null) w.spawnParticle(p.type, x, y, z, vx, vy, vz, p.args);
    }

    public static void spawnAll(net.minecraft.world.WorldServer w, String name, double x, double y, double z, int count, double dx, double dy,
            double dz, double speed) {
        Particle p = particle(name);
        if (p != null) w.spawnParticle(p.type, x, y, z, count, dx, dy, dz, speed, p.args);
    }

    // the block's collision box in the world, null without one (1.7.10: Block.getCollisionBoundingBoxFromPool)
    public static net.minecraft.util.math.AxisAlignedBB collisionBox(World w, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        net.minecraft.util.math.AxisAlignedBB b = w.getBlockState(p).getCollisionBoundingBox(w, p);
        return b == null || b == Block.NULL_AABB ? null : b.offset(p);
    }

    // whether the block's face (1.7.10's side numbers: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east) is drawn
    // (1.7.10: Block.shouldSideBeRendered at the neighbour)
    public static boolean sideShown(IBlockAccess w, int x, int y, int z, int face) {
        BlockPos p = new BlockPos(x, y, z);
        IBlockState s = w.getBlockState(p);
        return s.shouldSideBeRendered(w, p, net.minecraft.util.EnumFacing.byIndex(face));
    }


    // ---- a block's shape and kind, as 1.7.10 asked the block (1.12.2 asks its state at a position)

    public static net.minecraft.block.material.Material material(Block b) {
        return b.getDefaultState().getMaterial();
    }

    public static net.minecraft.block.material.Material material(IBlockState s) {
        return s.getMaterial();
    }

    // the block's collision boxes within mask added to list (1.7.10: Block.addCollisionBoxesToList)
    public static void collision(World w, int x, int y, int z, net.minecraft.util.math.AxisAlignedBB mask,
            java.util.List<net.minecraft.util.math.AxisAlignedBB> list, net.minecraft.entity.Entity e) {
        BlockPos p = new BlockPos(x, y, z);
        w.getBlockState(p).addCollisionBoxToList(w, p, mask, list, e, false);
    }

    private static final ThreadLocal<net.minecraft.util.math.AxisAlignedBB> BOUNDS = ThreadLocal.withInitial(
            () -> net.minecraft.block.Block.FULL_BLOCK_AABB);

    // the block's outline at x, y, z (0..1 in the block), read by bminX ... bmaxZ after (1.7.10:
    // setBlockBoundsBasedOnState, then getBlockBoundsMinX ...)
    public static void bounds(Block b, IBlockAccess w, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        IBlockState s = w.getBlockState(p);
        BOUNDS.set(s.getBlock() == b ? s.getActualState(w, p).getBoundingBox(w, p) : b.getDefaultState().getBoundingBox(w, p));
    }

    public static double bminX() { return BOUNDS.get().minX; }
    public static double bminY() { return BOUNDS.get().minY; }
    public static double bminZ() { return BOUNDS.get().minZ; }
    public static double bmaxX() { return BOUNDS.get().maxX; }
    public static double bmaxY() { return BOUNDS.get().maxY; }
    public static double bmaxZ() { return BOUNDS.get().maxZ; }

    public static float hardness(Block b, World w, int x, int y, int z) {
        if (w == null) return b.getDefaultState().getBlockHardness(null, null);
        return w.getBlockState(new BlockPos(x, y, z)).getBlockHardness(w, new BlockPos(x, y, z));
    }

    public static boolean canCollide(Block b, int meta, boolean liquids) {
        return b.canCollideCheck(state(b, meta), liquids);
    }

    // 1.7.10's render types: 0 a plain block (1.12.2: drawn from its model), -1 not drawn into the chunk (a block
    // entity's renderer draws it), others drawn by a renderer of their own
    public static int renderType(Block b) {
        return renderType(b.getDefaultState());
    }

    public static int renderType(IBlockState s) {
        switch (s.getRenderType()) {
            case MODEL: return 0;
            case INVISIBLE: return -1;
            case LIQUID: return 1;
            default: return 22;
        }
    }

    public static boolean opaque(Block b) {
        return b.getDefaultState().isOpaqueCube();
    }

    public static boolean opaque(IBlockState s) {
        return s.isOpaqueCube();
    }

    public static boolean normalCube(Block b) {
        return b.getDefaultState().isNormalCube();
    }

    public static boolean fullCube(Block b) {
        return b.getDefaultState().isFullCube();
    }

    // whether the fence at x, y, z joins its neighbour there (1.7.10: BlockFence.canConnectFenceTo(world, nx, ny, nz))
    public static boolean fenceConnects(net.minecraft.block.BlockFence f, IBlockAccess w, int x, int y, int z) {
        return joins(w, x, y, z, f);
    }

    public static boolean wallConnects(net.minecraft.block.BlockWall f, IBlockAccess w, int x, int y, int z) {
        return joins(w, x, y, z, f);
    }

    // 1.12.2 asks the fence at its own place which way it joins: the neighbour at x, y, z is joined by the fence beside
    // it toward the caller. 1.7.10 asked only about the neighbour, so here the neighbour is asked against each side
    // a fence could be on and the state of that fence read.
    private static boolean joins(IBlockAccess w, int x, int y, int z, Block fence) {
        BlockPos n = new BlockPos(x, y, z);
        for (net.minecraft.util.EnumFacing f : net.minecraft.util.EnumFacing.HORIZONTALS) {
            BlockPos at = n.offset(f);
            IBlockState s = w.getBlockState(at);
            if (s.getBlock() != fence) continue;
            IBlockState a = s.getActualState(w, at);
            net.minecraft.block.properties.IProperty<Boolean> side = side(fence, f.getOpposite());
            if (side != null && a.getValue(side)) return true;
        }
        return false;
    }

    private static net.minecraft.block.properties.IProperty<Boolean> side(Block fence, net.minecraft.util.EnumFacing f) {
        if (fence instanceof net.minecraft.block.BlockFence) {
            switch (f) {
                case NORTH: return net.minecraft.block.BlockFence.NORTH;
                case SOUTH: return net.minecraft.block.BlockFence.SOUTH;
                case EAST: return net.minecraft.block.BlockFence.EAST;
                case WEST: return net.minecraft.block.BlockFence.WEST;
                default: return null;
            }
        }
        if (fence instanceof net.minecraft.block.BlockWall) {
            switch (f) {
                case NORTH: return net.minecraft.block.BlockWall.NORTH;
                case SOUTH: return net.minecraft.block.BlockWall.SOUTH;
                case EAST: return net.minecraft.block.BlockWall.EAST;
                case WEST: return net.minecraft.block.BlockWall.WEST;
                default: return null;
            }
        }
        return null;
    }

    // a door's full metadata, both halves (1.7.10: BlockDoor.func_150012_g): bits 0-1 facing, 2 open, 3 top,
    // 4 hinge on the right
    public static int doorMeta(IBlockAccess w, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        IBlockState s = w.getBlockState(p).getActualState(w, p);
        if (!(s.getBlock() instanceof net.minecraft.block.BlockDoor)) return meta(w, x, y, z);
        int facing = s.getValue(net.minecraft.block.BlockDoor.FACING).rotateY().getHorizontalIndex();
        boolean open = s.getValue(net.minecraft.block.BlockDoor.OPEN);
        boolean top = s.getValue(net.minecraft.block.BlockDoor.HALF) == net.minecraft.block.BlockDoor.EnumDoorHalf.UPPER;
        boolean right = s.getValue(net.minecraft.block.BlockDoor.HINGE) == net.minecraft.block.BlockDoor.EnumHingePosition.RIGHT;
        return facing | (open ? 4 : 0) | (top ? 8 : 0) | (right ? 16 : 0);
    }

    // the block broken as a player breaks it, dropping itself or not (1.7.10: World.func_147480_a)
    public static boolean breakBlock(World w, int x, int y, int z, boolean drop) {
        return w.destroyBlock(new BlockPos(x, y, z), drop);
    }

    public static IBlockState state(World w, int x, int y, int z, net.minecraft.entity.EntityLivingBase placer) {
        return state(w, x, y, z);
    }

}
