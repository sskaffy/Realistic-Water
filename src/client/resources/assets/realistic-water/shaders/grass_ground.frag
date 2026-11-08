#version 450
#include "common.glsl"
#define GRASS_PARAMS_BINDING 1
#define GRASS_BLOCKS_BINDING 2
#include "grass_common.glsl"
layout(binding = 3) uniform sampler2D sceneDepth;
layout(binding = 4) uniform sampler2D grassShadowTex;
layout(location = 0) out vec4 outColor;
#include "grass_shadow.glsl"

void main() {
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    float device = textureLod(sceneDepth, uv, 0.0).r;
    if (device <= 0.0) {
        discard;
    }
    vec4 v = G.invProj * vec4(uv * 2.0 - 1.0, device, 1.0);
    vec3 view = v.xyz / v.w;
    vec3 rel = (G.invView * vec4(view, 1.0)).xyz;
    vec3 p = rel - G.domainOrigin.xyz;
    if (length(p.xz) > P.lod.x + 4.0 || G.sunDir.w <= 0.0) {
        discard;
    }
    float shade = 1.0 - grassShadowLit(p, 2.0);
    if (shade <= 0.0) {
        discard;
    }
    float edge = 1.0 - smoothstep(P.lod.x - 6.0, P.lod.x + 2.0, length(p.xz));
    outColor = vec4(0.0, 0.0, 0.0, shade * P.shadow.y * 0.85 * edge * clamp(G.sunDir.w, 0.0, 1.0));
}
