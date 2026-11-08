#version 450
#include "common.glsl"
#ifdef FREE
layout(std430, binding = 1) readonly buffer Verts { float verts[]; };
#else
layout(std430, binding = 1) readonly buffer Faces { ivec4 faces[]; };
#endif

layout(location = 0) out vec3 vView;
layout(location = 1) out vec3 vRel;
layout(location = 2) out vec3 vNormal;
layout(location = 3) out vec3 vPat;
layout(location = 4) out vec2 vLight;
layout(location = 5) out float vFilm;

const int CORNER[6] = int[](0, 1, 2, 0, 2, 3);
const vec2 UV[4] = vec2[](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));

void main() {
#ifdef FREE
    uint o = uint(gl_VertexIndex) * 12u;
    vec3 rel = G.domainOrigin.xyz + vec3(verts[o], verts[o + 1u], verts[o + 2u]);
    vec4 view = G.view * vec4(rel, 1.0);
    vView = view.xyz;
    vRel = rel;
    vNormal = vec3(verts[o + 3u], verts[o + 4u], verts[o + 5u]);
    vPat = vec3(verts[o + 6u], verts[o + 7u], verts[o + 8u]);
    float light = verts[o + 9u];
    vLight = vec2(floor(light / 16.0), mod(light, 16.0)) / 15.0;
    vFilm = verts[o + 10u];
    gl_Position = G.proj * view;
    return;
#else
    ivec4 f = faces[gl_VertexIndex / 6];
    vec2 uv = UV[CORNER[gl_VertexIndex % 6]];
    int dir = f.w & 7;
    vec3 n;
    vec3 local;
    if (dir < 2) {
        n = vec3(0.0, dir == 0 ? -1.0 : 1.0, 0.0);
        local = vec3(uv.x, float(dir), uv.y);
    } else if (dir < 4) {
        n = vec3(0.0, 0.0, dir == 2 ? -1.0 : 1.0);
        local = vec3(uv.x, uv.y, float(dir - 2));
    } else {
        n = vec3(dir == 4 ? -1.0 : 1.0, 0.0, 0.0);
        local = vec3(float(dir - 4), uv.y, uv.x);
    }
    vec3 rel = G.domainOrigin.xyz + vec3(f.xyz - G.dims.xyz) + local;
    vec4 view = G.view * vec4(rel, 1.0);
    vView = view.xyz;
    vRel = rel;
    vNormal = n;
    vPat = vec3(f.xyz & 1023) + local;
    vLight = vec2(float((f.w >> 3) & 15), float((f.w >> 7) & 15)) / 15.0;
    vFilm = 0.0;
    gl_Position = G.proj * view;
#endif
}
