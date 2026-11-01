// port/continuum: NTM NEXT's (HBM's Nuclear Tech Mod, 26.2) animation classes, so that a gun on NTM Continuum moves
// as on 26.2 (NextGunAnimations). Taken from NTM NEXT 1.0.3, LGPL-3.0-only: see continuum/third-party/ntm-next.txt.
package acup.gunfeel.next;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class AnimationLoader {
    public static final Gson gson = new Gson();

    public static HashMap<String, BusAnimation> load(Identifier file) {
        HashMap<String, BusAnimation> animations = new HashMap<>();

        InputStream in;
        try {
            in = Minecraft.getInstance().getResourceManager().getResourceOrThrow(file).open();
        } catch (IOException var16) {
            return null;
        }

        InputStreamReader reader = new InputStreamReader(in);
        JsonObject json = (JsonObject)gson.fromJson(reader, JsonObject.class);
        HashMap<String, double[]> offsets = new HashMap<>();

        for (Entry<String, JsonElement> root : json.getAsJsonObject("offset").entrySet()) {
            JsonArray array = root.getValue().getAsJsonArray();
            double[] offset = new double[3];

            for (int i = 0; i < 3; i++) {
                offset[i] = array.get(i).getAsDouble();
            }

            offsets.put(root.getKey(), offset);
        }

        HashMap<String, double[]> rotModes = new HashMap<>();
        if (json.has("rotmode")) {
            for (Entry<String, JsonElement> root : json.getAsJsonObject("rotmode").entrySet()) {
                String mode = root.getValue().getAsString();
                double[] rotMode = new double[]{getRot(mode.charAt(2)), getRot(mode.charAt(0)), getRot(mode.charAt(1))};
            }
        }

        for (Entry<String, JsonElement> root : json.getAsJsonObject("anim").entrySet()) {
            BusAnimation animation = new BusAnimation();
            JsonObject entryObject = root.getValue().getAsJsonObject();

            for (Entry<String, JsonElement> model : entryObject.entrySet()) {
                String modelName = model.getKey();
                double[] offset = new double[3];
                double[] rotMode = new double[]{0.0, 1.0, 2.0};
                if (offsets.containsKey(modelName)) {
                    offset = offsets.get(modelName);
                }

                if (rotModes.containsKey(modelName)) {
                    rotMode = rotModes.get(modelName);
                }

                animation.addBus(modelName, loadSequence(model.getValue().getAsJsonObject(), offset, rotMode));
            }

            animations.put(root.getKey(), animation);
        }

        return animations;
    }

    private static double getRot(char value) {
        switch (value) {
            case 'X':
                return 0.0;
            case 'Y':
                return 1.0;
            case 'Z':
                return 2.0;
            default:
                return 0.0;
        }
    }

    private static BusAnimationSequence loadSequence(JsonObject json, double[] offset, double[] rotMode) {
        BusAnimationSequence sequence = new BusAnimationSequence();
        if (json.has("location")) {
            JsonObject location = json.getAsJsonObject("location");
            if (location.has("x")) {
                addToSequence(sequence, BusAnimationSequence.Dimension.TX, location.getAsJsonArray("x"));
            }

            if (location.has("y")) {
                addToSequence(sequence, BusAnimationSequence.Dimension.TY, location.getAsJsonArray("y"));
            }

            if (location.has("z")) {
                addToSequence(sequence, BusAnimationSequence.Dimension.TZ, location.getAsJsonArray("z"));
            }
        }

        if (json.has("rotation_euler")) {
            JsonObject rotation = json.getAsJsonObject("rotation_euler");
            if (rotation.has("x")) {
                addToSequence(sequence, BusAnimationSequence.Dimension.RX, rotation.getAsJsonArray("x"));
            }

            if (rotation.has("y")) {
                addToSequence(sequence, BusAnimationSequence.Dimension.RY, rotation.getAsJsonArray("y"));
            }

            if (rotation.has("z")) {
                addToSequence(sequence, BusAnimationSequence.Dimension.RZ, rotation.getAsJsonArray("z"));
            }
        }

        if (json.has("scale")) {
            JsonObject scale = json.getAsJsonObject("scale");
            if (scale.has("x")) {
                addToSequence(sequence, BusAnimationSequence.Dimension.SX, scale.getAsJsonArray("x"));
            }

            if (scale.has("y")) {
                addToSequence(sequence, BusAnimationSequence.Dimension.SY, scale.getAsJsonArray("y"));
            }

            if (scale.has("z")) {
                addToSequence(sequence, BusAnimationSequence.Dimension.SZ, scale.getAsJsonArray("z"));
            }
        }

        sequence.offset = offset;
        sequence.rotMode = rotMode;
        return sequence;
    }

    private static void addToSequence(BusAnimationSequence sequence, BusAnimationSequence.Dimension dimension, JsonArray array) {
        BusAnimationKeyframe.IType prevInterp = null;

        for (JsonElement element : array) {
            BusAnimationKeyframe keyframe = loadKeyframe(element, prevInterp);
            prevInterp = keyframe.interpolationType;
            sequence.addKeyframe(dimension, keyframe);
        }
    }

    private static BusAnimationKeyframe loadKeyframe(JsonElement element, BusAnimationKeyframe.IType prevInterp) {
        JsonArray array = element.getAsJsonArray();
        double value = array.get(0).getAsDouble();
        int duration = array.get(1).getAsInt();
        BusAnimationKeyframe.IType interpolation = array.size() >= 3
            ? BusAnimationKeyframe.IType.valueOf(array.get(2).getAsString())
            : BusAnimationKeyframe.IType.LINEAR;
        BusAnimationKeyframe.EType easing = array.size() >= 4 ? BusAnimationKeyframe.EType.valueOf(array.get(3).getAsString()) : BusAnimationKeyframe.EType.AUTO;
        BusAnimationKeyframe keyframe = new BusAnimationKeyframe(value, duration, interpolation, easing);
        int i = 4;
        if (prevInterp == BusAnimationKeyframe.IType.BEZIER) {
            keyframe.leftX = array.get(i++).getAsDouble();
            keyframe.leftY = array.get(i++).getAsDouble();
            keyframe.leftType = BusAnimationKeyframe.HType.valueOf(array.get(i++).getAsString());
        }

        if (interpolation != BusAnimationKeyframe.IType.LINEAR && interpolation != BusAnimationKeyframe.IType.CONSTANT) {
            if (interpolation == BusAnimationKeyframe.IType.BEZIER) {
                keyframe.rightX = array.get(i++).getAsDouble();
                keyframe.rightY = array.get(i++).getAsDouble();
                keyframe.rightType = BusAnimationKeyframe.HType.valueOf(array.get(i++).getAsString());
            }

            if (interpolation == BusAnimationKeyframe.IType.ELASTIC) {
                keyframe.amplitude = array.get(i++).getAsDouble();
                keyframe.period = array.get(i++).getAsDouble();
            } else if (interpolation == BusAnimationKeyframe.IType.BACK) {
                keyframe.back = array.get(i++).getAsDouble();
            }

            return keyframe;
        } else {
            return keyframe;
        }
    }
}
