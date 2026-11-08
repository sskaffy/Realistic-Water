#version 450
#include "common.glsl"
#ifdef ZONE
layout(binding = 3) uniform sampler2D sceneDepth;
#else
layout(binding = 2) uniform sampler2D sceneDepth;
#endif
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vRel;
layout(location = 2) in float vAmp;
layout(location = 3) in vec2 vLight;
layout(location = 4) in vec3 vSideNormal;
layout(location = 5) in vec2 vSlopeDev;
layout(location = 0) out float outThickness;

float linearDepth(float device) {
    vec4 v = G.invProj * vec4(0.0, 0.0, device, 1.0);
    return -v.z / v.w;
}

#ifndef ZONE
layout(location = 6) in float vUnderZone;
#endif

void main() {
    float dist = -vView.z;
#ifdef ZONE
    if (dist < G.surf.z) {
        discard;
    }
#else
    if (dist < G.surf.z || vUnderZone > 0.5) {
        discard;
    }
#endif
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    float scene = linearDepth(texture(sceneDepth, uv).r);
    vec3 gn = cross(dFdx(vView), dFdy(vView));
    vec3 up = dot(vSideNormal, vSideNormal) > 0.5 ? mat3(G.view) * vSideNormal : mat3(G.view) * vec3(0.0, 1.0, 0.0);
    if (dot(gn, up) < 0.0) {
        gn = -gn;
    }
    bool fromOutside = dot(gn, -vView) > 0.0;
    outThickness = fromOutside ? max(scene - dist, 0.0) : 0.0;
}
