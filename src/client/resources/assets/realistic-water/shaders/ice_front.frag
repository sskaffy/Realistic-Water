#version 450
#include "common.glsl"
#define LIGHTMAP_BINDING 5
#include "fluid_light.glsl"
layout(binding = 2) uniform sampler2D sceneColor;
layout(binding = 3) uniform sampler2D sceneDepth;
layout(binding = 4) uniform sampler2D backDist;
layout(push_constant) uniform PC { vec4 look; vec4 more; vec4 c; vec4 d; } pc;
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vRel;
layout(location = 2) in vec3 vNormal;
layout(location = 3) in vec3 vPat;
layout(location = 4) in vec2 vLight;
layout(location = 5) in float vFilm;
layout(location = 0) out vec4 outColor;

float sceneDistAt(vec2 uv) {
    float device = texture(sceneDepth, uv).r;
    if (device <= 0.0) {
        return 1e9;
    }
    vec4 v = G.invProj * vec4(uv * 2.0 - 1.0, device, 1.0);
    return -v.z / v.w;
}

float hash3(ivec3 c) {
    return float(hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u ^ uint(c.z) * 83492791u)) * (1.0 / 4294967295.0);
}

float vnoise(vec3 p) {
    ivec3 b = ivec3(floor(p));
    vec3 f = p - vec3(b);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(hash3(b), hash3(b + ivec3(1, 0, 0)), f.x);
    float c = mix(hash3(b + ivec3(0, 1, 0)), hash3(b + ivec3(1, 1, 0)), f.x);
    float d = mix(hash3(b + ivec3(0, 0, 1)), hash3(b + ivec3(1, 0, 1)), f.x);
    float e = mix(hash3(b + ivec3(0, 1, 1)), hash3(b + ivec3(1, 1, 1)), f.x);
    return mix(mix(a, c, f.y), mix(d, e, f.y), f.z);
}

float grainHeight(vec3 p) {
    return vnoise(p) + 0.5 * vnoise(p * 2.3 + 17.0);
}

vec3 bumpNormal(vec3 p, vec3 n, float scale, float amp, float footprint) {
    float fade = 1.0 - smoothstep(0.3, 1.2, footprint * scale);
    if (amp * fade <= 1e-3) {
        return n;
    }
    const float E = 0.35;
    vec3 q = p * scale;
    float h0 = grainHeight(q);
    vec3 g = vec3(grainHeight(q + vec3(E, 0.0, 0.0)), grainHeight(q + vec3(0.0, E, 0.0)), grainHeight(q + vec3(0.0, 0.0, E))) - h0;
    g /= E;
    g -= n * dot(g, n);
    return normalize(n - g * amp * fade);
}

float crackAt(vec3 q) {
    ivec3 b = ivec3(floor(q));
    float f1 = 9.0;
    float f2 = 9.0;
    for (int k = 0; k < 27; k++) {
        ivec3 c = b + ivec3(k % 3, (k / 3) % 3, k / 9) - 1;
        uint h = hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u ^ uint(c.z) * 83492791u);
        vec3 feature = vec3(c) + vec3(float(h & 1023u), float((h >> 10) & 1023u), float((h >> 20) & 1023u)) / 1023.0;
        float d = length(q - feature);
        if (d < f1) {
            f2 = f1;
            f1 = d;
        } else if (d < f2) {
            f2 = d;
        }
    }
    return 1.0 - smoothstep(0.0, 0.035, f2 - f1);
}

vec3 iceInterior(vec3 entry, vec3 dir, float len, float footprint, out float trans) {
    const int STEPS = 6;
    float stepLen = len / float(STEPS);
    vec3 add = vec3(0.0);
    float T = 1.0;
    float bubbleFade = 1.0 - smoothstep(0.04, 0.12, footprint);
    for (int i = 0; i < STEPS; i++) {
        float s = (float(i) + 0.5) * stepLen;
        vec3 q = entry + dir * s;
        float cloud = vnoise(q * 1.6) * 0.65 + vnoise(q * 4.1 + 5.3) * 0.35;
        float core = smoothstep(0.05, 0.5, s);
        float dens = smoothstep(0.42, 0.85, cloud) * pc.look.x * mix(0.3, 1.0, core) * 6.0;
        float a = 1.0 - exp(-dens * stepLen);
        add += T * a * vec3(0.93, 0.96, 1.0);
        vec3 bq = q * vec3(16.0, 6.0, 16.0);
        ivec3 bc = ivec3(floor(bq));
        if (hash3(bc + ivec3(911, 37, 5)) < 0.35 * pc.look.y) {
            vec3 centre = vec3(hash3(bc + ivec3(1, 0, 0)), 0.5, hash3(bc + ivec3(0, 0, 1))) * 0.6 + 0.2;
            float r = 0.1 + 0.12 * hash3(bc + ivec3(0, 1, 0));
            float bubble = smoothstep(r, r * 0.35, length((fract(bq) - centre) * vec3(1.0, 0.35, 1.0)));
            add += T * bubble * 0.4 * bubbleFade;
            T *= 1.0 - bubble * 0.25 * bubbleFade;
        }
        if (pc.look.z > 0.0 && (i == 1 || i == 4)) {
            float crack = crackAt(q * 1.3) * pc.look.z;
            add += T * crack * 0.5;
            T *= 1.0 - crack * 0.3;
        }
        T *= 1.0 - a;
    }
    trans = T;
    return add;
}

vec3 environment(vec3 Rw, float sky, vec2 uv) {
    float t = Rw.y;
    vec3 env = t >= 0.0 ? mix(G.fogColor.rgb, G.skyColor.rgb, smoothstep(0.0, 0.45, t))
        : G.fogColor.rgb * mix(0.9, 0.35, smoothstep(0.0, 0.35, -t));
    env = mix(texture(sceneColor, uv).rgb * 0.8, env, smoothstep(-0.08, 0.05, t));
    return env * mix(0.08, 1.0, smoothstep(0.0, 0.7, sky));
}

float vanillaFog(vec3 viewPos) {
    vec3 rel = (G.invView * vec4(viewPos, 1.0)).xyz;
    float spherical = length(rel);
    float cylindrical = max(length(rel.xz), abs(rel.y));
    float env = clamp((spherical - G.fogB.x) / max(G.fogB.y - G.fogB.x, 1e-3), 0.0, 1.0);
    float far = clamp((cylindrical - G.fogB.z) / max(G.fogB.w - G.fogB.z, 1e-3), 0.0, 1.0);
    return max(env, far);
}

vec4 frostFilm(vec2 uv) {
    vec3 scene = texture(sceneColor, uv).rgb;
    vec3 light = vanillaLight(vLight);
    float grain = vnoise(vPat * vec3(9.0, 1.0, 9.0)) * 0.6 + vnoise(vPat * 31.0) * 0.4;
    float a = clamp(vFilm, 0.0, 1.0) * (0.10 + 0.14 * grain);
    vec3 color = mix(scene, vec3(0.88, 0.93, 1.0) * light, a);
    vec3 V = normalize(-vView);
    vec3 n = normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
    float nh = max(dot(n, normalize(normalize(G.sunDir.xyz) + V)), 0.0);
    color += vec3(1.0) * pow(nh, 160.0) * 0.25 * G.sunDir.w * clamp(vFilm, 0.0, 1.0) * smoothstep(0.5, 0.95, vLight.y);
    color = mix(color, G.fogColor.rgb, vanillaFog(vView));
    return vec4(color, 1.0);
}

void main() {
    if (dot(vRel, vNormal) >= 0.0) {
        discard;
    }
    vec2 size = max(G.screen.xy, vec2(1.0));
    vec2 uv = gl_FragCoord.xy / size;
    if (vFilm > 0.0) {
        outColor = frostFilm(uv);
        return;
    }
    float front = -vView.z;
    float back = texelFetch(backDist, ivec2(gl_FragCoord.xy), 0).r;
    float behind = sceneDistAt(uv);
    float exitDist = back > front ? min(back, behind) : min(front + 0.5, behind);
    float stretch = length(vView) / max(front, 1e-4);
    float thickness = max(exitDist - front, 0.0) * stretch;
    float footprint = length(vView) * 2.0 / max(size.y * G.proj[1][1], 1.0);

    vec3 Vw = normalize(-vRel);
    vec3 nW = bumpNormal(vPat, vNormal, 1.7, 0.05, footprint);
    vec3 n = normalize(mat3(G.view) * nW);
    vec3 V = normalize(-vView);

    vec2 offset = n.xy * pc.look.w * 0.12 * clamp(thickness, 0.0, 2.0) / max(front, 1.0);
    vec2 ruv = clamp(uv + offset, 1.0 / size, 1.0 - 1.0 / size);
    float path = thickness;
    if (sceneDistAt(ruv) < front) {
        ruv = uv;
    }
    vec3 light = vanillaLight(vLight);
    vec3 dir = refract(-Vw, nW, 1.0 / 1.31);
    dir = dot(dir, dir) > 0.0 ? dir : -Vw;
    float trans;
    vec3 inner = iceInterior(vPat - nW * 0.01, dir, min(path, 3.0), footprint, trans) * light;
    vec3 body = texture(sceneColor, ruv).rgb * exp(-vec3(0.30, 0.07, 0.04) * path) * trans + inner;

    vec3 R = reflect(-V, n);
    vec3 Rw = mat3(G.invView) * R;
    vec3 reflection = environment(Rw, vLight.y, uv);
    float F = 0.018 + 0.982 * pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 5.0);
    vec3 color = mix(body, reflection, F);
    vec3 L = normalize(G.sunDir.xyz);
    float nh = max(dot(n, normalize(L + V)), 0.0);
    float sun = G.sunDir.w * smoothstep(0.5, 0.95, vLight.y);
    color += vec3(1.0, 0.98, 0.94) * (pow(nh, 900.0) * 6.0 + pow(nh, 90.0) * 0.12) * sun;
    float frost = smoothstep(0.5, 0.9, nW.y) * (0.5 + 0.5 * vnoise(vPat * 22.0)) * 0.08;
    color = mix(color, vec3(0.9, 0.94, 1.0) * light, frost);
    color = mix(color, G.fogColor.rgb, vanillaFog(vView));
    outColor = vec4(color, 1.0);
}
