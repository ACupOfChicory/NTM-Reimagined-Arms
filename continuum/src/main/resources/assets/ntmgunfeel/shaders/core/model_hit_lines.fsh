#version 330

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec4 vertexColor;
out vec4 fragColor;

void main() {
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1);
    vec3 owner = floor(texelFetch(Sampler0, pixel, 0).rgb * 255.0 + 0.5);
    vec3 target = floor(vertexColor.rgb * 255.0 + 0.5);
    // port/continuum: 26.1's depth is not reversed (near 0, far 1) as 26.2's is; 1 - depth turns it into 26.2's form
    float surfaceDepth = 1.0 - texelFetch(Sampler1, pixel, 0).r;
    float fragDepth = 1.0 - gl_FragCoord.z;
    float epsilon = max(0.00000001, surfaceDepth * 0.00001);
    // Толстая линия покрывает соседние пиксели наклонной поверхности.
    // Для своего тела даём небольшой допуск, не меняя проверку других мобов
    // и блоков; дальние рёбра по-прежнему скрываются глубиной.
    if (all(equal(owner, target))) epsilon = max(epsilon, surfaceDepth * 0.0005);
    if (surfaceDepth > fragDepth + epsilon) discard;
    if (any(notEqual(owner, vec3(0.0))) && any(notEqual(owner, target))
        && surfaceDepth >= fragDepth - epsilon) discard;
    // Отладочная линия не зависит от света и тумана мира.
    fragColor = vertexColor.a < 0.25 ? vec4(0.15, 1.0, 1.0, 1.0)
        : vertexColor.a < 0.45 ? vec4(0.8, 0.45, 1.0, 1.0)
        : vertexColor.a > 0.55 && vertexColor.a < 0.75 ? vec4(0.15, 1.0, 0.15, 1.0)
        : vertexColor.a > 0.75 ? vec4(1.0, 0.8, 0.333333, 1.0) : vec4(1.0);
}
