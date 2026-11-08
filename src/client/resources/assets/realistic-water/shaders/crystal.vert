#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Crystals { vec4 crystals[]; };
layout(location = 0) out vec2 vUV;
layout(location = 1) flat out uint vSeed;
layout(location = 2) flat out vec3 vRel;
layout(location = 3) flat out float vFrac;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void main() {
    vec4 a = crystals[gl_InstanceIndex * 2];
    vec4 b = crystals[gl_InstanceIndex * 2 + 1];
    vec3 rel = G.domainOrigin.xyz + a.xyz * G.domainOrigin.w;
    vec3 center = (G.view * vec4(rel, 1.0)).xyz;
    if (-center.z < G.surf.z) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
    ivec3 q = ivec3(floor(a.xyz * 4.0));
    uint seed = hash(uint(q.x) * 73856093u ^ uint(q.y) * 19349663u ^ uint(q.z) * 83492791u);
    vec2 c = CORNERS[gl_VertexIndex % 6];
    gl_Position = G.proj * vec4(center + vec3(c * a.w, 0.0), 1.0);
    vUV = c;
    vSeed = seed;
    vRel = rel;
    vFrac = b.w;
}
