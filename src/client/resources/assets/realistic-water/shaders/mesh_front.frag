#version 450
#include "common.glsl"
layout(std430, binding = 3) readonly buffer ViewState { vec4 viewState; };
layout(std430, binding = 6) readonly buffer Heat { int cellHeat[]; };
layout(std430, binding = 7) readonly buffer Count { int cellCount[]; };
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vWall;
layout(location = 0) out vec4 outG;
layout(location = 1) out vec4 outMat;
#define REGION_LIGHT_BINDING 4
#include "region_light.glsl"
#define PACK_SHADOW_BINDING 5
#include "sun_shadow.glsl"

float frozenPhase(vec3 sim) {
    if (!heatOn() || G.freezeA.z <= 0.0) {
        return 0.0;
    }
    vec3 p = sim - 0.5;
    ivec3 b = ivec3(floor(p));
    vec3 f = p - vec3(b);
    float sumHeat = 0.0;
    float sumCount = 0.0;
    for (int k = 0; k < 8; k++) {
        ivec3 o = ivec3(k & 1, (k >> 1) & 1, k >> 2);
        ivec3 c = b + o;
        if (!inGrid(c)) {
            continue;
        }
        int i = cellIndex(c);
        int n = cellCount[i];
        if (n <= 0) {
            continue;
        }
        vec3 w3 = mix(1.0 - f, f, vec3(o));
        float w = w3.x * w3.y * w3.z;
        sumHeat += w * float(cellHeat[i]);
        sumCount += w * float(n);
    }
    if (sumCount <= 1e-4) {
        return 0.0;
    }
    float h = sumHeat / (sumCount * HEAT_FP);
    if (h >= 0.0) {
        return 0.0;
    }
    float L = latentFreeze();
    return h > -L ? -h / L : 1.0 + smoothstep(0.0, 0.08 * L, -h - L);
}

void main() {
    float dist = -vView.z;
    if (dist < G.surf.z) {
        discard;
    }
    if ((viewState.x > 0.5 || G.oceanA.x > 0.5) && vWall > 0.5) {
        discard;
    }
    vec3 n = normalize(vNormal);
    outG = vec4(n, dist);
    vec3 rel = (G.invView * vec4(vView, 1.0)).xyz;
    vec3 nWorld = mat3(G.invView) * n;
    float shadowed = abs(G.matA.x - 3.0) < 0.5 ? 1.0 - sunShadowFactor(relToSim(rel + nWorld * 0.1)) : 0.0;
    float a = G.oceanA.x > 0.1 ? 2.0 : shadowed;
    if (isWater() && G.oceanA.x < 0.5) {
        float phase = frozenPhase(relToSim(rel - nWorld * (0.6 * G.domainOrigin.w)));
        if (phase > 0.01) {
            a = phase * 0.7;
        }
    }
    outMat = vec4(G.matA.x, regionLightAt(relToSim(rel + nWorld * 0.5)), a);
}
