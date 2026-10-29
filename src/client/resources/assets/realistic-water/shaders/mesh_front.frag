#version 450
#include "common.glsl"
layout(std430, binding = 3) readonly buffer ViewState { vec4 viewState; };
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vWall;
layout(location = 0) out vec4 outG;
layout(location = 1) out vec4 outMat;
#define REGION_LIGHT_BINDING 4
#include "region_light.glsl"
#define PACK_SHADOW_BINDING 5
#include "sun_shadow.glsl"

void main() {
    float dist = -vView.z;
    if (dist < G.surf.z) {
        discard;
    }
    if (viewState.x > 0.5 && vWall > 0.5) {
        discard;
    }
    vec3 n = normalize(vNormal);
    outG = vec4(n, dist);
    vec3 rel = (G.invView * vec4(vView, 1.0)).xyz;
    vec3 nWorld = mat3(G.invView) * n;
    float shadowed = abs(G.matA.x - 3.0) < 0.5 ? 1.0 - sunShadowFactor(relToSim(rel + nWorld * 0.1)) : 0.0;
    outMat = vec4(G.matA.x, regionLightAt(relToSim(rel + nWorld * 0.5)), shadowed);
}
