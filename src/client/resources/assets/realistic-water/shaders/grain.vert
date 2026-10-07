#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Parts { Particle parts[]; };
layout(std430, binding = 2) readonly buffer Dens { float dens[]; };

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec3 vCenter;
layout(location = 2) out float vRadius;
layout(location = 3) flat out uint vSeed;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
}

void main() {
    Particle p = parts[gl_InstanceIndex];
    uint child = uint(gl_VertexIndex) / 6u;
    if (p.pos.w < 0.0) {
        hide();
        return;
    }
    ivec3 fp = ivec3(floor(p.pos.xyz * float(G.fine.w)));
    if (inFine(fp) && dens[fineIndex(fp)] > G.surf.x) {
        hide();
        return;
    }
    uint seed = hash(floatBitsToUint(p.vel.w) * 747796405u + child * 2891336453u + 1u);
    vec3 offset = child == 0u ? vec3(0.0) : randInSphere(seed) * G.matD.z;
    vec3 rel = G.domainOrigin.xyz + (p.pos.xyz + offset) * G.domainOrigin.w;
    vec3 center = (G.view * vec4(rel, 1.0)).xyz;
    float dist = -center.z;
    if (dist < G.surf.z) {
        hide();
        return;
    }
    float r = G.matB.w * (0.65 + 0.7 * rand(seed));
    float pixelR = r * G.proj[1][1] * 0.5 * G.screen.y / dist;
    float minPx = max(G.matD.x, 0.05);
    if (pixelR < minPx) {
        float keep = pixelR / minPx;
        if (child > 0u && rand(seed) > keep * keep) {
            hide();
            return;
        }
        r *= minPx / max(pixelR, 1e-5);
    }
    vec2 c = CORNERS[gl_VertexIndex % 6];
    gl_Position = G.proj * vec4(center + vec3(c * r, 0.0), 1.0);
    vUV = c;
    vCenter = center;
    vRadius = r;
    vSeed = seed;
}
