#version 450
#include "common.glsl"
layout(binding = 4) uniform sampler2D sceneDepth;
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vCenter;
layout(location = 2) in float vRadius;
layout(location = 3) in vec4 vData;
layout(location = 0) out float outThickness;

float linearDepth(float device) {
    vec4 v = G.invProj * vec4(0.0, 0.0, device, 1.0);
    return -v.z / v.w;
}

void main() {
    float r2 = dot(vUV, vUV);
    if (r2 > 1.0) {
        discard;
    }
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    if (-vCenter.z > linearDepth(texture(sceneDepth, uv).r)) {
        discard;
    }
    outThickness = 2.0 * vRadius * sqrt(1.0 - r2);
}
