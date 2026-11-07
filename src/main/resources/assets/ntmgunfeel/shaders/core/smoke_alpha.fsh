#version 330

out vec4 fragColor;

// writes only the alpha channel of the frame (SmokeRender.ALPHA_ZERO / ALPHA_ONE choose which)
void main() {
#ifdef ALPHA_ONE
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
#else
    fragColor = vec4(0.0);
#endif
}
