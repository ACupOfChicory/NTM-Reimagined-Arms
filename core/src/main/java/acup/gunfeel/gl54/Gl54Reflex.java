package acup.gunfeel.gl54;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// The GL-54's reflex sight and laser as the model has them (models/weapons/gl54_reflex.json, made by tools/gl54_export.py
// from the .blend's ReflexPanel and LaserRangefinder): the clear pane's corners, the frame of its uvs (an origin and two
// axes, each a unit direction and a length) and its normal, and the point the laser leaves from. All in NTM's model units
// (x to the gun's left, y up, z forward), the model's own frame, so a new model with another pane gives other numbers.
public record Gl54Reflex(boolean present, double[][] corners, double[][] uv, double[] origin, double[] uAxis, double uLength,
        double[] vAxis, double vLength, double[] normal, double[] laser) {
    public static final String RESOURCE = "/assets/ntmgunfeel/models/weapons/gl54_reflex.json";
    private static volatile Gl54Reflex loaded;

    public static Gl54Reflex get() {
        Gl54Reflex r = loaded;
        if (r == null) {
            try (InputStream in = Gl54Reflex.class.getResourceAsStream(RESOURCE)) {
                r = in == null ? none() : parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                r = none();
            }
            loaded = r;
        }
        return r;
    }

    public static Gl54Reflex none() {
        double[] z = {0, 0, 0};
        return new Gl54Reflex(false, new double[0][], new double[0][], z, z, 0, z, 0, z, z);
    }

    public static Gl54Reflex parse(Reader reader) {
        JsonObject j = JsonParser.parseReader(reader).getAsJsonObject();
        if (!j.has("present") || !j.get("present").getAsBoolean()) return none();
        JsonObject p = j.getAsJsonObject("panel");
        JsonArray c = p.getAsJsonArray("corners"), uv = p.getAsJsonArray("uv");
        double[][] corners = new double[c.size()][], uvs = new double[uv.size()][];
        for (int i = 0; i < corners.length; i++) corners[i] = vec(c.get(i).getAsJsonArray());
        for (int i = 0; i < uvs.length; i++) uvs[i] = vec(uv.get(i).getAsJsonArray());
        return new Gl54Reflex(true, corners, uvs, vec(p.getAsJsonArray("uvOrigin")), vec(p.getAsJsonArray("uAxis")),
                p.get("uLength").getAsDouble(), vec(p.getAsJsonArray("vAxis")), p.get("vLength").getAsDouble(),
                vec(p.getAsJsonArray("normal")), vec(j.getAsJsonObject("laser").getAsJsonArray("exit")));
    }

    private static double[] vec(JsonArray a) {
        double[] v = new double[a.size()];
        for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsDouble();
        return v;
    }

    // the point of the pane at uv (u across, v up), in the model
    public double[] at(double u, double v) {
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) out[k] = origin[k] + u * uAxis[k] * uLength + v * vAxis[k] * vLength;
        return out;
    }
}
