#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Parts { Particle parts[]; };
#define REGION_LIGHT_BINDING 2
#include "region_light.glsl"
#define LIGHTMAP_BINDING 3
#include "fluid_light.glsl"

layout(location = 0) out vec2 vUV;
layout(location = 1) flat out vec4 vColor;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

#define LIFE G.matA.y
#define SIZE G.matC.x
#define OPACITY G.matC.y
#define DARK G.matC.z
#define LIGHT G.matC.w

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
}

void main() {
    Particle p = parts[gl_InstanceIndex];
    if (p.pos.w < 0.0) {
        hide();
        return;
    }
    float life = LIFE * (0.6 + 0.8 * p.vel.w);
    float t = clamp(p.pos.w / max(life, 1e-3), 0.0, 1.0);
    float radius = SIZE * (0.8 + 1.2 * t);
    vec3 rel = G.domainOrigin.xyz + p.pos.xyz * G.domainOrigin.w;
    vec3 view = (G.view * vec4(rel, 1.0)).xyz;
    if (-view.z < G.surf.z) {
        hide();
        return;
    }
    vec2 c = CORNERS[gl_VertexIndex % 6];
    gl_Position = G.proj * vec4(view + vec3(c * radius, 0.0), 1.0);
    vUV = c;
    float fade = smoothstep(0.0, 0.06, t) * (1.0 - smoothstep(0.55, 1.0, t));
    float shade = mix(DARK, LIGHT, p.vel.w);
    vec2 bs = regionLightAt(p.pos.xyz + vec3(0.0, 0.5 * G.camGrid.w, 0.0));
    vColor = vec4(shade * vanillaLight(bs), OPACITY * fade);
}
