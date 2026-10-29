#define COVER_SHIFT 14u
#define COVER_OPACITY 0.9
#define SHADOW_STEPS 64

float coverOf(uint v) {
    return float((v >> COVER_SHIFT) & 15u) / 8.0;
}

#ifdef PACK_SHADOW_BINDING
layout(binding = PACK_SHADOW_BINDING) uniform sampler2D packShadowTex;

float packShadowVisibility(vec3 rel, vec3 L) {
    vec4 sp = G.packShadowProj * (G.packShadowMV * vec4(rel + L * G.packShadow.z, 1.0));
    float warp = G.packShadow.y;
    sp.xy /= length(sp.xy) * warp + (1.0 - warp);
    sp.z *= 0.2;
    vec3 uvz = sp.xyz * 0.5 + 0.5;
    if (any(lessThan(uvz, vec3(0.0))) || any(greaterThan(uvz, vec3(1.0)))) {
        return -1.0;
    }
    float t = G.packShadow.w;
    float lit = 0.0;
    for (int k = 0; k < 4; k++) {
        vec2 o = (vec2(k & 1, k >> 1) - 0.5) * t;
        lit += uvz.z - 0.00005 <= textureLod(packShadowTex, uvz.xy + o, 0.0).r ? 1.0 : 0.0;
    }
    return lit * 0.25;
}
#endif

float sunVisibility(vec3 sim) {
    if (G.compat.z <= 0.0) {
        return 1.0;
    }
    vec3 L = normalize(mat3(G.invView) * G.sunDir.xyz);
    if (L.y < 0.0) {
        L = -L;
    }
#ifdef PACK_SHADOW_BINDING
    if (G.packShadow.x > 0.5) {
        float fromPack = packShadowVisibility(G.domainOrigin.xyz + sim * G.domainOrigin.w, L);
        if (fromPack >= 0.0) {
            return fromPack;
        }
    }
#endif
    if (L.y < 0.02) {
        return 1.0;
    }
    int res = max(int(G.camGrid.w + 0.5), 1);
    ivec3 n = max(G.dims.xyz / res, ivec3(1));
    vec3 p = sim / float(res) + L * 0.3;
    ivec3 cell = ivec3(floor(p));
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, n))) {
        return 1.0;
    }
    ivec3 start = cell;
    ivec3 dir = ivec3(sign(L));
    vec3 invL = 1.0 / max(abs(L), vec3(1e-5));
    vec3 next = (vec3(cell) + step(vec3(0.0), L) - p) * sign(L) * invL;
    float light = 1.0;
    uint last = regionLight[cell.x + n.x * (cell.y + n.y * cell.z)];
    for (int i = 0; i < SHADOW_STEPS; i++) {
        if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, n))) {
            float sky = float((last >> 4) & 15u);
            return light * smoothstep(12.5, 15.0, sky);
        }
        uint v = regionLight[cell.x + n.x * (cell.y + n.y * cell.z)];
        if ((v & 0x100u) != 0u) {
            return 0.0;
        }
        if (cell != start) {
            light *= 1.0 - coverOf(v) * COVER_OPACITY;
            if (light < 0.03) {
                return 0.0;
            }
        }
        last = v;
        if (next.x < next.y && next.x < next.z) {
            cell.x += dir.x;
            next.x += invL.x;
        } else if (next.y < next.z) {
            cell.y += dir.y;
            next.y += invL.y;
        } else {
            cell.z += dir.z;
            next.z += invL.z;
        }
    }
    return light;
}

float sunShadowFactor(vec3 sim) {
    float weight = G.compat.z * clamp(G.sunDir.w, 0.0, 1.0);
    if (weight <= 0.0) {
        return 1.0;
    }
    return 1.0 - weight * (1.0 - sunVisibility(sim));
}
