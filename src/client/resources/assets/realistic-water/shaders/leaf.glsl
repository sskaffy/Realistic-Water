#define LEAF_TAU 6.2831853

uint leafBits(vec4 vel) {
    return uint(vel.w);
}

uint leafVariant(uint bits) {
    return bits & 3u;
}

uint leafKind(uint bits) {
    return (bits & 3u) | (((bits >> 8) & 3u) << 2);
}

float leafYaw(uint bits) {
    return float((bits >> 2) & 63u) / 64.0 * LEAF_TAU;
}

float leafPhase(uint bits) {
    return float((bits >> 10) & 255u) / 256.0 * LEAF_TAU;
}

float leafBurn(uint bits) {
    return float((bits >> 18) & 63u) / 63.0;
}

float packLeaf(uint bits, float phase, float burn) {
    uint q = uint(round(fract(phase / LEAF_TAU) * 256.0)) & 255u;
    uint b = uint(ceil(clamp(burn, 0.0, 1.0) * 63.0)) & 63u;
    return float((bits & 0x3FFu) | (q << 10) | (b << 18));
}

#define RANK_AWAKE 0x80000000u

uint cellTotal(uint word) {
    return (word >> 16) + (word & 0xFFFFu);
}

#define MEDIUM_WATER_SHIFT 9u
#define MEDIUM_HOT 0x2000u
