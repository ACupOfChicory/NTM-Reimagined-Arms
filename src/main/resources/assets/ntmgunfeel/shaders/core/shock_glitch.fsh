#version 330

// The HUD glitched by a shock (acup.gunfeel.shock.client.ShockHud): HudSampler is the HUD alone, drawn into a cleared
// texture (premultiplied). Rows slide sideways, cells take another cell's picture, thin lines tear far and light up,
// some rows go blocky, red and blue come apart, the whole flickers, drops out now and then, and is shaken.
uniform sampler2D HudSampler;

layout(std140) uniform ShockInfo {
    vec4 Info;  // x: seconds, y: strength 0 to 1, z: the glitch frame's seed, w: width / height
    vec4 Shake; // xy: the HUD's shift (screen shares), zw: the screen in pixels
};

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float hash1(float x) {
    return hash(vec2(x, x * 1.7 + 3.1));
}

vec4 tap(vec2 uv) {
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return vec4(0.0);
    return texture(HudSampler, uv);
}

void main() {
    float t = Info.x, s = Info.y, seed = Info.z;
    vec2 uv = texCoord - Shake.xy;
    // rows of random heights each glitch frame; some slide sideways
    float rows = mix(6.0, 28.0, hash1(seed * 1.3));
    float row = floor(uv.y * rows);
    float h = hash(vec2(row, seed));
    if (h < 0.45 * s) uv.x += (hash(vec2(row, seed + 7.0)) - 0.5) * 0.22 * s;
    // cells: some take another cell's picture
    vec2 cells = vec2(16.0, 9.0) * mix(1.0, 3.0, hash1(seed + 2.0));
    vec2 cell = floor(uv * cells);
    if (hash(cell + seed * 3.1) < 0.12 * s) uv += (vec2(hash(cell + 1.3 + seed), hash(cell + 2.9 + seed)) - 0.5) * 0.12 * s;
    // thin torn lines: a row a few pixels high jerked far
    float line = floor(uv.y * Shake.w / 3.0);
    float torn = step(1.0 - 0.07 * s, hash(vec2(line, floor(t * 30.0))));
    uv.x += torn * (hash(vec2(line, seed)) - 0.5) * 0.4 * s;
    // some rows blocky
    if (hash(vec2(row, seed + 11.0)) < 0.2 * s) {
        vec2 px = Shake.zw / mix(6.0, 14.0, hash1(seed + row));
        uv = (floor(uv * px) + 0.5) / px;
    }
    // red one way, blue the other, more in a row that slid
    float split = (0.003 + 0.016 * s * (0.5 + h)) * (hash1(seed + 5.0) < 0.5 ? 1.0 : -1.0);
    vec4 r = tap(uv + vec2(split, 0.0));
    vec4 g = tap(uv);
    vec4 b = tap(uv - vec2(split, split * 0.3 * Info.w));
    vec4 c = vec4(r.r, g.g, b.b, max(max(r.a, g.a), b.a));
    // the torn lines lit, cyan
    c.rgb += torn * c.a * vec3(0.2, 0.6, 0.8) * s;
    // noise on what is drawn
    c.rgb += (hash(texCoord * Shake.zw + fract(t) * 100.0) - 0.5) * 0.18 * s * c.a;
    // flicker: dimmer at random frames, gone now and then
    float f = hash1(seed + 9.0);
    float flick = mix(1.0, 0.6 + 0.4 * f, s);
    if (f < 0.07 * s) flick = 0.15;
    fragColor = clamp(c * flick, 0.0, 1.0);
}
