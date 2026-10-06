struct Particle {
    vec4 pos;
    vec4 vel;
};

#define GID_U (gl_GlobalInvocationID.x + gl_GlobalInvocationID.y * gl_NumWorkGroups.x * gl_WorkGroupSize.x)
#define GROUP_ID (gl_WorkGroupID.x + gl_WorkGroupID.y * gl_NumWorkGroups.x)

#define MAX_ENTITIES 32
#define MAX_SOURCES 64
#define MAX_REMOVE 16
#define AABB_BIAS 1048576

layout(std140, set = 0, binding = 0) uniform Globals {
    ivec4 dims;
    ivec4 faces;
    ivec4 caps;
    ivec4 flags;
    vec4 gravityDt;
    vec4 simA;
    vec4 simB;
    vec4 wwA;
    vec4 wwB;
    vec4 wwC;
    vec4 wwD;
    mat4 proj;
    mat4 view;
    mat4 invProj;
    mat4 invView;
    vec4 domainOrigin;
    vec4 screen;
    vec4 sunDir;
    vec4 skyColor;
    vec4 fogColor;
    vec4 waterA;
    vec4 absorb;
    vec4 scatter;
    vec4 foamA;
    vec4 ent[MAX_ENTITIES * 3];
    vec4 sources[MAX_SOURCES];
    vec4 camGrid;
    vec4 misc;
    ivec4 fine;
    vec4 surf;
    ivec4 boxMin;
    ivec4 boxMax;
    ivec4 fineBoxMin;
    ivec4 fineBoxMax;
    ivec4 removeInfo;
    vec4 removeBox[MAX_REMOVE * 2];
    vec4 killInfo;
    vec4 shadeA;
    vec4 shadeB;
} G;

#define NX G.dims.x
#define NY G.dims.y
#define NZ G.dims.z
#define NCELLS G.dims.w
#define NU G.faces.x
#define NV G.faces.y
#define NW G.faces.z
#define NFACES G.faces.w
#define DT G.gravityDt.w
#define CELL_H G.simB.y

#define CELL_AIR 0u
#define CELL_FLUID 1u
#define CELL_SOLID 2u

const float FP_SCALE = 65536.0;

int cellIndex(ivec3 c) {
    return c.x + NX * (c.y + NY * c.z);
}

bool inGrid(ivec3 c) {
    return c.x >= 0 && c.y >= 0 && c.z >= 0 && c.x < NX && c.y < NY && c.z < NZ;
}

ivec3 cellCoord(int i) {
    return ivec3(i % NX, (i / NX) % NY, i / (NX * NY));
}

ivec3 faceDims(int comp) {
    return ivec3(NX, NY, NZ) + ivec3(comp == 0 ? 1 : 0, comp == 1 ? 1 : 0, comp == 2 ? 1 : 0);
}

int faceBase(int comp) {
    return comp == 0 ? 0 : (comp == 1 ? NU : NU + NV);
}

int faceIndex(int comp, ivec3 f) {
    ivec3 d = faceDims(comp);
    return faceBase(comp) + f.x + d.x * (f.y + d.y * f.z);
}

vec3 faceOffset(int comp) {
    return vec3(comp == 0 ? 0.0 : 0.5, comp == 1 ? 0.0 : 0.5, comp == 2 ? 0.0 : 0.5);
}

ivec3 axisVec(int comp) {
    return ivec3(comp == 0 ? 1 : 0, comp == 1 ? 1 : 0, comp == 2 ? 1 : 0);
}

void decodeFace(int i, out int comp, out ivec3 f) {
    if (i < NU) {
        comp = 0;
    } else if (i < NU + NV) {
        comp = 1;
        i -= NU;
    } else {
        comp = 2;
        i -= NU + NV;
    }
    ivec3 d = faceDims(comp);
    f = ivec3(i % d.x, (i / d.x) % d.y, i / (d.x * d.y));
}

ivec3 boxDims() {
    return G.boxMax.xyz - G.boxMin.xyz;
}

bool inBox(ivec3 c) {
    return all(greaterThanEqual(c, G.boxMin.xyz)) && all(lessThan(c, G.boxMax.xyz));
}

bool inInnerBox(vec3 x) {
    return all(greaterThanEqual(x, vec3(G.boxMin.xyz) + 2.0)) && all(lessThan(x, vec3(G.boxMax.xyz) - 2.0));
}

ivec3 boxCell(int t) {
    ivec3 d = boxDims();
    return G.boxMin.xyz + ivec3(t % d.x, (t / d.x) % d.y, t / (d.x * d.y));
}

bool faceInBox(int comp, ivec3 f) {
    return all(greaterThanEqual(f, G.boxMin.xyz)) && all(lessThan(f, G.boxMax.xyz + axisVec(comp)));
}

bool boxFace(int t, out int comp, out ivec3 f) {
    ivec3 d = boxDims();
    for (comp = 0; comp < 3; comp++) {
        ivec3 fd = d + axisVec(comp);
        int n = fd.x * fd.y * fd.z;
        if (t < n) {
            f = G.boxMin.xyz + ivec3(t % fd.x, (t / fd.x) % fd.y, t / (fd.x * fd.y));
            return true;
        }
        t -= n;
    }
    f = ivec3(0);
    return false;
}

int numEntities() {
    return G.caps.z;
}

vec3 entMin(int e) { return G.ent[e * 3].xyz; }
vec3 entMax(int e) { return G.ent[e * 3 + 1].xyz; }
vec3 entVel(int e) { return G.ent[e * 3 + 2].xyz; }

int entityAt(vec3 p) {
    for (int e = 0; e < numEntities(); e++) {
        if (all(greaterThanEqual(p, entMin(e))) && all(lessThanEqual(p, entMax(e)))) {
            return e;
        }
    }
    return -1;
}

uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

float rand(inout uint state) {
    state = hash(state + 0x9e3779b9u);
    return float(state >> 8) * (1.0 / 16777216.0);
}

vec3 randInSphere(inout uint state) {
    for (int i = 0; i < 8; i++) {
        vec3 p = vec3(rand(state), rand(state), rand(state)) * 2.0 - 1.0;
        if (dot(p, p) <= 1.0) {
            return p;
        }
    }
    return vec3(0.0);
}

float clampPhi(float v, float lo, float hi) {
    return clamp((v - lo) / max(hi - lo, 1e-6), 0.0, 1.0);
}

int fineIndex(ivec3 p) {
    return p.x + G.fine.x * (p.y + G.fine.y * p.z);
}

bool inFine(ivec3 p) {
    return all(greaterThanEqual(p, G.fineBoxMin.xyz)) && all(lessThan(p, G.fineBoxMax.xyz));
}

uint packNormal(vec3 n, bool flag) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    uvec2 q = uvec2(clamp(e * 0.5 + 0.5, 0.0, 1.0) * 32767.0 + 0.5);
    return q.x | (q.y << 15) | (flag ? 0x40000000u : 0u);
}

vec3 unpackNormal(uint v, out bool flag) {
    flag = (v & 0x40000000u) != 0u;
    vec2 e = vec2(float(v & 0x7FFFu), float((v >> 15) & 0x7FFFu)) / 32767.0 * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}
