#version 450
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 1) flat in vec4 vColor;
layout(location = 2) flat in float vKind;
layout(location = 3) flat in vec3 vHot;
layout(location = 0) out vec4 outColor;

void main() {
    if (vKind > 1.5) {
        float r2 = dot(vUV, vUV);
        if (r2 >= 1.0) {
            discard;
        }
        float a = vColor.a * exp(-r2 * 2.5);
        outColor = vec4(vColor.rgb * a, 0.0);
        return;
    }
    if (vKind > 0.5) {
        float s = vUV.y * 0.5 + 0.5;
        float width = mix(1.0, 0.12, pow(s, 1.1));
        vec2 q = vec2(vUV.x / width, vUV.y * 0.92 + 0.08);
        float r2 = dot(q, q);
        if (r2 >= 1.0) {
            discard;
        }
        float body = pow(1.0 - r2, 1.4);
        float core = (1.0 - s) * pow(1.0 - r2, 2.0);
        vec3 col = mix(vColor.rgb, vHot * 1.1, clamp(core * 0.9, 0.0, 1.0));
        float a = vColor.a * body;
        outColor = vec4(col * a, a * 0.55);
        return;
    }
    float r2 = dot(vUV, vUV);
    if (r2 >= 1.0) {
        discard;
    }
    float a = vColor.a * exp(-r2 * 1.8) * (1.0 - r2);
    outColor = vec4(vColor.rgb * a, a);
}
