package acup.gunfeel;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

// What the game knows about every block, written to a file for the material tests (a dev run only):
// -Dntmgunfeel.dumpBlocks=<file> on a dedicated server writes one line per block once the mods and the tags are
// loaded, before any world, and stops the server. Without the property nothing is registered.
// The BlockFacts columns come from BlockFactsOf, as in the game; the rest is for people reading the file.
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

    static final List<String> EXTRA = List.of("breakSound", "mapColor", "instrument", "collisionBox", "occludes", "burns", "light", "states");

    static void write(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState st = block.defaultBlockState();
            List<String> f = new ArrayList<>(BlockFactsOf.of(st).tsv());
            f.add(String.valueOf(BuiltInRegistries.SOUND_EVENT.getKey(st.getSoundType().getBreakSound())));
            f.add(safe(() -> String.valueOf(st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id)));
            f.add(st.instrument().getSerializedName());
            f.add(safe(() -> {
                VoxelShape s = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (s.isEmpty()) return "-";
                AABB b = s.bounds();
                return BlockFacts.num(b.minX) + "," + BlockFacts.num(b.minY) + "," + BlockFacts.num(b.minZ) + ","
                        + BlockFacts.num(b.maxX) + "," + BlockFacts.num(b.maxY) + "," + BlockFacts.num(b.maxZ);
            }));
            f.add(String.valueOf(st.canOcclude()));
            f.add(String.valueOf(st.ignitedByLava()));
            f.add(String.valueOf(st.getLightEmission()));
            f.add(String.valueOf(block.getStateDefinition().getPossibleStates().size()));
            lines.add(String.join("\t", f));
        }
        lines.sort(null);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            List<String> head = new ArrayList<>(BlockFacts.COLUMNS);
            head.addAll(EXTRA);
            w.write(String.join("\t", head));
            w.write('\n');
            for (String l : lines) {
                w.write(l);
                w.write('\n');
            }
        }
        System.out.println("[ntmgunfeel] wrote " + lines.size() + " blocks to " + file);
    }

    static String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return "!" + e.getClass().getSimpleName();
        }
    }
}
