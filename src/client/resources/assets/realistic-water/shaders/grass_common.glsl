#define MAX_GRASS_COLLIDERS 128
#define GRASS_NODES 4
#define GRASS_STATE 10
#define GRAB_ENTRIES 64
#define GRASS_SHORT 0
#define GRASS_TALL 1
#define GRASS_SHORT_DRY 2
#define GRASS_TALL_DRY 3
#define CLUMP_BLADES 5

struct GrassBlock {
    ivec4 pos;
    vec4 look;
};

layout(std430, binding = GRASS_PARAMS_BINDING) readonly buffer GrassParams {
    ivec4 cam;
    ivec4 info;
    vec4 timing;
    vec4 wind;
    vec4 spring;
    vec4 lod;
    mat4 shadowVP;
    vec4 shadow;
    vec4 grabA;
    vec4 grabB;
    vec4 life;
    ivec4 cand[3];
    vec4 colliders[MAX_GRASS_COLLIDERS * 3];
    mat4 shadowNearVP;
    vec4 shadowNear;
    ivec4 floorOrigin;
} P;

layout(std430, binding = GRASS_BLOCKS_BINDING) readonly buffer GrassBlocks { GrassBlock blocks[]; };

bool grassActive(GrassBlock b) { return (b.pos.w & 4) != 0; }
int grassType(GrassBlock b) { return b.pos.w & 3; }
uint grassGen(GrassBlock b) { return uint(b.pos.w) >> 8; }
bool grassDry(int type) { return type >= GRASS_SHORT_DRY; }

struct GrassUnit {
    vec3 root;
    vec3 restTip;
    vec3 base;
    vec3 curlAxis;
    float curl;
    float height;
    float width;
    float yaw;
    uint seed;
    int type;
};

vec3 rotateBetween(vec3 v, vec3 a, vec3 b) {
    vec3 axis = cross(a, b);
    float s = length(axis);
    float c = dot(a, b);
    if (s < 1e-5) {
        return c > 0.0 ? v : reflect(v, a);
    }
    vec3 k = axis / s;
    return v * c + cross(k, v) * s + k * dot(k, v) * (1.0 - c);
}

vec3 rotateAbout(vec3 v, vec3 k, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return v * c + cross(k, v) * s + k * dot(k, v) * (1.0 - c);
}

void chainAt(vec3 nodes[GRASS_NODES + 1], float t, out vec3 pos, out vec3 tangent) {
    float x = clamp(t, 0.0, 1.0) * float(GRASS_NODES);
    int i = min(int(floor(x)), GRASS_NODES - 1);
    float f = x - float(i);
    vec3 p0 = nodes[max(i - 1, 0)];
    vec3 p1 = nodes[i];
    vec3 p2 = nodes[i + 1];
    vec3 p3 = nodes[min(i + 2, GRASS_NODES)];
    if (i == 0) {
        p0 = p1 - (p2 - p1);
    }
    if (i + 2 > GRASS_NODES) {
        p3 = p2 + (p2 - p1);
    }
    float f2 = f * f;
    float f3 = f2 * f;
    pos = 0.5 * ((2.0 * p1) + (-p0 + p2) * f + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * f3);
    tangent = 0.5 * ((-p0 + p2) + 2.0 * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f + 3.0 * (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * f2);
    tangent = normalize(tangent + vec3(0.0, 1e-5, 0.0));
}

vec3 grassRestDir(GrassUnit n, int i, vec3 setDir) {
    vec3 natural = rotateAbout(n.base, n.curlAxis, n.curl * float(i));
    return rotateBetween(natural, n.base, setDir);
}

uint grassSeed(ivec3 block, int u) {
    return hash(uint(block.x) * 73856093u ^ uint(block.y) * 19349663u ^ uint(block.z) * 83492791u ^ uint(u + 1) * 2654435761u);
}

vec2 grassHeightRange(int type) {
    if (type == GRASS_TALL) return vec2(1.05, 1.85);
    if (type == GRASS_SHORT_DRY) return vec2(0.25, 0.56);
    if (type == GRASS_TALL_DRY) return vec2(0.55, 1.0);
    return vec2(0.4, 0.88);
}

GrassUnit grassUnit(int slot, int u) {
    GrassBlock b = blocks[slot];
    GrassUnit n;
    n.type = grassType(b);
    n.seed = grassSeed(b.pos.xyz, u);
    uint s = n.seed;
    vec3 corner = vec3(b.pos.xyz - P.cam.xyz);
    vec2 range = grassHeightRange(n.type);
    int style = P.info.y;
    if (style == 2) {
        n.root = corner + vec3(0.5, 0.0, 0.5);
        n.height = (n.type == GRASS_TALL ? 2.0 : range.y) * P.spring.z;
        n.yaw = 0.785398 + float(u) * 1.570796;
        n.restTip = n.root + vec3(0.0, n.height, 0.0);
        n.base = vec3(0.0, 1.0, 0.0);
        n.curlAxis = vec3(1.0, 0.0, 0.0);
        n.curl = 0.0;
        n.width = 0.45;
        return n;
    }
    n.root = corner + vec3(0.04 + 0.92 * rand(s), 0.0, 0.04 + 0.92 * rand(s));
    n.height = mix(range.x, range.y, pow(rand(s), 0.75)) * P.spring.z;
    n.yaw = rand(s) * 6.2831853;
    float lean = 0.32 * rand(s);
    float la = rand(s) * 6.2831853;
    n.base = normalize(vec3(cos(la) * lean, 1.0, sin(la) * lean));
    n.restTip = n.root + n.base * n.height;
    vec3 leanDir = vec3(cos(la), 0.0, sin(la));
    n.curlAxis = normalize(cross(vec3(0.0, 1.0, 0.0), leanDir));
    n.curl = mix(0.04, 0.2, rand(s)) * (grassDry(n.type) ? 0.6 : 1.0);
    float w = grassDry(n.type) ? 0.034 : 0.046;
    n.width = (style == 1 ? w * 1.6 : w) * mix(0.7, 1.35, rand(s)) * P.spring.w;
    return n;
}

float cartoonLeaf(float t) {
    return pow(max(1.0 - t, 0.0), 0.9) * (0.6 + 1.6 * t) * 1.9;
}

float grassNoise(vec2 p) {
    ivec2 b = ivec2(floor(p));
    vec2 f = p - vec2(b);
    f = f * f * (3.0 - 2.0 * f);
    float a = float(hash(uint(b.x) * 73856093u ^ uint(b.y) * 19349663u)) * (1.0 / 4294967295.0);
    float c = float(hash(uint(b.x + 1) * 73856093u ^ uint(b.y) * 19349663u)) * (1.0 / 4294967295.0);
    float d = float(hash(uint(b.x) * 73856093u ^ uint(b.y + 1) * 19349663u)) * (1.0 / 4294967295.0);
    float e = float(hash(uint(b.x + 1) * 73856093u ^ uint(b.y + 1) * 19349663u)) * (1.0 / 4294967295.0);
    return mix(mix(a, c, f.x), mix(d, e, f.x), f.y);
}

vec2 grassWind(vec2 world, float t, uint seed) {
    vec2 dir = P.wind.xy;
    float strength = P.wind.z;
    if (strength <= 0.0) {
        return vec2(0.0);
    }
    vec2 q = world * P.wind.w - dir * t * 0.9;
    float gust = grassNoise(q) * 0.65 + grassNoise(q * 2.7 + 13.0) * 0.35;
    float phase = float(seed & 1023u) * 0.00614;
    float flutter = sin(t * 4.7 + phase * 6.2831853 + world.x * 1.3 + world.y * 0.9);
    vec2 side = vec2(-dir.y, dir.x);
    return strength * (dir * (0.25 + 1.2 * gust * gust) + side * 0.12 * flutter + dir * 0.08 * flutter);
}
