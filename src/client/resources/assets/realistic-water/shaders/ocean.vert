#version 450
#include "common.glsl"
#include "ocean.glsl"
layout(std430, binding = 1) readonly buffer Entries { uvec4 entries[]; };
layout(location = 0) out vec3 vView;
layout(location = 1) out vec3 vRel;
layout(location = 2) out float vAmp;
layout(location = 3) out vec2 vLight;
layout(location = 4) out vec3 vSideNormal;
layout(location = 5) out vec2 vSlopeDev;

const int CORNER[6] = int[](0, 1, 2, 0, 2, 3);
const vec2 CORNER_XZ[4] = vec2[](vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0), vec2(1.0, 0.0));
const ivec2 SIDE_CORNERS[4] = ivec2[](ivec2(0, 3), ivec2(2, 1), ivec2(1, 0), ivec2(3, 2));
const vec3 SIDE_NORMAL[4] = vec3[](vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0), vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0));

#define UNDER_ZONE 0.45

bool insideZone(vec3 p) {
    for (int z = 0; z < G.caps.z; z++) {
        if (all(greaterThanEqual(p, G.ent[z * 2].xyz)) && all(lessThan(p, G.ent[z * 2 + 1].xyz))) {
            return true;
        }
    }
    return false;
}

float byteAt(uint v, int i) {
    return float((v >> (8 * i)) & 0xFFu) / 255.0;
}

void main() {
    int id = gl_VertexIndex / 6;
    int c = CORNER[gl_VertexIndex % 6];
    uvec4 a = entries[id * 2];
    uvec4 b = entries[id * 2 + 1];
    ivec3 block = ivec3(int(a.x), int(a.z & 0xFFFFu) - 32768, int(a.y));
    uint kind = (a.z >> 24) & 7u;
    vLight = vec2(float((a.z >> 16) & 15u), float((a.z >> 20) & 15u)) / 15.0;
    vec3 local;
    float amp;
    if (kind == 0u) {
        local = vec3(CORNER_XZ[c].x, byteAt(a.w, c) - 0.001, CORNER_XZ[c].y);
        amp = byteAt(b.x, c) * OCEAN_AMP_MAX;
        vSideNormal = vec3(0.0);
    } else {
        int side = int(kind) - 1;
        int corner = c == 0 || c == 3 ? SIDE_CORNERS[side].x : SIDE_CORNERS[side].y;
        bool top = c < 2;
        local = vec3(CORNER_XZ[corner].x, top ? byteAt(a.w, corner) - 0.001 : 0.0, CORNER_XZ[corner].y);
        amp = top ? byteAt(b.x, corner) * OCEAN_AMP_MAX : 0.0;
        vSideNormal = SIDE_NORMAL[side];
    }
    vec3 rel = vec3(block - G.dims.xyz) + local;
    rel.y += amp * oceanHeight(rel.xz);
    if (local.y > 0.0 && insideZone(vec3(block - G.dims.xyz) + vec3(0.5, OCEAN_REST, 0.5))) {
        rel.y -= UNDER_ZONE;
    }
    vRel = rel;
    vAmp = amp;
    vSlopeDev = vec2(0.0);
    vec4 view = G.view * vec4(G.domainOrigin.xyz + rel, 1.0);
    vView = view.xyz;
    gl_Position = G.proj * view;
}
