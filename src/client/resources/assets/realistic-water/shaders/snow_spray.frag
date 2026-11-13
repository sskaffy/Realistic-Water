#version 450
layout(location = 0) in vec2 vUV;
layout(location = 1) flat in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    float d = length(vUV);
    if (d > 1.0) {
        discard;
    }
    float a = vColor.a * (1.0 - smoothstep(0.4, 1.0, d));
    outColor = vec4(vColor.rgb * a, a);
}
