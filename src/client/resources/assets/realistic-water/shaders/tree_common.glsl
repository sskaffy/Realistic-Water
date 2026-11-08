struct TreeBlock {
    ivec4 pos;
    uvec4 info;
    vec4 look;
};

layout(std430, binding = TREE_PARAMS_BINDING) readonly buffer TreeParams {
    ivec4 cam;
    ivec4 info;
    vec4 lod;
    vec4 timing;
    ivec4 caps;
    vec4 wind;
    vec4 rain;
    ivec4 sim;
} T;

struct TreeMotion {
    vec4 total;
    ivec4 link;
    vec4 hits[4];
};

layout(std430, binding = TREE_BLOCKS_BINDING) readonly buffer TreeBlocks { TreeBlock tblocks[]; };

#define TREE_NO_PARENT 31
#define TREE_STICK_SIDES 5
#define TREE_STICK_SEGMENTS 3
#define TREE_STICK_VERTS (TREE_STICK_SIDES * TREE_STICK_SEGMENTS * 6)
#define TREE_ATLAS_CELL 32
#define TREE_ATLAS_CELLS 16
#define TREE_WHITE_CELL 4
#define TREE_OVERLAP 0.14

bool treeActive(TreeBlock b) { return (b.pos.w & (1 << 19)) != 0; }
int treeOpen(TreeBlock b) { return b.pos.w & 63; }
int treeLeafy(TreeBlock b) { return (b.pos.w >> 6) & 63; }
int treeParent(TreeBlock b) { return (b.pos.w >> 12) & 31; }
bool treeParentLog(TreeBlock b) { return (b.pos.w & (1 << 17)) != 0; }
bool treeVanilla() { return T.info.z != 0; }
uint treeGen(TreeBlock b) { return uint(b.pos.w) >> 20; }

ivec3 treeNeighbour(int n) {
    int l = n < 13 ? n : n + 1;
    return ivec3(l % 3 - 1, (l / 3) % 3 - 1, l / 9 - 1);
}

uint treeSeed(ivec3 block, uint salt) {
    return hash(uint(block.x) * 73856093u ^ uint(block.y) * 19349663u ^ uint(block.z) * 83492791u ^ (salt + 1u) * 2654435761u);
}

vec3 treeRand3(inout uint s) {
    return vec3(rand(s), rand(s), rand(s));
}

vec3 treeRandUnit(inout uint s) {
    float z = rand(s) * 2.0 - 1.0;
    float a = rand(s) * 6.2831853;
    float r = sqrt(max(1.0 - z * z, 0.0));
    return vec3(r * cos(a), z, r * sin(a));
}

vec3 treeNode(ivec3 block) {
    uint s = treeSeed(block, 7u);
    return vec3(0.5) + (treeRand3(s) - 0.5) * 0.5;
}

float treeNoise(vec3 p) {
    ivec3 b = ivec3(floor(p));
    vec3 f = p - vec3(b);
    f = f * f * (3.0 - 2.0 * f);
    float v[8];
    for (int i = 0; i < 8; i++) {
        ivec3 c = b + ivec3(i & 1, (i >> 1) & 1, i >> 2);
        v[i] = float(hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u ^ uint(c.z) * 83492791u)) * (1.0 / 4294967295.0);
    }
    return mix(mix(mix(v[0], v[1], f.x), mix(v[2], v[3], f.x), f.y), mix(mix(v[4], v[5], f.x), mix(v[6], v[7], f.x), f.y), f.z);
}

int treeLeafCount(float dist) {
    int full = max(T.info.x, 1);
    float f = mix(1.0, T.lod.z, smoothstep(T.lod.y, max(T.lod.x, T.lod.y + 1.0), dist));
    return clamp(int(round(float(full) * f)), min(full, 8), full);
}

float treeLeafGrow(int count) {
    return min(sqrt(float(max(T.info.x, 1)) / float(max(count, 1))), 2.2);
}

bool treeLeafCasts(ivec3 block, int i) {
    uint s = hash(treeSeed(block, 1000u + uint(i)) ^ 0x2C1B3C6Du);
    return rand(s) < T.timing.z;
}

int treeStickCount(TreeBlock b) {
    return (treeParent(b) != TREE_NO_PARENT ? 1 : 0) + clamp(T.info.y, 0, 6);
}

struct TreeLeaf {
    vec3 centre;
    vec3 normal;
    vec3 along;
    vec3 across;
    float size;
    float ao;
    vec3 outward;
    uint seed;
};

TreeLeaf treeLeaf(TreeBlock b, int i, int count) {
    TreeLeaf l;
    ivec3 block = b.pos.xyz;
    l.seed = treeSeed(block, 1000u + uint(i));
    uint s = l.seed;
    vec3 r = treeRand3(s);
    int open = treeOpen(b);
    int leafy = treeLeafy(b);
    vec3 lo;
    vec3 hi;
    for (int a = 0; a < 3; a++) {
        int fLo = a * 2;
        int fHi = a * 2 + 1;
        lo[a] = (leafy & (1 << fLo)) != 0 ? -TREE_OVERLAP : 0.03;
        hi[a] = (leafy & (1 << fHi)) != 0 ? 1.0 + TREE_OVERLAP : 0.97;
    }
    vec3 p = lo + (hi - lo) * r;
    vec3 world = vec3(block) + p;
    vec3 outward = vec3(0.0);
    float edge = 9.0;
    for (int a = 0; a < 3; a++) {
        for (int side = 0; side < 2; side++) {
            int f = a * 2 + side;
            if ((open & (1 << f)) == 0) {
                continue;
            }
            vec3 plane = world;
            plane[a] = float(block[a]) + float(side);
            float n = treeNoise(plane * 1.6 + float(f) * 17.0) * 0.7 + treeNoise(plane * 4.1 + 5.0) * 0.3;
            float inset = 0.02 + 0.36 * n;
            if (side == 0) {
                lo[a] = inset;
            } else {
                hi[a] = 1.0 - inset;
            }
            outward[a] += side == 0 ? -1.0 : 1.0;
        }
    }
    p = lo + (hi - lo) * r;
    for (int a = 0; a < 3; a++) {
        if ((open & (1 << (a * 2))) != 0) {
            edge = min(edge, p[a]);
        }
        if ((open & (1 << (a * 2 + 1))) != 0) {
            edge = min(edge, 1.0 - p[a]);
        }
    }
    l.centre = p;
    l.ao = open == 0 ? 0.55 : mix(0.5, 1.0, exp(-max(edge, 0.0) * 2.6));
    vec3 out1 = dot(outward, outward) > 0.0 ? normalize(outward) : vec3(0.0);
    l.outward = out1;
    l.normal = normalize(treeRandUnit(s) + vec3(0.0, 0.7, 0.0) + out1 * 0.5 + vec3(0.0, 1e-4, 0.0));
    vec3 ref = abs(l.normal.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 t0 = normalize(cross(l.normal, ref));
    vec3 t1 = cross(l.normal, t0);
    float spin = rand(s) * 6.2831853;
    l.along = t0 * cos(spin) + t1 * sin(spin);
    l.across = cross(l.normal, l.along);
    float base = treeVanilla() ? 0.3125 : 0.21;
    l.size = base * mix(0.8, 1.2, rand(s)) * T.lod.w * treeLeafGrow(count);
    return l;
}

struct TreeStick {
    vec3 a;
    vec3 b;
    vec3 bend;
    float ra;
    float rb;
    vec3 da;
    vec3 db;
};

float treeStickRadius(float carried) {
    return min(0.017 + 0.011 * sqrt(max(carried, 1.0)), 0.13);
}

TreeStick treeStick(TreeBlock b, int k, vec3 dSelf, vec3 dParent) {
    TreeStick st;
    st.da = dParent;
    st.db = dSelf;
    ivec3 block = b.pos.xyz;
    int parent = treeParent(b);
    bool hasMain = parent != TREE_NO_PARENT;
    vec3 node = treeNode(block);
    float carried = float(b.info.y & 0xFFFFu);
    vec3 mainEnd = node;
    if (hasMain) {
        ivec3 o = treeNeighbour(parent);
        if (treeParentLog(b)) {
            vec3 lo = vec3(o);
            vec3 hi = lo + 1.0;
            vec3 q = clamp(node, lo, hi);
            uint s = treeSeed(block, 11u);
            for (int a = 0; a < 3; a++) {
                if (q[a] > lo[a] + 1e-3 && q[a] < hi[a] - 1e-3) {
                    q[a] = clamp(q[a] + (rand(s) - 0.5) * 0.7, lo[a] + 0.15, hi[a] - 0.15);
                }
            }
            vec3 c = (lo + hi) * 0.5;
            mainEnd = q + normalize(c - q + vec3(1e-4)) * 0.08;
        } else {
            mainEnd = vec3(o) + treeNode(block + o);
        }
    }
    bool vanilla = treeVanilla();
    if (k == 0 && hasMain) {
        st.a = mainEnd;
        st.b = node;
        float r = treeStickRadius(carried);
        st.ra = vanilla ? 0.0625 : treeStickRadius(carried + 1.0);
        st.rb = vanilla ? 0.0625 : r;
        uint s = treeSeed(block, 13u);
        float len = length(st.b - st.a);
        st.bend = treeRandUnit(s) * 0.07 * len;
        if (vanilla) {
            vec3 d = normalize(st.b - st.a + vec3(1e-5));
            st.a += d * 0.035;
            st.b -= d * 0.035;
            st.bend = vec3(0.0);
        }
        return st;
    }
    int side = hasMain ? k - 1 : k;
    uint s = treeSeed(block, 100u + uint(side));
    vec3 start;
    float r0;
    if (hasMain) {
        float t = mix(0.15, 0.85, rand(s));
        start = mix(mainEnd, node, t);
        r0 = mix(treeStickRadius(carried + 1.0), treeStickRadius(carried), t) * 0.5;
        st.da = mix(dParent, dSelf, t);
    } else {
        start = vec3(0.25) + treeRand3(s) * 0.5;
        r0 = 0.02;
        rand(s);
        st.da = dSelf;
    }
    vec3 outward = vec3(0.0);
    int open = treeOpen(b);
    for (int f = 0; f < 6; f++) {
        if ((open & (1 << f)) != 0) {
            vec3 n = vec3(0.0);
            n[f >> 1] = (f & 1) == 0 ? -1.0 : 1.0;
            outward += n;
        }
    }
    vec3 dir = normalize(treeRandUnit(s) + vec3(0.0, 0.35, 0.0) + (dot(outward, outward) > 0.0 ? normalize(outward) * 0.6 : vec3(0.0)));
    float len = mix(0.22, 0.48, rand(s));
    st.a = start;
    st.b = start + dir * len;
    st.ra = vanilla ? 0.03125 : max(r0, 0.012);
    st.rb = vanilla ? 0.03125 : max(r0 * 0.4, 0.006);
    st.bend = vanilla ? vec3(0.0) : treeRandUnit(s) * 0.05 * len;
    return st;
}

vec3 treeStickAt(TreeStick st, float t) {
    return mix(st.a + st.da, st.b + st.db, t) + st.bend * sin(3.14159265 * t);
}

vec3 treeStickDir(TreeStick st, float t) {
    vec3 d = (st.b + st.db - st.a - st.da) + st.bend * 3.14159265 * cos(3.14159265 * t);
    return normalize(d + vec3(1e-5, 0.0, 0.0));
}

void treeLeafMove(inout TreeLeaf l, TreeMotion m) {
    uint s = hash(l.seed ^ 0x51ED270Bu);
    float t = T.timing.x;
    float omega = mix(5.0, 9.0, rand(s));
    float angle = m.total.w * 0.22 * sin(t * omega + rand(s) * 6.2831853);
    for (int h = 0; h < 4; h++) {
        vec4 hit = m.hits[h];
        float age = t - hit.w;
        if (age < 0.0 || age > 1.2) {
            continue;
        }
        float d = length(l.centre - hit.xyz);
        if (d < 0.28) {
            angle -= 0.75 * T.rain.w * (1.0 - d / 0.28) * exp(-age * 5.0) * cos(age * 26.0);
        }
    }
    vec3 stalk = l.centre - l.along * (l.size * 0.5);
    float c = cos(angle);
    float sn = sin(angle);
    vec3 along = l.along * c + l.normal * sn;
    l.normal = l.normal * c - l.along * sn;
    l.along = along;
    l.centre = stalk + along * (l.size * 0.5) + m.total.xyz;
}

#ifdef TREE_MODS_BINDING
layout(std430, binding = TREE_MODS_BINDING) readonly buffer TreeMods { vec4 tmods[]; };
#define TREE_MOD_REMOVED 1
#define TREE_MOD_HELD 2
#define TREE_MOD_REGROW 3
#define TREE_STICK_MOD 2048

int treeLeafMod(TreeBlock b, int i, out vec4 target, out float growth) {
    target = vec4(0.0);
    growth = 1.0;
    uint at = b.info.y >> 16;
    if (at == 0u) {
        return 0;
    }
    uint o = at - 1u;
    int n = int(tmods[o].x);
    for (int k = 0; k < n; k++) {
        vec4 a = tmods[o + 1u + uint(k) * 2u];
        if (int(a.x + 0.5) == i) {
            target = tmods[o + 2u + uint(k) * 2u];
            growth = a.z;
            return int(a.y + 0.5);
        }
    }
    return 0;
}

bool treeLeafModify(TreeBlock b, int i, vec3 corner, inout TreeLeaf l) {
    vec4 target;
    float growth;
    int kind = treeLeafMod(b, i, target, growth);
    if (kind == TREE_MOD_REMOVED) {
        return false;
    }
    if (kind == TREE_MOD_HELD) {
        l.centre = mix(l.centre, target.xyz - corner, clamp(target.w, 0.0, 1.0));
    } else if (kind == TREE_MOD_REGROW) {
        l.size *= clamp(growth, 0.0, 1.0);
    }
    return true;
}
#endif

vec3 treeUnpackRgb(uint argb) {
    return vec3((argb >> 16) & 255u, (argb >> 8) & 255u, argb & 255u) / 255.0;
}
