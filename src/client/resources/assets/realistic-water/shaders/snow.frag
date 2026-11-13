#version 450
#include "common.glsl"
#define SNOW_PARAMS_BINDING 1
#define SNOW_COLUMNS_BINDING 2
#define SNOW_DETAIL_BINDING 3
#include "snow_common.glsl"
#define LIGHTMAP_BINDING 5
#include "fluid_light.glsl"
#define SNOW_SHADOW_BINDING 6
#include "snow_shade.glsl"

layout(location = 0) in vec3 vLocal;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vEdge;
layout(location = 3) in vec2 vLight;
layout(location = 4) in float vPack;
layout(location = 5) in float vWet;
layout(location = 6) in float vDent;
layout(location = 0) out vec4 outColor;

void main() {
    if (vEdge < 0.5) {
        discard;
    }
    outColor = vec4(snowShade(vLocal, vNormal, vLight, vPack, vWet, vDent), 1.0);
}
