#version 450
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 1) flat in vec4 vColor;
layout(location = 2) flat in float vFlame;
layout(location = 0) out vec4 outColor;

void main() {
    float r2 = dot(vUV, vUV);
    if (r2 >= 1.0) {
        discard;
    }
    float a = vColor.a * exp(-r2 * 3.0) * (1.0 - r2);
    if (vFlame > 0.5) {
        // Mostly opaque with a little additive glow: overlapping particles build a body of flame colour instead of burning out to white.
        outColor = vec4(vColor.rgb * a, a * 0.55);
    } else {
        outColor = vec4(vColor.rgb * a, a);
    }
}
