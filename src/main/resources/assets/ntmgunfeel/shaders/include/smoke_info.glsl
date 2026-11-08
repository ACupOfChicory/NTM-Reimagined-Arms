#version 330

// what SmokeRender writes each frame (acup.gunfeel.smoke.client.SmokeRender)
layout(std140) uniform SmokeInfo {
    mat4 View;      // the level's view rotation (the camera at 0)
    vec4 Screen;    // x: depth pixels per smoke pixel across, y: 1 when depth goes 0..1, z: seconds, w: most steps
    vec4 Atlas;     // xy: 1 / atlas size, z: partial tick, w: step length in blocks
    vec4 Extra;     // x: groups, y: 1 = two octaves of noise, z: depth pixels per smoke pixel up
    vec4 Anchor;    // xyz: a world point on a 1000-block lattice from the camera, where the noise is laid from
    // per cloud, by group: origin of its grid from the camera (w: atlas slot), box min in cells (w: extinction),
    // box max in cells, colour (w: unused)
    vec4 Clouds[64];
    // per group of clouds whose boxes meet, far to near: the box round them all from the camera, min (w: its first
    // cloud) and max (w: how many)
    vec4 Groups[32];
};
