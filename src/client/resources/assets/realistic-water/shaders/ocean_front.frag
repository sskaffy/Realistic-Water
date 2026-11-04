#version 450
#include "common.glsl"
#include "ocean.glsl"
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vRel;
layout(location = 2) in float vAmp;
layout(location = 3) in vec2 vLight;
layout(location = 4) in vec3 vSideNormal;
layout(location = 5) in vec2 vSlopeDev;
layout(location = 0) out vec4 outG;
layout(location = 1) out vec4 outMat;

void main() {
    float dist = -vView.z;
    if (dist < G.surf.z) {
        discard;
    }
    float footprint = max(length(dFdx(vRel.xz)), length(dFdy(vRel.xz)));
    vec3 n;
    float lost = 0.0;
    if (dot(vSideNormal, vSideNormal) > 0.5) {
        n = vSideNormal;
    } else {
        vec2 slope = oceanSlopeFiltered(vRel.xz, vAmp, footprint, lost) + vSlopeDev;
        n = normalize(vec3(-slope.x, 1.0, -slope.y));
    }
    float whitecap = 0.0;
    if (dot(vSideNormal, vSideNormal) < 0.5) {
        vec2 world = vec2(G.dims.xz & 4095) + vRel.xz;
        whitecap = oceanWhitecap(vRel.xz, world, vAmp);
    }
    outG = vec4(normalize(mat3(G.view) * n), dist);
    outMat = vec4(0.0, vLight, seaMaterialAlpha(lost, whitecap));
}
