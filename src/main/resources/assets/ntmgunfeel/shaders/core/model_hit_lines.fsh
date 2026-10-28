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
    float surfaceDepth = texelFetch(Sampler1, pixel, 0).r;
    float epsilon = max(0.00000001, surfaceDepth * 0.00001);
    // Толстая линия покрывает соседние пиксели наклонной поверхности.
    // Для своего тела даём небольшой допуск, не меняя проверку других мобов
    // и блоков; дальние рёбра по-прежнему скрываются глубиной.
    if (all(equal(owner, target))) epsilon = max(epsilon, surfaceDepth * 0.0005);
    if (surfaceDepth > gl_FragCoord.z + epsilon) discard;
    if (any(notEqual(owner, vec3(0.0))) && any(notEqual(owner, target))
        && surfaceDepth >= gl_FragCoord.z - epsilon) discard;
    // Отладочная линия не зависит от света и тумана мира.
    fragColor = vertexColor.a < 0.25 ? vec4(0.15, 1.0, 1.0, 1.0)
        : vertexColor.a < 0.45 ? vec4(0.8, 0.45, 1.0, 1.0)
        : vertexColor.a > 0.75 ? vec4(1.0, 0.8, 0.333333, 1.0) : vec4(1.0);
}
