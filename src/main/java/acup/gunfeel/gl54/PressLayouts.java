package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.mojang.serialization.Codec;

// The layouts a press remembers (PressLoading's free loading): up to MAX per press, each a list of 1 to 8 grenade keys in
// fire order. No game classes except the codec, so the rules are tested without the game. Newest last. They are listed
// in the press's recipe selector after NTM's own recipes: the selection index of the stored layout k is size of NTM's list
// plus k, and the layouts themselves (never the index) are what is saved, so a reload of the recipes loses none.
public final class PressLayouts {
    public static final int MAX = 6;
    public static final Codec<List<List<String>>> CODEC = Codec.STRING.listOf(1, MagContents.CAPACITY).listOf(0, MAX);

    private PressLayouts() {
    }

    // the stored layouts after a magazine with this layout was made: unchanged for a preset or an empty or too long
    // layout; a stored one moves to the newest place; a new one is added and the oldest goes when over MAX
    public static List<List<String>> remember(List<List<String>> stored, List<String> layout, Collection<List<String>> presets) {
        if (layout.isEmpty() || layout.size() > MagContents.CAPACITY || presets.contains(layout)) return stored;
        List<List<String>> out = new ArrayList<>(stored);
        out.remove(layout);
        out.add(List.copyOf(layout));
        while (out.size() > MAX) out.remove(0);
        return out;
    }

    // one stored layout deleted; unchanged for an index out of range
    public static List<List<String>> forget(List<List<String>> stored, int index) {
        if (index < 0 || index >= stored.size()) return stored;
        List<List<String>> out = new ArrayList<>(stored);
        out.remove(index);
        return out;
    }

    // the stored layout k a selection index means (the index counts NTM's recipes first), -1 when it is not one
    public static int stored(int selection, int recipes, int count) {
        int k = selection - recipes;
        return selection >= recipes && k < count ? k : -1;
    }

    // where a selected layout went after the list changed (-1 = it is gone)
    public static int indexOf(List<List<String>> stored, List<String> layout) {
        return layout == null ? -1 : stored.indexOf(layout);
    }
}
