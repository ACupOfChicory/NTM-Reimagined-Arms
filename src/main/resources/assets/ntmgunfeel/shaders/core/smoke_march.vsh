#version 330

#moj_import <minecraft:projection.glsl>
#moj_import <ntmgunfeel:smoke_info.glsl>

out vec3 vPos;
flat out int vCloud;

// a box's 12 triangles; a corner's bits are x, y, z
const int CORNERS[36] = int[36](
    0, 2, 6, 0, 6, 4,
    1, 5, 7, 1, 7, 3,
    0, 4, 5, 0, 5, 1,
    2, 3, 7, 2, 7, 6,
    0, 1, 3, 0, 3, 2,
    4, 6, 7, 4, 7, 5
);

void main() {
    int cloud = gl_VertexID / 36;
    int corner = CORNERS[gl_VertexID % 36];
    vec3 at = vec3(float(corner & 1), float((corner >> 1) & 1), float((corner >> 2) & 1));
    vec4 origin = Clouds[cloud * 4];
    vec3 pos = origin.xyz + mix(Clouds[cloud * 4 + 1].xyz, Clouds[cloud * 4 + 2].xyz, at);
    vPos = pos;
    vCloud = cloud;
    gl_Position = ProjMat * View * vec4(pos, 1.0);
}
