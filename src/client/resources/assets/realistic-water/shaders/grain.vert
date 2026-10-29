#version 450
#include "common.glsl"
#include "grain_common.glsl"
layout(std430, binding = 1) readonly buffer Visible { SurfaceParticle visible[]; };

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec3 vCenter;
layout(location = 2) out float vRadius;
layout(location = 3) flat out uint vSeed;
layout(location = 4) flat out vec3 vWorld;
layout(location = 5) flat out vec3 vNormal;
layout(location = 6) flat out float vShade;
layout(location = 7) flat out float vSun;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
}

void main() {
    SurfaceParticle p = visible[gl_InstanceIndex];
    uint child = uint(gl_VertexIndex) / 6u;
    vec3 nWorld = p.normal.xyz;

    uint seed = hash(floatBitsToUint(p.extra.x) * 747796405u + child * 2891336453u + 1u);
    vec3 offset = randInSphere(seed) * G.matD.z;
    float along = dot(offset, nWorld);
    offset = (offset - along * nWorld) * p.pos.w + along * nWorld;
    vec3 rel = G.domainOrigin.xyz + (p.pos.xyz + offset) * G.domainOrigin.w;
    vec3 center = (G.view * vec4(rel, 1.0)).xyz;
    float dist = -center.z;
    if (dist < G.surf.z) {
        hide();
        return;
    }
    float r = G.matB.w * (0.6 + 0.8 * rand(seed));
    float pixelR = r * G.proj[1][1] * 0.5 * G.screen.y / dist;
    float minPx = max(G.matD.x, 0.05);
    if (pixelR < minPx) {
        float keep = pixelR / minPx;
        if (rand(seed) > keep * keep) {
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
    vWorld = G.misc.yzw + rel;
    vNormal = mat3(G.view) * nWorld;
    vShade = p.normal.w;
    vSun = p.extra.y;
}
