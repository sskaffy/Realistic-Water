float grassShadowTap(vec2 uv, float z) {
    vec2 size = vec2(textureSize(grassShadowTex, 0));
    vec2 st = uv * size - 0.5;
    vec2 f = fract(st);
    vec4 d = textureGather(grassShadowTex, (floor(st) + 1.0) / size, 0);
    vec4 lit = vec4(greaterThanEqual(d, vec4(z)));
    return mix(mix(lit.w, lit.z, f.x), mix(lit.x, lit.y, f.x), f.y);
}

float grassShadowLit(vec3 p, float biasScale) {
    if (P.shadow.x < 0.5) {
        return 1.0;
    }
    const vec2 DISC[8] = vec2[](vec2(-0.613, 0.617), vec2(0.170, -0.040), vec2(-0.299, -0.792), vec2(0.645, 0.493),
        vec2(-0.651, -0.081), vec2(0.421, -0.554), vec2(-0.017, 0.977), vec2(0.909, -0.123));
    vec2 texel = vec2(0.5, 1.0) * P.shadow.z;
    vec4 sn = P.shadowNearVP * vec4(p, 1.0);
    vec2 un = sn.xy * 0.5 + 0.5;
    vec2 uv;
    float z;
    vec2 lo;
    vec2 hi;
    if (P.shadowNear.x > 0.0 && all(greaterThan(un, vec2(0.03))) && all(lessThan(un, vec2(0.97)))) {
        uv = vec2(un.x * 0.5, un.y);
        z = sn.z - P.shadowNear.z * biasScale;
        lo = vec2(texel.x, texel.y);
        hi = vec2(0.5 - texel.x, 1.0 - texel.y);
    } else {
        vec4 sf = P.shadowVP * vec4(p, 1.0);
        vec2 uf = sf.xy * 0.5 + 0.5;
        if (any(lessThan(uf, vec2(0.0))) || any(greaterThan(uf, vec2(1.0)))) {
            return 1.0;
        }
        uv = vec2(0.5 + uf.x * 0.5, uf.y);
        z = sf.z - P.shadow.w * biasScale;
        lo = vec2(0.5 + texel.x, texel.y);
        hi = vec2(1.0 - texel.x, 1.0 - texel.y);
    }
    float lit = 0.0;
    for (int k = 0; k < 8; k++) {
        lit += grassShadowTap(clamp(uv + DISC[k] * texel * 1.6, lo, hi), z);
    }
    return lit / 8.0;
}
