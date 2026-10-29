#version 450
layout(location = 0) in vec2 vUV;
layout(location = 1) flat in vec4 vColor;
layout(location = 2) flat in float vRipple;
layout(location = 0) out vec4 outColor;

void main() {
    float r = length(vUV);
    float a;
    if (vRipple > 0.5) {
        a = vColor.a * (1.0 - smoothstep(0.0, 0.12, abs(r - 0.85)));
    } else {
        a = vColor.a * (1.0 - smoothstep(0.4, 1.0, r));
    }
    if (a <= 0.002) {
        discard;
    }
    outColor = vec4(vColor.rgb * a, a);
}
