#version 450
#include "common.glsl"
#define TREE_PARAMS_BINDING 1
#define TREE_BLOCKS_BINDING 2
#define TREE_MODS_BINDING 8
#include "tree_common.glsl"
layout(std430, binding = 3) readonly buffer StickList { uint stickList[]; };
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

const int CT[6] = int[](0, 0, 1, 0, 1, 1);
const int CS[6] = int[](0, 1, 1, 0, 1, 0);

void main() {
    uint e = stickList[gl_InstanceIndex];
    int slot = int(e >> 3);
    int k = int(e & 7u);
    bool vanilla = treeVanilla();
    int sides = vanilla ? 4 : TREE_STICK_SIDES;
    int segments = vanilla ? 1 : TREE_STICK_SEGMENTS;
    int v = gl_VertexIndex;
    if (slot >= T.cam.w || !treeActive(tblocks[slot]) || v >= sides * segments * 6) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
    TreeBlock b = tblocks[slot];
    TreeMotion m = motion[slot];
    TreeStick st = treeStick(b, k, m.total.xyz, m.link.x >= 0 ? motion[m.link.x].total.xyz : vec3(0.0));
    vec4 target;
    float growth;
    int stickMod = treeLeafMod(b, TREE_STICK_MOD + k, target, growth);
    if (stickMod == TREE_MOD_REMOVED) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
    vec3 shift = stickMod == TREE_MOD_HELD ? target.xyz * clamp(target.w, 0.0, 1.0) : vec3(0.0);
    float grow = stickMod == TREE_MOD_REGROW ? clamp(growth, 0.0, 1.0) : 1.0;
    int quad = v / 6;
    int corner = v % 6;
    int seg = quad / sides;
    int side = quad % sides;
    float t = float(seg + CT[corner]) / float(segments);
    int around = side + CS[corner];
    vec3 centre = treeStickAt(st, t);
    vec3 dir = treeStickDir(st, t);
    vec3 ref = abs(dir.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 u = normalize(cross(dir, ref));
    vec3 w = cross(dir, u);
    float r = mix(st.ra, st.rb, t) * grow;
    float angle = float(around) / float(sides) * 6.2831853;
    vec3 normal;
    vec3 offset;
    if (vanilla) {
        angle += 0.78539816;
        offset = (u * cos(angle) + w * sin(angle)) * r * 1.41421356;
        float mid = (float(side) + 0.5) / float(sides) * 6.2831853 + 0.78539816;
        normal = u * cos(mid) + w * sin(mid);
    } else {
        offset = (u * cos(angle) + w * sin(angle)) * r;
        normal = normalize(offset);
    }
    vec3 corner3 = vec3(b.pos.xyz - T.cam.xyz);
    vec3 pos = corner3 + centre + offset + shift;
    gl_Position = G.proj * (G.view * vec4(G.domainOrigin.xyz + pos, 1.0));
    vPos = pos;
    vNormal = mat3(G.view) * normal;
    float len = length(st.b - st.a);
    vT = t;
    float packed = b.look.w;
    vLight = vec2(floor(packed / 16.0), mod(packed, 16.0)) / 15.0;
    vAo = 0.72;
    if (vanilla) {
        vUV = vec2(float(around) * 2.0 * r, t * min(len, 1.0));
        vCell = int((b.info.x >> 8) & 255u);
        vColor = vec3(1.0);
        vKind = 3;
    } else {
        vUV = vec2(float(around) / float(sides), t * len);
        vCell = TREE_WHITE_CELL;
        vColor = treeUnpackRgb(b.info.w) * 0.85;
        vKind = 2;
    }
}
