layout(push_constant) uniform PC { ivec4 dims; ivec4 cdims; ivec4 misc; ivec4 misc2; } pc;

int lvlIndex(ivec3 c, ivec4 d) {
    return d.w + c.x + d.x * (c.y + d.y * c.z);
}

ivec3 lvlBoxMin(int l) {
    return G.boxMin.xyz >> l;
}

ivec3 lvlBoxMax(int l, ivec4 d) {
    return min((G.boxMax.xyz + ((1 << l) - 1)) >> l, d.xyz);
}

bool lvlActive(ivec3 c, ivec4 d, int l) {
    return all(greaterThanEqual(c, lvlBoxMin(l))) && all(lessThan(c, lvlBoxMax(l, d)));
}

int lvlBoxCount(ivec4 d, int l) {
    ivec3 s = max(lvlBoxMax(l, d) - lvlBoxMin(l), ivec3(0));
    return s.x * s.y * s.z;
}

ivec3 lvlBoxCell(int t, ivec4 d, int l) {
    ivec3 s = lvlBoxMax(l, d) - lvlBoxMin(l);
    return lvlBoxMin(l) + ivec3(t % s.x, (t / s.x) % s.y, t / (s.x * s.y));
}

const ivec3 MG_NB[6] = ivec3[](ivec3(1, 0, 0), ivec3(-1, 0, 0), ivec3(0, 1, 0), ivec3(0, -1, 0), ivec3(0, 0, 1), ivec3(0, 0, -1));
