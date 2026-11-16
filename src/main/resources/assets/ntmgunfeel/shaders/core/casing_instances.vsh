#version 330

// NTM's spent casings, all of one part in one draw (CasingInstances): vanilla's entity.vsh with the casing's
// matrices, colour and light from its slot instead of baked into each vertex. The fragment shader is vanilla's entity.

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

struct Casing {
    mat4 pose;
    mat4 normal;
    vec4 color;
    ivec4 light;
};
layout(std140) uniform CasingPoses {
    Casing casings[64];
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    Casing c = casings[gl_InstanceID];
    vec3 pos = (c.pose * vec4(Position, 1.0)).xyz;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    vec3 normal = normalize(mat3(c.normal) * Normal);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, Color * c.color);
    lightMapColor = sample_lightmap(Sampler2, c.light.xy);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
