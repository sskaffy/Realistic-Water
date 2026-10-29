#version 450
layout(location = 0) in vec2 vUV;
layout(location = 1) flat in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    float across = 1.0 - vUV.y * vUV.y;
    float along = smoothstep(0.0, 0.12, vUV.x) * (1.0 - smoothstep(0.55, 1.0, vUV.x));
    float a = vColor.a * across * along;
    outColor = vec4(vColor.rgb * a, a);
}
