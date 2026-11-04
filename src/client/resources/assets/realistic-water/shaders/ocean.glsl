#define OCEAN_WAVES 8
#define OCEAN_GEOMETRIC 5
#define OCEAN_AMP_MAX 1.5
#define OCEAN_REST 0.8888889

float oceanPhase(int i, vec2 p) {
    return G.wavePhase[i >> 2][i & 3] + dot(G.waveA[i].xy, p);
}

float oceanHeight(vec2 p) {
    float h = 0.0;
    for (int i = 0; i < OCEAN_GEOMETRIC; i++) {
        h += G.waveA[i].z * sin(oceanPhase(i, p));
    }
    return h;
}

vec2 oceanSlope(vec2 p, int n) {
    vec2 g = vec2(0.0);
    for (int i = 0; i < n; i++) {
        g += G.waveA[i].xy * (G.waveA[i].z * cos(oceanPhase(i, p)));
    }
    return g;
}

vec2 oceanSlopeFiltered(vec2 p, float amp, float footprint, out float lost) {
    vec2 g = vec2(0.0);
    float lostVar = 0.0;
    for (int i = 0; i < OCEAN_WAVES; i++) {
        vec4 w = G.waveA[i];
        float k = length(w.xy);
        float perPixel = 6.2831853 / max(k * footprint, 1e-6);
        float fade = smoothstep(2.5, 6.0, perPixel);
        float slope = amp * w.z * k;
        g += w.xy * (amp * w.z * fade * cos(oceanPhase(i, p)));
        lostVar += (1.0 - fade * fade) * slope * slope;
    }
    lost = sqrt(lostVar);
    return g;
}

float oceanCorner(ivec2 c) {
    uint s = hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u);
    return rand(s);
}

float oceanNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    ivec2 c = ivec2(i);
    float a = oceanCorner(c);
    float b = oceanCorner(c + ivec2(1, 0));
    float d = oceanCorner(c + ivec2(0, 1));
    float e = oceanCorner(c + ivec2(1, 1));
    return mix(mix(a, b, f.x), mix(d, e, f.x), f.y);
}

float oceanWhitecap(vec2 p, vec2 world, float amp) {
    if (G.seaFoam.x <= 0.0 || amp <= 0.0) {
        return 0.0;
    }
    float h = 0.0;
    float trail = 0.0;
    float sumA = 0.0;
    for (int i = 0; i < OCEAN_GEOMETRIC; i++) {
        float a = G.waveA[i].z;
        float phase = oceanPhase(i, p);
        h += a * sin(phase);
        trail += a * sin(phase + 0.9);
        sumA += a;
    }
    float height = amp * sumA;
    float start = max(G.seaFoam.z, 0.05);
    float big = smoothstep(start, start * 2.5, height);
    if (big <= 0.0) {
        return 0.0;
    }
    h /= max(sumA, 1e-4);
    trail /= max(sumA, 1e-4);
    float reach = mix(0.7, 0.2, big);
    float crest = smoothstep(reach, reach + 0.25, h);
    float back = 0.7 * smoothstep(reach + 0.1, reach + 0.4, trail);
    float t = G.seaFoam.w;
    float n = oceanNoise(world * 0.45 + vec2(t * 0.11, t * 0.07)) * 0.6 + oceanNoise(world * 1.3 - vec2(t * 0.05, t * 0.13)) * 0.4;
    float patches = smoothstep(0.25 - 0.2 * big, 0.75, n);
    return clamp(max(crest, back) * patches * big * G.seaFoam.x, 0.0, 1.0);
}

float seaMaterialAlpha(float rough, float whitecap) {
    return 2.0 + floor(clamp(whitecap, 0.0, 1.0) * 7.0 + 0.5) + clamp(rough, 0.0, 0.9);
}

vec3 oceanVelocity(vec2 p, float depth) {
    vec3 u = vec3(0.0);
    for (int i = 0; i < OCEAN_GEOMETRIC; i++) {
        vec4 w = G.waveA[i];
        float k = length(w.xy);
        float phase = oceanPhase(i, p);
        float e = w.z * w.w * exp(k * min(depth, 0.0));
        vec2 dir = w.xy / max(k, 1e-6);
        u.xz += dir * (e * sin(phase));
        u.y -= e * cos(phase);
    }
    return u;
}
