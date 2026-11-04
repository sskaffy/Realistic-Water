#version 450
#include "common.glsl"
#include "ocean.glsl"
layout(std430, binding = 1) readonly buffer Heights { float heights[]; };
layout(std430, binding = 2) readonly buffer RegionLight { uint regionLight[]; };
layout(push_constant) uniform PC { ivec4 a; ivec4 b; ivec4 c; ivec4 d; } pc;
layout(location = 0) out vec3 vView;
layout(location = 1) out vec3 vRel;
layout(location = 2) out float vAmp;
layout(location = 3) out vec2 vLight;
layout(location = 4) out vec3 vSideNormal;
layout(location = 5) out vec2 vSlopeDev;

const int CORNER[6] = int[](0, 1, 2, 0, 2, 3);
const ivec2 CORNER_OFF[4] = ivec2[](ivec2(0, 0), ivec2(0, 1), ivec2(1, 1), ivec2(1, 0));

uint lightWord(ivec3 block) {
    ivec3 l = clamp(block - pc.c.xyz, ivec3(0), pc.d.xyz - 1);
    return regionLight[l.x + pc.d.x * (l.y + pc.d.y * l.z)];
}

float columnAmp(ivec2 column) {
    uint v = lightWord(ivec3(column.x, pc.b.y, column.y));
    return (v & 0x20000000u) != 0u ? float((v >> 24) & 31u) / 31.0 * OCEAN_AMP_MAX : 0.0;
}

float cornerAmp(ivec2 corner) {
    return min(min(columnAmp(corner + ivec2(-1, -1)), columnAmp(corner + ivec2(0, -1))),
        min(columnAmp(corner + ivec2(-1, 0)), columnAmp(corner)));
}

float ampAt(vec2 p) {
    ivec2 c = ivec2(floor(p));
    vec2 f = p - vec2(c);
    return mix(mix(cornerAmp(c), cornerAmp(c + ivec2(1, 0)), f.x), mix(cornerAmp(c + ivec2(0, 1)), cornerAmp(c + ivec2(1, 1)), f.x), f.y);
}

float calmAt(ivec2 g) {
    int res = pc.b.x;
    vec2 p = vec2(pc.a.xy) + vec2(g) / float(res);
    bool edgeX = g.x == 0 || g.x == pc.a.z;
    bool edgeZ = g.y == 0 || g.y == pc.a.w;
    if (edgeX || edgeZ) {
        vec2 lo = floor(p + 1e-4);
        vec2 hi = lo + vec2(edgeX ? 0.0 : 1.0, edgeX ? 1.0 : 0.0);
        float t = edgeX ? p.y - lo.y : p.x - lo.x;
        float h0 = OCEAN_REST + cornerAmp(ivec2(lo)) * oceanHeight(lo);
        float h1 = OCEAN_REST + cornerAmp(ivec2(hi)) * oceanHeight(hi);
        return mix(h0, h1, t);
    }
    return OCEAN_REST + ampAt(p) * oceanHeight(p);
}

float simAt(ivec2 g) {
    float sum = 0.0;
    float n = 0.0;
    for (int k = 0; k < 4; k++) {
        ivec2 col = clamp(g - 1 + ivec2(k & 1, k >> 1), ivec2(0), pc.a.zw - 1);
        float h = heights[col.x + pc.a.z * col.y];
        if (h > -1000.0) {
            sum += h;
            n += 1.0;
        }
    }
    return n > 0.0 ? sum / n : 0.0;
}

float blendAt(ivec2 g) {
    float band = max(intBitsToFloat(pc.b.z), 1e-3);
    float d = float(min(min(g.x, pc.a.z - g.x), min(g.y, pc.a.w - g.y))) / float(pc.b.x);
    return smoothstep(0.0, band, d);
}

float heightAt(ivec2 g, out float calm) {
    calm = calmAt(g);
    return calm + blendAt(g) * simAt(g);
}

void main() {
    int quad = gl_VertexIndex / 6;
    ivec2 g = ivec2(quad % pc.a.z, quad / pc.a.z) + CORNER_OFF[CORNER[gl_VertexIndex % 6]];
    float res = float(pc.b.x);
    float calm;
    float h = heightAt(g, calm);
    vec2 p = vec2(pc.a.xy) + vec2(g) / res;
    ivec2 gx0 = max(g - ivec2(1, 0), ivec2(0));
    ivec2 gx1 = min(g + ivec2(1, 0), pc.a.zw);
    ivec2 gz0 = max(g - ivec2(0, 1), ivec2(0));
    ivec2 gz1 = min(g + ivec2(0, 1), pc.a.zw);
    float c0, c1, c2, c3;
    float hx = (heightAt(gx1, c1) - heightAt(gx0, c0)) / (float(gx1.x - gx0.x) / res);
    float hz = (heightAt(gz1, c3) - heightAt(gz0, c2)) / (float(gz1.y - gz0.y) / res);
    float cx = (c1 - c0) / (float(gx1.x - gx0.x) / res);
    float cz = (c3 - c2) / (float(gz1.y - gz0.y) / res);
    vSlopeDev = vec2(hx - cx, hz - cz);
    vAmp = ampAt(p);
    uint word = lightWord(ivec3(floor(p.x), pc.b.y + 1, floor(p.y)));
    vLight = vec2(float(word & 15u), float((word >> 4) & 15u)) / 15.0;
    vSideNormal = vec3(0.0);
    vec3 rel = vec3(p.x, float(pc.b.y) + h, p.y);
    vRel = rel;
    vec4 view = G.view * vec4(G.domainOrigin.xyz + rel, 1.0);
    vView = view.xyz;
    gl_Position = G.proj * view;
}
