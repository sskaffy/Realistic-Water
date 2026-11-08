#version 450
#include "common.glsl"
#define GRASS_PARAMS_BINDING 1
#define GRASS_BLOCKS_BINDING 2
#include "grass_common.glsl"
layout(std430, binding = 3) readonly buffer State { vec4 st[]; };
#ifdef SHADOW
layout(push_constant) uniform PC { int cascade; } pc;
#endif

layout(location = 0) out vec3 vNormal;
layout(location = 1) out float vT;
layout(location = 2) out float vSide;
layout(location = 3) out vec2 vUV;
layout(location = 4) flat out vec3 vTint;
layout(location = 5) flat out vec2 vLight;
layout(location = 6) flat out float vShade;
layout(location = 7) flat out int vType;
layout(location = 8) flat out float vPress;
layout(location = 9) flat out uint vSeed;
layout(location = 10) out vec3 vPos;
layout(location = 11) flat out float vWilt;

const int CORNER_T[6] = int[](0, 0, 1, 0, 1, 1);
const float CORNER_SIDE[6] = float[](-1.0, 1.0, 1.0, -1.0, 1.0, -1.0);

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
}

void main() {
    int U = max(P.info.x, 1);
    int style = P.info.y;
    int g = gl_InstanceIndex;
    int slot = g / U;
    int u = g % U;
    if (slot >= P.cam.w || !grassActive(blocks[slot])) {
        hide();
        return;
    }
    GrassBlock b = blocks[slot];
    GrassUnit n = grassUnit(slot, u);
    vec3 rel = G.domainOrigin.xyz + n.root;
    float dist = length(rel);
    if (dist > P.lod.x + 4.0) {
        hide();
        return;
    }
    uint lodSeed = n.seed ^ 0x9E3779B9u;
#ifdef SHADOW
    if (pc.cascade == 0 ? dist > P.shadowNear.x + 3.0 : style == 0 && (u & 1) == 1) {
        hide();
        return;
    }
#endif
    if (style == 0 && dist > P.lod.y) {
        float keep = 1.0 - 0.6 * smoothstep(P.lod.y, P.lod.x, dist);
        if (rand(lodSeed) > keep) {
            hide();
            return;
        }
    }
    uint sbase = uint(g) * uint(GRASS_STATE);
    vec3 nodes[GRASS_NODES + 1];
    bool fresh = floatBitsToUint(st[sbase + 8u].w) != grassGen(b);
    nodes[0] = fresh ? vec3(0.0) : st[sbase + 9u].xyz;
    float phase = fresh ? 0.0 : st[sbase].w;
    float lying = phase > 0.5 && phase < 1.5 ? st[sbase + 9u].w : 0.0;
    vec3 at = vec3(0.0);
    for (int i = 0; i < GRASS_NODES; i++) {
        at += grassRestDir(n, i, n.base) * (n.height / float(GRASS_NODES));
        nodes[i + 1] = fresh ? at : st[sbase + uint(i)].xyz;
    }
    vec3 setDir = fresh ? n.base : normalize(st[sbase + 8u].xyz + vec3(0.0, 1e-5, 0.0));

    int vid = gl_VertexIndex;
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec3 pos;
    vec3 normal;
    float t;
    float side;
    vec2 uv = vec2(0.0);
    uint bladeSeed = n.seed;
    int corner = vid % 6;
    if (style == 2) {
        int segIndex = vid / 6;
        t = (float(segIndex) + float(CORNER_T[corner])) / float(GRASS_NODES);
        side = CORNER_SIDE[corner];
        vec3 p;
        vec3 tangent;
        chainAt(nodes, t, p, tangent);
        vec3 axis = vec3(cos(n.yaw), 0.0, sin(n.yaw));
        pos = n.root + p + axis * side * n.width;
        normal = normalize(cross(axis, tangent));
        uv = vec2(side * 0.5 + 0.5, t);
    } else {
        int segments = style == 1 ? 2 : 4;
        int perBlade = segments * 6;
        int blade = vid / perBlade;
        int local = vid % perBlade;
        vec3 root = n.root;
        float hs = 1.0;
        float yaw = n.yaw;
        float width = n.width;
        vec3 off = vec3(0.0);
        if (style == 1) {
            bladeSeed = hash(n.seed + uint(blade) * 747796405u);
            uint s = bladeSeed;
            float a = rand(s) * 6.2831853;
            off = vec3(cos(a), 0.0, sin(a)) * 0.07 * sqrt(rand(s));
            hs = mix(0.7, 1.1, rand(s));
            yaw = rand(s) * 6.2831853;
        }
        int segIndex = local / 6;
        t = (float(segIndex) + float(CORNER_T[corner])) / float(segments);
        side = CORNER_SIDE[corner];
        vec3 p;
        vec3 tangent;
        chainAt(nodes, t, p, tangent);
        p = p * hs + off * (1.0 + 1.5 * t);
        vec3 across = vec3(-sin(yaw), 0.0, cos(yaw));
        vec3 toCam = normalize(-(G.domainOrigin.xyz + root + p));
        vec3 facing = cross(tangent, toCam);
        if (dot(facing, facing) > 1e-6) {
            facing = normalize(facing);
            across = normalize(mix(across, facing * sign(dot(facing, across) + 1e-4), 0.35));
        }
        across = normalize(across - tangent * dot(across, tangent) + vec3(1e-5, 0.0, 0.0));
        float taper = P.lod.z > 0.5 ? cartoonLeaf(t) : 1.0 - pow(t, 1.35);
        float w = width * taper * (1.0 + 0.6 * smoothstep(P.lod.y, P.lod.x, dist));
        pos = root + p + across * side * w * 0.5;
        normal = normalize(cross(across, tangent) + across * side * 0.45);
    }
#ifdef SHADOW
    vec4 clip = (pc.cascade == 0 ? P.shadowNearVP : P.shadowVP) * vec4(pos, 1.0);
    clip.x = clip.x * 0.5 + (pc.cascade == 0 ? -0.5 : 0.5) * clip.w;
    gl_Position = clip;
#else
    vec4 view = G.view * vec4(G.domainOrigin.xyz + pos, 1.0);
    gl_Position = G.proj * view;
#endif
    vPos = pos;
    vNormal = mat3(G.view) * normal;
    vT = t;
    vSide = side;
    vUV = uv;
    vTint = b.look.rgb;
    float packed = b.look.w;
    vLight = vec2(floor(packed / 16.0), mod(packed, 16.0)) / 15.0;
    uint shadeSeed = bladeSeed ^ 0x85EBCA6Bu;
    vShade = rand(shadeSeed);
    vType = n.type;
    vPress = clamp(acos(clamp(dot(setDir, n.base), -1.0, 1.0)) / 1.4, 0.0, 1.0);
    vWilt = phase > 0.5 && phase < 1.5 ? clamp(0.25 + lying / max(P.life.x, 1.0), 0.0, 1.0) : 0.0;
    vSeed = bladeSeed;
}
