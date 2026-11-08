#version 450
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 1) flat in uint vSeed;
layout(location = 2) flat in vec3 vRel;
layout(location = 3) flat in float vFrac;
layout(location = 0) out vec4 outColor;
#define REGION_LIGHT_BINDING 2
#define LIGHTMAP_BINDING 3
#include "region_light.glsl"
#include "fluid_light.glsl"

void main() {
    uint s = vSeed;
    float ang = rand(s) * 6.2831853;
    float tilt = mix(0.3, 1.0, rand(s));
    vec2 q = mat2(cos(ang), -sin(ang), sin(ang), cos(ang)) * vUV;
    q.y /= tilt;
    vec2 aq = abs(q);
    float hex = max(aq.x * 0.866025 + aq.y * 0.5, aq.y);
    float broken = rand(s);
    hex += 0.22 * broken * sin(atan(q.y, q.x) * 3.0 + rand(s) * 6.2831853);
    if (hex > 0.9) {
        discard;
    }
    vec3 nView = normalize(vec3((rand(s) - 0.5) * 1.6, (rand(s) - 0.5) * 1.6, 1.0));
    vec2 bs = regionLightAt(relToSim(vRel + vec3(0.0, 0.5, 0.0)));
    vec3 light = vanillaLight(bs);
    vec3 L = normalize(G.sunDir.xyz);
    bool sun = G.sunDir.w > 0.0;
    float diffuse = sun ? 0.72 + 0.28 * max(dot(nView, L), 0.0) : 0.85;
    float edge = smoothstep(0.5, 0.9, hex);
    vec3 base = mix(vec3(0.70, 0.80, 0.88), vec3(0.86, 0.92, 0.96), rand(s));
    vec3 color = base * light * diffuse * mix(0.82, 1.12, edge);
    float glint = sun ? pow(max(dot(nView, normalize(L + vec3(0.0, 0.0, 1.0))), 0.0), 220.0) * 3.0 * G.sunDir.w * smoothstep(0.5, 0.95, bs.y) : 0.0;
    float opacity = clamp(G.freezeC.z, 0.0, 1.0);
    float alpha = clamp(opacity * mix(0.55, 1.0, edge) + min(glint, 1.0) * 0.6, 0.0, 1.0);
    outColor = vec4(color * alpha + vec3(1.0, 0.98, 0.94) * glint, alpha);
}
