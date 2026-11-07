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
        double originSide, double velFront, double velHeight, double velSide, float pitch, float yaw) {
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
        return new Gl54Casing(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0F, 0F);
    }

    public static Gl54Casing parse(Reader reader) {
        JsonObject j = JsonParser.parseReader(reader).getAsJsonObject();
        if (!j.has("present") || !j.get("present").getAsBoolean()) return none();
        JsonObject p = j.getAsJsonObject("position"), o = j.getAsJsonObject("origin"), v = j.getAsJsonObject("velocity"), t = j.getAsJsonObject("turn");
        return new Gl54Casing(true, j.get("tick").getAsInt(),
                p.get("front").getAsDouble(), p.get("height").getAsDouble(), p.get("side").getAsDouble(),
                o.get("front").getAsDouble(), o.get("height").getAsDouble(), o.get("side").getAsDouble(),
                v.get("front").getAsDouble(), v.get("height").getAsDouble(), v.get("side").getAsDouble(),
                t.get("pitch").getAsFloat(), t.get("yaw").getAsFloat());
    }

    // Where the case comes out from the eye (blocks: front, height, side with + to the left, as CasingCreator wants it):
    // `gunOrigin` is where the model's origin is from the eye, `blocksPerMetre` how big the gun is in hand.
    public double[] eyeOffset(double[] gunOrigin, double blocksPerMetre) {
        return new double[] {gunOrigin[0] + (front - originFront) * blocksPerMetre,
                gunOrigin[1] + (height - originHeight) * blocksPerMetre,
                gunOrigin[2] + (side - originSide) * blocksPerMetre};
    }
}
