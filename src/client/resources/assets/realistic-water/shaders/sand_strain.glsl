uint typeAt(ivec3 c) {
    return inBox(c) ? (cellFlags[cellIndex(c)] & 0xFFu) : CELL_AIR;
}

float faceVel(int comp, ivec3 f) {
    return faceInBox(comp, f) ? vel[faceIndex(comp, f)] : 0.0;
}

ivec2 planeAxes(int plane) {
    return plane == 0 ? ivec2(0, 1) : (plane == 1 ? ivec2(0, 2) : ivec2(1, 2));
}

float edgeStrain(int plane, ivec3 e) {
    ivec2 ab = planeAxes(plane);
    ivec3 ea = axisVec(ab.x);
    ivec3 eb = axisVec(ab.y);
    float dadb = faceVel(ab.x, e) - faceVel(ab.x, e - eb);
    float dbda = faceVel(ab.y, e) - faceVel(ab.y, e - ea);
    return 0.5 * (dadb + dbda);
}

bool edgeActive(int plane, ivec3 e) {
    ivec2 ab = planeAxes(plane);
    ivec3 ea = axisVec(ab.x);
    ivec3 eb = axisVec(ab.y);
    bool any = false;
    for (int k = 0; k < 4; k++) {
        ivec3 c = e - ((k & 1) != 0 ? ea : ivec3(0)) - ((k & 2) != 0 ? eb : ivec3(0));
        uint t = typeAt(c);
        if (t == CELL_AIR) {
            return false;
        }
        any = any || t == CELL_FLUID;
    }
    return any;
}

void cellStrain(ivec3 c, out vec3 diag, out vec3 shear) {
    diag = vec3(
        faceVel(0, c + ivec3(1, 0, 0)) - faceVel(0, c),
        faceVel(1, c + ivec3(0, 1, 0)) - faceVel(1, c),
        faceVel(2, c + ivec3(0, 0, 1)) - faceVel(2, c));
    diag -= (diag.x + diag.y + diag.z) / 3.0;
    for (int plane = 0; plane < 3; plane++) {
        ivec2 ab = planeAxes(plane);
        ivec3 ea = axisVec(ab.x);
        ivec3 eb = axisVec(ab.y);
        float sum = 0.0;
        float n = 0.0;
        for (int k = 0; k < 4; k++) {
            ivec3 e = c + ((k & 1) != 0 ? ea : ivec3(0)) + ((k & 2) != 0 ? eb : ivec3(0));
            if (edgeActive(plane, e)) {
                sum += edgeStrain(plane, e);
                n += 1.0;
            }
        }
        shear[plane] = n > 0.0 ? sum / n : 0.0;
    }
}
