#version 450

layout(std140, set = 0, binding = 0) uniform Snowfall {
    mat4 proj;
    mat4 view;
    vec4 camMod;
    vec4 box;
    vec4 fall;
    vec4 drift;
    vec4 screen;
    vec4 camFrac;
    ivec4 camBlock;
    vec4 sky;
} W;
layout(std430, binding = 1) readonly buffer Columns { ivec4 cols[]; };
layout(binding = 2) uniform sampler2D lightmapTex;

layout(location = 0) out vec2 vUV;
layout(location = 1) flat out vec4 vColor;
layout(location = 2) flat out vec4 vShape;
layout(location = 3) flat out float vSeed;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));
const float TAU = 6.2831853;

uint whash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

float wrand(inout uint state) {
    state = whash(state + 0x9e3779b9u);
    return float(state >> 8) * (1.0 / 16777216.0);
}

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
    vUV = vec2(0.0);
    vColor = vec4(0.0);
    vShape = vec4(0.0);
    vSeed = 0.0;
}

void main() {
    if (float(gl_InstanceIndex) >= W.drift.w) {
        hide();
        return;
    }
    uint s = whash(uint(gl_InstanceIndex) * 747796405u + 11u);
    vec3 home = vec3(wrand(s), wrand(s), wrand(s)) * W.box.xyx;
    float big = wrand(s);
    big *= big;
    float size = W.fall.w * (0.45 + 1.25 * big);
    float speed = W.fall.x * (0.7 + 0.5 * big + 0.25 * (wrand(s) - 0.5));
    float phase = wrand(s) * TAU;
    float spin = wrand(s) * TAU;
    float tumblePhase = wrand(s) * TAU;
    float tumbleRate = (1.2 + 2.6 * wrand(s)) * (1.0 - 0.5 * big);
    float shade = 0.86 + 0.14 * wrand(s);
    float seed = wrand(s);
    float t = W.camMod.w;

    float carried = 1.5 - 0.25 * float(min(int(big * 4.0), 3));
    vec3 rel;
    rel.x = mod(home.x + W.drift.x * carried - W.camMod.x, W.box.x) - W.box.z;
    rel.z = mod(home.z + W.drift.y * carried - W.camMod.z, W.box.x) - W.box.z;
    rel.y = mod(home.y - speed * t - W.camMod.y, W.box.y) - W.box.w;

    vec3 air = (W.camMod.xyz + rel) * vec3(TAU / W.box.x, TAU / W.box.y, TAU / W.box.x);
    vec2 eddy = vec2(sin(air.x + 2.0 * air.z + air.y + t * 0.37), cos(2.0 * air.x - air.z - air.y + t * 0.29))
        + 0.5 * vec2(cos(3.0 * air.x - 4.0 * air.z + 2.0 * air.y - t * 0.53), sin(4.0 * air.x + 3.0 * air.z - 2.0 * air.y + t * 0.61));
    float reach = W.fall.y * (0.6 + 0.9 * big);
    float rate = W.fall.z * (1.3 - 0.5 * big);
    vec2 flutter = vec2(sin(t * rate + phase), cos(t * rate * 0.83 + phase * 1.7))
        + 0.35 * vec2(sin(t * rate * 2.3 + phase * 2.1), sin(t * rate * 1.9 + phase * 0.7));
    rel.xz += eddy * W.sky.w * (0.5 + 0.5 * carried) + flutter * reach;
    rel.y += 0.25 * reach * sin(t * rate * 2.0 + phase);

    float flat_ = length(rel.xz);
    float dist = length(rel);
    if (flat_ > W.box.z || dist < 0.25) {
        hide();
        return;
    }
    ivec2 cell = W.camBlock.xz + ivec2(floor(W.camFrac.xz + rel.xz));
    int g = W.camBlock.w;
    ivec4 col = cols[(((cell.x % g) + g) % g) + (((cell.y % g) + g) % g) * g];
    float worldY = W.camFrac.y + rel.y;
    float ground = intBitsToFloat(col.z);
    if (col.x != cell.x || col.y != cell.y || (col.w & 1) == 0 || worldY < ground) {
        hide();
        return;
    }
    vec3 center = (W.view * vec4(rel, 1.0)).xyz;
    float depth = max(-center.z, 0.05);
    float pixel = 2.0 * depth / (W.proj[1][1] * W.screen.y);
    float blur = 1.0 - smoothstep(0.4, 2.0, dist);
    float radius = max(size * (1.0 + 1.5 * blur), W.screen.z * pixel);
    float thin = size * (1.0 + 1.5 * blur) / radius;
    float detail = clamp((size / pixel - 2.5) / 4.0, 0.0, 1.0);

    float turn = spin + t * (phase - 3.14159) * 0.25;
    float facing = mix(0.3, 1.0, abs(cos(tumblePhase + t * tumbleRate)));
    float squash = mix(1.0, facing, detail * (1.0 - blur));
    vec2 c = CORNERS[gl_VertexIndex % 6];
    vec2 axis = vec2(cos(turn), sin(turn));
    vec2 corner = axis * c.x + vec2(-axis.y, axis.x) * (c.y * squash);
    gl_Position = W.proj * vec4(center + vec3(corner * radius, 0.0), 1.0);
    vUV = c;

    vec2 light = vec2(float((col.w >> 8) & 15), float((col.w >> 12) & 15)) / 15.0;
    vec3 lit = texture(lightmapTex, (light * 15.0 + 0.5) / 16.0).rgb;
    float edge = 1.0 - smoothstep(0.75 * W.box.z, W.box.z, flat_);
    float top = 1.0 - smoothstep(W.box.y - W.box.w - 3.0, W.box.y - W.box.w, rel.y);
    float bottom = smoothstep(-W.box.w, -W.box.w + 2.0, rel.y);
    float landing = smoothstep(0.0, 0.25, worldY - ground);
    float near = smoothstep(0.25, 1.0, dist);
    float dim = mix(0.6 + 0.4 * facing, 1.0, detail);
    float alpha = W.drift.z * thin * thin * edge * top * bottom * landing * near * dim / (1.0 + 1.5 * blur);
    vColor = vec4(mix(vec3(0.97, 0.98, 1.0), W.sky.rgb, 0.12) * lit * shade, alpha);
    // The shape is drawn in the quad's own axes, which already turn with the flake.
    vShape = vec4(detail, 0.0, blur, smoothstep(0.2, 0.45, big));
    vSeed = seed * 37.0;
}
