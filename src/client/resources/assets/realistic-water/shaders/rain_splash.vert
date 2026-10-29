#version 450
#include "rain_common.glsl"
layout(std430, binding = 1) readonly buffer Splashes { Drop splashes[]; };
layout(std430, binding = 2) readonly buffer Columns { ivec4 cols[]; };
layout(binding = 3) uniform sampler2D lightmapTex;

layout(location = 0) out vec2 vUV;
layout(location = 1) flat out vec4 vColor;
layout(location = 2) flat out float vRipple;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void hide() {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
}

void main() {
    Drop s = splashes[gl_InstanceIndex];
    if (s.pos.w < 0.0 || s.vel.w == 0.0) {
        hide();
        return;
    }
    vec3 rel = s.pos.xyz - R.cam.xyz;
    if (length(rel.xz) > R.fall.y) {
        hide();
        return;
    }
    float life = abs(s.vel.w);
    float t = clamp(s.pos.w / life, 0.0, 1.0);
    vec2 c = CORNERS[gl_VertexIndex % 6];
    vUV = c;
    vec3 p;
    float alpha;
    if (s.vel.w < 0.0) {
        float radius = 0.03 + 0.22 * sqrt(t);
        p = rel + vec3(c.x * radius, 0.0, c.y * radius);
        gl_Position = R.proj * (R.view * vec4(p, 1.0));
        vRipple = 1.0;
        alpha = 0.55 * (1.0 - t);
    } else {
        vec4 v = R.view * vec4(rel, 1.0);
        float pixel = 2.0 * max(-v.z, 0.05) / (R.proj[1][1] * R.screen.y);
        float size = max(0.012, R.look.w * pixel);
        vec3 dir = mat3(R.view) * s.vel.xyz;
        vec2 axis = dot(dir.xy, dir.xy) > 1e-8 ? normalize(dir.xy) : vec2(0.0, 1.0);
        vec2 side = vec2(-axis.y, axis.x);
        vec2 offset = axis * c.y * size * 2.5 + side * c.x * size;
        gl_Position = R.proj * vec4(v.xyz + vec3(offset, 0.0), 1.0);
        vRipple = 0.0;
        alpha = 0.7 * (1.0 - t) * (0.012 / size);
    }
    ivec2 cell = ivec2(floor(s.pos.xz + R.grid.yz));
    ivec4 col = cols[columnIndex(cell)];
    vec2 light = vec2(1.0 / 15.0, 1.0);
    if (col.x == cell.x && col.y == cell.y) {
        light = vec2(float((col.w >> 8) & 15), float((col.w >> 12) & 15)) / 15.0;
    }
    vec3 lit = texture(lightmapTex, (light * 15.0 + 0.5) / 16.0).rgb;
    vColor = vec4(mix(vec3(0.7, 0.75, 0.82), R.sky.rgb, 0.4) * lit, alpha);
}
