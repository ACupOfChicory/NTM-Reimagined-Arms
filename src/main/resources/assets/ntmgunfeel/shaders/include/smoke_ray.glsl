#version 330

// Needs projection.glsl and smoke_info.glsl imported before it.

// Whether every group's box along the ray through a point of the screen ends at least margin blocks before the
// surface at depth raw there (reversed depth: 0 is the sky, no surface). Then the smoke in front of that surface is
// all the smoke on the ray: the march in front of glass and water is the march to the world (smoke_march.fsh FRONT
// leaves it out, smoke_composite.fsh takes SmokeFull for it). The sky's own clouds write depth too, so the smoke in
// front of them is not marched twice. Off (false) when Extra.w is 0.
// pos: the point in the depth's pixels; size: the depth's size; pv: ProjMat * View.
bool smokeEndsBefore(vec2 pos, vec2 size, float raw, float margin, mat4 pv) {
    if (Extra.w < 0.5) return false;
    vec4 p = InvProjView * vec4(pos / size * 2.0 - 1.0, 0.5, 1.0);
    if (abs(p.w) < 1e-12) return false;
    vec3 dir = normalize(p.xyz / p.w);
    float surface = 3.0e38;
    if (raw > 0.0) {
        float s = Screen.y > 0.5 ? raw : raw * 2.0 - 1.0;
        vec4 c0 = pv[3];
        vec4 c1 = pv * vec4(dir, 0.0);
        float den = c1.z - s * c1.w;
        if (abs(den) <= 1e-9) return false;
        surface = (s * c0.w - c0.z) / den;
        if (surface <= 0.0) return false;
    }
    vec3 safe = max(abs(dir), vec3(1e-6)) * mix(vec3(1.0), vec3(-1.0), vec3(lessThan(dir, vec3(0.0))));
    vec3 inv3 = 1.0 / safe;
    float far = 0.0;
    int n = int(Extra.x + 0.5);
    for (int g = 0; g < 16; g++) {
        if (g >= n) break;
        vec3 ta = Groups[g * 2].xyz * inv3;
        vec3 tb = Groups[g * 2 + 1].xyz * inv3;
        vec3 lo = min(ta, tb);
        vec3 hi = max(ta, tb);
        float t0 = max(max(lo.x, lo.y), lo.z);
        float t1 = min(min(hi.x, hi.y), hi.z);
        if (t1 > max(t0, 0.0)) far = max(far, t1);
    }
    return surface >= far + margin;
}
