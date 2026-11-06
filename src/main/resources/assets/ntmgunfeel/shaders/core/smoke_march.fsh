#version 330

#moj_import <minecraft:projection.glsl>
#moj_import <ntmgunfeel:smoke_info.glsl>

uniform sampler2D SmokeAtlas;
uniform sampler2D SceneDepth;

in vec3 vPos;
flat in int vCloud;

out vec4 fragColor;

// the atlas (SmokeAtlas): a cloud a row of 27x27 tiles, a tile a layer of cells with a border of open, empty air
const float TILE = 27.0;
const float LAYERS = 13.0;
const float CELLS_XZ = 25.0;

// one layer of a cloud, filtered between the cell centres; c: cell coordinates (a cell's centre at its index)
vec4 layer(vec2 c, float y, float slot) {
    if (y < 0.0 || y > LAYERS - 1.0) return vec4(0.0, 0.0, -1.0, 1.0);
    c = clamp(c, vec2(-1.0), vec2(CELLS_XZ));
    vec2 uv = vec2(y * TILE + c.x + 1.5, slot * TILE + c.y + 1.5) * Atlas.xy;
    return texture(SmokeAtlas, uv);
}

// density and light at a point of the grid (in cells); the closed cells' share is left out, so a wall neither thins
// the smoke next to it nor lets it through
vec2 smokeAt(vec3 g, float slot) {
    vec3 c = g - 0.5;
    float y0 = floor(c.y);
    float fy = c.y - y0;
    vec4 a = layer(c.xz, y0, slot);
    vec4 b = layer(c.xz, y0 + 1.0, slot);
    float da = mix(a.r, a.g, Atlas.z), db = mix(b.r, b.g, Atlas.z);
    float open = mix(a.a, b.a, fy);
    float d = open > 0.02 ? mix(da, db, fy) / open * 1.25 : 0.0;
    float light = a.b < 0.0 ? b.b : (b.b < 0.0 ? a.b : mix(a.b, b.b, fy));
    return vec2(d, light);
}

float hash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

float valueNoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float x00 = mix(hash(i), hash(i + vec3(1, 0, 0)), f.x);
    float x10 = mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x);
    float x01 = mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x);
    float x11 = mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x);
    return mix(mix(x00, x10, f.y), mix(x01, x11, f.y), f.z);
}

void main() {
    int at = vCloud * 4;
    vec4 origin = Clouds[at];
    vec4 lo = Clouds[at + 1];
    vec4 hi = Clouds[at + 2];
    vec4 colour = Clouds[at + 3];
    vec3 dir = normalize(vPos);
    // the direction with no component nearer zero than 1e-6, the sign kept (a zero counts as positive); mix with a
    // float weight, as #version 330 has it (the bvec3 form is GLSL 4.50)
    vec3 safe = max(abs(dir), vec3(1e-6)) * mix(vec3(1.0), vec3(-1.0), vec3(lessThan(dir, vec3(0.0))));
    vec3 inv = 1.0 / safe;
    vec3 ta = (origin.xyz + lo.xyz) * inv;
    vec3 tb = (origin.xyz + hi.xyz) * inv;
    vec3 tNear = min(ta, tb);
    vec3 tFar = max(ta, tb);
    float t0 = max(max(tNear.x, tNear.y), tNear.z);
    float t1 = min(min(tFar.x, tFar.y), tFar.z);
    // only the box's far side marches: a near side's fragment would count the same ray twice
    if (length(vPos) < t1 - 0.02 - t1 * 1e-4) discard;
    t0 = max(t0, 0.05);
    // where the world is along this ray: the copied depth (reversed: 0 = nothing, the sky) through the projection
    float raw = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy * vec2(Screen.x, Extra.z)), 0).r;
    if (raw > 0.0) {
        float s = Screen.y > 0.5 ? raw : raw * 2.0 - 1.0;
        mat4 pv = ProjMat * View;
        vec4 c0 = pv[3];
        vec4 c1 = pv * vec4(dir, 0.0);
        float den = c1.z - s * c1.w;
        if (abs(den) > 1e-9) {
            float tScene = (s * c0.w - c0.z) / den;
            if (tScene > 0.0) t1 = min(t1, tScene);
        }
    }
    if (t1 <= t0) discard;
    float len = t1 - t0;
    int steps = int(clamp(ceil(len / Atlas.w), 2.0, Screen.w));
    float dt = len / float(steps);
    // interleaved gradient noise: the first step's offset, so the steps leave no bands
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float t = t0 + dt * jitter;
    float through = 1.0;
    vec3 light = vec3(0.0);
    for (int i = 0; i < 128; i++) {
        if (i >= steps || through < 0.01) break;
        vec3 g = dir * t - origin.xyz;
        vec2 dl = smokeAt(g, origin.w);
        if (dl.x > 0.004) {
            vec3 q = g * 0.9 + vec3(hi.w, Screen.z * 0.12, hi.w * 0.7 - Screen.z * 0.05);
            float n = valueNoise(q);
            if (Extra.y > 0.5) n = n * 0.65 + valueNoise(q * 2.3 + 17.0) * 0.35;
            float d = max(0.0, dl.x * (0.45 + 1.1 * n) - 0.04);
            float a = 1.0 - exp(-lo.w * d * dt);
            vec3 c = colour.rgb * (0.10 + 0.90 * dl.y) * (0.88 + 0.24 * n);
            light += through * a * c;
            through *= 1.0 - a;
        }
        t += dt;
    }
    if (through > 0.999) discard;
    fragColor = vec4(light, 1.0 - through);
}
