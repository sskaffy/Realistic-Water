#version 450
#include "common.glsl"
#define GRASS_PARAMS_BINDING 1
#define GRASS_BLOCKS_BINDING 2
#include "grass_common.glsl"
#define LIGHTMAP_BINDING 4
#include "fluid_light.glsl"

layout(location = 0) in vec3 vNormal;
layout(location = 1) in float vT;
layout(location = 2) in float vSide;
layout(location = 3) in vec2 vUV;
layout(location = 4) flat in vec3 vTint;
layout(location = 5) flat in vec2 vLight;
layout(location = 6) flat in float vShade;
layout(location = 7) flat in int vType;
layout(location = 8) flat in float vPress;
layout(location = 9) flat in uint vSeed;
layout(location = 10) in vec3 vPos;
layout(location = 11) flat in float vWilt;
layout(location = 0) out vec4 outColor;
layout(binding = 5) uniform sampler2D grassShadowTex;
layout(binding = 6) uniform sampler2D packShadowTex;

#include "grass_shadow.glsl"

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

bool cardBlades(vec2 uv, uint seed, out float height) {
    height = 0.0;
    uint s = seed;
    for (int i = 0; i < 6; i++) {
        float x = 0.1 + 0.8 * rand(s);
        float h = mix(0.55, 1.0, rand(s));
        float lean = (rand(s) - 0.5) * 0.35;
        float w = mix(0.035, 0.07, rand(s));
        if (uv.y > h) {
            continue;
        }
        float f = uv.y / h;
        if (abs(uv.x - (x + lean * f * f)) < w * (1.0 - f)) {
            height = f;
            return true;
        }
    }
    return false;
}

vec3 saturate3(vec3 c, float s) {
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    return max(mix(vec3(l), c, s), vec3(0.0));
}

void main() {
    bool cartoon = P.lod.z > 0.5;
    float t = vT;
    if (P.info.y == 2) {
        float h;
        if (!cardBlades(vUV, vSeed, h)) {
            discard;
        }
        t = h;
    }
#ifdef SHADOW
    outColor = vec4(gl_FragCoord.z, 0.0, 0.0, 1.0);
    return;
#endif
    bool dry = grassDry(vType);
    uint s = vSeed;
    float variation = mix(0.82, 1.15, vShade);
    vec3 albedo;
    if (dry) {
        vec3 root = cartoon ? vec3(0.66, 0.47, 0.22) : vec3(0.52, 0.42, 0.26);
        vec3 tipC = cartoon ? vec3(0.98, 0.84, 0.45) : vec3(0.80, 0.70, 0.47);
        albedo = mix(root, tipC, smoothstep(0.0, 0.85, t)) * variation;
    } else if (cartoon) {
        vec3 lime = mix(vec3(0.40, 0.66, 0.13), vec3(0.66, 0.87, 0.22), smoothstep(0.0, 0.9, t));
        vec3 hue = mix(vec3(0.86, 1.0, 0.82), vec3(1.12, 1.02, 0.80), vShade);
        albedo = lime * hue;
        albedo = mix(albedo, albedo * (vTint / max(dot(vTint, vec3(0.333)), 0.05)) * 0.55, 0.3);
        albedo *= vSide > 0.0 ? 1.07 : 0.93;
    } else {
        vec3 base = vTint;
        albedo = base * mix(0.46, 0.8, smoothstep(0.0, 0.9, t));
        albedo = mix(albedo, albedo * vec3(1.1, 1.05, 0.72), smoothstep(0.55, 1.0, t) * 0.45);
        albedo *= variation;
        if (vShade < 0.07) {
            albedo = mix(albedo, vec3(0.55, 0.50, 0.30) * mix(0.5, 1.0, t), 0.45);
        }
        albedo *= 1.0 + 0.06 * (1.0 - abs(vSide));
    }

    albedo *= 1.0 - 0.1 * vPress;
    albedo = mix(albedo, vec3(0.62, 0.54, 0.34) * mix(0.7, 1.0, t), vWilt * 0.85);

    vec3 n = normalize(vNormal);

    if (!gl_FrontFacing) {
        n = -n;
    }

    vec3 upView = normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
    vec3 blade = n;
    n = normalize(mix(n, upView, 0.6));
    vec3 V = vec3(0.0, 0.0, 1.0);
    vec3 L = normalize(G.sunDir.xyz);
    float sun = max(G.sunDir.w, 0.0) * smoothstep(0.3, 0.9, vLight.y);
    vec3 light = vanillaLight(vLight);
    float ndl = dot(n, L);
    float bladeNdl = dot(blade, L);
    vec3 color;
    if (cartoon) {
        float shade = sun > 0.0 ? mix(0.82, 1.0, clamp(ndl * 0.5 + 0.5, 0.0, 1.0)) : 0.9;
        float foot = mix(0.8, 1.0, smoothstep(0.0, 0.45, t));
        float rim = pow(1.0 - abs(dot(n, V)), 3.0) * 0.12 * t;
        color = albedo * light * (shade * foot + rim);
    } else {
        float diffuse = sun > 0.0 ? mix(0.62, 1.0, max(ndl, 0.0)) : 0.8;
        float through = max(-bladeNdl, 0.0) * 0.35 * sun * (1.0 - vPress);
        float ao = mix(0.72, 1.0, smoothstep(0.0, 0.5, t));
        float spec = pow(max(dot(blade, normalize(L + V)), 0.0), 60.0) * 0.05 * sun * t * (1.0 - vPress);
        color = albedo * light * (diffuse * ao) + albedo * vec3(1.2, 1.25, 0.8) * light * through * ao + light * spec;
    }
    float lit = min(grassShadowLit(vPos, 1.0), packShadow(G.domainOrigin.xyz + vPos));
    color *= mix(1.0, 1.0 - P.shadow.y, (1.0 - lit) * (sun > 0.0 ? 1.0 : 0.0));
    outColor = vec4(color, 1.0);
}
