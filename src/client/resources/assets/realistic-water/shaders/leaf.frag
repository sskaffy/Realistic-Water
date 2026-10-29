#version 450
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vView;
layout(location = 2) flat in uint vVariant;
layout(location = 3) flat in vec3 vNormal;
layout(location = 4) flat in float vBurn;
layout(location = 5) flat in float vSun;
layout(location = 0) out vec4 outColor;

#define REGION_LIGHT_BINDING 3
#define LIGHTMAP_BINDING 4
#include "region_light.glsl"
#include "fluid_light.glsl"

layout(std430, binding = 2) readonly buffer LeafTex { int leafTex[]; };

void main() {
    ivec2 size = ivec2(leafTex[0], leafTex[1]);
    int variant = int(vVariant) % max(leafTex[2], 1);
    ivec2 t = clamp(ivec2(floor(vec2(vUV.x, 1.0 - vUV.y) * vec2(size))), ivec2(0), size - 1);
    int argb = leafTex[4 + variant * size.x * size.y + t.x + t.y * size.x];
    if (((argb >> 24) & 0xFF) < 128) {
        discard;
    }
    vec3 albedo = vec3((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF) / 255.0;
    albedo = mix(albedo, vec3(0.07, 0.05, 0.035), smoothstep(0.15, 0.75, vBurn));

    vec3 nWorld = normalize(vNormal);
    vec3 rel = (G.invView * vec4(vView, 1.0)).xyz;
    if (dot(nWorld, rel) > 0.0) {
        nWorld = -nWorld;
    }
    vec2 bs = regionLightAt(relToSim(rel + vec3(0.0, 0.25, 0.0)));
    vec3 n2 = nWorld * nWorld;
    float face = n2.x * 0.6 + n2.z * 0.8 + n2.y * (nWorld.y > 0.0 ? 1.0 : 0.5);
    vec3 lit = min(albedo * face * vanillaLight(bs) * G.matD.y, vec3(1.0)) * vSun;
    float ember = sin(clamp(vBurn, 0.0, 1.0) * 3.14159) * (0.6 + 0.4 * fract(sin(dot(vUV, vec2(12.9898, 78.233))) * 43758.5453));
    lit += vec3(1.0, 0.32, 0.05) * ember * 1.4;
    outColor = vec4(lit, 1.0);
}
