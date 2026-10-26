#version 330
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec3 Normal;
in float LineWidth;
out vec4 vertexColor;

void main() {
    vec4 p = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 q = ProjMat * ModelViewMat * vec4(Position + Normal, 1.0);
    vec2 direction = (q.xy / q.w - p.xy / p.w) * ScreenSize;
    float lengthSquared = dot(direction, direction);
    vec2 perpendicular = lengthSquared > 0.000001
        ? vec2(-direction.y, direction.x) * inversesqrt(lengthSquared)
        : vec2(1.0, 0.0);
    if (perpendicular.x < 0.0) perpendicular = -perpendicular;
    float side = (gl_VertexID & 1) == 0 ? 1.0 : -1.0;
    p.xy += perpendicular * LineWidth / ScreenSize * p.w * side;
    gl_Position = p;
    vertexColor = Color;
}
