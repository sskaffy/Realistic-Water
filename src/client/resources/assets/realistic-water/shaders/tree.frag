#version 450
#include "common.glsl"
#define TREE_PARAMS_BINDING 1
#define TREE_BLOCKS_BINDING 2
#include "tree_common.glsl"
layout(std430, binding = 4) readonly buffer Atlas { uint atlas[]; };
#define LIGHTMAP_BINDING 5
#include "fluid_light.glsl"
layout(binding = 6) uniform sampler2D packShadowTex;

layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec2 vUV;
layout(location = 2) flat in int vCell;
layout(location = 3) in vec3 vColor;
layout(location = 4) flat in vec2 vLight;
layout(location = 5) in float vAo;
layout(location = 6) in vec3 vPos;
layout(location = 7) flat in int vKind;
layout(location = 8) in float vT;
layout(location = 0) out vec4 outColor;

vec4 texel(int cell, ivec2 p) {
    p = clamp(p, ivec2(0), ivec2(TREE_ATLAS_CELL - 1));
    int x = (cell % TREE_ATLAS_CELLS) * TREE_ATLAS_CELL + p.x;
    int y = (cell / TREE_ATLAS_CELLS) * TREE_ATLAS_CELL + p.y;
    uint argb = atlas[x + y * TREE_ATLAS_CELL * TREE_ATLAS_CELLS];
    return vec4((argb >> 16) & 255u, (argb >> 8) & 255u, argb & 255u, argb >> 24) / 255.0;
}

vec4 nearest(int cell, vec2 uv) {
    return texel(cell, ivec2(floor(uv * float(TREE_ATLAS_CELL))));
}

//vec4 bilinear(int cell, vec2 uv) {
//    vec2 p = uv * float(TREE_ATLAS_CELL) - 0.5;
//    ivec2 i = ivec2(floor(p));
//    vec2 f = p - vec2(i);
//    vec4 a = texel(cell, i);
//    vec4 b = texel(cell, i + ivec2(1, 0));
//    vec4 c = texel(cell, i + ivec2(0, 1));
//    vec4 d = texel(cell, i + ivec2(1, 1));
//    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
//}

vec4 bilinear(int cell, vec2 uv) {
    vec2 p = uv * float(TREE_ATLAS_CELL) - 0.5;
    ivec2 i = ivec2(floor(p));
    vec2 f = p - vec2(i);
    vec4 a = texel(cell, i);
    vec4 b = texel(cell, i + ivec2(1, 0));
    vec4 c = texel(cell, i + ivec2(0, 1));
    vec4 d = texel(cell, i + ivec2(1, 1));
    vec4 w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y) * vec4(a.a, b.a, c.a, d.a);
    float ws = w.x + w.y + w.z + w.w;
    vec3 rgb = ws > 0.0 ? (a.rgb * w.x + b.rgb * w.y + c.rgb * w.z + d.rgb * w.w) / ws : a.rgb;
    float alpha = mix(mix(a.a, b.a, f.x), mix(c.a, d.a, f.x), f.y);
    return vec4(rgb, alpha);
}

float packShadow(vec3 rel) {
    if (G.packShadow.x < 0.5 || G.compat.z <= 0.0) {
        return 1.0;
    }
    vec3 L = normalize(mat3(G.invView) * G.sunDir.xyz);
    if (L.y < 0.0) {
        L = -L;
    }
    vec4 sp = G.packShadowProj * (G.packShadowMV * vec4(rel + L * G.packShadow.z, 1.0));
    float warp = G.packShadow.y;
    sp.xy /= length(sp.xy) * warp + (1.0 - warp);
    sp.z *= 0.2;
    vec3 uvz = sp.xyz * 0.5 + 0.5;
    if (any(lessThan(uvz, vec3(0.0))) || any(greaterThan(uvz, vec3(1.0)))) {
        return 1.0;
    }
    float lit = 0.0;
    for (int k = 0; k < 4; k++) {
        vec2 o = (vec2(k & 1, k >> 1) - 0.5) * G.packShadow.w;
        lit += uvz.z - 0.00005 <= textureLod(packShadowTex, uvz.xy + o, 0.0).r ? 1.0 : 0.0;
    }
    return mix(1.0, lit * 0.25, G.compat.z);
}

void main() {
    bool leaf = vKind <= 1;
    vec3 albedo;
    if (vKind == 0) {
        vec4 t = bilinear(vCell, vUV);
        if (t.a < 0.5) {
            discard;
        }
        albedo = vColor * t.r;
    } else if (vKind == 1) {
        vec4 t = nearest(vCell, vUV);
        if (t.a < 0.5) {
            discard;
        }
        albedo = t.rgb * vColor;
    } else if (vKind == 3) {
        albedo = nearest(vCell, fract(vUV)).rgb;
    } else {
        float streak = treeNoise(vec3(vUV.x * 9.0, vUV.y * 1.5, float(vCell)));
        albedo = vColor * mix(0.78, 1.08, streak);
    }

    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) {
        n = -n;
    }
    vec3 face = n;
    if (leaf) {
        vec3 upView = normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
        n = normalize(mix(n, upView, 0.45));
    }
    vec3 L = normalize(G.sunDir.xyz);
    float sun = max(G.sunDir.w, 0.0) * smoothstep(0.3, 0.9, vLight.y);
    vec3 light = vanillaLight(vLight);
    float ndl = dot(n, L);
    float diffuse = sun > 0.0 ? mix(0.62, 1.0, max(ndl, 0.0)) : 0.8;
    vec3 color = albedo * light * diffuse * vAo;
    if (leaf) {
        float through = max(-dot(face, L), 0.0) * 0.3 * sun;
        color += albedo * vec3(1.15, 1.2, 0.7) * light * through * vAo;
    }
    float lit = packShadow(G.domainOrigin.xyz + vPos);
    color *= mix(1.0, 0.45, (1.0 - lit) * (sun > 0.0 ? 1.0 : 0.0));
    outColor = vec4(color, 1.0);
}
