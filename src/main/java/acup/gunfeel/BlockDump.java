package acup.gunfeel;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

// What the game knows about every block, written to a file for the material tests (a dev run only):
// -Dntmgunfeel.dumpBlocks=<file> on a dedicated server writes one line per block once the mods and the tags are
// loaded, before any world, and stops the server. Without the property nothing is registered.
public final class BlockDump {
    public static final String PROPERTY = "ntmgunfeel.dumpBlocks";

    private BlockDump() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, e -> {
            int code = 0;
            try {
                write(Path.of(file));
            } catch (IOException | RuntimeException ex) {
                ex.printStackTrace();
                code = 1;
            }
            // no world is needed: stop here, without saving anything
            Runtime.getRuntime().halt(code);
        });
    }

    static final String HEADER = String.join("\t", "id", "classes", "sound", "breakSound", "hardness", "blast", "mapColor",
            "instrument", "toolRequired", "fullCollision", "collisionVolume", "collisionBox", "occludes", "burns", "blockEntity",
            "light", "states", "tags");

    static void write(Path file) throws IOException {
        Map<SoundType, String> sounds = soundNames();
        List<String> lines = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) lines.add(line(block, sounds));
        lines.sort(null);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(HEADER);
            w.write('\n');
            for (String l : lines) {
                w.write(l);
                w.write('\n');
            }
        }
        System.out.println("[ntmgunfeel] wrote " + lines.size() + " blocks to " + file);
    }

    static String line(Block block, Map<SoundType, String> sounds) {
        BlockState st = block.defaultBlockState();
        List<String> f = new ArrayList<>();
        f.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        List<String> classes = new ArrayList<>();
        for (Class<?> c = block.getClass(); c != null && c != Block.class && c != Object.class; c = c.getSuperclass()) classes.add(c.getName());
        f.add(classes.isEmpty() ? "-" : String.join(",", classes));
        SoundType sound = st.getSoundType();
        f.add(sounds.getOrDefault(sound, "?"));
        f.add(String.valueOf(BuiltInRegistries.SOUND_EVENT.getKey(sound.getBreakSound())));
        f.add(num(block.defaultDestroyTime()));
        f.add(num(block.getExplosionResistance()));
        f.add(safe(() -> String.valueOf(st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id)));
        f.add(st.instrument().getSerializedName());
        f.add(String.valueOf(st.requiresCorrectToolForDrops()));
        f.add(safe(() -> String.valueOf(st.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))));
        f.add(safe(() -> {
            VoxelShape s = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            double v = 0;
            for (AABB b : s.toAabbs()) v += b.getXsize() * b.getYsize() * b.getZsize();
            return num(v);
        }));
        f.add(safe(() -> {
            VoxelShape s = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (s.isEmpty()) return "-";
            AABB b = s.bounds();
            return num(b.minX) + "," + num(b.minY) + "," + num(b.minZ) + "," + num(b.maxX) + "," + num(b.maxY) + "," + num(b.maxZ);
        }));
        f.add(String.valueOf(st.canOcclude()));
        f.add(String.valueOf(st.ignitedByLava()));
        f.add(String.valueOf(st.hasBlockEntity()));
        f.add(String.valueOf(st.getLightEmission()));
        f.add(String.valueOf(block.getStateDefinition().getPossibleStates().size()));
        f.add(block.builtInRegistryHolder().tags().map(t -> t.location().toString()).sorted().collect(Collectors.joining(",")));
        return String.join("\t", f);
    }

    // SoundType has no names: the constants' field names, from vanilla and from any loaded mod class that holds some
    static Map<SoundType, String> soundNames() {
        Map<SoundType, String> out = new IdentityHashMap<>();
        for (Field fl : SoundType.class.getFields()) {
            if (!Modifier.isStatic(fl.getModifiers()) || fl.getType() != SoundType.class) continue;
            try {
                out.put((SoundType) fl.get(null), fl.getName().toLowerCase(Locale.ROOT));
            } catch (IllegalAccessException e) {
                // skip
            }
        }
        return out;
    }

    static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return Long.toString((long) d);
        return String.format(Locale.ROOT, "%.4f", d);
    }

    static String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return "!" + e.getClass().getSimpleName();
        }
    }
}
