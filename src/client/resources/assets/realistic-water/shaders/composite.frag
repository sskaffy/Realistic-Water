#version 450
#include "common.glsl"
layout(binding = 1) uniform sampler2D gbuffer;
layout(binding = 2) uniform sampler2D thicknessTex;
layout(binding = 3) uniform sampler2D sceneColor;
layout(binding = 4) uniform sampler2D sceneDepth;
layout(binding = 5) uniform sampler2D foamTex;
layout(binding = 6) uniform sampler2D materialTex;
layout(std430, binding = 7) readonly buffer ViewState { vec4 viewState; };
layout(std430, binding = 8) readonly buffer SlimeTex { int slimeTex[]; };
#define LIGHTMAP_BINDING 9
#include "fluid_light.glsl"
layout(binding = 10) uniform sampler2D foamSurfaceTex;
layout(std430, binding = 11) readonly buffer SeaColors { uint seaColors[]; };
layout(push_constant) uniform Push { vec4 area; float filterMat; float sea; float background; float debug; float colorMap; } pc;
#define SEA_COLOR_CELLS 512
const vec3 OCEAN_WATER = vec3(63.0, 118.0, 228.0) / 255.0;
const float VANILLA_WATER_TEXTURE = 0.694;

bool readable(vec2 uv) {
    return all(greaterThanEqual(uv, pc.area.xy)) && all(lessThanEqual(uv, pc.area.zw));
}

float thicknessAt(vec2 uv) {
    return readable(uv) ? texture(thicknessTex, uv).r : 0.0;
}
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265;
const float SPECULAR_CAP = 30.0;

float gBackground = -1.0;
float gDepth = 0.0;
vec3 gScatter = vec3(0.0);
vec3 gAbsorb = vec3(0.0);
vec3 gSeaWater = vec3(-1.0);
float gSeaRough = 0.0;

vec3 unpackRgb(uint v) {
    return vec3((v >> 16) & 0xFFu, (v >> 8) & 0xFFu, v & 0xFFu) / 255.0;
}

vec4 seaColorAt(vec3 rel) {
    vec2 local = vec2(G.dims.xz & 3) + G.camGrid.xz + rel.xz;
    vec2 f = local * 0.25 - 0.5;
    vec2 fl = floor(f);
    vec2 t = f - fl;
    ivec2 base = (G.dims.xz >> 2) + ivec2(fl);
    vec4 sum = vec4(0.0);
    for (int k = 0; k < 4; k++) {
        ivec2 c = base + ivec2(k & 1, k >> 1);
        uint v = seaColors[(c.x & (SEA_COLOR_CELLS - 1)) + (c.y & (SEA_COLOR_CELLS - 1)) * SEA_COLOR_CELLS];
        uint tag = 0x80u | uint((c.x >> 9) & 7) | uint((c.y >> 9) & 7) << 3;
        if ((v >> 24) == tag) {
            float w = ((k & 1) != 0 ? t.x : 1.0 - t.x) * ((k >> 1) != 0 ? t.y : 1.0 - t.y);
            sum += vec4(unpackRgb(v) * w, w);
        }
    }
    return sum.w > 1e-4 ? vec4(sum.rgb / sum.w, sum.w) : vec4(0.0);
}

void seaLook(vec3 rel) {
    gScatter = G.scatter.rgb;
    gAbsorb = G.absorb.rgb;
    gSeaWater = vec3(-1.0);
    if (pc.sea < 0.5 || pc.colorMap < 0.5) {
        return;
    }
    vec4 c = seaColorAt(rel);
    if (c.w <= 0.0) {
        return;
    }
    gSeaWater = c.rgb;
    vec3 ratio = clamp(c.rgb / OCEAN_WATER, vec3(0.25), vec3(4.0));
    gScatter *= ratio;
    gAbsorb /= ratio;
}

vec3 behindAt(vec2 uv) {
    return gBackground < 0.0 ? texture(sceneColor, uv).rgb : vec3(gBackground);
}

float linearDepth(float device) {
    vec4 v = G.invProj * vec4(0.0, 0.0, device, 1.0);
    return -v.z / v.w;
}

vec3 viewPosAt(vec2 uv, float dist) {
    vec4 v = G.invProj * vec4(uv * 2.0 - 1.0, 0.5, 1.0);
    vec3 dir = v.xyz / v.w;
    return dir * (dist / -dir.z);
}

float pixelFootprint(float dist) {
    return 2.0 * dist / max(G.proj[1][1] * G.screen.y, 1e-4);
}

vec3 skyColor(vec3 dirWorld) {
    float t = dirWorld.y;
    vec3 horizon = G.fogColor.rgb;
    vec3 zenith = G.skyColor.rgb;
    if (t >= 0.0) {
        return mix(horizon, zenith, smoothstep(0.0, 0.45, t));
    }
    return horizon * mix(0.9, 0.35, smoothstep(0.0, 0.35, -t));
}

vec3 traceReflection(vec3 origin, vec3 R, out float hit) {
    hit = 0.0;
    if (gBackground >= 0.0) {
        return vec3(0.0);
    }
    vec3 pos = origin;
    float stepLen = 0.15;
    for (int i = 0; i < 48; i++) {
        pos += R * stepLen;
        stepLen *= 1.1;
        vec4 clip = G.proj * vec4(pos, 1.0);
        if (clip.w <= 0.0) {
            break;
        }
        vec2 suv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(suv, vec2(0.0))) || any(greaterThan(suv, vec2(1.0)))) {
            break;
        }
        float sceneDist = linearDepth(texture(sceneDepth, suv).r);
        float rayDist = -pos.z;
        if (rayDist > sceneDist && rayDist - sceneDist < stepLen * 2.0 + 0.25) {
            vec2 edge = smoothstep(vec2(0.0), vec2(0.08), suv) * (1.0 - smoothstep(vec2(0.92), vec2(1.0), suv));
            hit = edge.x * edge.y;
            return texture(sceneColor, suv).rgb;
        }
    }
    return vec3(0.0);
}

const vec2 RIPPLE_DIR[5] = vec2[](vec2(0.94, 0.34), vec2(-0.42, 0.91), vec2(0.71, -0.70), vec2(-0.86, -0.51), vec2(0.21, 0.98));
const float RIPPLE_FREQ[5] = float[](1.0, 1.93, 3.57, 6.41, 11.7);
const float RIPPLE_WEIGHT[5] = float[](0.357, 0.250, 0.179, 0.125, 0.089);

float gRippleLost = 0.0;

vec2 rippleGradient(vec2 p, float t, float footprint) {
    vec2 g = vec2(0.0);
    vec2 q = p;
    float lostVar = 0.0;
    for (int i = 0; i < 5; i++) {
        float lambda = 6.2831853 / RIPPLE_FREQ[i];
        float fade = smoothstep(1.5, 4.0, lambda / max(footprint, 1e-5));
        lostVar += (1.0 - fade * fade) * RIPPLE_WEIGHT[i] * RIPPLE_WEIGHT[i];
    }
    gRippleLost = sqrt(lostVar);
    for (int i = 0; i < 5; i++) {
        float lambda = 6.2831853 / RIPPLE_FREQ[i];
        float fade = smoothstep(1.5, 4.0, lambda / max(footprint, 1e-5));
        if (fade <= 0.0) {
            break;
        }
        float phase = dot(q, RIPPLE_DIR[i]) * RIPPLE_FREQ[i] + t * (0.8 + 0.3 * float(i));
        g += RIPPLE_DIR[i] * (RIPPLE_WEIGHT[i] * fade * cos(phase));
        q += RIPPLE_DIR[i].yx * (RIPPLE_WEIGHT[i] / RIPPLE_FREQ[i] * sin(phase) * 0.6);
    }
    return g;
}

vec3 rippleNormal(vec3 nView, vec3 viewPos, float footprint) {
    gRippleLost = 0.0;
    if (G.shadeA.x <= 0.0) {
        return nView;
    }
    vec3 nWorld = normalize(mat3(G.invView) * nView);
    vec3 world = G.misc.yzw + (G.invView * vec4(viewPos, 1.0)).xyz;
    float scale = max(G.shadeA.y, 1e-3);
    vec2 grad = rippleGradient(world.xz * scale, G.simB.z * G.shadeA.z, footprint * scale);
    vec3 g = vec3(grad.x, 0.0, grad.y) * G.shadeA.x;
    return normalize(mat3(G.view) * normalize(nWorld - (g - nWorld * dot(g, nWorld))));
}

vec3 sunSpecular(vec3 n, vec3 V, float footprint, float extraRough) {
    vec3 L = normalize(G.sunDir.xyz);
    float NoL = dot(n, L);
    if (NoL <= 0.0 || G.sunDir.w <= 0.0) {
        return vec3(0.0);
    }
    vec3 H = normalize(L + V);
    float NoH = max(dot(n, H), 0.0);
    float NoV = max(dot(n, V), 1e-3);
    float base = G.shadeA.w + footprint * 0.25;
    float rough = clamp(extraRough > 0.0 ? sqrt(base * base + extraRough * extraRough) : base, 0.02, 0.7);
    float a2 = rough * rough * rough * rough;
    float den = NoH * NoH * (a2 - 1.0) + 1.0;
    float D = a2 / max(PI * den * den, 1e-9);
    float F = 0.02 + 0.98 * pow(1.0 - max(dot(V, H), 0.0), 5.0);
    float k = rough * rough * 0.5;
    float Vis = 0.5 / max(NoL * (NoV * (1.0 - k) + k) + NoV * (NoL * (1.0 - k) + k), 1e-5);
    return vec3(1.0, 0.96, 0.88) * min(D * Vis * F * NoL * G.shadeB.x, SPECULAR_CAP) * G.sunDir.w;
}

vec3 shadeAboveLava(vec2 uv, vec3 n, float fd, float ambient) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = normalize(-P);
    n = dot(n, V) < 0.0 ? -n : n;
    float footprint = pixelFootprint(fd);
    vec3 wrinkled = rippleNormal(n, P, footprint);
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.2, dot(wrinkled, V))));
    float path = max(thicknessAt(uv), 0.0) * G.waterA.y;
    vec3 glow = G.scatter.rgb * (1.4 + ambient);
    vec3 core = glow * (1.0 - exp(-G.scatter.w * path * 0.45));
    float fresnel = pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 2.0);
    vec3 rim = glow * fresnel * 0.85;
    return core + rim + sunSpecular(n, V, footprint, 0.0) * vec3(1.0, 0.65, 0.25);
}

vec3 shadeAboveWater(vec2 uv, vec3 n, float fd, float sceneDist, float ambient, vec2 bs) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = normalize(-P);
    n = dot(n, V) < 0.0 ? -n : n;
    float footprint = pixelFootprint(fd);
    float thickness = max(thicknessAt(uv), 0.0);
    vec3 upV = normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
    float vertical = thickness * abs(dot(V, upV));
    float film = 1.0 - smoothstep(0.02, max(G.shadeB.z, 0.03), vertical);
    n = normalize(mix(n, upV, film * 0.9));
    float stretched = footprint / max(abs(dot(n, V)), 0.02);
    vec3 wrinkled = rippleNormal(n, P, stretched);
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.15, dot(wrinkled, V))));
    float lostRough = pc.sea > 0.5 ? length(vec2(gSeaRough, gRippleLost * G.shadeA.x)) : 0.0;

    vec2 offset = n.xy * G.waterA.z * clamp(thickness, 0.0, 2.0) / max(fd, 1.0);
    vec2 ruv = clamp(uv + offset, vec2(0.0), vec2(1.0));
    float refrDist = linearDepth(texture(sceneDepth, ruv).r);
    float path = max(thicknessAt(ruv), 0.0);
    if (refrDist < fd) {
        ruv = uv;
        path = thickness;
    }
    path *= G.waterA.y;
    vec3 under = behindAt(ruv);
    float wet = G.shadeB.y * smoothstep(0.0, 0.03, path);
    under = pow(max(under, vec3(0.0)), vec3(1.0 + wet)) * (1.0 - 0.15 * wet);
    vec3 transmitted = under * exp(-gAbsorb * path);
    vec3 lightColor = vanillaLight(bs);
    vec3 scattered = gScatter * lightColor * (1.0 - exp(-G.scatter.w * path));
    vec3 water = transmitted + scattered;
    if (G.compat.w > 0.0) {
        vec3 tint = gSeaWater.r >= 0.0 ? gSeaWater * VANILLA_WATER_TEXTURE * lightColor
            : G.scatter.rgb / max(max(G.scatter.r, G.scatter.g), max(G.scatter.b, 1e-3)) * 0.6 * lightColor;
        water = mix(water, tint, G.compat.w);
    }

    vec3 Rv = reflect(-V, n);
    vec3 Rw = (G.invView * vec4(Rv, 0.0)).xyz;
    float hit;
    vec3 ssr = traceReflection(P + n * 0.05, Rv, hit);
    float skyVisible = smoothstep(0.0, 0.7, bs.y);
    vec3 reflection = mix(skyColor(Rw) * mix(lightColor * 0.3, vec3(1.0), skyVisible), ssr, hit);
    float F = 0.02 + (max(1.0 - lostRough, 0.02) - 0.02) * pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 5.0);
    return mix(water, reflection, F) + sunSpecular(n, V, footprint, lostRough) * smoothstep(0.5, 0.95, bs.y);
}

float vanillaFog(vec3 viewPos) {
    vec3 rel = (G.invView * vec4(viewPos, 1.0)).xyz;
    float spherical = length(rel);
    float cylindrical = max(length(rel.xz), abs(rel.y));
    float env = clamp((spherical - G.fogB.x) / max(G.fogB.y - G.fogB.x, 1e-3), 0.0, 1.0);
    float far = clamp((cylindrical - G.fogB.z) / max(G.fogB.w - G.fogB.z, 1e-3), 0.0, 1.0);
    return max(env, far);
}

vec3 slimeRgb(int argb) {
    return vec3((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF) / 255.0;
}

const float SLIME_IOR = 1.35;
const float BUBBLE_CELL = 0.25;

float slimeBubbles(vec3 origin, vec3 dir, float depth, float footprint) {
    vec3 p = origin / BUBBLE_CELL;
    ivec3 c = ivec3(floor(p));
    vec3 stepDir = sign(dir);
    vec3 inv = 1.0 / max(abs(dir), vec3(1e-5));
    vec3 tMax = mix(vec3(1e9), (stepDir * (vec3(c) - p) + max(stepDir, vec3(0.0))) * inv, abs(stepDir));
    float limit = depth / BUBBLE_CELL;
    float px = footprint / BUBBLE_CELL;
    float t = 0.0;
    float sum = 0.0;
    for (int k = 0; k < 16 && t < limit; k++) {
        uint s = hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u ^ uint(c.z) * 83492791u);
        if (rand(s) < 0.2) {
            vec3 center = vec3(c) + 0.2 + 0.6 * vec3(rand(s), rand(s), rand(s));
            float r = mix(0.07, 0.2, rand(s) * rand(s));
            vec3 oc = center - p;
            float along = dot(oc, dir);
            if (along > 0.0 && along < limit) {
                float d = length(oc - dir * along) / r;
                float edge = px / r;
                float disk = 1.0 - smoothstep(1.0 - edge, 1.0 + edge, d);
                float rim = disk * smoothstep(0.35, 0.95, d);
                sum += (rim + disk * 0.12) * smoothstep(0.6, 2.0, 1.0 / max(edge, 1e-4)) * exp(-along * BUBBLE_CELL * 1.2);
            }
        }
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            t = tMax.x;
            tMax.x += inv.x;
            c.x += int(stepDir.x);
        } else if (tMax.y < tMax.z) {
            t = tMax.y;
            tMax.y += inv.y;
            c.y += int(stepDir.y);
        } else {
            t = tMax.z;
            tMax.z += inv.z;
            c.z += int(stepDir.z);
        }
    }
    return min(sum, 1.0);
}

vec3 shadeSlime(vec2 uv, vec3 n, float fd, vec2 bs) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = normalize(-P);
    n = dot(n, V) < 0.0 ? -n : n;
    float footprint = pixelFootprint(fd);
    vec3 nWorld = normalize(mat3(G.invView) * n);
    vec3 world = G.misc.yzw + (G.invView * vec4(P, 1.0)).xyz;
    vec3 tint = slimeRgb(slimeTex[2]);
    vec3 lightColor = vanillaLight(bs) * G.matD.y;
    float skyVisible = smoothstep(0.0, 0.7, bs.y);
    float outdoors = smoothstep(0.5, 0.95, bs.y);

    float thickness = max(thicknessAt(uv), 0.0);
    vec2 offset = n.xy * G.waterA.z * clamp(thickness, 0.0, 2.0) / max(fd, 1.0);
    vec2 ruv = clamp(uv + offset, vec2(0.0), vec2(1.0));
    float depth = max(thicknessAt(ruv), 0.0);
    if (linearDepth(texture(sceneDepth, ruv).r) < fd) {
        ruv = uv;
        depth = thickness;
    }
    float path = max(depth * G.waterA.y, 0.04 + 0.25 * G.matD.w);
    vec3 sigma = -log(clamp(tint, vec3(0.02), vec3(0.98))) * mix(0.3, 6.0, G.matD.w);
    vec3 T = exp(-sigma * path);
    vec3 n2 = nWorld * nWorld;
    float face = n2.x * 0.6 + n2.z * 0.8 + n2.y * (nWorld.y > 0.0 ? 1.0 : 0.5);
    vec3 body = pow(tint, vec3(1.0 + 0.8 * smoothstep(0.0, 2.0, path))) * mix(face, 1.0, 0.45) * lightColor;
    vec3 color = behindAt(ruv) * T + body * (1.0 - dot(T, vec3(1.0 / 3.0)));

    if (G.shadeB.w > 0.0) {
        vec3 Vw = normalize(mat3(G.invView) * V);
        vec3 into = refract(-Vw, nWorld, 1.0 / SLIME_IOR);
        into = dot(into, into) > 0.5 ? normalize(into) : -Vw;
        float bubbles = slimeBubbles(world, into, min(depth, 3.0), footprint);
        color = mix(color, mix(tint, vec3(1.0), 0.6) * lightColor * 1.15, bubbles * clamp(G.shadeB.w, 0.0, 1.0));
    }

    vec3 L = normalize(G.sunDir.xyz);
    float through = pow(clamp(dot(-V, L), 0.0, 1.0), 6.0) * (1.0 - exp(-path * 2.0)) * exp(-path * 0.6);
    color += tint * through * G.sunDir.w * outdoors * 0.8;

    vec3 Rv = reflect(-V, n);
    vec3 Rw = (G.invView * vec4(Rv, 0.0)).xyz;
    float hit;
    vec3 ssr = traceReflection(P + n * 0.05, Rv, hit);
    vec3 reflection = mix(skyColor(Rw) * mix(lightColor * 0.3, vec3(1.0), skyVisible), ssr, hit);
    float F = 0.03 + 0.97 * pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 5.0);
    color = mix(color, reflection, F * 0.85);
    return color + sunSpecular(n, V, footprint, 0.0) * outdoors;
}

float foamLace(vec2 p, float coverage, float footprint) {
    float size = max(G.shadeB.w, 1e-3);
    vec2 q = p / size;
    ivec2 cell = ivec2(floor(q));
    float f1 = 9.0;
    float f2 = 9.0;
    for (int k = 0; k < 9; k++) {
        ivec2 c = cell + ivec2(k % 3 - 1, k / 3 - 1);
        uint s = hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u);
        vec2 feature = vec2(c) + 0.1 + 0.8 * vec2(rand(s), rand(s));
        float d = length(q - feature);
        if (d < f1) {
            f2 = f1;
            f1 = d;
        } else if (d < f2) {
            f2 = d;
        }
    }
    float wall = 1.0 - smoothstep(0.0, 0.06 + 0.6 * coverage, f2 - f1);
    float detail = smoothstep(0.7, 2.5, size / max(footprint, 1e-5));
    return mix(0.55, wall, detail);
}

vec3 shadeSurfaceFromBelow(vec2 uv, vec3 n, float fd, vec3 deep) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = normalize(-P);
    n = dot(n, V) < 0.0 ? -n : n;
    vec3 wrinkled = rippleNormal(n, P, pixelFootprint(fd));
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.15, dot(wrinkled, V))));
    float cosI = clamp(dot(n, V), 0.0, 1.0);
    const float ETA = 1.333;
    float sinT2 = ETA * ETA * (1.0 - cosI * cosI);
    float F;
    if (G.compat.y > 0.5) {
        if (sinT2 >= 1.0) {
            return deep * 1.4;
        }
        F = 0.02 + 0.98 * pow(1.0 - sqrt(1.0 - sinT2), 5.0);
    } else {
        F = (0.02 + 0.98 * pow(1.0 - cosI, 5.0)) * 0.25;
    }
    vec2 ruv = clamp(uv - n.xy * G.waterA.z * 0.6, vec2(0.0), vec2(1.0));
    vec3 above = behindAt(ruv);
    return mix(above, deep * 1.4, F);
}

vec3 shadePixel(vec2 uv) {
    vec4 g = texture(gbuffer, uv);
    float fd = g.w;
    vec4 matSample = texture(materialTex, uv);
    float matId = matSample.r;
    vec2 surfaceLight = matSample.gb;
    bool lavaPass = abs(pc.filterMat - 2.0) < 0.5;
    bool slimePass = pc.filterMat > 2.5;
    bool surfaceOnly = lavaPass || slimePass;
    vec4 foam = slimePass || !readable(uv) ? vec4(0.0) : texture(foamTex, uv);
    float sceneDev = texture(sceneDepth, uv).r;
    float sceneDist = linearDepth(sceneDev);
    bool hasFluid = fd > 0.0 && fd < sceneDist;
    bool underwater = viewState.x > 0.5 && !slimePass;
    bool matTagged = matId >= -0.5;
    if (hasFluid && matTagged) {
        if (abs(matId - pc.filterMat) > 0.25) {
            discard;
        }
    } else if (surfaceOnly && !(slimePass && G.matD.x > 0.5)) {
        discard;
    }
    if (!surfaceOnly) {
        bool seaPixel = matSample.a > 1.5;
        if (hasFluid && matTagged ? seaPixel != (pc.sea > 0.5) : pc.background < 0.5) {
            discard;
        }
    }
    if (slimePass) {
        vec3 slime = behindAt(uv);
        float slimeDepth = sceneDev;
        if (hasFluid && matTagged) {
            slime = shadeSlime(uv, g.xyz, fd, surfaceLight) * (1.0 - matSample.a);
            vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
            slimeDepth = clip.z / clip.w;
        }
        if (G.matD.x > 0.5) {
            vec3 inside = slimeRgb(slimeTex[2]) * 0.55 * clamp(G.skyColor.w / 0.85, 0.2, 1.0) * G.matD.y;
            slime = mix(slime, inside, G.matD.z);
        }
        gDepth = slimeDepth;
        return slime;
    }
    bool vanillaLavaFog = lavaPass && G.killInfo.w > 0.0;
    if (vanillaLavaFog) {
        if (!hasFluid) {
            discard;
        }
        vec3 lavaColor = underwater ? G.scatter.rgb * (1.2 + G.skyColor.w) * 1.35 : shadeAboveLava(uv, g.xyz, fd, G.skyColor.w);
        float dist = length(viewPosAt(uv, fd));
        float f = clamp((dist - G.killInfo.z) / max(G.killInfo.w - G.killInfo.z, 1e-3), 0.0, 1.0);
        vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
        gDepth = clip.z / clip.w;
        return mix(lavaColor, G.fogColor.rgb, f);
    }
    if (!hasFluid && foam.a < 1e-3 && !underwater) {
        discard;
    }
    float ambient = G.skyColor.w;
    vec3 color = behindAt(uv);
    float outDepth = sceneDev;
    bool seaSurface = hasFluid && matTagged && matSample.a > 1.5;
    gSeaRough = seaSurface ? clamp(fract(matSample.a), 0.0, 0.9) : 0.0;
    float whitecap = seaSurface ? floor(matSample.a - 2.0 + 0.01) / 7.0 : 0.0;
    seaLook(underwater || !hasFluid ? vec3(0.0) : (G.invView * vec4(viewPosAt(uv, fd), 1.0)).xyz);

    if (underwater) {
        vec3 deep = lavaPass ? G.scatter.rgb * (1.2 + ambient) : gScatter * ambient;
        float visible = min(sceneDist, 160.0);
        if (hasFluid) {
            color = lavaPass ? deep * 1.35 : shadeSurfaceFromBelow(uv, g.xyz, fd, deep);
            visible = fd;
            vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
            outDepth = clip.z / clip.w;
        }
        float fog = G.scatter.w * G.misc.x;
        color = color * exp(-gAbsorb * visible * G.misc.x) + deep * (1.0 - exp(-fog * visible));
    } else if (hasFluid) {
        color = lavaPass ? shadeAboveLava(uv, g.xyz, fd, ambient) : shadeAboveWater(uv, g.xyz, fd, sceneDist, ambient, surfaceLight);
        vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
        outDepth = clip.z / clip.w;
    }

    if (whitecap > 0.0 && !underwater) {
        vec3 P = viewPosAt(uv, fd);
        vec3 world = G.misc.yzw + (G.invView * vec4(P, 1.0)).xyz;
        float lace = foamLace(world.xz * 0.8, whitecap, pixelFootprint(fd) * 0.8);
        float cover = whitecap * mix(0.55, 1.0, lace);
        vec3 L = G.sunDir.w > 0.0 ? normalize(G.sunDir.xyz) : normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
        float lit = mix(0.72, 1.08, clamp(dot(normalize(g.xyz), L) * 0.5 + 0.5, 0.0, 1.0));
        color = mix(color, vec3(0.93, 0.96, 1.0) * lit * vanillaLight(surfaceLight), clamp(cover, 0.0, 0.95));
    }

    if (foam.a > 1e-4) {
        vec3 foamColor = foam.rgb / foam.a;
        float coverage = min(1.0 - exp(-foam.a * G.foamA.y), 0.9);
        vec4 foamSurface = texture(foamSurfaceTex, uv);
        float surface = clamp(foamSurface.r / foam.a, 0.0, 1.0);
        if (hasFluid && !underwater) {
            vec3 world = G.misc.yzw + (G.invView * vec4(viewPosAt(uv, fd), 1.0)).xyz;
            coverage *= mix(1.0, foamLace(world.xz, coverage, pixelFootprint(fd)), surface);
        } else if (!underwater) {
            float foamDist = foamSurface.g / foam.a;
            if (foamDist > 0.0) {
                vec3 P = viewPosAt(uv, foamDist);
                vec3 world = G.misc.yzw + (G.invView * vec4(P, 1.0)).xyz;
                coverage *= mix(1.0, foamLace(world.xz, coverage, pixelFootprint(foamDist)), surface);
                if (!hasFluid && G.compat.x < 0.5 && coverage > 0.35) {
                    vec4 clip = G.proj * vec4(P, 1.0);
                    outDepth = max(outDepth, clip.z / clip.w);
                }
            }
        }
        vec3 foamLight = vanillaLight(hasFluid && matTagged ? surfaceLight : vec2(0.0, 1.0));
        color = mix(color, foamColor * foamLight, coverage);
    }

    if (hasFluid && !underwater && matSample.a > 1.5) {
        color = mix(color, G.fogColor.rgb, vanillaFog(viewPosAt(uv, fd)));
    }

    gDepth = outDepth;
    return color;
}

bool debugView(vec2 uv) {
    if (pc.debug < 0.5 || abs(pc.filterMat) > 0.25) {
        return false;
    }
    if (pc.sea < 0.5) {
        discard;
    }
    float fd = texture(gbuffer, uv).w;
    if (fd <= 0.0) {
        discard;
    }
    vec4 mat = texture(materialTex, uv);
    float sceneDist = linearDepth(texture(sceneDepth, uv).r);
    vec3 c = fd >= sceneDist ? vec3(1.0, 1.0, 0.0) : mat.r < -0.5 ? vec3(1.0, 0.0, 1.0) : mat.a > 1.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.3, 1.0);
    outColor = vec4(c, 1.0);
    gl_FragDepth = texture(sceneDepth, uv).r;
    return true;
}

void main() {
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    if (debugView(uv)) {
        return;
    }
    if (G.compat.x < 0.5) {
        outColor = vec4(shadePixel(uv), 1.0);
        gl_FragDepth = gDepth;
        return;
    }
    gBackground = 0.0;
    vec3 over = shadePixel(uv);
    gBackground = 1.0;
    vec3 through = clamp(shadePixel(uv) - over, vec3(0.0), vec3(1.0));
    outColor = vec4(max(over, vec3(0.0)), 1.0 - dot(through, vec3(1.0 / 3.0)));
    gl_FragDepth = gDepth;
}
