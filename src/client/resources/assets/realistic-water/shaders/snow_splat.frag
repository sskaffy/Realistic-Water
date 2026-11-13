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
layout(location = 2) flat in vec2 vLight;
layout(location = 3) in vec3 vDisc;
layout(location = 0) out vec4 outColor;

void main() {
    float r = length(vDisc.xy);
    float lace = snowNoise(vDisc.xy * 6.5 + vDisc.z * 5.0);
    if (r > 0.6 + 0.4 * lace) {
        discard;
    }
    outColor = vec4(snowShade(vLocal, vNormal, vLight, 0.0, 0.0, 0.0), 1.0);
}
