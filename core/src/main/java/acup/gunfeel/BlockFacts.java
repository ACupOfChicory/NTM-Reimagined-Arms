package acup.gunfeel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

// What the game knows about a block that decides what a bullet meets in it. Built once per block state in the game
// (BlockFactsOf) and written by the dump server (runDumpBlocks) with the same code, so the tests read back exactly
// what the game sees and the material of every block is checked without starting the game. No Minecraft here.
//   classes: the block's class and its superclasses below Block, nearest first (simple names)
//   sound: the name of its SoundType constant ("stone", "metal"...), "?" for a mod's own
//   modelShape: drawn as a plain block model (not by a block entity renderer, not invisible)
public record BlockFacts(String id, List<String> classes, String sound, float hardness, float blast, boolean toolRequired,
        boolean fullCollision, double collisionVolume, boolean blockEntity, boolean modelShape, Set<String> tags) {

    public static final List<String> COLUMNS = List.of("id", "classes", "sound", "hardness", "blast", "toolRequired",
            "fullCollision", "collisionVolume", "blockEntity", "modelShape", "tags");

    public String namespace() {
        int c = id.indexOf(':');
        return c < 0 ? "minecraft" : id.substring(0, c);
    }

    public String path() {
        return id.substring(id.indexOf(':') + 1);
    }

    // %mod in the bullet rules: a machine (a block entity, or drawn by a renderer) or a block that is not a full cube
    public boolean machineOrPartial() {
        return blockEntity || !modelShape || !fullCollision;
    }

    public List<String> tsv() {
        return List.of(id, classes.isEmpty() ? "-" : String.join(",", classes), sound, num(hardness), num(blast),
                String.valueOf(toolRequired), String.valueOf(fullCollision), num(collisionVolume), String.valueOf(blockEntity),
                String.valueOf(modelShape), String.join(",", tags));
    }

    public static String num(double d) {
        if (Double.isInfinite(d) || Double.isNaN(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return Long.toString((long) d);
        return String.format(java.util.Locale.ROOT, "%.4f", d);
    }

    // the dump's header names the columns; others in it (kept for people reading it) are skipped
    public static List<BlockFacts> parse(List<String> lines) {
        if (lines.isEmpty()) return List.of();
        String[] head = lines.get(0).split("\t", -1);
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < head.length; i++) at.put(head[i], i);
        for (String c : COLUMNS) if (!at.containsKey(c)) throw new IllegalArgumentException("the dump has no column " + c);
        List<BlockFacts> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] f = line.split("\t", -1);
            String classes = f[at.get("classes")];
            String tags = f[at.get("tags")];
            out.add(new BlockFacts(f[at.get("id")],
                    classes.equals("-") ? List.of() : List.of(classes.split(",")),
                    f[at.get("sound")],
                    Float.parseFloat(f[at.get("hardness")]),
                    Float.parseFloat(f[at.get("blast")]),
                    Boolean.parseBoolean(f[at.get("toolRequired")]),
                    Boolean.parseBoolean(f[at.get("fullCollision")]),
                    Double.parseDouble(f[at.get("collisionVolume")]),
                    Boolean.parseBoolean(f[at.get("blockEntity")]),
                    Boolean.parseBoolean(f[at.get("modelShape")]),
                    tags.isEmpty() ? Set.of() : new TreeSet<>(Arrays.asList(tags.split(",")))));
        }
        return out;
    }
}
