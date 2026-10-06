float sampleFace(int comp, vec3 x, bool old) {
    ivec3 d = faceDims(comp);
    vec3 g = clamp(x - faceOffset(comp), vec3(0.0), vec3(d - 1));
    ivec3 b = min(ivec3(floor(g)), max(d - 2, ivec3(0)));
    vec3 f = g - vec3(b);
    int base = faceBase(comp);
    float r = 0.0;
    for (int k = 0; k < 2; k++) {
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                ivec3 c = b + ivec3(i, j, k);
                float w = (i == 1 ? f.x : 1.0 - f.x) * (j == 1 ? f.y : 1.0 - f.y) * (k == 1 ? f.z : 1.0 - f.z);
                int idx = base + c.x + d.x * (c.y + d.y * c.z);
#ifdef WITH_VEL_OLD
                r += w * (old ? velOld[idx] : vel[idx]);
#else
                r += w * vel[idx];
#endif
            }
        }
    }
    return r;
}

vec3 sampleVel(vec3 x) {
    return vec3(sampleFace(0, x, false), sampleFace(1, x, false), sampleFace(2, x, false));
}

#ifdef WITH_VEL_OLD
vec3 sampleVelOld(vec3 x) {
    return vec3(sampleFace(0, x, true), sampleFace(1, x, true), sampleFace(2, x, true));
}
#endif
