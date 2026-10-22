package acup.gunfeel;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

// BlockFacts of a block state, as the game has it. The dump server writes these too (BlockDump), so the material tests
// run on exactly what the game sees. Called once per block state (the callers cache the result).
public final class BlockFactsOf {
    private BlockFactsOf() {
    }

    private static final Map<SoundType, String> SOUNDS = soundNames();

    public static BlockFacts of(BlockState st) {
        Block block = st.getBlock();
        List<String> classes = new ArrayList<>();
        for (Class<?> c = block.getClass(); c != null && c != Block.class && c != Object.class; c = c.getSuperclass()) classes.add(c.getSimpleName());
        boolean full;
        double volume;
        try {
            VoxelShape s = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            full = Block.isShapeFullBlock(s);
            double v = 0;
            for (AABB b : s.toAabbs()) v += b.getXsize() * b.getYsize() * b.getZsize();
            volume = v;
        } catch (RuntimeException e) {
            // a shape that needs a real world: take it as a full block
            full = true;
            volume = 1;
        }
        TreeSet<String> tags = new TreeSet<>();
        block.builtInRegistryHolder().tags().forEach(t -> tags.add(t.location().toString()));
        return new BlockFacts(BuiltInRegistries.BLOCK.getKey(block).toString(), List.copyOf(classes),
                SOUNDS.getOrDefault(st.getSoundType(), "?"), block.defaultDestroyTime(), block.getExplosionResistance(),
                st.requiresCorrectToolForDrops(), full, volume, st.hasBlockEntity(), st.getRenderShape() == RenderShape.MODEL, tags);
    }

    // SoundType has no names: its constants' field names, as the bullet rules' @sound selectors use them; one sound
    // under two names gets both, "a/b"
    static Map<SoundType, String> soundNames() {
        Map<SoundType, TreeSet<String>> names = new IdentityHashMap<>();
        for (Field fl : SoundType.class.getFields()) {
            if (!Modifier.isStatic(fl.getModifiers()) || fl.getType() != SoundType.class) continue;
            try {
                names.computeIfAbsent((SoundType) fl.get(null), k -> new TreeSet<>()).add(fl.getName().toLowerCase(Locale.ROOT));
            } catch (IllegalAccessException e) {
                // skip
            }
        }
        Map<SoundType, String> out = new IdentityHashMap<>();
        names.forEach((k, v) -> out.put(k, String.join("/", v)));
        return out;
    }
}
