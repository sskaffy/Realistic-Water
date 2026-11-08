#version 450
#include "common.glsl"
#define LIGHTMAP_BINDING 6
#include "fluid_light.glsl"
layout(binding = 3) uniform sampler2D sceneColor;
layout(binding = 4) uniform sampler2D sceneDepth;
layout(binding = 5) uniform sampler2D backDist;
layout(std430, binding = 7) readonly buffer GelParams { vec4 gp[8]; };

layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vRel;
layout(location = 2) in vec3 vNormal;
layout(location = 3) in vec3 vRest;
layout(location = 4) in vec2 vLight;
layout(location = 5) in float vHang;
layout(location = 6) flat in int vKind;
layout(location = 0) out vec4 outColor;

float sceneDistAt(vec2 uv) {
    float device = texture(sceneDepth, uv).r;
    if (device <= 0.0) {
        return 1e9;
    }
    vec4 v = G.invProj * vec4(uv * 2.0 - 1.0, device, 1.0);
    return -v.z / v.w;
}

vec3 gelScene(vec2 uv) {
    return texture(sceneColor, uv).rgb;
}

float gelSceneDist(vec2 uv) {
    return sceneDistAt(uv);
}

vec3 gelReflection(vec3 P, vec3 n, vec3 R, float sky) {
    vec3 Rw = mat3(G.invView) * R;
    float t = Rw.y;
    vec3 env = t >= 0.0 ? mix(G.fogColor.rgb, G.skyColor.rgb, smoothstep(0.0, 0.45, t))
        : G.fogColor.rgb * mix(0.9, 0.35, smoothstep(0.0, 0.35, -t));
    vec3 ground = texture(sceneColor, gl_FragCoord.xy / max(G.screen.xy, vec2(1.0))).rgb;
    env = mix(ground * 0.8, env, smoothstep(-0.08, 0.05, t));
    return env * mix(0.08, 1.0, smoothstep(0.0, 0.7, sky));
}

#include "gel_shade.glsl"

Gel gelOf(int kind) {
    int b = kind * 4;
    Gel g;
    g.absorb = gp[b].rgb;
    g.density = gp[b].w;
    g.scatter = gp[b + 1].rgb;
    g.haze = gp[b + 1].w;
    g.refraction = gp[b + 2].x;
    g.glow = gp[b + 2].y;
    g.bubbles = gp[b + 2].z;
    g.gloss = gp[b + 2].w;
    g.sheen = gp[b + 3].rgb;
    return g;
}

float runHash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 honeyRuns(vec3 rest, vec3 n) {
    if (abs(n.y) > 0.6) {
        return vec3(0.0);
    }
    bool alongZ = abs(n.x) > abs(n.z);
    vec3 across = alongZ ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 face = alongZ ? vec3(sign(n.x), 0.0, 0.0) : vec3(0.0, 0.0, sign(n.z));
    vec3 cell = floor(rest - face * 0.02);
    vec3 local = rest - cell;
    float u = dot(local, across);
    float h = local.y;
    vec3 tilt = vec3(0.0);
    for (int k = 0; k < 3; k++) {
        vec3 s = cell * 3.7 + face * 11.0 + float(k) * 5.3;
        float x = 0.1 + 0.8 * runHash(s);
        float width = 0.018 + 0.018 * runHash(s + 1.7);
        float end = 1.0 - (0.18 + 0.5 * runHash(s + 4.1));
        float dropR = width * 1.7;
        float du = u - x;
        float dh = max(end - h, 0.0);
        float run = length(vec2(du, dh)) / width;
        float drop = length(vec2(du, h - end + dropR * 0.4)) / dropR;
        float r = min(run, drop);
        if (r < 1.0) {
            vec2 d = run < drop ? vec2(du, -dh) : vec2(du, h - end + dropR * 0.4);
            d = length(d) > 1e-5 ? normalize(d) : vec2(0.0);
            float slope = min(r / sqrt(max(1.0 - r * r, 0.05)), 3.0);
            tilt += (across * d.x + vec3(0.0, d.y, 0.0)) * slope * 0.16;
        }
    }
    return tilt;
}

float vanillaFog(vec3 viewPos) {
    vec3 rel = (G.invView * vec4(viewPos, 1.0)).xyz;
    float spherical = length(rel);
    float cylindrical = max(length(rel.xz), abs(rel.y));
    float env = clamp((spherical - G.fogB.x) / max(G.fogB.y - G.fogB.x, 1e-3), 0.0, 1.0);
    float far = clamp((cylindrical - G.fogB.z) / max(G.fogB.w - G.fogB.z, 1e-3), 0.0, 1.0);
    return max(env, far);
}

void main() {
    if (dot(vRel, vNormal) >= 0.0) {
        discard;
    }
    vec2 uv = gl_FragCoord.xy / max(G.screen.xy, vec2(1.0));
    float front = -vView.z;
    float back = texelFetch(backDist, ivec2(gl_FragCoord.xy), 0).r;
    float behind = sceneDistAt(uv);
    float exitDist = back > front ? min(back, behind) : min(front + 0.5, behind);
    float thickness = max(exitDist - front, 0.0) * length(vView) / max(front, 1e-4);
    vec3 nWorld = normalize(vNormal);
    if (vKind == 1) {
        nWorld = normalize(nWorld + honeyRuns(vRest, nWorld));
    }
    vec3 n = normalize(mat3(G.view) * nWorld);
    vec3 light = vanillaLight(vLight);
    float sunVis = smoothstep(0.5, 0.95, vLight.y);
    vec3 color = gelShade(gelOf(vKind), uv, vView, n, thickness, light, vLight.y, sunVis);
    color = mix(color, G.fogColor.rgb, vanillaFog(vView));
    outColor = vec4(color, 1.0);
}
