#version 450
#include "common.glsl"
layout(push_constant) uniform PC { vec4 look; vec4 more; vec4 c; vec4 d; } pc;
layout(location = 0) in vec3 vPat;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vHeat;
layout(location = 3) in float vDist;
layout(location = 0) out vec4 outColor;

float hash3(ivec3 c) {
    return float(hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u ^ uint(c.z) * 83492791u)) * (1.0 / 4294967295.0);
}

float vnoise(vec3 p) {
    ivec3 b = ivec3(floor(p));
    vec3 f = p - vec3(b);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(hash3(b), hash3(b + ivec3(1, 0, 0)), f.x);
    float c = mix(hash3(b + ivec3(0, 1, 0)), hash3(b + ivec3(1, 1, 0)), f.x);
    float d = mix(hash3(b + ivec3(0, 0, 1)), hash3(b + ivec3(1, 0, 1)), f.x);
    float e = mix(hash3(b + ivec3(0, 1, 1)), hash3(b + ivec3(1, 1, 1)), f.x);
    return mix(mix(a, c, f.y), mix(d, e, f.y), f.z);
}

void main() {
    float t = pc.look.x;
    vec3 texel = floor(vPat * 16.0) / 16.0;
    float mottle = 0.65 * vnoise(vPat * 2.3 + vec3(0.0, t * 0.05, 0.0)) + 0.35 * vnoise(texel * 9.0 + 3.0);
    float flicker = 0.94 + 0.06 * sin(t * 2.7 + vPat.x * 3.1 + vPat.z * 2.3 + vPat.y * 1.7);
    float heat = clamp(vHeat * (0.75 + 0.5 * mottle), 0.0, 1.0);
    vec3 glow = mix(vec3(0.42, 0.02, 0.0), vec3(1.0, 0.16, 0.02), smoothstep(0.0, 0.45, heat));
    glow = mix(glow, vec3(1.0, 0.5, 0.08), smoothstep(0.45, 0.8, heat));
    glow = mix(glow, vec3(1.0, 0.85, 0.45), smoothstep(0.8, 1.0, heat));
    float cover = smoothstep(0.0, 0.2, heat) * mix(0.3, 0.9, heat) * pc.look.y;
    float far = 1.0 - smoothstep(pc.look.z * 0.8, pc.look.z, vDist);
    cover *= far;
    outColor = vec4(glow * (0.7 + 0.9 * heat) * flicker * cover, cover);
}
