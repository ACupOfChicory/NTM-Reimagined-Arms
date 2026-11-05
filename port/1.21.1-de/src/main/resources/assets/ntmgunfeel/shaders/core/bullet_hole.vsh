#version 150

// The port's bullet holes (1.21.1): rendertype_entity_translucent with the position Position + ModelOffset, so the
// holes kept on the graphics card around an origin of their own (Tracers batches) are drawn and fogged from the
// camera, and lit per face: the colour for either side, picked in the fragment by which side is seen (26.2's
// PER_FACE_LIGHTING). Holes sent each frame have no offset.
#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec3 ModelOffset;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColorFront;
out vec4 vertexColorBack;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    vec3 pos = Position + ModelOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vertexColorFront = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    vertexColorBack = minecraft_mix_light(Light0_Direction, Light1_Direction, -Normal, Color);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
