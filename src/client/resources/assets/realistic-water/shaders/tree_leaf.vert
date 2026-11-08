#version 450
#include "common.glsl"
#define TREE_PARAMS_BINDING 1
#define TREE_BLOCKS_BINDING 2
#define TREE_MODS_BINDING 8
#include "tree_common.glsl"
layout(std430, binding = 3) readonly buffer LeafList { uint leafList[]; };
layout(std430, binding = 7) readonly buffer Motion { TreeMotion motion[]; };

layout(location = 0) out vec3 vNormal;
layout(location = 1) out vec2 vUV;
layout(location = 2) flat out int vCell;
layout(location = 3) out vec3 vColor;
layout(location = 4) flat out vec2 vLight;
layout(location = 5) out float vAo;
layout(location = 6) out vec3 vPos;
layout(location = 7) flat out int vKind;
layout(location = 8) out float vT;

const vec2 CORNER[6] = vec2[](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));

void main() {
    uint e = leafList[gl_InstanceIndex];
    int slot = int(e >> 10);
    int i = int(e & 1023u);
    if (slot >= T.cam.w || !treeActive(tblocks[slot])) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
    TreeBlock b = tblocks[slot];
    vec3 corner = vec3(b.pos.xyz - T.cam.xyz);
    float dist = length(G.domainOrigin.xyz + corner + 0.5);
    TreeLeaf l = treeLeaf(b, i, treeLeafCount(dist));
    treeLeafMove(l, motion[slot]);
    if (!treeLeafModify(b, i, corner, l)) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
    vec2 c = CORNER[gl_VertexIndex % 6];
    vec3 pos = corner + l.centre + l.across * (c.x * l.size * 0.5) + l.along * (c.y * l.size * 0.5);
    gl_Position = G.proj * (G.view * vec4(G.domainOrigin.xyz + pos, 1.0));
    vPos = pos;
    vNormal = mat3(G.view) * l.normal;
    vAo = l.ao;
    vT = c.y * 0.5 + 0.5;
    float packed = b.look.w;
    vLight = vec2(floor(packed / 16.0), mod(packed, 16.0)) / 15.0;
    uint s = hash(l.seed ^ 0x68E31DA4u);
    if (treeVanilla()) {
        float w = min(l.size, 1.0);
        vec2 o = vec2(rand(s), rand(s)) * (1.0 - w);
        vUV = o + vec2(c.x * 0.5 + 0.5, 0.5 - c.y * 0.5) * w;
        vCell = int(b.info.x & 255u);
        vColor = b.look.rgb;
        vKind = 1;
    } else {
        vUV = vec2(c.x * 0.5 + 0.5, 0.5 - c.y * 0.5);
        vCell = int(l.seed & 3u);
        float variation = mix(0.80, 1.18, rand(s));
        vec3 hue = mix(vec3(1.05, 0.98, 0.85), vec3(0.92, 1.03, 1.06), rand(s));
        vColor = treeUnpackRgb(b.info.z) * b.look.rgb * 1.38 * variation * hue;
        vKind = 0;
    }
}
