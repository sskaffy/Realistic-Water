layout(binding = SNOW_SHADOW_BINDING) uniform sampler2D packShadowTex;

#define SPARKLE_CELLS 28.0

float snowPackShadow(vec3 rel, vec3 L, vec3 n) {
    if (G.packShadow.x < 0.5 || G.compat.z <= 0.0) {
        return 1.0;
    }
    float facing = clamp(dot(n, L), 0.0, 1.0);
    vec3 p = rel + n * 0.06 + L * (G.packShadow.z + 0.04 + 0.12 * (1.0 - facing));
    vec4 sp = G.packShadowProj * (G.packShadowMV * vec4(p, 1.0));
    float warp = G.packShadow.y;
    sp.xy /= length(sp.xy) * warp + (1.0 - warp);
    sp.z *= 0.2;
    vec3 uvz = sp.xyz * 0.5 + 0.5;
    if (any(lessThan(uvz, vec3(0.0))) || any(greaterThan(uvz, vec3(1.0)))) {
        return 1.0;
    }
    float t = G.packShadow.w;
    float lit = 0.0;
    float total = 0.0;
    for (int k = 0; k < 9; k++) {
        vec2 o = vec2(k % 3 - 1, k / 3 - 1);
        float w = (2.0 - abs(o.x)) * (2.0 - abs(o.y));
        lit += w * (uvz.z - 0.00005 <= textureLod(packShadowTex, uvz.xy + o * t, 0.0).r ? 1.0 : 0.0);
        total += w;
    }
    return mix(1.0, lit / total, G.compat.z);
}

float snowFog(vec3 rel) {
    float spherical = length(rel);
    float cylindrical = max(length(rel.xz), abs(rel.y));
    float env = clamp((spherical - G.fogB.x) / max(G.fogB.y - G.fogB.x, 1e-3), 0.0, 1.0);
    float far = clamp((cylindrical - G.fogB.z) / max(G.fogB.w - G.fogB.z, 1e-3), 0.0, 1.0);
    return max(env, far);
}

uint snowSeed(ivec3 c) {
    return hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u ^ uint(c.z) * 83492791u);
}

vec3 snowGlints(vec3 world, vec3 n, vec3 L, vec3 V, float footprint, float cells, float salt, float fadeIn) {
    float perPixel = footprint * cells;
    float visible = 1.0 - smoothstep(0.6, 2.0, perPixel);
    if (fadeIn > 0.0) {
        visible *= smoothstep(0.5 * fadeIn, fadeIn, perPixel);
    }
    if (visible <= 0.0) {
        return vec3(0.0);
    }
    vec3 p = world * cells + salt;
    vec3 base = floor(p);
    uint seed = snowSeed(ivec3(base));
    if (rand(seed) < 0.55) {
        return vec3(0.0);
    }
    vec3 centre = vec3(0.3) + 0.4 * vec3(rand(seed), rand(seed), rand(seed));
    float radius = clamp(perPixel, 0.14, 0.5);
    float point = 1.0 - smoothstep(0.5 * radius, radius, length(p - base - centre));
    if (point <= 0.0) {
        return vec3(0.0);
    }
    vec3 facet = normalize(n * 1.2 + randInSphere(seed));
    float glint = pow(max(dot(reflect(-L, facet), V), 0.0), 600.0);
    vec3 tint = mix(vec3(1.0, 0.97, 0.9), 0.5 + 0.5 * cos(6.2831853 * (rand(seed) + vec3(0.0, 0.33, 0.67))), 0.3);
    return tint * (glint * point * visible);
}

vec2 snowGrain(vec2 uv) {
    vec2 g1 = vec2(snowNoise(uv * 9.0) - snowNoise(uv * 9.0 + vec2(0.37, 0.0)),
        snowNoise(uv * 9.0) - snowNoise(uv * 9.0 + vec2(0.0, 0.37)));
    vec2 g2 = vec2(snowNoise(uv * 31.0 + 7.0) - snowNoise(uv * 31.0 + vec2(7.37, 7.0)),
        snowNoise(uv * 31.0 + 7.0) - snowNoise(uv * 31.0 + vec2(7.0, 7.37)));
    return g1 * 0.35 + g2 * 0.2;
}

vec3 snowShade(vec3 local, vec3 nIn, vec2 lightLevel, float trodden, float wetIn, float dent) {
    vec3 rel = G.domainOrigin.xyz + local;
    vec3 world = local + vec3(SP.noiseOrigin.x, SP.track2.z, SP.noiseOrigin.y);
    float packed = clamp(trodden, 0.0, 1.0) * SP.look2.z;
    float wetness = clamp(wetIn, 0.0, 1.0);
    float overcast = clamp(SP.noiseOrigin.z, 0.0, 1.0);
    vec3 V = normalize(-rel);

    vec3 n = normalize(nIn);
    float footprint = length(fwidth(local));
    float grainFade = 1.0 - smoothstep(0.02, 0.12, footprint);
    float topShare = smoothstep(0.35, 0.65, abs(n.y));
    vec3 sideAxis = abs(n.x) > abs(n.z) ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec2 sideUv = vec2(dot(world, sideAxis), world.y);
    vec3 bend = vec3(0.0);
    float mottle = 0.0;
    if (topShare > 0.0) {
        vec2 g = snowGrain(world.xz);
        bend += topShare * vec3(g.x, 0.0, g.y);
        mottle += topShare * snowNoise(world.xz * 2.3);
    }
    if (topShare < 1.0) {
        vec2 g = snowGrain(sideUv);
        bend += (1.0 - topShare) * (sideAxis * g.x + vec3(0.0, g.y, 0.0));
        mottle += (1.0 - topShare) * snowNoise(sideUv * 2.3);
    }
    n = normalize(n + bend * (grainFade * (1.0 - 0.7 * packed)));

    vec3 light = vanillaLight(lightLevel);
    vec3 n2 = n * n;
    float vanillaFace = n2.x * 0.6 + n2.z * 0.8 + n2.y * (n.y > 0.0 ? 1.0 : 0.5);
    float face = mix(vanillaFace, 1.0, 0.55);
    vec3 albedo = vec3(0.955, 0.97, 1.0);
    albedo *= 0.96 + 0.06 * mottle;
    albedo = mix(albedo, albedo * vec3(0.8, 0.86, 0.95), packed);
    albedo *= 1.0 - 0.35 * wetness;
    vec3 color = albedo * light * face * SP.look.w;

    float cavity = clamp(-dent / 0.3, 0.0, 1.0);
    color *= 1.0 - 0.06 * cavity;

    vec3 L = normalize(mat3(G.invView) * G.sunDir.xyz);
    bool day = G.sunDir.w > 0.0 && L.y > 0.0;
    float sky = smoothstep(0.5, 0.95, lightLevel.y);
    float sun = day ? smoothstep(0.0, 0.25, G.sunDir.w) * (1.0 - 0.85 * overcast) : 0.0;
    float sunlit = 0.0;
    if (sun > 0.0) {
        sunlit = sun * snowPackShadow(rel, L, n) * sky * clamp((dot(n, L) + 0.25) / 1.25 * 1.5, 0.0, 1.0);
    }
    float shade = sky * (1.0 - sunlit) * (1.0 - 0.6 * overcast);
    color = mix(color, color * vec3(0.8, 0.88, 1.06), clamp(shade * SP.look2.y * (1.0 + 0.25 * cavity), 0.0, 1.0));
    vec3 sunTint = mix(vec3(1.15, 1.0, 0.86), vec3(1.06), smoothstep(0.05, 0.4, L.y));
    color *= mix(vec3(1.0), sunTint, sunlit);

    float graze = pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 4.0);
    color += G.skyColor.rgb * light * (graze * 0.07 * sky * (1.0 - 0.5 * packed));

    float gloss = 0.1 * packed + 0.3 * wetness;
    if (gloss > 0.0 && sunlit > 0.0) {
        vec3 H = normalize(L + V);
        color += sunTint * (pow(max(dot(n, H), 0.0), 40.0) * gloss * sunlit);
    }

    if (SP.look2.x > 0.0) {
        bool night = L.y < -0.1;
        vec3 from = night ? -L : L;
        float power = night
            ? 0.12 * sky * (1.0 - overcast) * clamp(dot(n, from) * 1.5, 0.0, 1.0) * smoothstep(0.1, 0.3, -L.y)
            : sunlit;
        power *= SP.look2.x * (1.0 - 0.8 * packed) * (1.0 - wetness);
        if (power > 0.0) {
            vec3 glints = snowGlints(world, n, from, V, footprint, SPARKLE_CELLS, 0.0, 0.0) * 6.0
                + snowGlints(world, n, from, V, footprint, SPARKLE_CELLS * 0.37, 19.5, 0.25) * 4.0;
            color += glints * power * (night ? vec3(0.75, 0.85, 1.0) : vec3(1.0));
        }
    }
    return mix(color, G.fogColor.rgb, snowFog(rel));
}
