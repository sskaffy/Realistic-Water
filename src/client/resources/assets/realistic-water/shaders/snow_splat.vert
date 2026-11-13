#version 450

#include "common.glsl"
#define SNOW_PARAMS_BINDING 1
#define SNOW_COLUMNS_BINDING 2
#define SNOW_DETAIL_BINDING 3
#include "snow_common.glsl"
struct Splat {
    vec4 a;
    vec4 b;
    vec4 c;
    vec4 d;
};
layout(std430, binding = 4) readonly buffer Splats { Splat splats[]; };

layout(location = 0) out vec3 vLocal;
layout(location = 1) out vec3 vNormal;
layout(location = 2) flat out vec2 vLight;
layout(location = 3) out vec3 vDisc;

#define GRID 12
#define CORE 0.55
const ivec2 CORNERS[6] = ivec2[](ivec2(0, 0), ivec2(1, 0), ivec2(1, 1), ivec2(0, 0), ivec2(1, 1), ivec2(0, 1));

void main() {
    Splat s = splats[gl_InstanceIndex];
    int quad = gl_VertexIndex / 6;
    ivec2 g = ivec2(quad % GRID, quad / GRID) + CORNERS[gl_VertexIndex % 6];
    vec2 uv = vec2(g) / float(GRID) * 2.0 - 1.0;
    float m = max(abs(uv.x), abs(uv.y));
    float len = length(uv);
    vec2 unit = len > 1e-5 ? uv / len : vec2(1.0, 0.0);
    vec2 disc = unit * m;
    vec3 n = normalize(s.b.xyz);
    vec3 t = abs(n.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : normalize(cross(vec3(0.0, 1.0, 0.0), n));
    vec3 bt = cross(n, t);
    float seed = s.b.w;
    float r = m;
    float outline = 0.72 + 0.5 * snowNoise(unit * 1.6 + seed) + 0.16 * (snowNoise(unit * 4.3 - seed) - 0.5);
    vec2 p = disc * s.a.w * outline;
    vec2 smear = vec2(dot(s.d.xyz, t), dot(s.d.xyz, bt));
    float smearLength = length(smear);
    float thin = 1.0;
    if (smearLength > 1e-4) {
        vec2 along = smear / smearLength;
        float ahead = max(dot(p, along), 0.0);
        p += along * ahead * smearLength;
        thin = 1.0 - 0.45 * min(smearLength, 1.0) * clamp(ahead / max(s.a.w, 1e-3), 0.0, 1.0);
    }
    float core = pow(max(1.0 - (r / CORE) * (r / CORE), 0.0), 0.7);
    float skirt = 0.16 * pow(max(1.0 - r * r, 0.0), 0.6);
    float lumps = 0.75 + 0.5 * snowNoise(disc * 3.1 + seed * 1.7);
    float dome = s.c.w * thin * (core * lumps + skirt);
    vec3 offset = t * p.x + bt * p.y;
    if (abs(n.y) < 0.5) {
        float below = min(offset.y, 0.0);
        offset.y += below * 0.5;
    }
    vec3 local = s.a.xyz + offset + n * (dome + 0.004);
    vec3 rel = G.domainOrigin.xyz + local;
    gl_Position = G.proj * (G.view * vec4(rel, 1.0));
    vLocal = local;
    float slope = (s.c.w / max(s.a.w, 0.01)) * mix(2.4, 0.4, smoothstep(0.35, 0.65, r));
    vNormal = normalize(n + (t * disc.x + bt * disc.y) * slope);
    vLight = s.c.xy;
    vDisc = vec3(disc, seed);
}
