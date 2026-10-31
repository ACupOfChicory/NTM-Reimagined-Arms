package acup.gunfeel;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelDragon;
import net.minecraft.client.model.ModelRenderer;

// The names of a 1.7.10 model's parts, written as 26.2 names them (26.2: ModelPoseCapture's path of ModelPart
// children, "root/head", "root/left_arm"). The part names are what the 26.2 head and limb tables
// (ntmgunfeel-heads.json, -limbs.json) bind by, and what ModelWool and DragonModelParts read. A 1.7.10 ModelRenderer
// has no name, only the model's field that holds it, and that field is "bipedHead" in the dev run but "field_78116_c"
// in a player's game: TABLE gives both for the vanilla models whose geometry is 26.2's, with the 26.2 path. Any other
// part is "root/" + its field's name (an array's with its index), a child that no field holds is its parent's path
// + "/" + its index in childModels, and a part no field reaches is "root/part" + its index in boxList.
public final class ModelNames {
    // {model class, MCP field, SRG field, 26.2 path}; "%d" in the path is an array field's index
    private static final String[][] TABLE = {
            {"ModelBiped", "bipedHead", "field_78116_c", "root/head"},
            {"ModelBiped", "bipedHeadwear", "field_78114_d", "root/head/hat"},
            {"ModelBiped", "bipedBody", "field_78115_e", "root/body"},
            {"ModelBiped", "bipedRightArm", "field_78112_f", "root/right_arm"},
            {"ModelBiped", "bipedLeftArm", "field_78113_g", "root/left_arm"},
            {"ModelBiped", "bipedRightLeg", "field_78123_h", "root/right_leg"},
            {"ModelBiped", "bipedLeftLeg", "field_78124_i", "root/left_leg"},
            {"ModelQuadruped", "head", "field_78150_a", "root/head"},
            {"ModelQuadruped", "body", "field_78148_b", "root/body"},
            {"ModelQuadruped", "leg1", "field_78149_c", "root/right_hind_leg"},
            {"ModelQuadruped", "leg2", "field_78146_d", "root/left_hind_leg"},
            {"ModelQuadruped", "leg3", "field_78147_e", "root/right_front_leg"},
            {"ModelQuadruped", "leg4", "field_78144_f", "root/left_front_leg"},
            {"ModelCreeper", "head", "field_78135_a", "root/head"},
            {"ModelCreeper", "body", "field_78134_c", "root/body"},
            {"ModelCreeper", "leg1", "field_78131_d", "root/right_hind_leg"},
            {"ModelCreeper", "leg2", "field_78132_e", "root/left_hind_leg"},
            {"ModelCreeper", "leg3", "field_78129_f", "root/right_front_leg"},
            {"ModelCreeper", "leg4", "field_78130_g", "root/left_front_leg"},
            {"ModelSpider", "spiderHead", "field_78209_a", "root/head"},
            {"ModelSpider", "spiderNeck", "field_78207_b", "root/body0"},
            {"ModelSpider", "spiderBody", "field_78208_c", "root/body1"},
            {"ModelSpider", "spiderLeg1", "field_78205_d", "root/right_hind_leg"},
            {"ModelSpider", "spiderLeg2", "field_78206_e", "root/left_hind_leg"},
            {"ModelSpider", "spiderLeg3", "field_78203_f", "root/right_middle_hind_leg"},
            {"ModelSpider", "spiderLeg4", "field_78204_g", "root/left_middle_hind_leg"},
            {"ModelSpider", "spiderLeg5", "field_78212_h", "root/right_middle_front_leg"},
            {"ModelSpider", "spiderLeg6", "field_78213_i", "root/left_middle_front_leg"},
            {"ModelSpider", "spiderLeg7", "field_78210_j", "root/right_front_leg"},
            {"ModelSpider", "spiderLeg8", "field_78211_k", "root/left_front_leg"},
            {"ModelChicken", "head", "field_78142_a", "root/head"},
            {"ModelChicken", "bill", "field_78137_g", "root/head/beak"},
            {"ModelChicken", "chin", "field_78143_h", "root/head/red_thing"},
            {"ModelChicken", "body", "field_78140_b", "root/body"},
            {"ModelChicken", "rightLeg", "field_78141_c", "root/right_leg"},
            {"ModelChicken", "leftLeg", "field_78138_d", "root/left_leg"},
            {"ModelChicken", "rightWing", "field_78139_e", "root/right_wing"},
            {"ModelChicken", "leftWing", "field_78136_f", "root/left_wing"},
            {"ModelWolf", "wolfHeadMain", "field_78185_a", "root/head/real_head"},
            {"ModelWolf", "wolfBody", "field_78183_b", "root/body"},
            {"ModelWolf", "wolfMane", "field_78186_h", "root/upper_body"},
            {"ModelWolf", "wolfLeg1", "field_78184_c", "root/right_hind_leg"},
            {"ModelWolf", "wolfLeg2", "field_78181_d", "root/left_hind_leg"},
            {"ModelWolf", "wolfLeg3", "field_78182_e", "root/right_front_leg"},
            {"ModelWolf", "wolfLeg4", "field_78179_f", "root/left_front_leg"},
            {"ModelWolf", "wolfTail", "field_78180_g", "root/tail/real_tail"},
            {"ModelVillager", "villagerHead", "field_78191_a", "root/head"},
            {"ModelVillager", "villagerNose", "field_82898_f", "root/head/nose"},
            {"ModelVillager", "villagerBody", "field_78189_b", "root/body"},
            {"ModelVillager", "villagerArms", "field_78190_c", "root/arms"},
            {"ModelVillager", "rightVillagerLeg", "field_78187_d", "root/right_leg"},
            {"ModelVillager", "leftVillagerLeg", "field_78188_e", "root/left_leg"},
            {"ModelWitch", "witchHat", "field_82902_i", "root/head/hat"},
            {"ModelIronGolem", "ironGolemHead", "field_78178_a", "root/head"},
            {"ModelIronGolem", "ironGolemBody", "field_78176_b", "root/body"},
            {"ModelIronGolem", "ironGolemRightArm", "field_78177_c", "root/right_arm"},
            {"ModelIronGolem", "ironGolemLeftArm", "field_78174_d", "root/left_arm"},
            {"ModelIronGolem", "ironGolemRightLeg", "field_78173_f", "root/right_leg"},
            {"ModelIronGolem", "ironGolemLeftLeg", "field_78175_e", "root/left_leg"},
            {"ModelBlaze", "blazeHead", "field_78105_b", "root/head"},
            {"ModelBlaze", "blazeSticks", "field_78106_a", "root/part%d"},
            {"ModelGhast", "body", "field_78128_a", "root/body"},
            {"ModelGhast", "tentacles", "field_78127_b", "root/tentacle%d"},
            {"ModelSquid", "squidBody", "field_78202_a", "root/body"},
            {"ModelSquid", "squidTentacles", "field_78201_b", "root/tentacle%d"},
            {"ModelBat", "batHead", "field_82895_a", "root/head"},
            {"ModelBat", "batBody", "field_82893_b", "root/body"},
            {"ModelBat", "batRightWing", "field_82894_c", "root/body/right_wing"},
            {"ModelBat", "batLeftWing", "field_82891_d", "root/body/left_wing"},
            {"ModelBat", "batOuterRightWing", "field_82892_e", "root/body/right_wing/right_wing_tip"},
            {"ModelBat", "batOuterLeftWing", "field_82890_f", "root/body/left_wing/left_wing_tip"},
            {"ModelOcelot", "ocelotHead", "field_78156_g", "root/head"},
            {"ModelOcelot", "ocelotBody", "field_78162_h", "root/body"},
            {"ModelOcelot", "ocelotBackLeftLeg", "field_78161_a", "root/left_hind_leg"},
            {"ModelOcelot", "ocelotBackRightLeg", "field_78159_b", "root/right_hind_leg"},
            {"ModelOcelot", "ocelotFrontLeftLeg", "field_78160_c", "root/left_front_leg"},
            {"ModelOcelot", "ocelotFrontRightLeg", "field_78157_d", "root/right_front_leg"},
            {"ModelOcelot", "ocelotTail", "field_78158_e", "root/tail1"},
            {"ModelOcelot", "ocelotTail2", "field_78155_f", "root/tail2"},
            {"ModelSnowMan", "head", "field_78195_c", "root/head"},
            {"ModelSnowMan", "body", "field_78196_a", "root/upper_body"},
            {"ModelSnowMan", "bottomBody", "field_78194_b", "root/lower_body"},
            {"ModelSnowMan", "rightHand", "field_78192_d", "root/right_arm"},
            {"ModelSnowMan", "leftHand", "field_78193_e", "root/left_arm"},
            {"ModelSilverfish", "silverfishBodyParts", "field_78171_a", "root/segment%d"},
            {"ModelSilverfish", "silverfishWings", "field_78169_b", "root/layer%d"},
            {"ModelWither", "field_82905_a", "field_82905_a", "root/body%d"},
            {"ModelWither", "field_82904_b", "field_82904_b", "root/head%d"},
            {"ModelDragon", "head", "field_78221_a", "root/head"},
            {"ModelDragon", "jaw", "field_78220_c", "root/head/jaw"},
            {"ModelDragon", "body", "field_78217_d", "root/body"},
            {"ModelDragon", "spine", "field_78219_b", "root/spine"},
            {"ModelDragon", "wing", "field_78225_k", "root/body/*_wing"},
            {"ModelDragon", "wingTip", "field_78222_l", "root/body/*_wing/*_wing_tip"},
            {"ModelDragon", "frontLeg", "field_78215_f", "root/body/*_front_leg"},
            {"ModelDragon", "frontLegTip", "field_78226_h", "root/body/*_front_leg/*_front_leg_tip"},
            {"ModelDragon", "frontFoot", "field_78224_j", "root/body/*_front_leg/*_front_leg_tip/*_front_foot"},
            {"ModelDragon", "rearLeg", "field_78218_e", "root/body/*_hind_leg"},
            {"ModelDragon", "rearLegTip", "field_78216_g", "root/body/*_hind_leg/*_hind_leg_tip"},
            {"ModelDragon", "rearFoot", "field_78227_i", "root/body/*_hind_leg/*_hind_leg_tip/*_hind_foot"},
    };
    // 26.2's wither: shoulders, ribcage, tail; center, right and left head (1.7.10's second head is at -x)
    private static final String[] WITHER = {"root/shoulders", "root/ribcage", "root/tail", "root/center_head", "root/right_head", "root/left_head"};
    private static final Map<String, String> PATHS = new HashMap<>();
    static {
        for (String[] row : TABLE) {
            PATHS.put(row[0] + "." + row[1], row[3]);
            PATHS.put(row[0] + "." + row[2], row[3]);
        }
    }
    private static final Map<ModelBase, Map<ModelRenderer, String>> CACHE = new WeakHashMap<>();

    private ModelNames() {
    }

    // part -> path for every ModelRenderer of the model (boxList and their children); built once per model
    public static synchronized Map<ModelRenderer, String> of(ModelBase model) {
        return CACHE.computeIfAbsent(model, ModelNames::build);
    }

    private static Map<ModelRenderer, String> build(ModelBase model) {
        Map<ModelRenderer, String> out = new IdentityHashMap<>();
        for (Class<?> c = model.getClass(); c != null && c != ModelBase.class && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(model);
                    if (value instanceof ModelRenderer r) {
                        if (!out.containsKey(r)) out.put(r, path(c, f.getName(), -1));
                    } else if (value instanceof ModelRenderer[] array) {
                        for (int i = 0; i < array.length; i++)
                            if (array[i] != null && !out.containsKey(array[i])) out.put(array[i], path(c, f.getName(), i));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        // children no field holds: their parent's path and index. Before boxList, which holds every part made with
        // this model, children too (the ModelRenderer constructor adds itself)
        for (var entry : new java.util.ArrayList<>(out.entrySet())) children(entry.getKey(), entry.getValue(), out, 0);
        List<ModelRenderer> parts = model.boxList;
        for (int i = 0; parts != null && i < parts.size(); i++) {
            ModelRenderer r = parts.get(i);
            if (r != null && !out.containsKey(r)) {
                out.put(r, "root/part" + i);
                children(r, out.get(r), out, 0);
            }
        }
        return out;
    }

    private static void children(ModelRenderer parent, String path, Map<ModelRenderer, String> out, int depth) {
        if (parent.childModels == null || depth > 32) return;
        for (int i = 0; i < parent.childModels.size(); i++) {
            ModelRenderer child = parent.childModels.get(i);
            if (child == null) continue;
            if (!out.containsKey(child)) out.put(child, path + "/" + i);
            children(child, out.get(child), out, depth + 1);
        }
    }

    private static String path(Class<?> owner, String field, int index) {
        String row = PATHS.get(owner.getSimpleName() + "." + field);
        if (row == null) return "root/" + field + (index < 0 ? "" : String.valueOf(index));
        if (owner.getSimpleName().equals("ModelWither")) return WITHER[(field.equals("field_82904_b") ? 3 : 0) + Math.min(index, 2)];
        return row.contains("%d") ? row.replace("%d", String.valueOf(Math.max(index, 0))) : row;
    }

    // The name of one draw of a part: a part drawn again in the same pose is the same name. The dragon draws its spine
    // 5 times as the neck, then 12 times as the tail, and its wings and legs once on the right, then mirrored on the
    // left (ModelDragon.render); 26.2 names those draws root/neck<i>, root/tail<i>, right_* and left_*.
    public static String draw(ModelBase model, String path, int ordinal) {
        String name = path;
        if (model instanceof ModelDragon) {
            if (path.equals("root/spine")) name = ordinal < 5 ? "root/neck" + ordinal : "root/tail" + (ordinal - 5);
            else if (path.contains("*")) name = path.replace("*", ordinal == 0 ? "right" : "left");
            else if (ordinal > 0) name = path + "#" + ordinal;
        } else if (ordinal > 0) name = path + "#" + ordinal;
        return label(name);
    }

    // 26.2's ModelPoseCapture.label: a long path keeps its start and its leaf
    static String label(String path) {
        if (path.length() <= 48) return path;
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        if (leaf.length() > 20) leaf = leaf.substring(leaf.length() - 20);
        return path.substring(0, 16) + "~" + Integer.toHexString(path.hashCode()) + "/" + leaf;
    }
}
