#version 450
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vCenter;
layout(location = 2) in float vRadius;
layout(location = 3) flat in uint vSeed;
layout(location = 0) out vec4 outColor;

#include "sand_shade.glsl"

void main() {
    float r2 = dot(vUV, vUV);
    if (r2 > 1.0) {
        discard;
    }
    vec3 n = vec3(vUV, sqrt(1.0 - r2));
    vec3 pos = vCenter + n * vRadius;
    vec4 clip = G.proj * vec4(pos, 1.0);
    gl_FragDepth = clip.z / clip.w;
    uint s = vSeed;
    vec3 albedo = grainAlbedo(s);
    outColor = vec4(sandLight(n, albedo, 0.85), 1.0);
}
