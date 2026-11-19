#version 330

// With the vanilla transparency chain (improved transparency, no Flywheel OIT) the translucent things are in targets
// of their own, laid over the main one by minecraft:post/transparency after the smoke. SmokeRender.afterWeather lays
// them itself first, here, where the smoke may be, and clears them there, so that the smoke goes over the frame as
// without the chain (the frame's alpha their cover, the depth of the nearest of them beside it).
// Without FOLD_DEPTH: the frame the chain would make, with the same layers in the same order, laid the same way
// (post/transparency.fsh), its alpha the cover of the layers in front of the main one.
// FOLD_DEPTH: the depth of the nearest layer in front of the main one, else the main one's (reversed depth, near 1).

#if !defined(FOLD_DEPTH)
uniform sampler2D MainSampler;
#endif
uniform sampler2D MainDepthSampler;
uniform sampler2D TranslucentSampler;
uniform sampler2D TranslucentDepthSampler;
uniform sampler2D ItemEntitySampler;
uniform sampler2D ItemEntityDepthSampler;
uniform sampler2D ParticlesSampler;
uniform sampler2D ParticlesDepthSampler;
uniform sampler2D WeatherSampler;
uniform sampler2D WeatherDepthSampler;
uniform sampler2D CloudsSampler;
uniform sampler2D CloudsDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

#if defined(FOLD_DEPTH)
float nearest;

void consider(vec4 color, float depth) {
    if (color.a == 0.0) return;
    // the chain sorts a layer as deep as the main one over it
    if (depth >= nearest) nearest = depth;
}

void main() {
    float mainDepth = texture(MainDepthSampler, texCoord).r;
    nearest = mainDepth;
    consider(texture(TranslucentSampler, texCoord), texture(TranslucentDepthSampler, texCoord).r);
    consider(texture(ItemEntitySampler, texCoord), texture(ItemEntityDepthSampler, texCoord).r);
    consider(texture(ParticlesSampler, texCoord), texture(ParticlesDepthSampler, texCoord).r);
    consider(texture(WeatherSampler, texCoord), texture(WeatherDepthSampler, texCoord).r);
    consider(texture(CloudsSampler, texCoord), texture(CloudsDepthSampler, texCoord).r);
    fragColor = vec4(nearest, 0.0, 0.0, 1.0);
}
#else
// post/transparency.fsh as it is, the main layer marked so that the cover of what is over it can be told
vec4 color_layers[6] = vec4[](vec4(0.0), vec4(0.0), vec4(0.0), vec4(0.0), vec4(0.0), vec4(0.0));
float depth_layers[6] = float[](0, 0, 0, 0, 0, 0);
bool main_layer[6] = bool[](false, false, false, false, false, false);
int active_layers = 0;

void try_insert(vec4 color, float depth) {
    if (color.a == 0.0) {
        return;
    }

    color_layers[active_layers] = color;
    depth_layers[active_layers] = depth;
    main_layer[active_layers] = false;

    int jj = active_layers++;
    int ii = jj - 1;
    while (jj > 0 && depth_layers[jj] < depth_layers[ii]) {
        float depthTemp = depth_layers[ii];
        depth_layers[ii] = depth_layers[jj];
        depth_layers[jj] = depthTemp;

        vec4 colorTemp = color_layers[ii];
        color_layers[ii] = color_layers[jj];
        color_layers[jj] = colorTemp;

        bool mainTemp = main_layer[ii];
        main_layer[ii] = main_layer[jj];
        main_layer[jj] = mainTemp;

        jj = ii--;
    }
}

vec3 blend(vec3 dst, vec4 src) {
    return (dst * (1.0 - src.a)) + src.rgb;
}

void main() {
    color_layers[0] = vec4(texture(MainSampler, texCoord).rgb, 1.0);
    depth_layers[0] = texture(MainDepthSampler, texCoord).r;
    main_layer[0] = true;
    active_layers = 1;

    try_insert(texture(TranslucentSampler, texCoord), texture(TranslucentDepthSampler, texCoord).r);
    try_insert(texture(ItemEntitySampler, texCoord), texture(ItemEntityDepthSampler, texCoord).r);
    try_insert(texture(ParticlesSampler, texCoord), texture(ParticlesDepthSampler, texCoord).r);
    try_insert(texture(WeatherSampler, texCoord), texture(WeatherDepthSampler, texCoord).r);
    try_insert(texture(CloudsSampler, texCoord), texture(CloudsDepthSampler, texCoord).r);

    vec3 texelAccum = color_layers[0].rgb;
    // what the layers over the main one let through of it
    float through = 1.0;
    for (int ii = 1; ii < active_layers; ++ii) {
        texelAccum = blend(texelAccum, color_layers[ii]);
        through = main_layer[ii] ? 1.0 : through * (1.0 - color_layers[ii].a);
    }

    fragColor = vec4(texelAccum.rgb, 1.0 - through);
}
#endif
