#ifndef SNOW_DETAIL_ACCESS
#define SNOW_DETAIL_ACCESS readonly
#endif

#define SNOW_MAX_SHAPES 128
#define SNOW_MAX_ADJUST 64
#define SNOW_DEP_FP 65536.0

struct SnowSpray {
    vec4 pos;
    vec4 vel;
};
#define COL_LIGHT_MASK 0xFF
#define COL_SNOW 0x100
#define COL_WET 0x200
#define COL_PACKED 0x400
#define SNOW_STEP 0.6
#define SNOW_WALL_BUMP 0.03

struct SnowColumn {
    ivec4 a;
    vec4 b;
    vec4 c;
};

layout(std430, binding = SNOW_PARAMS_BINDING) readonly buffer SnowParams {
    ivec4 grid;
    ivec4 track;
    ivec4 trackOld;
    vec4 look;
    vec4 look2;
    vec4 track2;
    vec4 noiseOrigin;
    vec4 shapes[SNOW_MAX_SHAPES * 3];
    ivec4 extra;
    vec4 motion;
    ivec4 sprayOrigin;
    ivec4 adjust[SNOW_MAX_ADJUST];
} SP;

layout(std430, binding = SNOW_COLUMNS_BINDING) readonly buffer SnowColumns { SnowColumn cols[]; };
layout(std430, binding = SNOW_DETAIL_BINDING) SNOW_DETAIL_ACCESS buffer SnowDetail { uint detail[]; };

int snowMod(int a, int n) {
    return a >= 0 ? a % n : n - 1 - ((-a - 1) % n);
}

ivec2 snowOrigin() {
    return SP.grid.yz;
}

vec3 sprayToLocal(vec3 p) {
    return vec3(p.x + float(SP.sprayOrigin.x - SP.grid.y), p.y - SP.track2.z, p.z + float(SP.sprayOrigin.y - SP.grid.z));
}

vec3 localToSpray(vec3 p) {
    return vec3(p.x - float(SP.sprayOrigin.x - SP.grid.y), p.y + SP.track2.z, p.z - float(SP.sprayOrigin.y - SP.grid.z));
}

int snowFloorDiv(int a, int b) {
    return a >= 0 ? a / b : -((-a - 1) / b) - 1;
}

ivec2 snowPointAt(vec2 xz) {
    return ivec2(floor(xz * float(SP.grid.w))) + snowOrigin() * SP.grid.w;
}

bool snowPointSlot(ivec2 world, out int i) {
    int n = SP.track.x;
    ivec2 r = world - SP.track.yz;
    i = 0;
    if (n <= 0 || any(lessThan(r, ivec2(0))) || any(greaterThanEqual(r, ivec2(n)))) {
        return false;
    }
    i = snowMod(world.x, n) + snowMod(world.y, n) * n;
    return true;
}

bool snowColumn(ivec2 c, out SnowColumn col) {
    int g = SP.grid.x;
    int i = snowMod(c.x, g) + snowMod(c.y, g) * g;
    col = cols[i];
    return col.a.x == c.x && col.a.y == c.y;
}

bool hasSnow(SnowColumn col) {
    return (col.a.z & COL_SNOW) != 0;
}

float snowTop(SnowColumn col) {
    return col.b.x - col.b.w + SP.look.x - SP.track2.z;
}

vec2 snowLight(SnowColumn col) {
    return vec2(float(col.a.z & 15), float((col.a.z >> 4) & 15)) / 15.0;
}

float snowHash(vec2 p) {
    ivec2 c = ivec2(p);
    return float(hash(uint(c.x) * 1597334677u ^ uint(c.y) * 3812015801u) >> 8) * (1.0 / 16777216.0);
}

float snowNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = snowHash(i);
    float b = snowHash(i + vec2(1.0, 0.0));
    float c = snowHash(i + vec2(0.0, 1.0));
    float d = snowHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

struct SnowSurface {
    float y;
    float edge;
    float firm;
    float shape;
    vec2 light;
    float wet;
    float pack;
    float base;
    float dent;
};

ivec2 snowSideDir(int side) {
    return side == 0 ? ivec2(-1, 0) : side == 1 ? ivec2(1, 0) : side == 2 ? ivec2(0, -1) : ivec2(0, 1);
}

bool snowBlends(SnowColumn a, ivec2 ca, int side) {
    if (((a.a.w >> (side * 2)) & 3) != 0) {
        return false;
    }
    SnowColumn b;
    if (!snowColumn(ca + snowSideDir(side), b) || !hasSnow(b)) {
        return false;
    }
    return ((b.a.w >> ((side ^ 1) * 2)) & 3) == 0;
}

#define SNOW_MAX_EDGES 12

struct SnowShape {
    bool snow;
    vec2 lo;
    float corner[4];
    vec3 cornerLight[4];
    int open;
    int blend;
    float floorH[4];
    int edgeCount;
    vec4 edgeSeg[SNOW_MAX_EDGES];
    float edgeFloor[SNOW_MAX_EDGES];
    float base;
    float firm;
    float refTop;
    bool packed;
};

bool snowOpenSide(SnowColumn col, ivec2 c, int side, out float floorH) {
    float top = snowTop(col);
    float base = col.b.z - SP.track2.z;
    floorH = base;
    if (snowBlends(col, c, side)) {
        return false;
    }
    int kind = (col.a.w >> (side * 2)) & 3;
    if (kind == 2 || kind == 0 && col.c[side] - SP.track2.z + SP.look.x < top) {
        floorH = clamp(col.c[side] - SP.track2.z, base, max(top, base));
        return true;
    }
    return false;
}

void snowCorner(ivec2 c, SnowColumn own, int ox, int oz, out float height, out vec3 light) {
    int sideX = ox == 1 ? 1 : 0;
    int sideZ = oz == 1 ? 3 : 2;
    ivec2 cx = c + snowSideDir(sideX);
    ivec2 cz = c + snowSideDir(sideZ);
    SnowColumn colX;
    SnowColumn colZ;
    SnowColumn colD;
    bool eX = snowBlends(own, c, sideX);
    bool eZ = snowBlends(own, c, sideZ);
    bool hasX = snowColumn(cx, colX) && hasSnow(colX);
    bool hasZ = snowColumn(cz, colZ) && hasSnow(colZ);
    bool eXD = hasX && snowBlends(colX, cx, sideZ);
    bool eZD = hasZ && snowBlends(colZ, cz, sideX);
    bool inX = eX || (eZ && eZD && eXD);
    bool inZ = eZ || (eX && eXD && eZD);
    bool inD = (eX && eXD) || (eZ && eZD);
    float sum = snowTop(own);
    vec3 l = vec3(snowLight(own), (own.a.z & COL_WET) != 0 ? 1.0 : 0.0);
    float count = 1.0;
    if (inX) {
        sum += snowTop(colX);
        l += vec3(snowLight(colX), (colX.a.z & COL_WET) != 0 ? 1.0 : 0.0);
        count += 1.0;
    }
    if (inZ) {
        sum += snowTop(colZ);
        l += vec3(snowLight(colZ), (colZ.a.z & COL_WET) != 0 ? 1.0 : 0.0);
        count += 1.0;
    }
    if (inD && snowColumn(cx + snowSideDir(sideZ), colD) && hasSnow(colD)) {
        sum += snowTop(colD);
        l += vec3(snowLight(colD), (colD.a.z & COL_WET) != 0 ? 1.0 : 0.0);
        count += 1.0;
    }
    height = sum / count;
    light = l / count;
}

void snowAddEdge(inout SnowShape s, vec2 lo, int side, float floorH) {
    if (s.edgeCount >= SNOW_MAX_EDGES) {
        return;
    }
    vec2 a = side == 1 ? lo + vec2(1.0, 0.0) : side == 3 ? lo + vec2(0.0, 1.0) : lo;
    vec2 b = side == 0 ? lo + vec2(0.0, 1.0) : side == 2 ? lo + vec2(1.0, 0.0) : lo + vec2(1.0, 1.0);
    s.edgeSeg[s.edgeCount] = vec4(a, b);
    s.edgeFloor[s.edgeCount] = floorH;
    s.edgeCount++;
}

SnowShape snowColumnLoad(ivec2 c) {
    SnowShape s;
    SnowColumn own;
    bool valid = snowColumn(c, own);
    s.snow = valid && hasSnow(own);
    s.lo = vec2(c - snowOrigin());
    s.open = 0;
    s.blend = 0;
    s.edgeCount = 0;
    s.packed = false;
    s.base = s.snow ? own.b.z - SP.track2.z : (valid ? own.b.x - SP.track2.z : 0.0);
    s.firm = s.base;
    s.refTop = s.base;
    for (int k = 0; k < 4; k++) {
        s.corner[k] = s.base;
        s.cornerLight[k] = vec3(0.0, 1.0, 0.0);
        s.floorH[k] = s.base;
    }
    if (!s.snow) {
        return s;
    }
    s.refTop = snowTop(own);
    s.firm = own.b.y - SP.track2.z;
    s.packed = (own.a.z & COL_PACKED) != 0;
    for (int k = 0; k < 4; k++) {
        float h;
        vec3 l;
        snowCorner(c, own, k & 1, k >> 1, h, l);
        s.corner[k] = h;
        s.cornerLight[k] = l;
    }
    for (int side = 0; side < 4; side++) {
        if (snowBlends(own, c, side)) {
            s.blend |= 1 << side;
            continue;
        }
        float floorH;
        if (snowOpenSide(own, c, side, floorH)) {
            s.open |= 1 << side;
            s.floorH[side] = floorH;
            snowAddEdge(s, s.lo, side, floorH);
        }
    }
    for (int side = 0; side < 4; side++) {
        if ((s.blend & (1 << side)) == 0) {
            continue;
        }
        ivec2 cn = c + snowSideDir(side);
        SnowColumn next;
        if (!snowColumn(cn, next)) {
            continue;
        }
        int perp = side < 2 ? 2 : 0;
        for (int k = 0; k < 2; k++) {
            float floorH;
            if (snowOpenSide(next, cn, perp + k, floorH)) {
                snowAddEdge(s, vec2(cn - snowOrigin()), perp + k, floorH);
            }
        }
    }
    return s;
}

SnowSurface snowColumnEval(SnowShape sh, vec2 xz, float minEdge) {
    SnowSurface s;
    s.y = sh.base;
    s.edge = 0.0;
    s.firm = sh.base;
    s.shape = 1.0;
    s.light = vec2(0.0, 1.0);
    s.wet = 0.0;
    s.pack = 0.0;
    s.base = sh.base;
    s.dent = 0.0;
    if (!sh.snow) {
        return s;
    }
    vec2 p = clamp(xz, sh.lo, sh.lo + 1.0);
    vec2 f = p - sh.lo;
    float h = mix(mix(sh.corner[0], sh.corner[1], f.x), mix(sh.corner[2], sh.corner[3], f.x), f.y);
    vec3 l = mix(mix(sh.cornerLight[0], sh.cornerLight[1], f.x), mix(sh.cornerLight[2], sh.cornerLight[3], f.x), f.y);
    vec2 nz = p + SP.noiseOrigin.xy;
    h += SP.look.y * ((snowNoise(nz * 0.31) - 0.5) * 1.4 + (snowNoise(nz * 1.13 + 17.0) - 0.5) * 0.6);

    float y = h;
    float lowest = sh.base;
    for (int k = 0; k < sh.edgeCount; k++) {
        float floorH = sh.edgeFloor[k];
        float hRel = h - floorH;
        if (hRel <= 0.0) {
            continue;
        }
        vec2 a = sh.edgeSeg[k].xy;
        vec2 ab = sh.edgeSeg[k].zw - a;
        vec2 nearest = a + ab * clamp(dot(p - a, ab) / dot(ab, ab), 0.0, 1.0);
        float d = length(p - nearest);
        float tall = smoothstep(0.3, 1.0, hRel);
        float shoulder = mix(hRel, min(hRel, 0.22), tall);
        float width = clamp(shoulder * 1.2, 0.04, max(SP.look.z, 0.04));
        float t = clamp(max(d, minEdge) / width, 0.0, 1.0);
        float gentle = 1.0 - (1.0 - t) * (1.0 - t);
        float edge = floorH + (hRel - shoulder) + shoulder * mix(gentle, sqrt(gentle), tall);
        if (edge < y) {
            y = edge;
            lowest = floorH;
        }
    }
    s.y = max(y, lowest + 0.004);
    s.edge = 1.0;
    s.firm = min(sh.firm, s.y);
    s.pack = sh.packed ? 0.8 : 0.0;
    s.light = l.xy;
    s.wet = l.z;
    return s;
}

SnowSurface snowColumnRest(ivec2 c, vec2 xz, float minEdge) {
    return snowColumnEval(snowColumnLoad(c), xz, minEdge);
}

float snowDetail(SnowShape sh, ivec2 c, vec2 xz, out float pack) {
    pack = 0.0;
    int q = SP.grid.w;
    int n = SP.track.x;
    if (n <= 0) {
        return 0.0;
    }
    vec2 lo = vec2(c - snowOrigin()) * float(q) + 0.5;
    vec2 hi = lo + float(q) - 1.0;
    if ((sh.blend & 1) != 0) {
        lo.x = -1.0e9;
    }
    if ((sh.blend & 2) != 0) {
        hi.x = 1.0e9;
    }
    if ((sh.blend & 4) != 0) {
        lo.y = -1.0e9;
    }
    if ((sh.blend & 8) != 0) {
        hi.y = 1.0e9;
    }
    vec2 sp = clamp(xz * float(q), lo, hi) + vec2(snowOrigin() * q - SP.track.yz) - 0.5;
    if (any(lessThan(sp, vec2(1.0))) || any(greaterThan(sp, vec2(float(n) - 2.0)))) {
        return 0.0;
    }
    vec2 fl = floor(sp);
    vec2 t = sp - fl;
    ivec2 b = ivec2(fl) + SP.track.yz;
    float d = 0.0;
    for (int k = 0; k < 4; k++) {
        ivec2 cc = b + ivec2(k & 1, k >> 1);
        int i = snowMod(cc.x, n) + snowMod(cc.y, n) * n;
        vec2 v = unpackHalf2x16(detail[i]);
        float w = ((k & 1) != 0 ? t.x : 1.0 - t.x) * ((k >> 1) != 0 ? t.y : 1.0 - t.y);
        d += w * v.x;
        pack += w * v.y;
    }
    float border = min(min(sp.x, sp.y), min(float(n) - 1.0 - sp.x, float(n) - 1.0 - sp.y));
    float fade = clamp((border - 1.0) / float(2 * q), 0.0, 1.0);
    pack *= fade;
    return d * fade;
}

SnowSurface snowShapeSurface(SnowShape sh, ivec2 c, vec2 xz, float minEdge) {
    SnowSurface s = snowColumnEval(sh, xz, minEdge);
    if (s.edge > 0.5) {
        float pack;
        float d = snowDetail(sh, c, xz, pack);
        float moved = max(s.y + d, s.base + 0.01);
        s.dent = moved - s.y;
        s.y = moved;
        s.pack = max(s.pack, pack);
    }
    return s;
}

SnowSurface snowColumnSurface(ivec2 c, vec2 xz, float minEdge) {
    return snowShapeSurface(snowColumnLoad(c), c, xz, minEdge);
}

ivec2 snowColumnAt(vec2 xz) {
    return ivec2(floor(xz)) + snowOrigin();
}

SnowSurface snowRest(vec2 xz) {
    return snowColumnRest(snowColumnAt(xz), xz, 0.0);
}

SnowSurface snowSurface(vec2 xz) {
    return snowColumnSurface(snowColumnAt(xz), xz, 0.0);
}
