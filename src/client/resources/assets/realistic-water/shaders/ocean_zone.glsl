#include "ocean.glsl"
#define OCEAN_REST_WATER 0x800000u
#define OCEAN_TOP 0x20000000u

bool oceanOn() {
    return G.oceanA.x > 0.5;
}

float oceanBand(vec3 x) {
    float d = min(min(x.x - G.oceanA.y, G.oceanB.y - x.x), min(x.z - G.oceanA.z, G.oceanB.z - x.z));
    return 1.0 - smoothstep(0.0, max(G.oceanA.w, 1.0), d);
}

bool oceanColumn(vec3 x, out float top, out float amp) {
    int res = max(int(G.camGrid.w + 0.5), 1);
    ivec3 n = max(G.dims.xyz / res, ivec3(1));
    ivec3 b = clamp(ivec3(floor(x / float(res))), ivec3(0), n - 1);
    for (int k = 0; k < 9; k++) {
        int dy = (k & 1) == 0 ? k / 2 : -(k + 1) / 2;
        int y = b.y + dy;
        if (y < 0 || y >= n.y) {
            continue;
        }
        uint v = regionLight[b.x + n.x * (y + n.y * b.z)];
        if ((v & OCEAN_TOP) != 0u) {
            top = (float(y) + OCEAN_REST) * float(res);
            amp = float((v >> 24) & 31u) / 31.0 * OCEAN_AMP_MAX;
            return true;
        }
    }
    top = 0.0;
    amp = 0.0;
    return false;
}

bool oceanTarget(vec3 x, out float surface, out vec3 velocity) {
    float top;
    float amp;
    if (!oceanColumn(x, top, amp)) {
        surface = 0.0;
        velocity = vec3(0.0);
        return false;
    }
    float res = G.camGrid.w;
    vec2 p = x.xz / res;
    surface = top + amp * oceanHeight(p) * res;
    velocity = amp * oceanVelocity(p, (x.y - surface) / res) * res;
    return true;
}
