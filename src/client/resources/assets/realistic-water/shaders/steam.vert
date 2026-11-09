#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Parts { Particle parts[]; };
#define REGION_LIGHT_BINDING 2
#include "region_light.glsl"
#define LIGHTMAP_BINDING 3
#include "fluid_light.glsl"

layout(location = 0) out vec2 vUV;
layout(location = 1) flat out vec4 vColor;
layout(location = 2) flat out float vFlame;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

#define LIFE G.matA.y
#define SIZE G.matC.x
#define OPACITY G.matC.y
#define DARK G.matC.z
#define LIGHT G.matC.w

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
}

// Blackbody-like flame colours: white-yellow core, orange body, deep red tips. Soul fire is the same ramp in blue.
vec3 flameRamp(float t, bool soul) {
    vec3 hot = soul ? vec3(0.65, 0.9, 1.0) : vec3(1.0, 0.78, 0.3);
    vec3 mid = soul ? vec3(0.12, 0.5, 1.0) : vec3(1.0, 0.38, 0.04);
    vec3 cool = soul ? vec3(0.015, 0.1, 0.55) : vec3(0.55, 0.06, 0.0);
    return t < 0.35 ? mix(hot, mid, t / 0.35) : mix(mid, cool, (t - 0.35) / 0.65);
}

void main() {
    Particle p = parts[gl_InstanceIndex];
    if (p.pos.w < 0.0) {
        hide();
        return;
    }
    float kind = G.matA.x;
    bool flame = kind > 6.5;
    bool smoke = !flame && kind > 5.5;
    float life = LIFE * (0.6 + 0.8 * p.vel.w);
    float t = clamp(p.pos.w / max(life, 1e-3), 0.0, 1.0);
    float radius;
    if (flame) {
        radius = SIZE * (1.0 - 0.7 * t) * (0.7 + 0.6 * p.vel.w);
    } else if (smoke) {
        radius = SIZE * (0.6 + 2.4 * t);
    } else {
        radius = SIZE * (0.8 + 1.2 * t);
    }
    vec3 rel = G.domainOrigin.xyz + p.pos.xyz * G.domainOrigin.w;
    vec3 view = (G.view * vec4(rel, 1.0)).xyz;
    if (-view.z < G.surf.z) {
        hide();
        return;
    }
    vec2 c = CORNERS[gl_VertexIndex % 6];
    gl_Position = G.proj * vec4(view + vec3(c * radius, 0.0), 1.0);
    vUV = c;
    vFlame = flame ? 1.0 : 0.0;
    if (flame) {
        // Flames glow by themselves: no vanilla lighting, brighter at the core and fading out toward the tip.
        float fade = smoothstep(0.0, 0.05, t) * (1.0 - smoothstep(0.5, 1.0, t));
        float flicker = 0.85 + 0.3 * fract(sin(float(gl_InstanceIndex) * 12.9898 + G.simB.z * 9.0) * 43758.5453);
        vColor = vec4(flameRamp(t, kind > 7.5) * DARK * flicker, OPACITY * fade);
        return;
    }
    float fade = smoothstep(0.0, 0.06, t) * (1.0 - smoothstep(0.55, 1.0, t));
    float shade = mix(DARK, LIGHT, clamp(p.vel.w, 0.0, 1.0));
    if (smoke) {
        shade = mix(DARK, LIGHT, clamp(0.5 * fract(p.vel.w) + 0.6 * t, 0.0, 1.0));
    }
    vec2 bs = regionLightAt(p.pos.xyz + vec3(0.0, 0.5 * G.camGrid.w, 0.0));
    vColor = vec4(shade * vanillaLight(bs), OPACITY * fade);
}
