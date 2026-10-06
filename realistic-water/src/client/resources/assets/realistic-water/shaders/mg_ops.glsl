float applyA(ivec3 c, int i, ivec4 d, int l) {
    float s = 0.0;
    float n = 0.0;
    for (int k = 0; k < 6; k++) {
        ivec3 nb = c + MG_NB[k];
        if (!lvlActive(nb, d, l)) {
            n += 1.0;
            continue;
        }
        int j = lvlIndex(nb, d);
        uint t = mgFlags[j];
        if (t == CELL_SOLID) {
            continue;
        }
        n += 1.0;
        if (t == CELL_FLUID) {
            s += X_OF(j);
        }
    }
    return n * X_OF(i) - s;
}
