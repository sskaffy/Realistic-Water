#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Faces { ivec4 faces[]; };

layout(location = 0) out vec3 vPat;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out float vHeat;
layout(location = 3) out float vDist;

const int CORNER[6] = int[](0, 1, 2, 0, 2, 3);
const vec2 UV[4] = vec2[](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));

void main() {
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
    float dist = length(view.xyz);
    view = G.view * vec4(rel + n * (0.002 + 0.0015 * dist), 1.0);
    vPat = vec3(f.xyz & 1023) + local;
    vNormal = n;
    vHeat = float((f.w >> 3) & 255) / 255.0;
    vDist = dist;
    gl_Position = G.proj * view;
}
