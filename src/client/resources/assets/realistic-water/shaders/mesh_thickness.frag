#version 450
#include "common.glsl"
layout(std430, binding = 3) readonly buffer ViewState { vec4 viewState; };
layout(binding = 4) uniform sampler2D sceneDepth;
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vWall;
layout(location = 0) out float outThickness;

float linearDepth(float device) {
    vec4 v = G.invProj * vec4(0.0, 0.0, device, 1.0);
    return -v.z / v.w;
}

void main() {
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    float dist = min(-vView.z, linearDepth(texture(sceneDepth, uv).r));
    vec3 gn = cross(dFdx(vView), dFdy(vView));
    if (dot(gn, vNormal) < 0.0) {
        gn = -gn;
    }
    bool entering = dot(gn, -vView) > 0.0;
    outThickness = entering ? -dist : dist;
}
