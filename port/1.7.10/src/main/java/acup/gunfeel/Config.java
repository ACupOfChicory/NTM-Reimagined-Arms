package acup.gunfeel;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// port: FMLPaths -> PortPaths (copied from 26.2, see notes/ports/port-1.7.10-copied.md)

// config/ntm-gunfeel.properties, re-read whenever the file changes so values can be tuned in game.
public final class Config {
    public static boolean enabled = true;
    public static boolean tracers = true, bulletHoles = true;
    public static float tracerLength = 8F, tracerWidth = 0.02F, holeLife = 30F, holeSize = 0.28F, scorchSize = 0.9F;
    public static int holeMax = 500;
    public static int holeMaxMachines = 150;
    public static String quality = "high";
    public static float particleScale = 1F;
    // Hip-fire X position of the gun relative to NTM's: 1 = NTM default, 0 = screen centre.
    public static float hipX = 0.6F;
    public static String modelTilt = "";
    // Hip-fire Y position relative to NTM's (1 = unchanged).
    public static float hipY = 1.0F;
    public static float hipZ = 0.75F;
    // Camera recoil: degrees per unit of NTM recoil, curve duration, roll shake.
    public static float cameraScale = 0.55F;
    public static float cameraTime = 1.0F;
    public static float cameraShake = 1.0F;
    // recoil presets (RecoilPresets); cameraPreset empty = the same as kickPreset
    public static String kickPreset = "default", cameraPreset = "";
    // one movement of the preset replaced: a Spring "amount,attack,settle,bounce,delay,freq" or a curve "t:v t:v ...";
    // empty = the preset's
    public static String cameraPitch = "", cameraYaw = "", cameraRoll = "";
    public static String kickPitch = "", kickYaw = "", kickRoll = "", kickPosY = "", kickPosZ = "";
    // Gun model kick per shot, and how much of NTM's own RECOIL animation is kept.
    public static float modelKick = 1.0F;
    public static float adsRecoil = 0.7F;
    public static String armModel = "auto";
    public static float modelRotate = 1.0F;
    public static float modelSpeed = 1.0F;
    public static float ntmRecoilAnim = 0.3F;
    public static boolean hideOffhand = true;
    public static boolean cornerHud = true;
    public static String ammoMenu = "steel";
    public static int ammoMenuColor = 0xFF8C1A;
    // Both-hands grip. Each arm runs from its hand back along a fixed direction (camera frame: +X left,
    // +Y up, +Z forward) until it reaches the depth armReach*; the directions follow how a modern
    // shooter's arms come into view (right arm back past the cheek, left arm up from below).
    // Thickness keeps a vanilla arm's 4:18 proportion to the arm's length, so a far hand gets a
    // proportionally thicker arm, the way an arm looks when it is closer to the eye.
    public static boolean arms = true;
    public static float armScale = 0.8F;
    public static float armMaxScale = 4.0F;
    public static float armRollRight = -5F;
    public static float armRollLeft = -55F;
    public static float[] armDirRight = {-0.21F, 0.13F, -1.09F};
    public static float[] armDirLeft = {0.29F, -0.53F, -0.95F};
    public static float armReachRight = -0.81F;
    public static float armReachLeft = 0.41F;
    // How much the arms turn with the gun when an animation (inspect, reload) turns it away from rest.
    public static float armFollow = 1.0F;
    // Support (left) hand roll while firing/cycling; the gun turns inside the loose hand.
    public static float armFollowSupport = 0.25F;
    // During animations the hands slide along the gun toward the stock instead of the arms stretching.
    public static boolean armSlide = true;
    public static float armSlideTo = 0.05F;
    public static float armStretch = 0.1F;
    public static float armInspectDrop = 1.0F;
    // Model part a hand moves with (a pump, a lever), by its name in the model; empty = the gun's body.
    public static String armPartRight = "";
    public static String armPartLeft = "";
    // NTM animation bus (an angle, like Maresleg's FLIP) during which the left hand lets go of the gun
    public static String armReleaseLeft = "";
    public static float[] armReleaseMove = {4F, 0F, 0F};
    // the same for the right hand; it stays where it was by default (Henry inspect: the gun spins away from it)
    public static String armReleaseRight = "";
    public static float[] armReleaseMoveRight = {0F, 0F, 0F};
    // left hand moves around the held part in an animation: "RELOAD 0:-90 240:90 ...; INSPECT ..."
    public static String armLeftKeys = "";
    // right hand carries something NTM animates (Old Classic's new shells): "<part> <bus> <grab ms> x,y,z"
    public static String armRightCarry = "";
    public static String armRightCarryKeys = "";
    public static String armLeftCarry = "";
    public static String armLeftCarryReload = "", armRightCarryReload = "";
    public static String armLeftCarryKeys = "";
    public static String armLeftPath = "", armRightPath = "";
    public static String armLeftAdjustments = "", armRightAdjustments = "";
    public static float armPathMinLength = 0F;
    public static String armPathTimes = "";
    public static float armInspectReleaseMs = -1F;
    public static boolean instantSwitch = true;
    public static boolean bulletFx = true;
    // loudness of bullet hit sounds, times the rule's volume; above 1 the sound is doubled up (Minecraft stops at 1)
    public static float hitVolume = 2F;
    public static boolean holeCache = true;
    // arm thickness before armScale, the same on every gun (Maresleg / Old Classic); 0 = grows with the arm's length
    public static float armBaseRight = 1.70F;
    public static float armBaseLeft = 3.02F;
    // thickness of an arm loading shells (armRightCarry / armLeftCarry), on top of armScale
    public static float armLoadScaleRight = 1F;
    public static float armLoadScaleLeft = 1F;
    // longest arm, in vanilla arms of its thickness (1 = never longer than the vanilla 4:12 arm); shorter arms are
    // cut, not squashed; 0 = the old way, stretched or squashed to reach armReach
    public static float armLength = 1.0F;
    // turn of an arm around its own axis while it loads shells, degrees; NaN = the same as armRollRight/Left
    public static float armLoadRollRight = Float.NaN;
    public static float armLoadRollLeft = Float.NaN;
    // draw NTM's muzzle smoke rising up the screen whatever frame the renderer gives it (SPAS-12 sends it at the camera)
    public static boolean armCarryKeepAngle = false;
    public static boolean armPathBlend = false;
    public static float armSwitchMs = 0F;
    public static boolean armSlideNearest = false;
    public static String armLeftHide = "", armRightHide = "";
    public static float armAroundTopDrop = 0F;
    public static float armAroundShift = 0F;
    public static float armCarryTurnRight = 0F;
    public static float armCarryTurnLeft = 0F;
    public static float armAroundShiftReload = Float.NaN;
    public static float armAroundTopDropReload = Float.NaN;
    // width of NTM's muzzle smoke, 1 = as NTM draws it (SPAS-12's is 3x as wide as Maresleg's)
    public static float smokeWidth = 1F;
    public static float armScaleRight = 1F;
    public static float armScaleLeft = 1F;

    private static final String HEADER = """
            NTM Gun Feel. Saved values are re-read while the game runs.
            tracers: thin tracers in the NTM bullet's colours instead of NTM's wide ribbon (needs the mod on the server)
            tracerLength, tracerWidth: tracer length and half width, blocks
            bulletHoles: holes where bullets hit blocks; holeLife seconds, holeMax hits' holes at most on blocks, holeMaxMachines on NTM
              machines and doors (oldest first; one hit there leaves a piece per part, heavier to draw), holeSize blocks;
              scorchSize: the soot mark of explosive / incendiary bullets instead of a hole, blocks
              holes are cut to the hitbox and to the block's visible pixels; blocks drawn without a block model
              (machines) get none; "hole" lines in ntm-gunfeel-bullets.txt choose per block (glass: hitbox only)
            quality: low, medium, high or realism: sets holeMax, holeMaxMachines, holeLife and particleScale at once
              (realism = five times high's holes, kept five times longer); a key changed here by hand keeps its value
            particleScale: share of the particles of bullet hits, 1 = as the rules say
            hipX: gun X position in hip fire, 1 = NTM default, 0 = centre of the screen
            modelTilt: "pitch,yaw,roll" degrees, the gun turned around its hip-fire position (none when aimed), empty = none
            hipY: gun Y position in hip fire, 1 = NTM default
            hipZ: gun distance in hip fire, 1 = NTM default, less = closer and bigger
            cameraScale: camera kick, degrees per unit of NTM recoil (0.55: lever shotgun ~5.5 deg)
            cameraTime: duration of the camera kick, 1 = as the preset
            cameraShake: camera roll shake on each shot, 0 = off
            kickPreset: recoil preset of the gun model kick (ntmgunfeel-recoil.properties in the jar, your own in
              config/ntm-gunfeel-recoil.properties): default, pistol, revolver, smg, assault, rifle, lever, shotgun,
              sniper, mg, launcher; cameraPreset: the same for the camera, empty = as kickPreset
            cameraPitch, cameraYaw, cameraRoll: one camera movement instead of the preset's, empty = the preset's:
              "amount,attack,settle,bounce,delay,freq" (a push and a spring: rises to amount in attack seconds after
              delay, settles in settle seconds, swings back past zero by bounce x amount, or at freq Hz when freq > 0)
              or a curve "t:v t:v ..." (seconds:value); pitch and yaw amount 1 = NTM recoil x cameraScale, roll in
              degrees (times cameraShake)
            kickPitch, kickYaw, kickRoll, kickPosY, kickPosZ: one gun model movement instead of the preset's, same
              formats: muzzle up, sideways and roll in degrees, up and back in 1/16 gun-length units
            modelKick: strength of the gun model kick, 0 = off
            adsRecoil: multiplier of all recoil movements while aiming, 0..1; camera still respects the server minimum
            modelRotate: share of the rotation in the model kick (muzzle up, yaw, roll)
            modelSpeed: playback speed of the model kick, higher = shorter
            ntmRecoilAnim: share of NTM's own recoil animation kept, 1 = unchanged
            hideOffhand: hide the off-hand item while holding an NTM gun
            cornerHud: TaCZ-style ammo counter in the bottom-right corner instead of NTM's
            ammoMenu: look of the ammo menu (hold Y): steel, petals, bubbles, ntm, glass, arc
            ammoMenuColor: accent colour of the ammo menu, hex RRGGBB
            arms: draw the player's arms holding the gun with both hands
            armModel: auto = skin model, classic = four-pixel arms, slim = three-pixel arms;
              both models share arm adjustments; slim arms move one pixel toward their inner side
            armScale: arm thickness on top of the automatic one, 1 = vanilla arm proportions
            armMaxScale: upper limit of the automatic thickness
            armBaseRight, armBaseLeft: arm thickness before armScale, the same on every gun (as on Maresleg); 0 = automatic (grows with the arm's length)
            armLength: arm length in vanilla arms of its thickness (1 = the whole vanilla arm, never cut or stretched, like TaCZ); 0 = stretched/squashed to armReach
            armLoadScaleRight, armLoadScaleLeft: thickness of each arm while it loads shells into the gun, on top of armScale, 1 = unchanged
            armLoadRollRight, armLoadRollLeft: turn of each arm around its own axis while it loads shells, degrees, NaN = armRollRight/Left
            armAroundTopDrop: while armLeftKeys holds the left hand over the part (90), lower it toward the part by this many arm pixels; none at the side
            armAroundShift: while armLeftKeys moves the left hand round a part, move it left (out of the part) by this many arm pixels; none under the part (-90)
            armAroundShiftReload, armAroundTopDropReload: the same during reloads, NaN = armAroundShift / armAroundTopDrop
            armCarryTurnRight, armCarryTurnLeft: while a hand carries a shell, turn the arm around that point about the shell's long axis, degrees, + = the shoulder end to the right
            armLeftHide / armRightHide: "RELOAD 0-650; RELOAD_END 0-700" = the hand is not drawn in these ms of an animation (Henry: while the gun is down)
            armSlideNearest: a hand sliding along the gun (armSlide) stops at the reachable point nearest its grip, stepping along the gun (Henry)
            armSwitchMs: when an animation starts or ends the arm goes from where it was over this many ms (NTM shows an animation ~50 ms in), 0 = off
            armPathBlend: near the grip a hand on its path (armLeftPath/armRightPath) turns smoothly into its usual hold
            armCarryKeepAngle: a carrying hand keeps the arm exactly as it holds the gun and only moves it (Sexy: the bottle)
            smokeWidth: width of the muzzle smoke, 1 = as NTM draws it
            armScaleRight, armScaleLeft: thickness of each arm holding the gun on top of armScale (not while carrying shells), 1 = unchanged
            armRollRight, armRollLeft: turn of each arm around its own axis, degrees
            armDirRight, armDirLeft: direction from the hand to the shoulder: x (+ left), y (+ up), z (+ forward)
            armReachRight, armReachLeft: the arm goes back until this distance in front of the eye (- = behind); only for automatic thickness (armBase 0) or armLength 0
            armFollow: how much the arms roll with the gun in animations (inspect etc.), 0 = not at all, 1 = fully
            armFollowSupport: same for the left hand while firing and cycling (the gun turns inside the hand)
            armSlide: in animations the hands slide along the gun instead of the arms stretching
            armSlideTo: how far toward the stock a hand may slide, 0 = stock end, 1 = muzzle
            armInspectDrop: in inspect animations, how far the left hand drops on screen when the gun lies on its side, arm pixels
            armStretch: how much an arm may stretch in animations before the hand starts sliding, 0.1 = 10%
            armPartRight, armPartLeft: model part the hand moves with (a pump: SPAS-12 PumpGrip), empty = the gun's body
            armReleaseLeft: NTM animation bus (a turn angle) during which the left hand lets go (sawn-off Maresleg: FLIP), empty = never
            armReleaseMove: where the released left hand moves and back, arm pixels: x (+ left), y (+ up), z (+ forward)
            armReleaseRight, armReleaseMoveRight: the same for the right hand (default move 0,0,0 = it stays where it was)
            armLeftKeys: the left hand goes around the part it holds during NTM animations, per animation:
              "RELOAD 0:-90 240:90 1250:90 1500:-90; INSPECT ..." = milliseconds:angle, angle around the part
              (-90 under, 0 the gun's left side, 90 on top, 180 right side); empty = the hand stays put
            armRightCarryReload, armLeftCarryReload: used instead of armRightCarry / armLeftCarry during reloads, empty = the same
            armRightCarry, armLeftCarry: the hand carries something an NTM animation bus moves, "<part> <bus> <grab ms> x,y,z [dx,dy,dz]":
              the bus moves it within that part, the hand holds point x,y,z from side dx,dy,dz (default 0,0,-1 = behind;
              the side's length is the distance in half arm widths, 1 = the point on the hand's surface),
              and before the grab time it reaches for where the thing will be then (Old Classic: "BarrelShort SHELLS 900 0,0,-1.75")
              several buses and points split by | ("Barrel SHELL1|SHELL2 0 x,y,z|x,y,z"): the one whose bus moves now (Liberator)
            armRightCarryKeys, armLeftCarryKeys: how much the hand is on the carried thing, per animation:
              "RELOAD 650:0 900:1 1300:1 1500:0" = ms:weight; ms:weight/b takes the thing where the bus has it at b ms
              (walking b back retraces its path); ms:weight@x,y,z = a fixed spot instead; RELOAD/<bus> = only when that bus plays
            armLeftPath, armRightPath: the hand goes from part to part during an NTM animation, per animation:
              "RELOAD 0:grip 60:Lever@0.5,1,-7 200:Lever@0.5,1,-7 260:Hood@0,1,-5.3/0,1,0 ...; INSPECT ..." = ms:target,
              target = grip (its place on the gun) or <part>@x,y,z[/dx,dy,dz]: point x,y,z of that part as drawn now
              (moved by the animation), held from side dx,dy,dz (default 0,0,-1, length as in armLeftCarry);
              <part>#last / <part>#n = the last / n-th (from 0) time that part is drawn this frame (a belt drawn link by link).
              Between two keys the hand moves smoothly from one target to the other; used instead of armLeftCarry.
              Part!@x,y,z anchors a fixed inner hand-end corner to the point instead of the hand centre.
              >~sx,sy,sz keeps the shoulder at that camera-space offset from the moving target (whole-arm travel).
              Append ^degrees to a target to key the wrist roll relative to its normal hold.
              ...[/dx,dy,dz]>sx,sy,sz moves the shoulder end of the arm by sx,sy,sz in view space (y up, z back; x on the left arm: + = left):
              turns the arm (Sexy's box: the shoulder end to the left, ">0.65,0,0", so the arm misses the gun)
            armPathMinLength: an arm on its path (armLeftPath) shows at least this many arm pixels (0 = cut wherever
              the reach plane cuts it; a hand right in front of the eye then shows only a pixel or two)
            armPathTimes: named times for armLeftPath / armRightPath, "release=2350,grab=1400": a key's ms may then be
              a name or name+ms / name-ms ("release:Magazine@... release+300:Gun@..."), so a moment is set in one place
            instantSwitch: switching to or from an NTM gun shows the new slot at once: the old gun is not drawn up
              until the next tick, and the new gun starts its draw animation without waiting for the server
            bulletFx: particles and sounds of bullets (block hits, a bullet whizzing past, the in-flight sound) by the
              rules in config/ntm-gunfeel-bullets.txt
            hitVolume: loudness of bullet hit sounds, times each hit rule's volume (1 = as the rules say, 0 = silent, up to 4;
              above 1 the same sound is played several times at once, Minecraft goes no louder than 1; explosive rounds
              no louder than 1, their blast is loud enough). /gunfeel hitvolume
            holeCache: bullet holes kept on the graphics card in batches, rebuilt only when a hole comes or goes, not
              every frame (false = the old way, every hole sent again each frame)
            Every value except enabled can be overridden per gun in config/ntm-gunfeel-guns.properties
            (hbm\\:gun_maresleg.armRollLeft = -55) or with /gunfeel set <key> <value> for the held gun.
            Hand positions per gun: <gun>.right / <gun>.left there, or /gunfeel grip""";

    // quality presets: keys -> value; high is the built-in defaults
    static final java.util.Map<String, java.util.Map<String, String>> PRESETS = java.util.Map.of(
            "low", java.util.Map.of("holeMax", "100", "holeMaxMachines", "30", "holeLife", "10", "particleScale", "0.25"),
            "medium", java.util.Map.of("holeMax", "250", "holeMaxMachines", "75", "holeLife", "20", "particleScale", "0.5"),
            "high", java.util.Map.of("holeMax", "500", "holeMaxMachines", "150", "holeLife", "30", "particleScale", "1"),
            "realism", java.util.Map.of("holeMax", "2500", "holeMaxMachines", "750", "holeLife", "150", "particleScale", "1"));

    private static final Properties GLOBAL = new Properties();
    private static final Properties BUILTIN = snapshot();
    private static String selected;

    // the gun whose values are in effect (null = none)
    public static String selectedGun() {
        return selected;
    }
    private static int selectedVersion = -1;
    private static Path file;
    private static long stamp = Long.MIN_VALUE;
    private static long nextCheck;

    private Config() {
    }

    public static void poll() {
        long now = System.currentTimeMillis();
        if (now < nextCheck) return;
        nextCheck = now + 1000;
        Grips.poll();
        try {
            if (file == null) file = PortPaths.config().resolve("ntm-gunfeel.properties");
            if (!Files.exists(file)) {
                save();
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == stamp) return;
            stamp = modified;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
            }
            GLOBAL.clear();
            GLOBAL.putAll(p);
            selected = null;
            GunFeel.LOGGER.info("Loaded {}", file);
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("Could not read {}", file, e);
        }
    }

    // Values in effect for one gun: built-in defaults, then the global file, then the gun's overrides.
    // Called every frame with the held gun; re-applies only when something changed.
    public static void select(String gun) {
        int v = Grips.version();
        if (java.util.Objects.equals(gun, selected) && v == selectedVersion) return;
        selected = gun;
        selectedVersion = v;
        Properties p = layered(BUILTIN, GLOBAL);
        boolean on = bool(p, "enabled", true);
        if (gun != null) p.putAll(Grips.overrides(gun));
        apply(p);
        enabled = on;
        if (TestLog.client) {
            String q = quality + " " + holeMax + " " + holeMaxMachines + " " + holeLife + " " + particleScale;
            if (!q.equals(loggedQuality)) {
                loggedQuality = q;
                logQuality();
            }
        }
    }

    private static String loggedQuality;

    // the test log: the preset and what it set, and the preset keys the global file holds changed by hand
    static void logQuality() {
        java.util.Map<String, String> preset = PRESETS.get(quality);
        java.util.List<String> byHand = new java.util.ArrayList<>();
        if (preset != null) for (String k : preset.keySet()) {
            String mine = GLOBAL.getProperty(k);
            if (mine != null && !same(mine, BUILTIN.getProperty(k))) byHand.add(k);
        }
        TestLog.c(-1, "quality", "preset", quality, "holeMax", holeMax, "holeMaxMachines", holeMaxMachines, "holeLife", holeLife,
                "particleScale", particleScale, "byHand", String.join(",", byHand));
    }

    // built-in defaults, the quality preset, then the global file; a preset key the file holds at its built-in value
    // (the file is written with every key) takes the preset's value, one changed by hand keeps it
    static Properties layered(Properties builtin, Properties global) {
        Properties p = new Properties();
        p.putAll(builtin);
        p.putAll(global);
        java.util.Map<String, String> preset = PRESETS.get(p.getProperty("quality", "high").trim().toLowerCase(java.util.Locale.ROOT));
        if (preset != null) {
            preset.forEach((k, v) -> {
                String mine = global.getProperty(k);
                if (mine == null || same(mine, builtin.getProperty(k))) p.setProperty(k, v);
            });
        }
        return p;
    }

    private static boolean same(String a, String b) {
        if (b == null) return false;
        try {
            return Float.parseFloat(a.trim()) == Float.parseFloat(b.trim());
        } catch (NumberFormatException e) {
            return a.trim().equals(b.trim());
        }
    }

    // count particles at share `scale`, rounded up or down at random so the average stays count * scale
    public static int scaledCount(int count, float scale, double roll) {
        return (int) Math.floor(count * Math.max(0F, scale) + roll);
    }

    public static boolean isKey(String key) {
        return BUILTIN.containsKey(key);
    }

    public static java.util.Set<String> keys() {
        return new java.util.TreeSet<>(BUILTIN.stringPropertyNames());
    }

    // the mod's own default, before the player's global file
    public static String codeValue(String key) {
        return BUILTIN.getProperty(key);
    }

    public static String globalValue(String key) {
        return GLOBAL.getProperty(key, BUILTIN.getProperty(key));
    }

    private static void apply(Properties p) {
        enabled = bool(p, "enabled", enabled);
        hipX = num(p, "hipX", hipX);
        tracers = bool(p, "tracers", tracers);
        bulletHoles = bool(p, "bulletHoles", bulletHoles);
        tracerLength = num(p, "tracerLength", tracerLength);
        tracerWidth = num(p, "tracerWidth", tracerWidth);
        holeLife = num(p, "holeLife", holeLife);
        holeSize = num(p, "holeSize", holeSize);
        scorchSize = num(p, "scorchSize", scorchSize);
        holeMax = Math.round(num(p, "holeMax", holeMax));
        holeMaxMachines = Math.round(num(p, "holeMaxMachines", holeMaxMachines));
        quality = p.getProperty("quality", quality).trim();
        particleScale = num(p, "particleScale", particleScale);
        modelTilt = p.getProperty("modelTilt", modelTilt).trim();
        hipY = num(p, "hipY", hipY);
        hipZ = num(p, "hipZ", hipZ);
        cameraScale = num(p, "cameraScale", cameraScale);
        cameraTime = Math.max(0.05F, num(p, "cameraTime", cameraTime));
        cameraShake = num(p, "cameraShake", cameraShake);
        kickPreset = p.getProperty("kickPreset", kickPreset).trim();
        cameraPreset = p.getProperty("cameraPreset", cameraPreset).trim();
        cameraPitch = p.getProperty("cameraPitch", cameraPitch).trim();
        cameraYaw = p.getProperty("cameraYaw", cameraYaw).trim();
        cameraRoll = p.getProperty("cameraRoll", cameraRoll).trim();
        kickPitch = p.getProperty("kickPitch", kickPitch).trim();
        kickYaw = p.getProperty("kickYaw", kickYaw).trim();
        kickRoll = p.getProperty("kickRoll", kickRoll).trim();
        kickPosY = p.getProperty("kickPosY", kickPosY).trim();
        kickPosZ = p.getProperty("kickPosZ", kickPosZ).trim();
        modelKick = num(p, "modelKick", modelKick);
        adsRecoil = Math.clamp(num(p, "adsRecoil", adsRecoil), 0F, 1F);
        armModel = p.getProperty("armModel", "auto");
        modelRotate = num(p, "modelRotate", modelRotate);
        modelSpeed = Math.max(0.05F, num(p, "modelSpeed", modelSpeed));
        ntmRecoilAnim = num(p, "ntmRecoilAnim", ntmRecoilAnim);
        hideOffhand = bool(p, "hideOffhand", hideOffhand);
        cornerHud = bool(p, "cornerHud", cornerHud);
        ammoMenu = p.getProperty("ammoMenu", ammoMenu).trim().toLowerCase(java.util.Locale.ROOT);
        try {
            ammoMenuColor = Integer.parseInt(p.getProperty("ammoMenuColor", String.format("%06x", ammoMenuColor)).trim().replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            ammoMenuColor = 0xFF8C1A;
        }
        arms = bool(p, "arms", arms);
        armScale = num(p, "armScale", armScale);
        armRollRight = num(p, "armRollRight", armRollRight);
        armRollLeft = num(p, "armRollLeft", armRollLeft);
        armMaxScale = num(p, "armMaxScale", armMaxScale);
        armDirRight = vec(p, "armDirRight", armDirRight);
        armDirLeft = vec(p, "armDirLeft", armDirLeft);
        armReachRight = num(p, "armReachRight", armReachRight);
        armReachLeft = num(p, "armReachLeft", armReachLeft);
        armFollow = num(p, "armFollow", armFollow);
        armFollowSupport = num(p, "armFollowSupport", armFollowSupport);
        armSlide = bool(p, "armSlide", armSlide);
        armSlideTo = num(p, "armSlideTo", armSlideTo);
        armStretch = num(p, "armStretch", armStretch);
        armInspectDrop = num(p, "armInspectDrop", armInspectDrop);
        armPartRight = p.getProperty("armPartRight", armPartRight).trim();
        armPartLeft = p.getProperty("armPartLeft", armPartLeft).trim();
        armReleaseLeft = p.getProperty("armReleaseLeft", armReleaseLeft).trim();
        armReleaseMove = vec(p, "armReleaseMove", armReleaseMove);
        armReleaseRight = p.getProperty("armReleaseRight", armReleaseRight).trim();
        armReleaseMoveRight = vec(p, "armReleaseMoveRight", armReleaseMoveRight);
        armLeftKeys = p.getProperty("armLeftKeys", armLeftKeys).trim();
        armRightCarry = p.getProperty("armRightCarry", armRightCarry).trim();
        armRightCarryKeys = p.getProperty("armRightCarryKeys", armRightCarryKeys).trim();
        armLeftCarry = p.getProperty("armLeftCarry", armLeftCarry).trim();
        armLeftCarryReload = p.getProperty("armLeftCarryReload", armLeftCarryReload).trim();
        armRightCarryReload = p.getProperty("armRightCarryReload", armRightCarryReload).trim();
        armLeftCarryKeys = p.getProperty("armLeftCarryKeys", armLeftCarryKeys).trim();
        armLeftPath = p.getProperty("armLeftPath", armLeftPath).trim();
        armRightPath = p.getProperty("armRightPath", armRightPath).trim();
        armLeftAdjustments = p.getProperty("armLeftAdjustments", armLeftAdjustments).trim();
        armRightAdjustments = p.getProperty("armRightAdjustments", armRightAdjustments).trim();
        armPathMinLength = num(p, "armPathMinLength", armPathMinLength);
        armPathTimes = p.getProperty("armPathTimes", armPathTimes).trim();
        armInspectReleaseMs = num(p, "armInspectReleaseMs", -1F);
        instantSwitch = bool(p, "instantSwitch", instantSwitch);
        bulletFx = bool(p, "bulletFx", bulletFx);
        hitVolume = Math.max(0F, Math.min(4F, num(p, "hitVolume", hitVolume)));
        holeCache = bool(p, "holeCache", holeCache);
        armBaseRight = num(p, "armBaseRight", armBaseRight);
        armBaseLeft = num(p, "armBaseLeft", armBaseLeft);
        armLoadScaleRight = num(p, "armLoadScaleRight", armLoadScaleRight);
        armLoadScaleLeft = num(p, "armLoadScaleLeft", armLoadScaleLeft);
        armLength = num(p, "armLength", armLength);
        armLoadRollRight = num(p, "armLoadRollRight", armLoadRollRight);
        armLoadRollLeft = num(p, "armLoadRollLeft", armLoadRollLeft);
        armCarryKeepAngle = bool(p, "armCarryKeepAngle", armCarryKeepAngle);
        armPathBlend = bool(p, "armPathBlend", armPathBlend);
        armSwitchMs = num(p, "armSwitchMs", armSwitchMs);
        armSlideNearest = bool(p, "armSlideNearest", armSlideNearest);
        armLeftHide = p.getProperty("armLeftHide", armLeftHide).trim();
        armRightHide = p.getProperty("armRightHide", armRightHide).trim();
        armAroundTopDrop = num(p, "armAroundTopDrop", armAroundTopDrop);
        armAroundShift = num(p, "armAroundShift", armAroundShift);
        armCarryTurnRight = num(p, "armCarryTurnRight", armCarryTurnRight);
        armCarryTurnLeft = num(p, "armCarryTurnLeft", armCarryTurnLeft);
        armAroundShiftReload = num(p, "armAroundShiftReload", armAroundShiftReload);
        armAroundTopDropReload = num(p, "armAroundTopDropReload", armAroundTopDropReload);
        smokeWidth = num(p, "smokeWidth", smokeWidth);
        armScaleRight = num(p, "armScaleRight", armScaleRight);
        armScaleLeft = num(p, "armScaleLeft", armScaleLeft);
    }

    private static Properties snapshot() {
        Properties p = new Properties() {
            // keep a stable, readable order
            @Override
            public synchronized java.util.Set<java.util.Map.Entry<Object, Object>> entrySet() {
                java.util.Set<java.util.Map.Entry<Object, Object>> s = new java.util.LinkedHashSet<>();
                super.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().toString())).forEach(s::add);
                return s;
            }
        };
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("hipX", Float.toString(hipX));
        p.setProperty("tracers", Boolean.toString(tracers));
        p.setProperty("bulletHoles", Boolean.toString(bulletHoles));
        p.setProperty("tracerLength", Float.toString(tracerLength));
        p.setProperty("tracerWidth", Float.toString(tracerWidth));
        p.setProperty("holeLife", Float.toString(holeLife));
        p.setProperty("holeSize", Float.toString(holeSize));
        p.setProperty("scorchSize", Float.toString(scorchSize));
        p.setProperty("holeMax", Integer.toString(holeMax));
        p.setProperty("holeMaxMachines", Integer.toString(holeMaxMachines));
        p.setProperty("quality", quality);
        p.setProperty("particleScale", Float.toString(particleScale));
        p.setProperty("modelTilt", modelTilt);
        p.setProperty("hipY", Float.toString(hipY));
        p.setProperty("hipZ", Float.toString(hipZ));
        p.setProperty("cameraScale", Float.toString(cameraScale));
        p.setProperty("cameraTime", Float.toString(cameraTime));
        p.setProperty("cameraShake", Float.toString(cameraShake));
        p.setProperty("kickPreset", kickPreset);
        p.setProperty("cameraPreset", cameraPreset);
        p.setProperty("cameraPitch", cameraPitch);
        p.setProperty("cameraYaw", cameraYaw);
        p.setProperty("cameraRoll", cameraRoll);
        p.setProperty("kickPitch", kickPitch);
        p.setProperty("kickYaw", kickYaw);
        p.setProperty("kickRoll", kickRoll);
        p.setProperty("kickPosY", kickPosY);
        p.setProperty("kickPosZ", kickPosZ);
        p.setProperty("modelKick", Float.toString(modelKick));
        p.setProperty("adsRecoil", Float.toString(adsRecoil));
        p.setProperty("armModel", armModel);
        p.setProperty("modelRotate", Float.toString(modelRotate));
        p.setProperty("modelSpeed", Float.toString(modelSpeed));
        p.setProperty("ntmRecoilAnim", Float.toString(ntmRecoilAnim));
        p.setProperty("hideOffhand", Boolean.toString(hideOffhand));
        p.setProperty("cornerHud", Boolean.toString(cornerHud));
        p.setProperty("ammoMenu", ammoMenu);
        p.setProperty("ammoMenuColor", String.format("%06x", ammoMenuColor));
        p.setProperty("arms", Boolean.toString(arms));
        p.setProperty("armScale", Float.toString(armScale));
        p.setProperty("armRollRight", Float.toString(armRollRight));
        p.setProperty("armRollLeft", Float.toString(armRollLeft));
        p.setProperty("armMaxScale", Float.toString(armMaxScale));
        p.setProperty("armDirRight", armDirRight[0] + "," + armDirRight[1] + "," + armDirRight[2]);
        p.setProperty("armDirLeft", armDirLeft[0] + "," + armDirLeft[1] + "," + armDirLeft[2]);
        p.setProperty("armReachRight", Float.toString(armReachRight));
        p.setProperty("armReachLeft", Float.toString(armReachLeft));
        p.setProperty("armFollow", Float.toString(armFollow));
        p.setProperty("armFollowSupport", Float.toString(armFollowSupport));
        p.setProperty("armSlide", Boolean.toString(armSlide));
        p.setProperty("armSlideTo", Float.toString(armSlideTo));
        p.setProperty("armStretch", Float.toString(armStretch));
        p.setProperty("armInspectDrop", Float.toString(armInspectDrop));
        p.setProperty("armPartRight", armPartRight);
        p.setProperty("armPartLeft", armPartLeft);
        p.setProperty("armReleaseLeft", armReleaseLeft);
        p.setProperty("armReleaseMove", armReleaseMove[0] + "," + armReleaseMove[1] + "," + armReleaseMove[2]);
        p.setProperty("armReleaseRight", armReleaseRight);
        p.setProperty("armReleaseMoveRight", armReleaseMoveRight[0] + "," + armReleaseMoveRight[1] + "," + armReleaseMoveRight[2]);
        p.setProperty("armLeftKeys", armLeftKeys);
        p.setProperty("armRightCarry", armRightCarry);
        p.setProperty("armRightCarryKeys", armRightCarryKeys);
        p.setProperty("armLeftCarry", armLeftCarry);
        p.setProperty("armLeftCarryReload", armLeftCarryReload);
        p.setProperty("armRightCarryReload", armRightCarryReload);
        p.setProperty("armLeftCarryKeys", armLeftCarryKeys);
        p.setProperty("armLeftPath", armLeftPath);
        p.setProperty("armRightPath", armRightPath);
        p.setProperty("armLeftAdjustments", armLeftAdjustments);
        p.setProperty("armRightAdjustments", armRightAdjustments);
        p.setProperty("armPathMinLength", Float.toString(armPathMinLength));
        p.setProperty("armPathTimes", armPathTimes);
        p.setProperty("armInspectReleaseMs", Float.toString(armInspectReleaseMs));
        p.setProperty("instantSwitch", Boolean.toString(instantSwitch));
        p.setProperty("bulletFx", Boolean.toString(bulletFx));
        p.setProperty("hitVolume", Float.toString(hitVolume));
        p.setProperty("holeCache", Boolean.toString(holeCache));
        p.setProperty("armBaseRight", Float.toString(armBaseRight));
        p.setProperty("armBaseLeft", Float.toString(armBaseLeft));
        p.setProperty("armLoadScaleRight", Float.toString(armLoadScaleRight));
        p.setProperty("armLoadScaleLeft", Float.toString(armLoadScaleLeft));
        p.setProperty("armLength", Float.toString(armLength));
        p.setProperty("armLoadRollRight", Float.toString(armLoadRollRight));
        p.setProperty("armLoadRollLeft", Float.toString(armLoadRollLeft));
        p.setProperty("armCarryKeepAngle", Boolean.toString(armCarryKeepAngle));
        p.setProperty("armPathBlend", Boolean.toString(armPathBlend));
        p.setProperty("armSwitchMs", Float.toString(armSwitchMs));
        p.setProperty("armSlideNearest", Boolean.toString(armSlideNearest));
        p.setProperty("armLeftHide", armLeftHide);
        p.setProperty("armRightHide", armRightHide);
        p.setProperty("armAroundTopDrop", Float.toString(armAroundTopDrop));
        p.setProperty("armAroundShift", Float.toString(armAroundShift));
        p.setProperty("armCarryTurnRight", Float.toString(armCarryTurnRight));
        p.setProperty("armCarryTurnLeft", Float.toString(armCarryTurnLeft));
        p.setProperty("armAroundShiftReload", Float.toString(armAroundShiftReload));
        p.setProperty("armAroundTopDropReload", Float.toString(armAroundTopDropReload));
        p.setProperty("smokeWidth", Float.toString(smokeWidth));
        p.setProperty("armScaleRight", Float.toString(armScaleRight));
        p.setProperty("armScaleLeft", Float.toString(armScaleLeft));
        return p;
    }

    // one key of config/ntm-gunfeel.properties changed in the file (for commands); the file is read back on the next poll
    public static void setInFile(String key, String value) throws IOException {
        if (file == null) file = PortPaths.config().resolve("ntm-gunfeel.properties");
        if (!Files.exists(file)) save();
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        }
        p.setProperty(key, value);
        try (Writer w = Files.newBufferedWriter(file)) {
            p.store(w, HEADER);
        }
    }

    private static void save() throws IOException {
        Properties p = snapshot();
        try (Writer w = Files.newBufferedWriter(file)) {
            p.store(w, HEADER);
        }
    }

    private static float num(Properties p, String key, float def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float[] vec(Properties p, String key, float[] def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        String[] parts = v.split(",");
        if (parts.length != 3) return def;
        try {
            return new float[] {Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()), Float.parseFloat(parts[2].trim())};
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }
}
