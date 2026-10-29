layout(std140, set = 0, binding = 0) uniform Rain {
    mat4 proj;
    mat4 view;
    vec4 cam;
    vec4 shift;
    vec4 fall;
    vec4 wind;
    vec4 look;
    vec4 grid;
    vec4 screen;
    vec4 splash;
    vec4 extra;
    vec4 sky;
    ivec4 counts;
} R;

struct Drop {
    vec4 pos;
    vec4 vel;
};

#define COL_RAINS 1
#define COL_WATER 2
#define COL_LAVA 8
#define EVENT_WATER 1.0
#define EVENT_LAVA 2.0

uint rhash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

float rrand(inout uint state) {
    state = rhash(state + 0x9e3779b9u);
    return float(state >> 8) * (1.0 / 16777216.0);
}

int columnIndex(ivec2 c) {
    int g = int(R.grid.x);
    return (((c.x % g) + g) % g) + (((c.y % g) + g) % g) * g;
}
