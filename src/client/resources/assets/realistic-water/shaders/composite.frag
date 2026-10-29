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
layout(push_constant) uniform Push { vec4 area; float filterMat; } pc;

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

vec2 rippleGradient(vec2 p, float t, float footprint) {
    vec2 g = vec2(0.0);
    vec2 q = p;
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

vec3 sunSpecular(vec3 n, vec3 V, float footprint) {
    vec3 L = normalize(G.sunDir.xyz);
    float NoL = dot(n, L);
    if (NoL <= 0.0 || G.sunDir.w <= 0.0) {
        return vec3(0.0);
    }
    vec3 H = normalize(L + V);
    float NoH = max(dot(n, H), 0.0);
    float NoV = max(dot(n, V), 1e-3);
    float rough = clamp(G.shadeA.w + footprint * 0.25, 0.02, 0.7);
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
    return core + rim + sunSpecular(n, V, footprint) * vec3(1.0, 0.65, 0.25);
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
    vec3 wrinkled = rippleNormal(n, P, footprint);
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.15, dot(wrinkled, V))));

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
    vec3 transmitted = under * exp(-G.absorb.rgb * path);
    vec3 lightColor = vanillaLight(bs);
    vec3 scattered = G.scatter.rgb * lightColor * (1.0 - exp(-G.scatter.w * path));
    vec3 water = transmitted + scattered;

    vec3 Rv = reflect(-V, n);
    vec3 Rw = (G.invView * vec4(Rv, 0.0)).xyz;
    float hit;
    vec3 ssr = traceReflection(P + n * 0.05, Rv, hit);
    float skyVisible = smoothstep(0.0, 0.7, bs.y);
    vec3 reflection = mix(skyColor(Rw) * mix(lightColor * 0.3, vec3(1.0), skyVisible), ssr, hit);
    float F = 0.02 + 0.98 * pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 5.0);
    return mix(water, reflection, F) + sunSpecular(n, V, footprint) * smoothstep(0.5, 0.95, bs.y);
}

vec3 slimeRgb(int argb) {
    return vec3((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF) / 255.0;
}

vec3 slimeTexel(vec2 uv, float texelsPerBlock) {
    ivec2 size = ivec2(slimeTex[0], slimeTex[1]);
    ivec2 t = ivec2(floor(uv * texelsPerBlock));
    t = ((t % size) + size) % size;
    return slimeRgb(slimeTex[4 + t.x + t.y * size.x]);
}

vec3 slimeAlbedo(vec3 world, vec3 nWorld, float footprint) {
    float tpb = float(slimeTex[0]);
    vec3 w = pow(abs(nWorld), vec3(8.0));
    w /= w.x + w.y + w.z;
    vec3 c = w.y * slimeTexel(vec2(world.x, world.z), tpb)
           + w.x * slimeTexel(vec2(world.z, -world.y), tpb)
           + w.z * slimeTexel(vec2(world.x, -world.y), tpb);
    float detail = 1.0 - smoothstep(0.5, 1.5, footprint * tpb);
    return mix(slimeRgb(slimeTex[2]), c, detail);
}

vec3 shadeSlime(vec2 uv, vec3 n, float fd, vec2 bs) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = normalize(-P);
    n = dot(n, V) < 0.0 ? -n : n;
    vec3 nWorld = normalize(mat3(G.invView) * n);
    vec3 world = G.misc.yzw + (G.invView * vec4(P, 1.0)).xyz - nWorld * 0.01;
    vec3 albedo = slimeAlbedo(world, nWorld, pixelFootprint(fd));
    vec3 n2 = nWorld * nWorld;
    float face = n2.x * 0.6 + n2.z * 0.8 + n2.y * (nWorld.y > 0.0 ? 1.0 : 0.5);
    vec3 lit = min(albedo * face * vanillaLight(bs) * G.matD.y, vec3(1.0));
    float path = max(thicknessAt(uv), 0.0) * G.waterA.y;
    float opacity = mix(G.matD.w, 1.0, 1.0 - exp(-path * 3.0));
    vec3 behind = behindAt(uv) * mix(vec3(1.0), albedo * 1.3, 0.75);
    return mix(behind, lit, opacity);
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

    if (underwater) {
        vec3 deep = lavaPass ? G.scatter.rgb * (1.2 + ambient) : G.scatter.rgb * ambient;
        float visible = min(sceneDist, 160.0);
        if (hasFluid) {
            color = lavaPass ? deep * 1.35 : shadeSurfaceFromBelow(uv, g.xyz, fd, deep);
            visible = fd;
            vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
            outDepth = clip.z / clip.w;
        }
        float fog = G.scatter.w * G.misc.x;
        color = color * exp(-G.absorb.rgb * visible * G.misc.x) + deep * (1.0 - exp(-fog * visible));
    } else if (hasFluid) {
        color = lavaPass ? shadeAboveLava(uv, g.xyz, fd, ambient) : shadeAboveWater(uv, g.xyz, fd, sceneDist, ambient, surfaceLight);
        vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
        outDepth = clip.z / clip.w;
    }

    if (foam.a > 1e-4) {
        vec3 foamColor = foam.rgb / foam.a;
        float coverage = min(1.0 - exp(-foam.a * G.foamA.y), 0.9);
        if (hasFluid && !underwater) {
            vec3 world = G.misc.yzw + (G.invView * vec4(viewPosAt(uv, fd), 1.0)).xyz;
            float surface = clamp(texture(foamSurfaceTex, uv).r / foam.a, 0.0, 1.0);
            coverage *= mix(1.0, foamLace(world.xz, coverage, pixelFootprint(fd)), surface);
        }
        vec3 foamLight = vanillaLight(hasFluid && matTagged ? surfaceLight : vec2(0.0, 1.0));
        color = mix(color, foamColor * foamLight, coverage);
    }

    gDepth = outDepth;
    return color;
}

void main() {
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
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
