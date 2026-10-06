#version 450
#include "common.glsl"
layout(binding = 1) uniform sampler2D gbuffer;
layout(binding = 2) uniform sampler2D thicknessTex;
layout(binding = 3) uniform sampler2D sceneColor;
layout(binding = 4) uniform sampler2D sceneDepth;
layout(binding = 5) uniform sampler2D foamTex;
layout(std430, binding = 6) readonly buffer ViewState { vec4 viewState; };
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265;

float linearDepth(float device) {
    vec4 v = G.invProj * vec4(0.0, 0.0, device, 1.0);
    return -v.z / v.w;
}

vec3 viewPosAt(vec2 uv, float dist) {
    vec4 v = G.invProj * vec4(uv * 2.0 - 1.0, 0.5, 1.0);
    vec3 dir = v.xyz / v.w;
    return dir * (dist / -dir.z);
}

/** How wide one pixel is in blocks at this distance; the scale everything has to stay above to not alias. */
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
    vec3 world = G.misc.yzw + (G.invView * vec4(viewPos, 0.0)).xyz;
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
    return vec3(1.0, 0.96, 0.88) * min(D * Vis * F * NoL * G.shadeB.x, 30.0) * G.sunDir.w;
}

vec3 shadeAboveWater(vec2 uv, vec3 n, float fd, float sceneDist, float ambient) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = normalize(-P);
    n = dot(n, V) < 0.0 ? -n : n;
    float footprint = pixelFootprint(fd);
    vec3 wrinkled = rippleNormal(n, P, footprint);
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.15, dot(wrinkled, V))));
    float thickness = max(texture(thicknessTex, uv).r, 0.0);

    vec2 offset = n.xy * G.waterA.z * clamp(thickness, 0.0, 2.0) / max(fd, 1.0);
    vec2 ruv = clamp(uv + offset, vec2(0.0), vec2(1.0));
    float refrDist = linearDepth(texture(sceneDepth, ruv).r);
    float path = max(texture(thicknessTex, ruv).r, 0.0);
    if (refrDist < fd) {
        ruv = uv;
        path = thickness;
    }
    path *= G.waterA.y;
    vec3 transmitted = texture(sceneColor, ruv).rgb * exp(-G.absorb.rgb * path);
    vec3 scattered = G.scatter.rgb * ambient * (1.0 - exp(-G.scatter.w * path));
    vec3 water = transmitted + scattered;

    vec3 Rv = reflect(-V, n);
    vec3 Rw = (G.invView * vec4(Rv, 0.0)).xyz;
    float hit;
    vec3 ssr = traceReflection(P + n * 0.05, Rv, hit);
    vec3 reflection = mix(skyColor(Rw), ssr, hit);
    float F = 0.02 + 0.98 * pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 5.0);
    return mix(water, reflection, F) + sunSpecular(n, V, footprint);
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
    if (sinT2 >= 1.0) {
        return deep * 1.4;
    }
    float cosT = sqrt(1.0 - sinT2);
    float F = 0.02 + 0.98 * pow(1.0 - cosT, 5.0);
    vec2 ruv = clamp(uv - n.xy * G.waterA.z * 0.6, vec2(0.0), vec2(1.0));
    vec3 above = texture(sceneColor, ruv).rgb;
    return mix(above, deep * 1.4, F);
}

void main() {
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    vec4 g = texture(gbuffer, uv);
    float fd = g.w;
    vec4 foam = texture(foamTex, uv);
    float sceneDev = texture(sceneDepth, uv).r;
    float sceneDist = linearDepth(sceneDev);
    bool hasFluid = fd > 0.0 && fd < sceneDist;
    bool underwater = viewState.x > 0.5;
    if (!hasFluid && foam.a < 1e-3 && !underwater) {
        discard;
    }
    float ambient = G.skyColor.w;
    vec3 color = texture(sceneColor, uv).rgb;
    float outDepth = sceneDev;

    if (underwater) {
        vec3 deep = G.scatter.rgb * ambient;
        float visible = min(sceneDist, 160.0);
        if (hasFluid) {
            color = shadeSurfaceFromBelow(uv, g.xyz, fd, deep);
            visible = fd;
            vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
            outDepth = clip.z / clip.w;
        }
        float fog = G.scatter.w * G.misc.x;
        color = color * exp(-G.absorb.rgb * visible * G.misc.x) + deep * (1.0 - exp(-fog * visible));
    } else if (hasFluid) {
        color = shadeAboveWater(uv, g.xyz, fd, sceneDist, ambient);
        vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
        outDepth = clip.z / clip.w;
    }

    if (foam.a > 1e-4) {
        vec3 foamColor = foam.rgb / foam.a;
        float coverage = min(1.0 - exp(-foam.a * G.foamA.y), 0.9);
        color = mix(color, foamColor * mix(0.25, 1.0, ambient), coverage);
    }

    outColor = vec4(color, 1.0);
    gl_FragDepth = outDepth;
}
