#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

struct HitPose {
    mat4 transform;
    vec4 color;
};
layout(std140) uniform HitPoses {
    HitPose poses[128];
};
in vec3 Position;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * poses[gl_InstanceID].transform * vec4(Position, 1.0);
    vertexColor = poses[gl_InstanceID].color;
}
