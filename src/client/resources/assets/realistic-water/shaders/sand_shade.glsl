uint cellHash(ivec3 c) {
    return hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u ^ uint(c.z) * 83492791u);
}

vec3 grainAlbedo(inout uint s) {
    float k = 1.0 + G.matC.w * (rand(s) - 0.5) * 2.0;
    vec3 c = G.matC.rgb * k;
    float t = rand(s);
    if (t < 0.06) {
        c *= vec3(0.6, 0.56, 0.52);
    } else if (t > 0.965) {
        c = mix(c, vec3(0.97, 0.95, 0.9), 0.6);
    }
    return c;
}

vec3 grainSurface(vec3 world, vec3 nWorld, float footprint, out vec3 albedo) {
    float gs = 2.0 * G.matB.w;
    vec3 base = G.matC.rgb * 0.97;
    float detail = smoothstep(0.7, 2.5, gs / max(footprint, 1e-5));
    if (detail <= 0.0) {
        albedo = base;
        return nWorld;
    }
    vec3 p = world / gs;
    ivec3 cell = ivec3(floor(p));
    float f1 = 1e9;
    float f2 = 1e9;
    vec3 best = p;
    uint bestId = 0u;
    for (int k = 0; k < 27; k++) {
        ivec3 c = cell + ivec3(k % 3, (k / 3) % 3, k / 9) - 1;
        uint id = cellHash(c);
        uint s = id;
        vec3 feature = vec3(c) + 0.15 + 0.7 * vec3(rand(s), rand(s), rand(s));
        float d = length(p - feature);
        if (d < f1) {
            f2 = f1;
            f1 = d;
            best = feature;
            bestId = id;
        } else if (d < f2) {
            f2 = d;
        }
    }
    uint s = bestId ^ 0x5bd1e995u;
    vec3 grain = grainAlbedo(s);
    float seam = smoothstep(0.0, 0.18, f2 - f1);
    albedo = mix(base, grain * mix(0.7, 1.0, seam), detail);
    vec3 r = p - best;
    vec3 tangent = r - nWorld * dot(r, nWorld);
    return normalize(nWorld + tangent * (1.1 * detail));
}

vec3 sandLight(vec3 nView, vec3 albedo, float ao) {
    vec3 L = normalize(G.sunDir.xyz);
    float sun = max(dot(nView, L), 0.0) * G.sunDir.w;
    vec3 nWorld = normalize(mat3(G.invView) * nView);
    float up = nWorld.y * 0.5 + 0.5;
    vec3 skyTint = mix(vec3(1.0), G.skyColor.rgb, 0.2);
    vec3 ambient = skyTint * G.skyColor.w * (0.48 + 0.22 * up) * ao;
    vec3 direct = vec3(1.0, 0.97, 0.92) * 0.42 * sun * mix(0.75, 1.0, ao);
    return albedo * (ambient + direct);
}
