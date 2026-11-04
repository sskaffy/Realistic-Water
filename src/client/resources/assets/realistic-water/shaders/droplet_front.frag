#version 450
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vCenter;
layout(location = 2) in float vRadius;
layout(location = 3) in vec4 vData;
layout(location = 0) out vec4 outG;
layout(location = 1) out vec4 outMat;
#define REGION_LIGHT_BINDING 4
#include "region_light.glsl"

void main() {
    float r2 = dot(vUV, vUV);
    if (r2 > 1.0) {
        discard;
    }
    vec3 n = vec3(vUV, sqrt(1.0 - r2));
    vec3 pos = vCenter + n * vRadius;
    vec4 clip = G.proj * vec4(pos, 1.0);
    gl_FragDepth = clip.z / clip.w;
    outG = vec4(n, -pos.z);
    outMat = vec4(G.matA.x, regionLightAt(relToSim((G.invView * vec4(vCenter, 1.0)).xyz)), G.oceanA.x > 0.1 ? 2.0 : 0.0);
}
