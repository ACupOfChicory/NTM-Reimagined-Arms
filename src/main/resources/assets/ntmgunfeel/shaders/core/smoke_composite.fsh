#version 330

// The smoke laid over the finished frame, glass and water in it taken into account (SmokeRender.afterWeather).
// FramePost: the frame now; its alpha is the share of the opaque world that the translucent things (glass, water, ...)
//   cover, since the alpha was cleared before they were drawn: transmittance T = 1 - alpha.
// FrameOpaque: the frame before them. SmokeFull: the smoke from the eye to the first opaque surface. SmokeFront: the
//   smoke from the eye to the nearest translucent surface, only where there is one. Both premultiplied.
// Depth0: the opaque depth, Depth1: the depth now (translucent surfaces write theirs).
uniform sampler2D FramePost;
uniform sampler2D FrameOpaque;
uniform sampler2D SmokeFront;
uniform sampler2D SmokeFull;
uniform sampler2D Depth0;
uniform sampler2D Depth1;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 p = texelFetch(FramePost, px, 0);
    vec4 full = texture(SmokeFull, texCoord);
    float d0 = texelFetch(Depth0, px, 0).r;
    float d1 = texelFetch(Depth1, px, 0).r;
    // depth is reversed (near 1, far and the sky 0): a surface nearer than the opaque one is a translucent one
    bool glass = d1 > d0 + max(1e-7, d0 * 2e-4);
    vec3 rgb;
    if (!glass) {
        rgb = full.rgb + (1.0 - full.a) * p.rgb;
    } else {
        // glass over (smoke behind it over the world), then the smoke in front of it over all: the frame is
        // T * world + glass, so the smoke behind the glass is taken off the world's share of it
        vec4 front = texture(SmokeFront, texCoord);
        vec3 world = texelFetch(FrameOpaque, px, 0).rgb;
        float t = 1.0 - p.a;
        rgb = front.rgb + (1.0 - front.a) * p.rgb + t * ((full.rgb - front.rgb) - (full.a - front.a) * world);
    }
    fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
