#version 450
#include "rain_common.glsl"
layout(std430, binding = 1) readonly buffer Drops { Drop drops[]; };
layout(std430, binding = 2) readonly buffer Columns { ivec4 cols[]; };
layout(binding = 3) uniform sampler2D lightmapTex;

layout(location = 0) out vec2 vUV;
layout(location = 1) flat out vec4 vColor;

const vec2 CORNERS[6] = vec2[](vec2(0, -1), vec2(1, -1), vec2(1, 1), vec2(0, -1), vec2(1, 1), vec2(0, 1));

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
}

void main() {
    if (gl_InstanceIndex >= R.counts.w) {
        hide();
        return;
    }
    Drop d = drops[gl_InstanceIndex];
    vec3 rel = d.pos.xyz - R.cam.xyz;
    float dist = length(rel);
    float radius = R.fall.y;
    float flat_ = length(rel.xz);
    if (d.pos.w <= 0.0 || dist < 0.4 || flat_ > radius) {
        hide();
        return;
    }
    vec3 dir = d.vel.xyz;
    float speed = length(dir);
    dir /= max(speed, 1e-4);
    float len = max(speed * R.look.x, 0.04);
    vec4 head = R.view * vec4(rel, 1.0);
    vec4 tail = R.view * vec4(rel - dir * len, 1.0);
    vec2 axis = tail.xy / max(-tail.z, 0.05) - head.xy / max(-head.z, 0.05);
    axis = dot(axis, axis) > 1e-10 ? normalize(axis) : vec2(0.0, 1.0);
    vec2 side = vec2(-axis.y, axis.x);
    float pixel = 2.0 * max(-head.z, 0.05) / (R.proj[1][1] * R.screen.y);
    float width = max(R.look.z, R.look.w * pixel);
    float thin = R.look.z / width;

    vec2 c = CORNERS[gl_VertexIndex % 6];
    vec3 p = mix(head.xyz, tail.xyz, c.x) + vec3(side * c.y * width * 0.5, 0.0);
    gl_Position = R.proj * vec4(p, 1.0);
    vUV = c;

    ivec2 cell = ivec2(floor(d.pos.xz + R.grid.yz));
    ivec4 col = cols[columnIndex(cell)];
    vec2 light = vec2(1.0 / 15.0, 1.0);
    if (col.x == cell.x && col.y == cell.y) {
        light = vec2(float((col.w >> 8) & 15), float((col.w >> 12) & 15)) / 15.0;
    }
    vec3 lit = texture(lightmapTex, (light * 15.0 + 0.5) / 16.0).rgb;
    float edge = 1.0 - smoothstep(0.7 * radius, radius, flat_);
    float near = smoothstep(0.4, 1.5, dist);
    vColor = vec4(mix(vec3(0.62, 0.68, 0.78), R.sky.rgb, 0.35) * lit, R.look.y * thin * edge * near);
}
