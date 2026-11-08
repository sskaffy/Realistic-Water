struct Gel {
    vec3 absorb;
    vec3 scatter;
    float density;
    float haze;
    float refraction;
    vec3 sheen;
    float gloss;
    float glow;
    float bubbles;
};

float gelHash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float gelNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(gelHash(i), gelHash(i + vec3(1, 0, 0)), u.x), mix(gelHash(i + vec3(0, 1, 0)), gelHash(i + vec3(1, 1, 0)), u.x), u.y),
               mix(mix(gelHash(i + vec3(0, 0, 1)), gelHash(i + vec3(1, 0, 1)), u.x), mix(gelHash(i + vec3(0, 1, 1)), gelHash(i + vec3(1, 1, 1)), u.x), u.y),
               u.z);
}

vec3 gelNoiseGradient(vec3 p) {
    const float e = 0.08;
    float c = gelNoise(p);
    return vec3(gelNoise(p + vec3(e, 0, 0)) - c, gelNoise(p + vec3(0, e, 0)) - c, gelNoise(p + vec3(0, 0, e)) - c) / e;
}

vec3 gelSurface(vec3 nWorld, vec3 world, float footprint) {
    float t = G.simB.z;
    float detail = clamp(1.0 - footprint * 6.0, 0.0, 1.0);
    vec3 bump = gelNoiseGradient(world * 2.3 + vec3(0.0, t * 0.05, 0.0)) * 0.045 + gelNoiseGradient(world * 7.5 + 7.0) * 0.012;
    return normalize(nWorld - (bump - nWorld * dot(nWorld, bump)) * detail);
}

vec3 gelHazy(vec2 uv, float radiusPixels) {
    vec3 sum = gelScene(uv);
    if (radiusPixels < 0.75) {
        return sum;
    }
    vec2 texel = 1.0 / max(G.screen.xy, vec2(1.0));
    const float GOLDEN = 2.39996;
    for (int i = 1; i <= 8; i++) {
        float r = sqrt(float(i) / 8.0) * radiusPixels;
        float a = float(i) * GOLDEN;
        sum += gelScene(clamp(uv + vec2(cos(a), sin(a)) * r * texel, texel, 1.0 - texel));
    }
    return sum / 9.0;
}

float gelBubbles(vec3 entry, vec3 dir, float path, float footprint) {
    float fade = clamp(1.0 - footprint * 14.0, 0.0, 1.0);
    if (fade <= 0.0 || path <= 0.02) {
        return 0.0;
    }
    float light = 0.0;
    for (int k = 0; k < 2; k++) {
        float depth = path * (k == 0 ? 0.22 : 0.58);
        vec3 p = (entry + dir * depth) * 9.0;
        vec3 cell = floor(p);
        float h = gelHash(cell + 17.0 * float(k));
        if (h > 0.09) {
            continue;
        }
        vec3 centre = cell + 0.3 + 0.4 * vec3(gelHash(cell + 3.1), gelHash(cell + 5.7), gelHash(cell + 9.3));
        vec3 d = p - centre;
        float r = 0.08 + 0.12 * gelHash(cell + 1.3);
        float along = dot(d, dir);
        float off = length(d - dir * along) / r;
        light += (1.0 - smoothstep(0.55, 1.0, off)) * 0.9 - smoothstep(0.8, 1.0, off) * (1.0 - smoothstep(1.0, 1.2, off)) * 0.35;
    }
    return light * fade;
}

vec3 gelShade(Gel gel, vec2 uv, vec3 P, vec3 n, float thickness, vec3 lightColor, float sky, float sunVis) {
    vec3 V = normalize(-P);
    float dist = length(P);
    float footprint = 2.0 * dist / max(G.proj[1][1] * G.screen.y, 1e-4);
    vec3 world = (G.invView * vec4(P, 1.0)).xyz + G.misc.yzw;
    vec3 nWorld = normalize(mat3(G.invView) * (dot(n, V) < 0.0 ? -n : n));
    nWorld = gelSurface(nWorld, world, footprint);
    n = normalize(mat3(G.view) * nWorld);
    float facing = dot(n, V);
    if (facing < 0.05) {
        n = normalize(n + V * (0.05 - facing));
    }

    float path = clamp(thickness, 0.0, 4.0);
    vec2 bend = n.xy * gel.refraction * clamp(path, 0.0, 1.2) / max(dist * 0.12, 1.0);
    vec2 ruv = clamp(uv + bend, vec2(0.0), vec2(1.0));
    if (gelSceneDist(ruv) < -P.z) {
        ruv = uv;
    }
    float blur = min(gel.haze * clamp(path, 0.0, 1.5) * 0.05 / max(footprint, 1e-4), 24.0);
    vec3 behind = gelHazy(ruv, blur) * mix(vec3(1.0), gel.sheen, 0.15);
    float optical = path / (1.0 + 0.45 * path);
    vec3 transmit = exp(-gel.absorb * optical);

    vec3 L = normalize(G.sunDir.xyz);
    float sunUp = max(G.sunDir.w, 0.0) * sunVis;
    float wrap = clamp((dot(n, L) + 0.5) / 1.5, 0.0, 1.0);
    float through = pow(clamp(dot(-V, L), 0.0, 1.0), 5.0);
    vec3 sunColor = vec3(1.0, 0.95, 0.86) * sunUp;
    vec3 inner = lightColor + sunColor * (0.45 * wrap + gel.glow * 1.4 * through);
    float built = 1.0 - exp(-gel.density * optical);
    vec3 color = behind * transmit + gel.scatter * inner * built;
    float rim = pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 3.0);
    color += gel.scatter * inner * rim * 0.25 * gel.glow * (1.0 - built);

    vec3 dirW = normalize(mat3(G.invView) * refract(-V, n, 1.0 / 1.34) + vec3(0.0, 1e-5, 0.0));
    float bubbles = gelBubbles(world, dirW, path, footprint) * gel.bubbles;
    color += (lightColor * 0.6 + sunColor * 0.8) * mix(gel.sheen, vec3(1.0), 0.5) * bubbles;

    vec3 R = reflect(-V, n);
    vec3 reflection = gelReflection(P, n, R, sky) * mix(vec3(1.0), gel.sheen, 0.35);
    float F = 0.021 + 0.979 * pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 5.0);
    color = mix(color, reflection, F);
    float nh = max(dot(n, normalize(L + V)), 0.0);
    vec3 highlight = mix(vec3(1.0, 0.97, 0.9), gel.sheen, 0.4);
    color += highlight * (pow(nh, 900.0) * 3.5 + pow(nh, 80.0) * 0.18) * sunUp * gel.gloss;
    return color;
}
