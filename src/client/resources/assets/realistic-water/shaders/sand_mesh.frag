#version 450
#include "common.glsl"
layout(std430, binding = 3) readonly buffer Dens { float dens[]; };
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vWall;
layout(location = 0) out vec4 outColor;

#include "sand_shade.glsl"

float densAt(ivec3 p) {
    return inFine(p) ? dens[fineIndex(p)] : 0.0;
}

float densFine(vec3 g) {
    ivec3 b = ivec3(floor(g));
    vec3 f = g - vec3(b);
    float r = 0.0;
    for (int k = 0; k < 8; k++) {
        ivec3 o = ivec3(k & 1, (k >> 1) & 1, (k >> 2) & 1);
        vec3 w = mix(1.0 - f, f, vec3(o));
        r += w.x * w.y * w.z * densAt(b + o);
    }
    return r;
}

void main() {
    float dist = -vView.z;
    if (dist < G.surf.z) {
        discard;
    }
    vec3 n = normalize(vNormal);
    if (dot(n, -vView) < 0.0) {
        n = -n;
    }
    vec3 rel = (G.invView * vec4(vView, 1.0)).xyz;
    vec3 world = G.misc.yzw + rel;
    vec3 nWorld = normalize(mat3(G.invView) * n);
    float footprint = 2.0 * dist / max(G.proj[1][1] * G.screen.y, 1e-4);
    vec3 albedo;
    vec3 shaded = grainSurface(world, nWorld, footprint, albedo);

    vec3 g = (rel - G.domainOrigin.xyz) / G.domainOrigin.w * float(G.fine.w) - 0.5;
    float occ = densFine(g + nWorld * 1.5) + 0.5 * densFine(g + nWorld * 3.0);
    float ao = 1.0 - G.matD.y * clamp(occ - 0.2, 0.0, 1.0);

    outColor = vec4(sandLight(normalize(mat3(G.view) * shaded), albedo, ao), 1.0);
}
