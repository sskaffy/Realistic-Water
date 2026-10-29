#version 450
#include "common.glsl"
#include "leaf.glsl"
layout(std430, binding = 1) readonly buffer Parts { Particle parts[]; };
layout(std430, binding = 5) readonly buffer Static { uint staticSolid[]; };
#define REGION_LIGHT_BINDING 3
#include "region_light.glsl"
#define PACK_SHADOW_BINDING 6
#include "sun_shadow.glsl"

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec3 vView;
layout(location = 2) flat out uint vVariant;
layout(location = 3) flat out vec3 vNormal;
layout(location = 4) flat out float vBurn;
layout(location = 5) flat out float vSun;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
}

mat3 rotAxis(vec3 a, float angle) {
    float s = sin(angle);
    float c = cos(angle);
    float t = 1.0 - c;
    return mat3(
        t * a.x * a.x + c, t * a.x * a.y + s * a.z, t * a.x * a.z - s * a.y,
        t * a.x * a.y - s * a.z, t * a.y * a.y + c, t * a.y * a.z + s * a.x,
        t * a.x * a.z + s * a.y, t * a.y * a.z - s * a.x, t * a.z * a.z + c);
}

void main() {
    Particle p = parts[gl_InstanceIndex];
    if (p.pos.w < 0.0) {
        hide();
        return;
    }
    uint bits = leafBits(p.vel);
    float yaw = leafYaw(bits);
    float phase = leafPhase(bits);
    float burn = leafBurn(bits);
    uint look = hash((bits & 0xFFu) * 2654435761u + 7u);
    float h1 = float(look & 0xFFu) / 255.0;
    float h2 = float((look >> 8) & 0xFFu) / 255.0;
    float h3 = float((look >> 16) & 0xFFu) / 255.0;
    float h4 = float((look >> 24) & 0xFFu) / 255.0;

    float res = G.camGrid.w;
    float speed = length(p.vel.xyz) / res;
    float moving = smoothstep(0.05, 0.6, speed);
    vec3 heading = vec3(cos(yaw), 0.0, sin(yaw));
    vec3 side = vec3(-heading.z, 0.0, heading.x);
    float swing = (h1 - 0.5) * 0.5 + moving * 0.75 * sin(phase);
    if (speed > 3.0) {
        swing = phase;
    }
    float roll = (h2 - 0.5) * 0.5 + moving * 0.35 * cos(phase);
    mat3 R = rotAxis(side, roll) * rotAxis(heading, swing) * rotAxis(vec3(0.0, 1.0, 0.0), h3 * LEAF_TAU);

    vec2 c = CORNERS[gl_VertexIndex % 6];
    float size = G.matD.z * (0.85 + 0.3 * h4) * (1.0 - 0.6 * burn);
    vec3 offset = R * vec3(c.x * 0.5 * size, 0.0, c.y * 0.5 * size);
    vec3 normal = R * vec3(0.0, 1.0, 0.0);

    vec3 sim = p.pos.xyz;
    ivec3 below = ivec3(floor(sim)) - ivec3(0, 1, 0);
    bool onBlock = inGrid(below) && staticSolid[cellIndex(below)] != 0u;
    float lift = 0.012 - (onBlock ? 0.0 : h2 * 0.7 * CELL_H * (1.0 - moving));
    vec3 rel = G.domainOrigin.xyz + sim * G.domainOrigin.w + vec3(0.0, lift, 0.0) + offset;
    vec3 view = (G.view * vec4(rel, 1.0)).xyz;
    if (-view.z < G.surf.z) {
        hide();
        return;
    }
    gl_Position = G.proj * vec4(view, 1.0);
    vUV = c * 0.5 + 0.5;
    vView = view;
    vVariant = bits & 3u;
    vNormal = normal;
    vBurn = burn;
    vSun = sunShadowFactor(sim);
}
