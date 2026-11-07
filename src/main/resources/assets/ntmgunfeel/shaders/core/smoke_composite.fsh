#version 330

// The smoke laid over the finished frame, glass and water in it taken into account (SmokeRender.afterWeather).
// SmokeFull: the smoke from the eye to the first opaque surface. SmokeFront: the smoke from the eye to the nearest
// translucent surface, only where there is one. Both premultiplied, at a part of the screen's resolution.
// Depth0: the opaque depth, Depth1: the depth with the translucent surfaces.
// Without the transparency chain: FramePost is the frame now, its alpha the cover of the translucent things (the alpha
//   was cleared before they were drawn: transmittance T = 1 - alpha); FrameOpaque is the frame before them.
// With it (CHAIN_MAIN, CHAIN_GLASS): FramePost is the translucent target (glass premultiplied, alpha its cover),
//   FrameOpaque the main target (the opaque world). CHAIN_MAIN writes the main target: the world with the smoke
//   behind the glass over it. CHAIN_GLASS writes the translucent target: the smoke in front of the glass over it.
//   The chain then lays the glass over the main target as before.
uniform sampler2D FramePost;
uniform sampler2D FrameOpaque;
uniform sampler2D SmokeFront;
uniform sampler2D SmokeFull;
uniform sampler2D Depth0;
uniform sampler2D Depth1;

in vec2 texCoord;

out vec4 fragColor;

// depth is reversed (near 1, far and the sky 0): a surface nearer than the opaque one is a translucent one.
// The same test as in smoke_march.fsh (FRONT).
bool nearer(float d1, float d0) {
    return d1 > d0 + max(1e-7, d0 * 2e-4);
}

// SmokeFront filtered as texture() would, but a texel whose own pixel has no glass (the march skipped it, so it is
// empty) is taken from SmokeFull: there the two marches end at the same surface. Else the empty texels next to a pane
// thin the smoke in front of its edge.
vec4 frontAt(vec2 uv) {
    ivec2 low = textureSize(SmokeFront, 0);
    vec2 scale = vec2(textureSize(Depth0, 0)) / vec2(low);
    vec2 c = uv * vec2(low) - 0.5;
    ivec2 i0 = ivec2(floor(c));
    vec2 f = c - vec2(i0);
    vec4 sum = vec4(0.0);
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k & 1, k >> 1);
        ivec2 t = clamp(i0 + o, ivec2(0), low - 1);
        // the pixel the march read the depth at (smoke_march.fsh: gl_FragCoord times the scale)
        ivec2 dpx = ivec2((vec2(t) + 0.5) * scale);
        bool g = nearer(texelFetch(Depth1, dpx, 0).r, texelFetch(Depth0, dpx, 0).r);
        vec4 v = g ? texelFetch(SmokeFront, t, 0) : texelFetch(SmokeFull, t, 0);
        vec2 w = mix(1.0 - f, f, vec2(o));
        sum += v * (w.x * w.y);
    }
    return sum;
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 p = texelFetch(FramePost, px, 0);
    vec4 full = texture(SmokeFull, texCoord);
    bool glass = nearer(texelFetch(Depth1, px, 0).r, texelFetch(Depth0, px, 0).r);
#if defined(CHAIN_MAIN)
    vec3 world = texelFetch(FrameOpaque, px, 0).rgb;
    vec3 rgb = full.rgb + (1.0 - full.a) * world;
    if (glass) {
        // the chain lays front-over-glass over this: so this is the smoke behind the glass over the world, the share
        // the smoke in front lets through taken out again
        vec4 front = frontAt(texCoord);
        float left = 1.0 - front.a;
        rgb = left < 1e-3 ? world : ((full.rgb - front.rgb) + (1.0 - full.a) * world) / left;
    }
    fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
#elif defined(CHAIN_GLASS)
    vec4 outc = p;
    if (glass) {
        vec4 front = frontAt(texCoord);
        outc = front + (1.0 - front.a) * p;
    }
    fragColor = clamp(outc, 0.0, 1.0);
#else
    vec3 rgb;
    if (!glass) {
        rgb = full.rgb + (1.0 - full.a) * p.rgb;
    } else {
        // glass over (smoke behind it over the world), then the smoke in front of it over all: the frame is
        // T * world + glass, so the smoke behind the glass is taken off the world's share of it
        vec4 front = frontAt(texCoord);
        vec3 world = texelFetch(FrameOpaque, px, 0).rgb;
        float t = 1.0 - p.a;
        rgb = front.rgb + (1.0 - front.a) * p.rgb + t * ((full.rgb - front.rgb) - (full.a - front.a) * world);
    }
    fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
#endif
}
