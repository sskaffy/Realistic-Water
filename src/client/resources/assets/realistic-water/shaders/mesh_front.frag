#version 450
#include "common.glsl"
layout(std430, binding = 3) readonly buffer ViewState { vec4 viewState; };
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vWall;
layout(location = 0) out vec4 outG;

void main() {
    float dist = -vView.z;
    if (dist < G.surf.z) {
        discard;
    }
    if (viewState.x > 0.5 && vWall > 0.5) {
        discard;
    }
    outG = vec4(normalize(vNormal), dist);
}
