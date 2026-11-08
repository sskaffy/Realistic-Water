#version 450
#include "common.glsl"
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vRel;
layout(location = 2) in vec3 vNormal;
layout(location = 0) out float outDist;

void main() {
    if (dot(vRel, vNormal) <= 0.0) {
        discard;
    }
    outDist = -vView.z;
}
