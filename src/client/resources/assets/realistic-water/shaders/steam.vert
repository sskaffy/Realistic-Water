#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Parts { Particle parts[]; };
#define REGION_LIGHT_BINDING 2
#include "region_light.glsl"
#define LIGHTMAP_BINDING 3
#include "fluid_light.glsl"

layout(location = 0) out vec2 vUV;
layout(location = 1) flat out vec4 vColor;
layout(location = 2) flat out float vKind;
layout(location = 3) flat out vec3 vHot;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

#define LIFE G.matA.y
#define SIZE G.matC.x
#define OPACITY G.matC.y
#define DARK G.matC.z
#define LIGHT G.matC.w

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
}

// Blackbody-like flame colours: yellow-white core, orange body, deep red tips. Soul fire is the same ramp in blue.
vec3 flameRamp(float t, bool soul) {
    vec3 hot = soul ? vec3(0.65, 0.9, 1.0) : vec3(1.0, 0.8, 0.32);
    vec3 mid = soul ? vec3(0.12, 0.5, 1.0) : vec3(1.0, 0.38, 0.04);
    vec3 cool = soul ? vec3(0.015, 0.1, 0.55) : vec3(0.55, 0.06, 0.0);
    return t < 0.35 ? mix(hot, mid, t / 0.35) : mix(mid, cool, (t - 0.35) / 0.65);
}

float hash11(float x) {
    return fract(sin(x * 12.9898) * 43758.5453);
}

void main() {
    Particle p = parts[gl_InstanceIndex];
    if (p.pos.w < 0.0) {
        hide();
        return;
    }
    float kind = G.matA.x;
    bool spark = kind > 8.5;
    bool flame = kind > 6.5 && !spark;
    bool smoke = kind > 5.5 && kind < 6.5;
    float life = LIFE * (0.6 + 0.8 * p.vel.w);
    float t = clamp(p.pos.w / max(life, 1e-3), 0.0, 1.0);
    float rnd = hash11(float(gl_InstanceIndex));
    float radius;
    if (spark) {
        radius = SIZE * (0.6 + 0.8 * rnd) * (1.0 - 0.5 * t);
    } else if (flame) {
        radius = SIZE * (1.0 - 0.55 * t) * (0.7 + 0.6 * p.vel.w);
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
    vUV = c;
    vKind = spark ? 2.0 : flame ? 1.0 : 0.0;
    vHot = kind > 7.5 ? vec3(0.8, 0.95, 1.0) : vec3(1.0, 0.92, 0.6);
    vec2 axis = vec2(0.0, 1.0);
    float stretch = 1.0;
    if (flame) {
        // A flame is a tongue, not a ball: stretch each particle along the way it is moving on screen, so a column of
        // them licks upward and flickers. The faster it climbs the longer the tongue.
        vec3 vv = (G.view * vec4(p.vel.xyz, 0.0)).xyz;
        float speed = length(p.vel.xyz) / max(G.camGrid.w, 1.0);
        if (length(vv.xy) > 1e-4) {
            axis = normalize(vv.xy);
        }
        stretch = 1.3 + 0.9 * clamp(speed, 0.0, 3.0);
    }
    vec2 perp = vec2(-axis.y, axis.x);
    vec2 offset = (perp * c.x + axis * c.y * stretch) * radius;
    gl_Position = G.proj * vec4(view + vec3(offset, 0.0), 1.0);
    if (spark) {
        // Embers drift up and wink out: bright, tiny and flickering.
        float flicker = 0.6 + 0.8 * hash11(rnd * 91.7 + floor(G.simB.z * 14.0));
        float fade = smoothstep(0.0, 0.04, t) * (1.0 - smoothstep(0.6, 1.0, t));
        vColor = vec4(mix(vec3(1.0, 0.75, 0.3), vec3(1.0, 0.25, 0.03), t) * DARK * flicker, OPACITY * fade);
        return;
    }
    if (flame) {
        // Flames glow by themselves: no vanilla lighting, brighter at the core and fading out toward the tip.
        float fade = smoothstep(0.0, 0.05, t) * (1.0 - smoothstep(0.5, 1.0, t));
        float flicker = 0.85 + 0.3 * hash11(float(gl_InstanceIndex) + G.simB.z * 9.0);
        vColor = vec4(flameRamp(t, kind > 7.5) * DARK * flicker, OPACITY * fade);
        return;
    }
    float fade = smoothstep(0.0, 0.06, t) * (1.0 - smoothstep(0.55, 1.0, t));
    float shade = mix(DARK, LIGHT, clamp(p.vel.w, 0.0, 1.0));
    if (smoke) {
        shade = mix(DARK, LIGHT, clamp(0.5 * fract(p.vel.w) + 0.6 * t, 0.0, 1.0));
    }
    vec2 bs = regionLightAt(p.pos.xyz + vec3(0.0, 0.5 * G.camGrid.w, 0.0));
    vec3 col = vec3(shade) * vanillaLight(bs);
    if (smoke) {
        // Smoke right above the flames is lit orange from below and cools to grey as it climbs away.
        col += vec3(1.0, 0.42, 0.1) * 0.55 * (1.0 - smoothstep(0.0, 0.4, t));
    }
    vColor = vec4(col, OPACITY * fade);
}
