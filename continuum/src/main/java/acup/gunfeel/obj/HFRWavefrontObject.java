package acup.gunfeel.obj;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hbm.client.model.WavefrontObj;

import acup.gunfeel.GunFeel;
import dev.golem.core.render.gem.Geometry;
import net.minecraft.resources.Identifier;

// port/continuum: a gun model as the shared Arms, GunGeometry and Silhouette read NTM NEXT's
// com.hbm.render.loader.HFRWavefrontObject (the import is renamed to this class in stonecutter.gradle.kts): its
// groups by index, in the order of the OBJ. Built from NTM Continuum's WavefrontObj, which holds the same OBJ files
// (group names, order and bounds checked against NEXT's .hbm_mesh for Maresleg, SPAS-12, Henry, Liberator, Sexy).
// A usemtl run in Continuum is a group of its own with the same name; here the name is one group, as in NEXT.
//
// Continuum draws a gun through Golem Core's Gem: each declared part holds a Geometry, and the OBJ part of it is only
// in its name, "<model id><variant>#<selection>[#2]" (WavefrontObj.ObjGeometry): selection "+A,B" = those groups,
// "*" = the whole model, "-A,B" = all but those, "=mat" = the runs of that material. select() reads it back.
public final class HFRWavefrontObject {
    private static final Map<String, HFRWavefrontObject> LOADED = new HashMap<>();
    private static final Map<Geometry, Selection> SELECTIONS = new IdentityHashMap<>();
    private static final int[] WHOLE = {-1};

    public final String source;
    public final GroupObject[] groups;
    private final Map<String, List<Integer>> byMaterial;

    private HFRWavefrontObject(String source, WavefrontObj obj) {
        this.source = source;
        Map<String, Integer> index = new LinkedHashMap<>();
        Map<String, List<Integer>> materials = new HashMap<>();
        List<GroupObject> list = new ArrayList<>();
        for (String name : obj.partNames()) {
            index.put(name, list.size());
            list.add(new GroupObject(name, obj.part(name).vertices()));
        }
        for (WavefrontObj.Group g : obj.groups) {
            Integer i = index.get(g.name);
            if (i == null || g.faceCount() == 0) continue;
            List<Integer> ids = materials.computeIfAbsent(g.material, k -> new ArrayList<>());
            if (!ids.contains(i)) ids.add(i);
        }
        this.groups = list.toArray(new GroupObject[0]);
        this.byMaterial = materials;
    }

    public boolean smoothing() {
        return true;
    }

    public int partId(String name) {
        for (int i = 0; i < groups.length; i++) if (groups[i].name.equals(name)) return i;
        return -1;
    }

    // the model loaded from its resource id (once; null when it cannot be read)
    public static HFRWavefrontObject of(String source) {
        if (LOADED.containsKey(source)) return LOADED.get(source);
        HFRWavefrontObject model = null;
        try {
            model = new HFRWavefrontObject(source, WavefrontObj.load(Identifier.parse(source)));
        } catch (RuntimeException e) {
            GunFeel.LOGGER.warn("[port] could not read the model {} for the arms", source, e);
        }
        LOADED.put(source, model);
        return model;
    }

    // What a Gem part draws: the model and its group indices ({-1} = the whole model), or null for a geometry that
    // is not a WavefrontObj part.
    public record Selection(HFRWavefrontObject model, int[] groups) {
    }

    public static Selection select(Geometry geometry) {
        if (SELECTIONS.containsKey(geometry)) return SELECTIONS.get(geometry);
        Selection s = null;
        Name n = parse(geometry.name());
        HFRWavefrontObject model = n == null ? null : of(n.source());
        if (model != null) s = new Selection(model, model.indices(n));
        SELECTIONS.put(geometry, s);
        return s;
    }

    private int[] indices(Name n) {
        return switch (n.kind()) {
            case '*' -> WHOLE;
            case '+' -> ids(n.names(), true);
            case '-' -> n.names().isEmpty() ? WHOLE : ids(n.names(), false);
            default -> {
                List<Integer> ids = new ArrayList<>();
                for (String m : n.names()) ids.addAll(byMaterial.getOrDefault(m, List.of()));
                yield ids.stream().sorted().mapToInt(Integer::intValue).toArray();
            }
        };
    }

    private int[] ids(List<String> names, boolean include) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < groups.length; i++) if (names.contains(groups[i].name) == include) out.add(i);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    // a geometry name taken apart: the model id (variant dropped), the kind of selection and its names
    record Name(String source, char kind, List<String> names) {
    }

    static Name parse(String name) {
        int hash = name.indexOf('#');
        if (hash < 0) return null;
        String head = name.substring(0, hash);
        int obj = head.indexOf(".obj");
        if (obj < 0) return null;
        String source = head.substring(0, obj + 4);
        String selection = name.substring(hash + 1);
        int more = selection.indexOf('#');
        if (more >= 0) selection = selection.substring(0, more);
        if (selection.isEmpty()) return null;
        char kind = selection.charAt(0);
        if (kind == '*') return new Name(source, kind, List.of());
        if (kind != '+' && kind != '-' && kind != '=') return null;
        String rest = selection.substring(1);
        return new Name(source, kind, kind == '=' ? List.of(rest) : rest.isEmpty() ? List.of() : List.of(rest.split(",")));
    }
}
