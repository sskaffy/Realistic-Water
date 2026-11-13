#version 450
#include "common.glsl"
#define SNOW_PARAMS_BINDING 1
#define SNOW_COLUMNS_BINDING 2
#define SNOW_DETAIL_BINDING 3
#include "snow_common.glsl"
layout(std430, binding = 4) readonly buffer Tiles { ivec4 tiles[]; };
layout(push_constant) uniform PC { ivec4 a; ivec4 b; } pc;
#define TILE 8

layout(location = 0) out vec3 vLocal;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out float vEdge;
layout(location = 3) out vec2 vLight;
layout(location = 4) out float vPack;
layout(location = 5) out float vWet;
layout(location = 6) out float vDent;

const ivec2 CORNERS[6] = ivec2[](ivec2(0, 0), ivec2(1, 0), ivec2(1, 1), ivec2(0, 0), ivec2(1, 1), ivec2(0, 1));
const ivec2 CORNERS_FLIPPED[6] = ivec2[](ivec2(0, 0), ivec2(1, 0), ivec2(0, 1), ivec2(1, 0), ivec2(1, 1), ivec2(0, 1));

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
    vLocal = vec3(0.0);
    vNormal = vec3(0.0, 1.0, 0.0);
    vEdge = 0.0;
    vLight = vec2(0.0);
    vPack = 0.0;
    vWet = 0.0;
    vDent = 0.0;
}

void emit(vec3 local, vec3 normal, SnowSurface s) {
    gl_Position = G.proj * (G.view * vec4(G.domainOrigin.xyz + local, 1.0));
    vLocal = local;
    vNormal = normal;
    vEdge = 1.0;
    vLight = s.light;
    vPack = s.pack;
    vWet = s.wet;
    vDent = s.dent;
}

void main() {
    ivec4 tile = tiles[pc.a.x + gl_InstanceIndex];
    int q = max(pc.a.y, 1);
    int n = TILE * q;
    float minEdge = q >= 4 ? 0.0 : 0.5 / float(q);
    int capVertices = n * n * 6;
    ivec2 tileColumn = tile.xy + snowOrigin();
    if (gl_VertexIndex < capVertices) {
        int quad = gl_VertexIndex / 6;
        ivec2 cell = ivec2(quad % n, quad / n);
        ivec2 column = tileColumn + cell / q;
        SnowShape sh = snowColumnLoad(column);
        if (!sh.snow) {
            hide();
            return;
        }
        ivec2 inColumn = cell % q;
        bool flip = (inColumn.x * 2 >= q) != (inColumn.y * 2 >= q);
        ivec2 g = cell + (flip ? CORNERS_FLIPPED[gl_VertexIndex % 6] : CORNERS[gl_VertexIndex % 6]);
        vec2 xz = vec2(tile.xy) + vec2(g) / float(q);
        SnowSurface s = snowShapeSurface(sh, column, xz, minEdge);
        float e = min(0.5 / float(q), 0.25);
        float hx = snowShapeSurface(sh, column, xz + vec2(e, 0.0), minEdge).y - snowShapeSurface(sh, column, xz - vec2(e, 0.0), minEdge).y;
        float hz = snowShapeSurface(sh, column, xz + vec2(0.0, e), minEdge).y - snowShapeSurface(sh, column, xz - vec2(0.0, e), minEdge).y;
        emit(vec3(xz.x, s.y, xz.y), normalize(vec3(-hx, 2.0 * e, -hz)), s);
        return;
    }
    int rows = max(pc.a.z, 1);
    int w = gl_VertexIndex - capVertices;
    int piece = w / 6;
    int row = piece % rows;
    int segment = piece / rows;
    int j = segment % q;
    int side = (segment / q) % 4;
    int index = segment / (4 * q);
    ivec2 cell = ivec2(index % TILE, index / TILE);
    ivec2 column = tileColumn + cell;
    if (index >= TILE * TILE) {
        hide();
        return;
    }
    SnowShape sh = snowColumnLoad(column);
    if (!sh.snow) {
        hide();
        return;
    }
    SnowColumn beside;
    float bottom;
    if (snowColumn(column + snowSideDir(side), beside) && hasSnow(beside)) {
        bottom = max(sh.base, beside.b.z - SP.track2.z) + 0.002;
    } else if ((sh.open & (1 << side)) != 0) {
        bottom = sh.floorH[side];
    } else {
        hide();
        return;
    }
    ivec2 corner = CORNERS[gl_VertexIndex % 6];
    vec2 ends[2];
    float tops[2];
    SnowSurface mine;
    for (int k = 0; k < 2; k++) {
        float along = float(j + k) / float(q);
        vec2 xz = side == 0 ? sh.lo + vec2(0.0, along) : side == 1 ? sh.lo + vec2(1.0, along) : side == 2 ? sh.lo + vec2(along, 0.0) : sh.lo + vec2(along, 1.0);
        SnowSurface s = snowShapeSurface(sh, column, xz, minEdge);
        ends[k] = xz;
        tops[k] = s.y;
        if (k == corner.x) {
            mine = s;
        }
    }
    if (tops[0] - bottom < 1e-4 && tops[1] - bottom < 1e-4) {
        hide();
        return;
    }
    vec2 dir = vec2(snowSideDir(side));
    vec2 xz = ends[corner.x];
    float t = float(row + 1 - corner.y) / float(rows);
    float y = mix(min(bottom, tops[corner.x]), tops[corner.x], t);
    emit(vec3(xz.x, y, xz.y), vec3(dir.x, 0.0, dir.y), mine);
}
