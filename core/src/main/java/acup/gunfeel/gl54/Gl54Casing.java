package acup.gunfeel.gl54;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// The spent case of the GL-54 as the model has it (models/weapons/gl54_casing.json, made by tools/gl54_export.py from
// the EjectedCase of the .blend): the tick of the shot's animation it comes out in, where it is in the model (metres of
// the .blend: front, height, side, side + = the gun's left), how fast it goes (blocks a tick, the same frame) and how
// it turns (degrees a tick). A new model with another case gives other numbers in the file, not in the code.
public record Gl54Casing(boolean present, int tick, double front, double height, double side, double originFront, double originHeight,
        double originSide, double portX, double portY, double portZ, double restX, double restY, double restZ, double velFront, double velHeight, double velSide, float pitch, float yaw) {
    // How fast the case leaves, blocks a tick: Congo Lake's (0.12 sideways, 0.18 up), the one constant for it. The direction is the
    // model's (its animation); the model's own speed is a tenth of this and is not used.
    public static final double SPEED = Math.hypot(0.12, 0.18);
    public static final String RESOURCE = "/assets/ntmgunfeel/models/weapons/gl54_casing.json";
    private static volatile Gl54Casing loaded;

    // the file in the mod; none when it is missing (no case then)
    public static Gl54Casing get() {
        Gl54Casing c = loaded;
        if (c == null) {
            try (InputStream in = Gl54Casing.class.getResourceAsStream(RESOURCE)) {
                c = in == null ? none() : parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                c = none();
            }
            loaded = c;
        }
        return c;
    }

    public static Gl54Casing none() {
        return new Gl54Casing(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0F, 0F);
    }

    public static Gl54Casing parse(Reader reader) {
        JsonObject j = JsonParser.parseReader(reader).getAsJsonObject();
        if (!j.has("present") || !j.get("present").getAsBoolean()) return none();
        JsonObject m = j.getAsJsonObject("port"), r = j.getAsJsonObject("rest");
        JsonObject p = j.getAsJsonObject("position"), o = j.getAsJsonObject("origin"), v = j.getAsJsonObject("velocity"), t = j.getAsJsonObject("turn");
        return new Gl54Casing(true, j.get("tick").getAsInt(),
                p.get("front").getAsDouble(), p.get("height").getAsDouble(), p.get("side").getAsDouble(),
                o.get("front").getAsDouble(), o.get("height").getAsDouble(), o.get("side").getAsDouble(),
                m.get("x").getAsDouble(), m.get("y").getAsDouble(), m.get("z").getAsDouble(),
                r.get("x").getAsDouble(), r.get("y").getAsDouble(), r.get("z").getAsDouble(),
                v.get("front").getAsDouble(), v.get("height").getAsDouble(), v.get("side").getAsDouble(),
                t.get("pitch").getAsFloat(), t.get("yaw").getAsFloat());
    }

    // the way the case leaves, blocks a tick: the model's direction (front, height, side) at SPEED
    public double[] launch() {
        double n = Math.sqrt(velFront * velFront + velHeight * velHeight + velSide * velSide);
        if (n < 1e-9) return new double[] {0, 0, 0};
        return new double[] {velFront / n * SPEED, velHeight / n * SPEED, velSide / n * SPEED};
    }

    // Where the case comes out from the eye (blocks: front, height, side with + to the left, as CasingCreator wants it): the port
    // of the model (units: x left, y up, z forward) put where the hand holds the gun. `origin` is the model's origin from the eye
    // and `scale` the blocks a model unit comes to along front, height and side, as the stand measures the real render
    // (Gl54Probe: the origin and the unit axes in the world's view space; aimed, the view's field of view shrinks x and y).
    public double[] eyeOffset(double[] origin, double[] scale) {
        return new double[] {origin[0] + portZ * scale[0], origin[1] + portY * scale[1], origin[2] + portX * scale[2]};
    }
}
