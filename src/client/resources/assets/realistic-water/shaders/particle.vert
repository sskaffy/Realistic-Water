#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Parts { Particle parts[]; };
#ifdef DROPLETS
layout(std430, binding = 2) readonly buffer Dens { float dens[]; };
#endif
layout(std430, binding = VIEW_BINDING) readonly buffer ViewState { vec4 viewState; };
layout(push_constant) uniform PC { vec4 a; vec4 b; } pc;

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec3 vCenter;
layout(location = 2) out float vRadius;
layout(location = 3) out vec4 vData;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void main() {
    uint id = uint(gl_InstanceIndex);
    Particle p = parts[id];
#ifdef DROPLETS
    if (p.pos.w < 0.0) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
    ivec3 fp = ivec3(floor(p.pos.xyz * float(G.fine.w)));
    float nearDens = 0.0;
    for (int k = 0; k < 27; k++) {
        ivec3 q = fp + ivec3(k % 3, (k / 3) % 3, k / 9) - 1;
        if (inFine(q)) {
            nearDens = max(nearDens, dens[fineIndex(q)]);
        }
    }
    if (nearDens > G.surf.w || viewState.x > 0.5) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
#endif
    vec3 rel = G.domainOrigin.xyz + p.pos.xyz * G.domainOrigin.w;
    vec3 center = (G.view * vec4(rel, 1.0)).xyz;
    float r = pc.a.x;
#ifndef DROPLETS
    bool foam = p.vel.w > 0.5 && p.vel.w < 1.5;
    if (foam) {
        r *= pc.b.x;
    }
#else
    bool foam = false;
#endif
    float alpha = 1.0;
    float dist = -center.z;
    if (dist < pc.a.z) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
    float pixelR = r * G.proj[1][1] * 0.5 * G.screen.y / dist;
    if (pixelR < pc.a.y) {
        float s = pc.a.y / max(pixelR, 1e-4);
        alpha = 1.0 / (s * s);
        r *= s;
    } else if (pc.a.w > 0.0 && pixelR > pc.a.w && !foam) {
        r *= pc.a.w / pixelR;
    }
    if (pc.a.w > 0.0) {
        alpha *= smoothstep(pc.a.z, pc.a.z + 1.2, dist);
    }
    vec2 c = CORNERS[gl_VertexIndex];
    gl_Position = G.proj * vec4(center + vec3(c * r, 0.0), 1.0);
    vUV = c;
    vCenter = center;
    vRadius = r;
    vData = vec4(p.pos.w, p.vel.w, alpha, 0.0);
}
