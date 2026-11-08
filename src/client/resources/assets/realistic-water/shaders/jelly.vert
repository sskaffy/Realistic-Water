#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Verts { float verts[]; };
layout(std430, binding = 2) readonly buffer Indices { uint indices[]; };

layout(location = 0) out vec3 vView;
layout(location = 1) out vec3 vRel;
layout(location = 2) out vec3 vNormal;
layout(location = 3) out vec3 vRest;
layout(location = 4) out vec2 vLight;
layout(location = 5) out float vHang;
layout(location = 6) flat out int vKind;

void main() {
    uint o = indices[gl_VertexIndex] * 12u;
    vec3 p = vec3(verts[o], verts[o + 1u], verts[o + 2u]);
    vec3 rel = G.domainOrigin.xyz + p;
    vec4 view = G.view * vec4(rel, 1.0);
    vView = view.xyz;
    vRel = rel;
    vNormal = vec3(verts[o + 3u], verts[o + 4u], verts[o + 5u]);
    vRest = vec3(verts[o + 6u], verts[o + 7u], verts[o + 8u]);
    vKind = int(verts[o + 9u] + 0.5);
    float light = verts[o + 10u];
    vLight = vec2(floor(light / 16.0), mod(light, 16.0)) / 15.0;
    vHang = verts[o + 11u];
    gl_Position = G.proj * view;
}
