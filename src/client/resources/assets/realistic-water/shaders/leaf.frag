#version 450
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vView;
layout(location = 2) flat in uint vVariant;
layout(location = 3) flat in vec3 vNormal;
layout(location = 4) flat in float vBurn;
layout(location = 5) flat in float vSun;
layout(location = 6) flat in uint vSeed;
layout(location = 0) out vec4 outColor;

#define REGION_LIGHT_BINDING 3
#define LIGHTMAP_BINDING 4
#include "region_light.glsl"
#include "fluid_light.glsl"

layout(std430, binding = 2) readonly buffer LeafTex { int leafTex[]; };
layout(std430, binding = 7) readonly buffer TreeAtlas { uint atlas[]; };
layout(std430, binding = 8) readonly buffer LeafLooks { ivec4 looks[12]; };
#define TREE_ATLAS_CELL 32
#define TREE_ATLAS_CELLS 16

vec4 atlasTexel(int cell, vec2 uv) {
    ivec2 p = clamp(ivec2(floor(uv * float(TREE_ATLAS_CELL))), ivec2(0), ivec2(TREE_ATLAS_CELL - 1));
    int x = (cell % TREE_ATLAS_CELLS) * TREE_ATLAS_CELL + p.x;
    int y = (cell / TREE_ATLAS_CELLS) * TREE_ATLAS_CELL + p.y;
    uint argb = atlas[x + y * TREE_ATLAS_CELL * TREE_ATLAS_CELLS];
    return vec4((argb >> 16) & 255u, (argb >> 8) & 255u, argb & 255u, argb >> 24) / 255.0;
}

void main() {
    vec3 albedo;
    if (vVariant >= 4u) {
        ivec4 look = looks[min(int(vVariant) - 4, 11)];
        uint h = hash(vSeed * 2654435761u ^ vVariant);
        int cell = look.x >= 0 ? look.x : int(h & 3u);
        vec2 uv = vec2(vUV.x, 1.0 - vUV.y);
        if (look.z > 0) {
            float w = float(look.z) / 1000.0;
            vec2 o = vec2(float((h >> 4) & 255u), float((h >> 12) & 255u)) / 255.0 * (1.0 - w);
            uv = o + uv * w;
        }
        vec4 t = atlasTexel(cell, uv);
        if (t.a < 0.5) {
            discard;
        }
        vec3 tint = vec3((look.y >> 16) & 0xFF, (look.y >> 8) & 0xFF, look.y & 0xFF) / 255.0;
        if (look.x >= 0) {
            albedo = t.rgb * tint;
        } else {
            uint h2 = hash(h ^ 0x68E31DA4u);
            float variation = mix(0.80, 1.18, float(h2 & 255u) / 255.0);
            vec3 hue = mix(vec3(1.05, 0.98, 0.85), vec3(0.92, 1.03, 1.06), float((h2 >> 8) & 255u) / 255.0);
            albedo = tint * variation * hue * t.r;
        }
    } else {
        ivec2 size = ivec2(leafTex[0], leafTex[1]);
        int variant = int(vVariant) % max(leafTex[2], 1);
        ivec2 t = clamp(ivec2(floor(vec2(vUV.x, 1.0 - vUV.y) * vec2(size))), ivec2(0), size - 1);
        int argb = leafTex[4 + variant * size.x * size.y + t.x + t.y * size.x];
        if (((argb >> 24) & 0xFF) < 128) {
            discard;
        }
        albedo = vec3((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF) / 255.0;
    }
    albedo = mix(albedo, vec3(0.07, 0.05, 0.035), smoothstep(0.15, 0.75, vBurn));

    vec3 nWorld = normalize(vNormal);
    vec3 rel = (G.invView * vec4(vView, 1.0)).xyz;
    if (dot(nWorld, rel) > 0.0) {
        nWorld = -nWorld;
    }
    vec2 bs = regionLightAt(relToSim(rel + vec3(0.0, 0.25, 0.0)));
    if (vVariant >= 4u) {
        vec3 n = normalize(mat3(G.view) * nWorld);
        vec3 upView = normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
        n = normalize(mix(n, upView, 0.45));
        vec3 L = normalize(G.sunDir.xyz);
        float sun = max(G.sunDir.w, 0.0) * smoothstep(0.3, 0.9, bs.y);
        float diffuse = sun > 0.0 ? mix(0.62, 1.0, max(dot(n, L), 0.0)) : 0.8;
        vec3 treeLit = albedo * vanillaLight(bs) * diffuse * vSun;
        vec3 face = normalize(mat3(G.view) * nWorld);
        treeLit += albedo * vec3(1.15, 1.2, 0.7) * vanillaLight(bs) * max(-dot(face, L), 0.0) * 0.3 * sun * vSun;
        float glow = sin(clamp(vBurn, 0.0, 1.0) * 3.14159) * (0.6 + 0.4 * fract(sin(dot(vUV, vec2(12.9898, 78.233))) * 43758.5453));
        outColor = vec4(treeLit + vec3(1.0, 0.32, 0.05) * glow * 1.4, 1.0);
        return;
    }
    vec3 n2 = nWorld * nWorld;
    float face = n2.x * 0.6 + n2.z * 0.8 + n2.y * (nWorld.y > 0.0 ? 1.0 : 0.5);
    vec3 lit = min(albedo * face * vanillaLight(bs) * G.matD.y, vec3(1.0)) * vSun;
    float ember = sin(clamp(vBurn, 0.0, 1.0) * 3.14159) * (0.6 + 0.4 * fract(sin(dot(vUV, vec2(12.9898, 78.233))) * 43758.5453));
    lit += vec3(1.0, 0.32, 0.05) * ember * 1.4;
    outColor = vec4(lit, 1.0);
}
