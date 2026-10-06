#version 450
#include "common.glsl"
layout(binding = 2) uniform sampler2D sceneDepth;
layout(binding = 3) uniform sampler2D gbuffer; // .w = distance to the water surface
layout(std430, binding = 4) readonly buffer ViewState { vec4 viewState; };
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vCenter;
layout(location = 2) in float vRadius;
layout(location = 3) in vec4 vData;
layout(location = 0) out vec4 outFoam;

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
    float myDist = -vCenter.z - vRadius * sqrt(1.0 - r2);
    if (myDist > linearDepth(texture(sceneDepth, uv).r)) {
        discard;
    }
    float type = vData.y;
    float a = vData.z;
    vec3 color = vec3(1.0);
    if (type < 0.5) {
        a *= G.foamA.w;
        color = vec3(0.85, 0.92, 1.0);
    } else if (type > 1.5) {
        a *= G.foamA.z;
        color = vec3(0.8, 0.9, 1.0);
    } else {
        a *= 0.9 * clamp(vData.x * 0.8, 0.0, 1.0);
    }
    float fd = texture(gbuffer, uv).w;
    if (viewState.x > 0.5) {
        if (type > 0.5 && type < 1.5) {
            a *= 0.15;
        }
        a *= exp(-myDist * G.scatter.w * G.misc.x * 2.0);
    } else if (fd > 0.0 && myDist > fd + 0.08) {
        a *= exp(-(myDist - fd) * 1.8);
    }
    outFoam = vec4(color * a, a);
}
