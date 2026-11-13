#version 450
layout(location = 0) in vec2 vUV;
layout(location = 1) flat in vec4 vColor;
layout(location = 2) flat in vec4 vShape;
layout(location = 3) flat in float vSeed;
layout(location = 0) out vec4 outColor;

void main() {
    float d = length(vUV);
    if (d > 1.0) {
        discard;
    }

    float dot_ = 1.0 - smoothstep(0.35, 1.0, d);
    float angle = atan(vUV.y, vUV.x);
    float arm = abs(cos(3.0 * angle));
    float crystal = (1.0 - smoothstep(0.12, 0.2, d)) + pow(arm, 10.0 + 40.0 * d) * (1.0 - smoothstep(0.75, 1.0, d));
    float rim = 0.74 + 0.16 * sin(angle * 3.0 + vSeed) + 0.1 * sin(angle * 5.0 - vSeed * 1.7);
    float clump = (1.0 - smoothstep(0.45 * rim, rim, d)) * (0.75 + 0.25 * sin(vUV.x * 9.0 + vSeed) * sin(vUV.y * 8.0 - vSeed));
    float shape = mix(clamp(crystal, 0.0, 1.0), clamp(clump, 0.0, 1.0), vShape.w);
    float a = mix(dot_, shape, vShape.x);
    float soft = 1.0 - smoothstep(0.55, 1.0, d);
    a = vColor.a * mix(a, soft, vShape.z);
    outColor = vec4(vColor.rgb * a, a);
}
