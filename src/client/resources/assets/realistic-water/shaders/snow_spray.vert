#version 450

#include "common.glsl"
#define SNOW_PARAMS_BINDING 1
#define SNOW_COLUMNS_BINDING 2
#define SNOW_DETAIL_BINDING 3
#include "snow_common.glsl"
layout(std430, binding = 4) readonly buffer Spray { uint sprayCursor; uint sprayPad0; uint sprayPad1; uint sprayPad2; SnowSpray spray[]; };
#define LIGHTMAP_BINDING 5
#include "fluid_light.glsl"

layout(location = 0) out vec2 vUV;
layout(location = 1) flat out vec4 vColor;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void main() {
    SnowSpray p = spray[gl_InstanceIndex];
    if (p.vel.w < 0.0) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        vUV = vec2(0.0);
        vColor = vec4(0.0);
        return;
    }
    vec3 local = sprayToLocal(p.pos.xyz);
    vec3 rel = G.domainOrigin.xyz + local;
    vec3 center = (G.view * vec4(rel, 1.0)).xyz;
    float depth = max(-center.z, 0.05);
    float pixel = 2.0 * depth / (G.proj[1][1] * G.screen.y);
    uint seed = hash(uint(gl_InstanceIndex) * 2654435761u);
    float size = 0.012 + 0.018 * rand(seed);
    float radius = max(size, 0.8 * pixel);
    vec2 c = CORNERS[gl_VertexIndex % 6];
    gl_Position = G.proj * vec4(center + vec3(c * radius, 0.0), 1.0);
    vUV = c;
    SnowSurface below = snowRest(local.xz);
    vec3 light = vanillaLight(below.light);
    float fade = 1.0 - smoothstep(6.0, 8.0, p.vel.w);
    vColor = vec4(vec3(0.96, 0.97, 1.0) * light * SP.look.w, 0.85 * (size / radius) * fade);
}
