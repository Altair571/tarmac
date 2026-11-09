/*
 * Renders the still picture on Tarmac's connecting screen: a car's cabin from the driver's seat, with
 * CarPlay's icon on the head unit. The cabin is modelled from signed-distance shapes and lit by its
 * own screens and by what comes through the glass, so it has real perspective, soft shadows and
 * reflections, which a picture drawn from flat shapes in the app could not have.
 *
 * Build and run with scripts/cabin/render.sh, which also writes the images into the app's resources.
 *
 *   render_cabin <night|day> <width> <height> <samples per pixel side> <icon.rgba> <out.bmp>
 *
 * Units are metres: x to the car's right, y up, z forward. The floor is y = 0 and the car's
 * centreline is x = 0. The driver sits on the left; the app mirrors the picture for right-hand drive.
 */
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct { float x, y, z; } v3;
#define V(x, y, z) ((v3){(float)(x), (float)(y), (float)(z)})
static inline v3 add(v3 a, v3 b) { return V(a.x + b.x, a.y + b.y, a.z + b.z); }
static inline v3 sub(v3 a, v3 b) { return V(a.x - b.x, a.y - b.y, a.z - b.z); }
static inline v3 mul(v3 a, float s) { return V(a.x * s, a.y * s, a.z * s); }
static inline v3 mulv(v3 a, v3 b) { return V(a.x * b.x, a.y * b.y, a.z * b.z); }
static inline float dot(v3 a, v3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
static inline v3 cross(v3 a, v3 b) { return V(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x); }
static inline float len(v3 a) { return sqrtf(dot(a, a)); }
static inline v3 norm(v3 a) { return mul(a, 1.0f / (len(a) + 1e-12f)); }
static inline v3 mix3(v3 a, v3 b, float t) { return add(mul(a, 1 - t), mul(b, t)); }
static inline float clampf(float x, float a, float b) { return x < a ? a : (x > b ? b : x); }
static inline float mixf(float a, float b, float t) { return a + (b - a) * t; }
static inline float sstep(float a, float b, float x) { float t = clampf((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
static inline float maxf(float a, float b) { return a > b ? a : b; }
static inline float minf(float a, float b) { return a < b ? a : b; }
static inline v3 reflectv(v3 d, v3 n) { return sub(d, mul(n, 2 * dot(d, n))); }
static inline float hash1(float n) { float s = sinf(n) * 43758.5453f; return s - floorf(s); }
static inline float hash2(float x, float y) { return hash1(x * 127.1f + y * 311.7f); }
static float noise2(float x, float y) {
    float ix = floorf(x), iy = floorf(y), fx = x - ix, fy = y - iy;
    fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
    return mixf(mixf(hash2(ix, iy), hash2(ix + 1, iy), fx), mixf(hash2(ix, iy + 1), hash2(ix + 1, iy + 1), fx), fy);
}

static int NIGHT = 1;

/* ---- A local frame: w points out of a part toward whoever looks at it, u to their right, v up. ---- */
typedef struct { v3 o, u, v, w; } Frame;
static Frame frameOf(v3 o, v3 w) {
    Frame f; f.o = o; f.w = norm(w); f.u = norm(cross(f.w, V(0, 1, 0))); f.v = cross(f.u, f.w); return f;
}
static inline v3 loc(const Frame *f, v3 p) { v3 d = sub(p, f->o); return V(dot(d, f->u), dot(d, f->v), dot(d, f->w)); }
static inline v3 world(const Frame *f, v3 q) { return add(f->o, add(mul(f->u, q.x), add(mul(f->v, q.y), mul(f->w, q.z)))); }

static Frame FW, FH, FC, FM;      /* steering wheel, head unit, driver's display, mirror */
static const float ROAD_Y = -0.33f;

/* ---- Signed distances ---- */
static inline float sdBox(v3 p, v3 b) {
    v3 q = V(fabsf(p.x) - b.x, fabsf(p.y) - b.y, fabsf(p.z) - b.z);
    return len(V(maxf(q.x, 0), maxf(q.y, 0), maxf(q.z, 0))) + minf(maxf(q.x, maxf(q.y, q.z)), 0);
}
static inline float sdRBox(v3 p, v3 b, float r) { return sdBox(p, V(b.x - r, b.y - r, b.z - r)) - r; }
static inline float sdCapsule(v3 p, v3 a, v3 b, float r) {
    v3 pa = sub(p, a), ba = sub(b, a); float h = clampf(dot(pa, ba) / dot(ba, ba), 0, 1);
    return len(sub(pa, mul(ba, h))) - r;
}
static inline float sdRRect2(float x, float y, float hx, float hy, float r) {
    float qx = fabsf(x) - hx + r, qy = fabsf(y) - hy + r;
    return sqrtf(maxf(qx, 0) * maxf(qx, 0) + maxf(qy, 0) * maxf(qy, 0)) + minf(maxf(qx, qy), 0) - r;
}
/* A rounded rectangle in the frame's u-v plane, as thick as hz either side of zc. */
static inline float sdPlate(v3 q, float cx, float cy, float hx, float hy, float r, float zc, float hz) {
    float d2 = sdRRect2(q.x - cx, q.y - cy, hx, hy, r), dz = fabsf(q.z - zc) - hz;
    return minf(maxf(d2, dz), 0) + sqrtf(maxf(d2, 0) * maxf(d2, 0) + maxf(dz, 0) * maxf(dz, 0));
}
static inline float sdDisc(v3 q, float cx, float cy, float r, float zc, float hz) {
    float d2 = sqrtf((q.x - cx) * (q.x - cx) + (q.y - cy) * (q.y - cy)) - r, dz = fabsf(q.z - zc) - hz;
    return minf(maxf(d2, dz), 0) + sqrtf(maxf(d2, 0) * maxf(d2, 0) + maxf(dz, 0) * maxf(dz, 0));
}
static inline float smin(float a, float b, float k) {
    float h = clampf(0.5f + 0.5f * (b - a) / k, 0, 1); return mixf(b, a, h) - k * h * (1 - h);
}

enum {
    M_DASH = 1, M_TRIM, M_PIANO, M_GLASS, M_LEATHER, M_METAL, M_KNOB, M_KEY, M_HEADLINER, M_PILLAR, M_DOOR,
    M_MIRROR, M_LED, M_RUBBER, M_PAINT, M_CARAHEAD, M_ROAD, M_SKY, M_VISOR, M_COLUMN, M_FLOOR
};
typedef struct { float d; int m; } Hit;
#define U(dist, mat) do { float d_ = (dist); if (d_ < h.d) { h.d = d_; h.m = (mat); } } while (0)

/* Head unit measures, in its own frame (metres). The glass is the plane z = 0. */
#define HU_GLASS_HX 0.163f
#define HU_GLASS_HY 0.100f
#define HU_DISPLAY_HX 0.150f
#define HU_DISPLAY_HY 0.088f
#define HU_FASCIA_Z (-0.006f)
#define HU_STRIP_Y (-0.130f)
#define HU_KNOB_X (-0.128f)
#define HU_VENT_Y (-0.186f)
#define HU_ICON_X 0.014f
#define SIDE_VENT_Y 0.872f
#define HU_ICON_HALF 0.050f

static Hit map(v3 p) {
    Hit h = {1e9f, 0};

    /* ---- Dashboard: a deep rolled slab, the hood over the driver's display, the centre stack ---- */
    float dash = sdRBox(sub(p, V(0, 0.765, 0.985)), V(0.83, 0.230, 0.365), 0.075);
    float hood = sdRBox(sub(p, V(-0.365, 1.085, 0.81)), V(0.180, 0.068, 0.12), 0.045);
    dash = smin(dash, hood, 0.03);
    v3 qh = loc(&FH, p);
    float stack = sdRBox(sub(qh, V(0, -0.058, HU_FASCIA_Z - 0.06)), V(0.186, 0.176, 0.06), 0.028);
    dash = smin(dash, stack, 0.022);
    float console = sdRBox(sub(p, V(0.02, 0.60, 0.20)), V(0.135, 0.135, 0.50), 0.035);
    dash = smin(dash, console, 0.07);
    /* The driver's display sits in a recess under the hood. */
    v3 qc = loc(&FC, p);
    dash = maxf(dash, -sdRBox(sub(qc, V(0, 0, 0.03)), V(0.158, 0.046, 0.03), 0.01));
    /* The vent is a slot in the fascia; the charging pad a shallow tray in the console. */
    dash = maxf(dash, -sdPlate(qh, 0, HU_VENT_Y, 0.128, 0.0125, 0.0125, HU_FASCIA_Z, 0.016));
    dash = maxf(dash, -sdRBox(sub(p, V(-0.012, 0.735, 0.40)), V(0.062, 0.006, 0.088), 0.004));
    dash = maxf(dash, -sdRBox(sub(p, V(0.555, SIDE_VENT_Y, 0.622)), V(0.150, 0.0135, 0.016), 0.012));
    dash = maxf(dash, -sdRBox(sub(p, V(-0.705, SIDE_VENT_Y, 0.622)), V(0.075, 0.0135, 0.016), 0.012));
    U(dash, M_DASH);
    if (p.z < 0.66f && p.y > 0.82f && p.y < 0.96f) {
        float ring = minf(fabsf(sdRRect2(p.x - 0.555f, p.y - SIDE_VENT_Y, 0.153, 0.0165, 0.0165)),
                          fabsf(sdRRect2(p.x + 0.705f, p.y - SIDE_VENT_Y, 0.078, 0.0165, 0.0165))) - 0.0016f;
        U(maxf(ring, fabsf(p.z - 0.6195f) - 0.0025f), M_METAL);
        U(sdCapsule(V(fabsf(p.x - 0.02f), p.y, p.z), V(0.215, 0.9085, 0.6185), V(0.82, 0.9085, 0.6185), 0.0016), M_METAL);
    }

    if (qh.z > -0.2f && fabsf(qh.x) < 0.3f) {
        /* Glass, volume knob, hard keys, the vent's bezel. */
        U(sdPlate(qh, 0, 0, HU_GLASS_HX, HU_GLASS_HY, 0.012, -0.004, 0.004), M_GLASS);
        U(sdDisc(qh, HU_KNOB_X, HU_STRIP_Y, 0.0165, 0.006, 0.012) - 0.0015f, M_KNOB);
        for (int i = 0; i < 5; i++)
            U(sdPlate(qh, -0.070f + 0.048f * i, HU_STRIP_Y, 0.0205, 0.0115, 0.004, HU_FASCIA_Z + 0.002f, 0.002) - 0.001f, M_KEY);
        float ring = fabsf(sdRRect2(qh.x, qh.y - HU_VENT_Y, 0.131, 0.0155, 0.0155)) - 0.0016f;
        U(maxf(ring, fabsf(qh.z - HU_FASCIA_Z) - 0.0025f), M_METAL);
    }

    /* ---- Ambient light line across the dash, broken by the stack ---- */
    if (p.y > 0.88f && p.y < 0.98f && p.z < 0.75f) {
        U(sdCapsule(p, V(-0.80, 0.930, 0.617), V(-0.19, 0.930, 0.617), 0.0026), M_LED);
        U(sdCapsule(p, V(0.235, 0.930, 0.617), V(0.80, 0.930, 0.617), 0.0026), M_LED);
    }

    /* ---- Console furniture: the rotary selector with its metal collar ---- */
    if (p.y < 0.82f && p.z < 0.6f) {
        v3 k = sub(p, V(0.088, 0.748, 0.315));
        float body = maxf(sqrtf(k.x * k.x + k.z * k.z) - 0.027f, fabsf(k.y) - 0.014f) - 0.002f;
        U(body, M_KNOB);
        float collar = maxf(fabsf(sqrtf(k.x * k.x + k.z * k.z) - 0.031f) - 0.0025f, fabsf(k.y + 0.010f) - 0.006f);
        U(collar, M_METAL);
    }

    /* ---- Steering wheel, column and stalks ---- */
    v3 qw = loc(&FW, p);
    if (dot(qw, qw) < 0.20f) {
        float rim = sqrtf(powf(sqrtf(qw.x * qw.x + qw.y * qw.y) - 0.185f, 2) + qw.z * qw.z * 0.8f) - 0.0185f;
        float hub = sdRBox(sub(qw, V(0, -0.006, -0.046)), V(0.080, 0.060, 0.024), 0.032);
        float spokes = minf(sdRBox(sub(V(fabsf(qw.x), qw.y, qw.z), V(0.128, -0.002, -0.026)), V(0.058, 0.022, 0.011), 0.009),
                            sdRBox(sub(qw, V(0, -0.128, -0.026)), V(0.022, 0.060, 0.011), 0.009));
        float wheel = smin(rim, smin(hub, spokes, 0.022f), 0.016f);
        U(wheel, M_LEATHER);
        U(sdCapsule(qw, V(0, -0.004, -0.07), V(0, -0.03, -0.36), 0.043), M_COLUMN);
        float ax = fabsf(qw.x);
        v3 qs = V(ax, qw.y, qw.z);
        U(sdCapsule(qs, V(0.05, 0.030, -0.115), V(0.168, 0.046, -0.080), 0.0060), M_COLUMN);
        U(sdCapsule(qs, V(0.140, 0.042, -0.088), V(0.172, 0.047, -0.079), 0.0090), M_COLUMN);
        /* Thumb pads on the side spokes and the roundel on the hub. */
        U(sdPlate(qs, 0.128, -0.002, 0.036, 0.0155, 0.006, -0.0135, 0.0015), M_PIANO);
        U(maxf(fabsf(sqrtf(qw.x * qw.x + (qw.y + 0.006f) * (qw.y + 0.006f)) - 0.021f) - 0.0022f, fabsf(qw.z + 0.0215f) - 0.002f), M_METAL);
    }

    /* ---- Body shell: doors, pillars, header, roof, bonnet, floor ---- */
    float ax = fabsf(p.x);
    v3 ps = V(ax, p.y, p.z);
    float door = sdRBox(sub(ps, V(0.875, 0.50, 0.30)), V(0.075, 0.52, 1.15), 0.03);
    door = smin(door, sdRBox(sub(ps, V(0.775, 0.80, 0.10)), V(0.05, 0.04, 0.34), 0.03), 0.04);
    U(door, M_DOOR);
    if (p.y > 0.86f && p.y < 0.98f) U(sdCapsule(ps, V(0.797, 0.925, -0.3), V(0.797, 0.925, 0.60), 0.003), M_LED);
    float pillar = sdCapsule(ps, V(0.775, 1.02, 1.31), V(0.615, 1.435, 0.47), 0.058);
    float rail = sdCapsule(ps, V(0.615, 1.435, 0.47), V(0.66, 1.44, -0.9), 0.055);
    U(minf(pillar, rail), M_PILLAR);
    float header = sdCapsule(p, V(-0.62, 1.445, 0.455), V(0.62, 1.445, 0.455), 0.05);
    float roof = sdRBox(sub(p, V(0, 1.585, -0.35)), V(0.95, 0.10, 0.86), 0.03);
    U(smin(header, roof, 0.05), M_HEADLINER);
    U(sdRBox(sub(ps, V(0.335, 1.468, 0.275)), V(0.205, 0.011, 0.09), 0.009), M_VISOR);
    U(sdBox(sub(p, V(0, -0.05, 0.2)), V(0.95, 0.07, 1.3)), M_FLOOR);
    U(sdBox(sub(p, V(0, 0.75, -1.25)), V(0.95, 0.85, 0.08)), M_FLOOR);
    /* Bonnet and door mirrors, outside the glass. */
    if (p.z > 0.6f) {
        v3 b = sub(p, V(0, 0.80, 1.86));
        b = V(b.x, b.y * 0.9912f + b.z * 0.1323f, b.z * 0.9912f - b.y * 0.1323f);    /* the bonnet falls away to its nose */
        U(sdRBox(b, V(0.88, 0.15, 0.56), 0.14), M_PAINT);
        float mir = sdRBox(sub(ps, V(1.02, 1.065, 1.02)), V(0.080, 0.050, 0.032), 0.028);
        mir = smin(mir, sdCapsule(ps, V(0.86, 1.02, 1.08), V(0.96, 1.035, 1.04), 0.020), 0.02);
        U(mir, M_PAINT);
    }

    /* ---- Interior mirror on the centreline, with its stalk and the sensor housing on the glass ---- */
    if (p.y > 1.2f && ax < 0.3f) {
        v3 qm = loc(&FM, p);
        U(sdPlate(qm, 0, 0, 0.118, 0.033, 0.016, -0.008, 0.008) - 0.002f, M_MIRROR);
        U(sdCapsule(p, world(&FM, V(0, 0.01, -0.016)), V(0.0, 1.405, 0.615), 0.010), M_PILLAR);
        U(sdRBox(sub(p, V(0.0, 1.395, 0.625)), V(0.062, 0.022, 0.075), 0.018), M_PILLAR);
    }

    /* ---- The car ahead ---- */
    if (p.z > 12.0f) {
        v3 c = sub(p, V(0.0, ROAD_Y + 0.66, 27.0));
        float body = sdRBox(c, V(0.93, 0.42, 2.15), 0.22);
        float cabin = sdRBox(sub(c, V(0, 0.58, 0.25)), V(0.76, 0.30, 1.05), 0.24);
        U(smin(body, cabin, 0.18), M_CARAHEAD);
    }
    return h;
}

static v3 normalAt(v3 p) {
    const float e = 0.0007f;
    v3 a = V(e, -e, -e), b = V(-e, -e, e), c = V(-e, e, -e), d = V(e, e, e);
    return norm(add(add(mul(a, map(add(p, a)).d), mul(b, map(add(p, b)).d)), add(mul(c, map(add(p, c)).d), mul(d, map(add(p, d)).d))));
}

/* March [ro, rd]; returns the distance, or -1 for a miss. The road is a plane, met analytically. */
static float trace(v3 ro, v3 rd, float tmax, int *mat) {
    float troad = rd.y < -1e-5f ? (ROAD_Y - ro.y) / rd.y : 1e9f;
    float lim = minf(tmax, troad), t = 0.015f;
    int last = 0;
    for (int i = 0; i < 260 && t < lim; i++) {
        Hit h = map(add(ro, mul(rd, t)));
        if (h.d < 0.00035f + 0.00045f * t) { *mat = h.m; return t; }
        t += h.d * 0.9f; last = h.m;
    }
    /* Out of steps while grazing a surface: it is that surface, not the sky. */
    if (t < lim && t < 6.0f && last) { *mat = last; return t; }
    if (troad < tmax && t >= troad * 0.999f) { *mat = M_ROAD; return troad; }
    *mat = M_SKY; return -1;
}

static float softShadow(v3 ro, v3 rd, float maxt, float k) {
    float res = 1, t = 0.012f;
    for (int i = 0; i < 56 && t < maxt; i++) {
        float d = map(add(ro, mul(rd, t))).d;
        if (d < 0.0006f) return 0;
        res = minf(res, k * d / t);
        t += clampf(d, 0.006f, 0.12f);
    }
    return clampf(res, 0, 1);
}

static float occlusion(v3 p, v3 n) {
    float occ = 0, sca = 1;
    for (int i = 0; i < 5; i++) {
        float hr = 0.012f + 0.045f * i;
        occ += (hr - map(add(p, mul(n, hr))).d) * sca; sca *= 0.72f;
    }
    return clampf(1 - 2.6f * occ, 0, 1);
}

/* ---- What the screens show ---- */
static const uint8_t *ICON; static int ICON_W, ICON_H;
static v3 iconTexel(float u, float v, float *alpha) {   /* u, v in 0..1, v down */
    float x = clampf(u, 0, 1) * (ICON_W - 1), y = clampf(v, 0, 1) * (ICON_H - 1);
    int x0 = (int)x, y0 = (int)y, x1 = x0 + 1 < ICON_W ? x0 + 1 : x0, y1 = y0 + 1 < ICON_H ? y0 + 1 : y0;
    float fx = x - x0, fy = y - y0; float c[4] = {0, 0, 0, 0};
    for (int k = 0; k < 4; k++) {
        float a = ICON[(y0 * ICON_W + x0) * 4 + k], b = ICON[(y0 * ICON_W + x1) * 4 + k];
        float cc = ICON[(y1 * ICON_W + x0) * 4 + k], d = ICON[(y1 * ICON_W + x1) * 4 + k];
        c[k] = mixf(mixf(a, b, fx), mixf(cc, d, fx), fy) / 255.0f;
    }
    *alpha = c[3];
    return V(powf(c[0], 2.2f), powf(c[1], 2.2f), powf(c[2], 2.2f));
}

/* The head unit's picture: CarPlay's dock on the driver's side, its icon in the middle of a dark wallpaper. */
static v3 screenTex(float x, float y) {
    float gy = (y + HU_DISPLAY_HY) / (2 * HU_DISPLAY_HY);
    v3 c = mix3(V(0.0018, 0.0045, 0.014), V(0.0045, 0.012, 0.038), gy);
    float r = sqrtf((x - HU_ICON_X) * (x - HU_ICON_X) * 0.55f + y * y);
    c = add(c, mul(V(0.006, 0.026, 0.090), expf(-r * r / 0.006f)));
    if (x < -0.113f) {
        c = V(0.004, 0.006, 0.014);
        const v3 apps[4] = {V(0.10, 0.42, 1.0), V(1.0, 0.18, 0.30), V(0.16, 0.85, 0.36), V(0.55, 0.58, 0.66)};
        for (int i = 0; i < 4; i++) {
            float cy = 0.057f - 0.038f * i, d = sdRRect2(x + 0.1315f, y - cy, 0.0135, 0.0135, 0.0042);
            float a = sstep(0.0006f, -0.0006f, d);
            v3 tile = mul(apps[i], 0.75f + 0.5f * (y - cy + 0.0135f) / 0.027f);
            if (i == 3) {   /* the home grid */
                float gx = fabsf(fabsf(x + 0.1315f) - 0.0048f) - 0.0030f, gyy = fabsf(fabsf(y - cy) - 0.0048f) - 0.0030f;
                tile = mul(V(0.9, 0.92, 1.0), sstep(0.0004f, -0.0004f, maxf(gx, gyy)) * 0.9f + 0.05f);
            }
            c = mix3(c, tile, a);
        }
        return mul(c, 0.38f);
    }
    /* Two rings ripple out from the icon, as while CarPlay connects. */
    float ix = x - HU_ICON_X;
    for (int i = 1; i <= 2; i++) {
        float grow = 0.0125f * i, d = fabsf(sdRRect2(ix, y, HU_ICON_HALF + grow, HU_ICON_HALF + grow, 0.0105f + grow * 0.8f));
        c = add(c, mul(V(0.06, 0.26, 1.0), sstep(0.0016f, 0.0003f, d) * (i == 1 ? 0.55f : 0.26f)));
    }
    float alpha;
    v3 icon = iconTexel((ix + HU_ICON_HALF) / (2 * HU_ICON_HALF), (HU_ICON_HALF - y) / (2 * HU_ICON_HALF), &alpha);
    if (fabsf(ix) > HU_ICON_HALF || fabsf(y) > HU_ICON_HALF) alpha = 0;
    c = mix3(c, mul(icon, 0.80f), alpha);
    return c;
}

/* The driver's display: two fine-ticked dials, a readout between them. */
static v3 clusterTex(float x, float y) {
    v3 c = V(0.004, 0.007, 0.014);
    for (int s = -1; s <= 1; s += 2) {
        float dx = x - s * 0.098f, dy = y + 0.004f, r = sqrtf(dx * dx + dy * dy), a = atan2f(dy, dx);
        float sweep = (a > -0.45f * (float)M_PI || a < -0.55f * (float)M_PI) ? 1 : 0;      /* open at the bottom */
        c = add(c, mul(V(0.10, 0.55, 1.0), sstep(0.0012f, 0.0003f, fabsf(r - 0.032f)) * sweep * 1.6f));
        float tick = fabsf(fmodf(a + 10 * (float)M_PI, (float)M_PI / 12) - (float)M_PI / 24);
        if (r > 0.0255f && r < 0.0295f && sweep > 0) c = add(c, mul(V(0.6, 0.7, 0.8), sstep(0.035f, 0.012f, tick) * 0.9f));
        float na = s < 0 ? 2.2f : 0.9f, nx = cosf(na), ny = sinf(na), along = dx * nx + dy * ny, off = fabsf(dx * ny - dy * nx);
        if (along > 0.002f && along < 0.027f) c = add(c, mul(V(1, 1, 1), sstep(0.0011f, 0.0003f, off) * 1.6f));
    }
    for (int i = -1; i <= 1; i++) {
        float d = sqrtf((x - i * 0.011f) * (x - i * 0.011f) + (y - 0.016f) * (y - 0.016f));
        c = add(c, mul(i == 0 ? V(0.2, 0.7, 1.0) : V(0.2, 0.9, 0.4), sstep(0.0026f, 0.0016f, d) * 1.5f));
    }
    c = add(c, mul(V(0.7, 0.75, 0.8), sstep(0.0006f, -0.0006f, sdRRect2(x, y + 0.012f, 0.030, 0.0016, 0.0016)) * 0.5f));
    c = add(c, mul(V(0.15, 0.5, 1.0), sstep(0.0006f, -0.0006f, sdRRect2(x + 0.010f, y + 0.012f, 0.020, 0.0016, 0.0016)) * 1.6f));
    return c;
}

/* What the mirror shows: the rear window between two headrests, and whoever follows. */
static v3 mirrorTex(float u, float v) {   /* -1..1 each way */
    v3 c = NIGHT ? V(0.010, 0.012, 0.018) : V(0.16, 0.155, 0.15);
    float win = sdRRect2(u, v - 0.08f, 0.60, 0.50, 0.22);
    v3 glass = NIGHT ? mix3(V(0.020, 0.030, 0.075), V(0.045, 0.060, 0.14), v * 0.5f + 0.5f)
                     : mix3(V(0.75, 0.82, 0.92), V(0.42, 0.62, 0.95), v * 0.5f + 0.5f);
    c = mix3(c, glass, sstep(0.03f, -0.03f, win));
    if (NIGHT) for (int s = -1; s <= 1; s += 2) {
        float d = sqrtf((u - s * 0.085f) * (u - s * 0.085f) * 4.0f + (v + 0.02f) * (v + 0.02f) * 0.25f);
        c = add(c, mul(V(1.0, 0.95, 0.82), 1.1f * expf(-d * d / 0.0007f) + 0.03f * expf(-d * d / 0.012f)));
    }
    for (int s = -1; s <= 1; s += 2) {
        float d = sdRRect2(u - s * 0.66f, v + 0.70f, 0.20, 0.52, 0.12);
        c = mix3(c, NIGHT ? V(0.004, 0.004, 0.006) : V(0.05, 0.05, 0.055), sstep(0.03f, -0.03f, d));
    }
    return c;
}

/* ---- Outside the glass ---- */
typedef struct { v3 dir; float r; v3 col; } Bokeh;
static Bokeh CITY[220]; static int NCITY;
static v3 LAMP[10]; static int NLAMP;
static void buildOutside(v3 eye) {
    (void)eye;
    NCITY = 0;
    for (int i = 0; i < 150; i++) {
        float a = (hash1(i * 3.17f + 1) * 2 - 1), az = a * fabsf(a) * 0.95f + 0.02f;
        float el = 0.003f + hash1(i * 7.31f + 2) * hash1(i * 1.7f + 9) * 0.030f;
        float size = 0.0022f + 0.0075f * powf(hash1(i * 5.3f + 4), 3.0f);
        float warm = hash1(i * 9.1f + 5);
        v3 col = warm < 0.62f ? V(1.0, 0.72, 0.38) : (warm < 0.86f ? V(1.0, 0.92, 0.80) : (warm < 0.94f ? V(0.55, 0.75, 1.0) : V(1.0, 0.30, 0.22)));
        CITY[NCITY].dir = norm(V(sinf(az), el, cosf(az)));
        CITY[NCITY].r = size;
        CITY[NCITY].col = mul(col, ((0.25f + 0.9f * hash1(i * 2.9f + 6)) * (0.003f / size) * 0.9f + 0.10f) * 0.16f);
        NCITY++;
    }
    NLAMP = 0;
    for (int i = 0; i < 6; i++) LAMP[NLAMP++] = V(-7.0f, ROAD_Y + 8.6f, 34.0f + 42.0f * i);
}

static v3 skyColor(v3 rd) {
    float e = rd.y;
    if (NIGHT) {
        v3 c = mix3(V(0.012, 0.010, 0.024), V(0.0016, 0.0024, 0.007), sstep(0.0f, 0.42f, e));
        float glow = expf(-maxf(e, 0) * 22.0f);
        c = add(c, mul(V(0.36, 0.15, 0.085), glow * 0.10f * (0.55f + 0.45f * expf(-rd.x * rd.x * 4.0f))));
        c = add(c, mul(V(0.09, 0.05, 0.11), expf(-maxf(e, 0) * 6.0f) * 0.10f));
        /* Stars, thinning toward the glow. */
        float sx = atan2f(rd.x, rd.z) * 420.0f, sy = e * 420.0f, cx = floorf(sx), cy = floorf(sy);
        float star = hash2(cx, cy);
        if (star > 0.985f && e > 0.06f) {
            float dx = sx - cx - 0.5f, dy = sy - cy - 0.5f;
            c = add(c, mul(V(0.8, 0.85, 1.0), expf(-(dx * dx + dy * dy) * 26.0f) * (star - 0.985f) * 60.0f * sstep(0.06f, 0.25f, e)));
        }
        return c;
    }
    v3 c = mix3(V(0.56, 0.72, 0.95), V(0.13, 0.33, 0.80), sstep(0.0f, 0.50f, e));
    c = add(c, mul(V(0.50, 0.46, 0.40), expf(-maxf(e, 0) * 12.0f) * 0.22f));
    /* A line of trees and low hills along the horizon. */
    float az = atan2f(rd.x, rd.z);
    float ridge = 0.012f + 0.010f * noise2(az * 9.0f, 1.0f) + 0.006f * noise2(az * 31.0f, 4.0f);
    c = mix3(c, V(0.10, 0.17, 0.11), sstep(ridge + 0.002f, ridge - 0.002f, e) * 0.85f);
    float far = 0.030f + 0.012f * noise2(az * 3.0f, 9.0f);
    c = mix3(c, V(0.30, 0.40, 0.50), sstep(far + 0.004f, far - 0.004f, e) * sstep(ridge - 0.002f, ridge + 0.002f, e) * 0.55f);
    return c;
}

static v3 roadColor(v3 p, float t) {
    float blur = 0.012f + t * 0.0016f;
    float n = noise2(p.x * 9.0f, p.z * 9.0f) * 0.5f + noise2(p.x * 37.0f, p.z * 37.0f) * 0.5f;
    v3 asphalt = NIGHT ? mul(V(0.020, 0.021, 0.026), 0.75f + 0.5f * n) : mul(V(0.20, 0.20, 0.21), 0.85f + 0.3f * n);
    v3 verge = NIGHT ? V(0.006, 0.008, 0.010) : mul(V(0.17, 0.25, 0.11), 0.75f + 0.5f * noise2(p.x * 0.9f, p.z * 0.9f));
    v3 c = mix3(asphalt, verge, maxf(sstep(2.15f, 2.45f, p.x), sstep(-5.75f, -6.05f, p.x)));
    /* Headlights pool on the near road at night. */
    if (NIGHT) {
        float pool = expf(-powf((p.z - 11.0f) / 10.0f, 2)) * expf(-p.x * p.x / 3.2f);
        c = add(c, mul(mulv(asphalt, V(1.0, 0.93, 0.80)), pool * 14.0f));
    }
    float edge = minf(fabsf(p.x - 1.85f), fabsf(p.x + 5.45f)), centre = fabsf(p.x + 1.80f);
    float dash = sstep(0.0f, blur * 3, 1.5f - fabsf(fmodf(p.z + 1000.0f, 9.0f) - 4.5f));
    v3 white = NIGHT ? V(0.42, 0.43, 0.46) : V(0.85, 0.85, 0.85), amber = NIGHT ? V(0.85, 0.56, 0.10) : V(0.95, 0.70, 0.16);
    float lit = NIGHT ? 0.025f + 1.9f * expf(-powf((p.z - 14.0f) / 20.0f, 2)) * expf(-p.x * p.x / 14.0f) : 1.0f;
    c = mix3(c, mul(white, lit), sstep(0.075f + blur, 0.075f - blur, edge));
    c = mix3(c, mul(amber, lit), sstep(0.075f + blur, 0.075f - blur, centre) * dash);
    return c;
}

/* Lights far beyond the road, and the street lamps along it: soft discs, as a lens focused on the cabin sees them. */
static v3 outsideLights(v3 ro, v3 rd, float tHit) {
    v3 c = V(0, 0, 0);
    if (!NIGHT) return c;
    if (tHit < 0) for (int i = 0; i < NCITY; i++) {
        float d = acosf(clampf(dot(rd, CITY[i].dir), -1, 1));
        if (d < CITY[i].r * 1.25f) c = add(c, mul(CITY[i].col, sstep(CITY[i].r * 1.12f, CITY[i].r * 0.82f, d) * (0.75f + 0.25f * sstep(CITY[i].r * 0.9f, CITY[i].r * 0.75f, d))));
    }
    for (int i = 0; i < NLAMP; i++) {
        v3 to = sub(LAMP[i], ro); float dist = len(to);
        if (tHit > 0 && tHit < dist) continue;
        float d = acosf(clampf(dot(rd, mul(to, 1 / dist)), -1, 1)), r = 0.0045f + 0.07f / dist;
        c = add(c, mul(V(1.0, 0.80, 0.52), 0.22f * sstep(r * 1.1f, r * 0.8f, d) + 0.03f * expf(-d * d / (r * r * 9.0f))));
    }
    return c;
}

/* ---- Materials ---- */
typedef struct { v3 alb; float rough, metal, refl; v3 emis; } Mat;
static Mat material(int m, v3 p, v3 *n) {
    Mat a = {V(0.05, 0.05, 0.055), 0.8f, 0, 0, V(0, 0, 0)};
    float day = NIGHT ? 0 : 1;
    switch (m) {
    case M_DASH: {
        v3 qh = loc(&FH, p), qc = loc(&FC, p);
        a.alb = mix3(V(0.050, 0.052, 0.060), V(0.060, 0.058, 0.058), day); a.rough = 0.82f;
        /* The lower half of the dash is a lighter, grained panel under the light line. */
        if (p.y < 0.925f && p.z < 0.75f && fabsf(p.x - 0.02f) > 0.20f) { a.alb = mix3(V(0.075, 0.072, 0.072), V(0.11, 0.10, 0.095), day); a.rough = 0.7f; }
        /* The fascia: one piece of piano black from the glass to the vent. */
        if (fabsf(qh.x) < 0.170f && qh.y > -0.212f && qh.y < 0.108f && qh.z > HU_FASCIA_Z - 0.02f) {
            a.alb = V(0.012, 0.012, 0.014); a.rough = 0.10f; a.refl = 1;
            float kr = sqrtf((qh.x - HU_KNOB_X) * (qh.x - HU_KNOB_X) + (qh.y - HU_STRIP_Y) * (qh.y - HU_STRIP_Y));
            a.emis = mul(V(0.25, 0.55, 1.0), sstep(0.0026f, 0.0008f, fabsf(kr - 0.0205f)) * 2.2f);
            /* Slats and the tab inside the vent. */
            if (fabsf(qh.y - HU_VENT_Y) < 0.0125f && fabsf(qh.x) < 0.128f && qh.z < HU_FASCIA_Z - 0.004f) {
                float s = fabsf(fmodf(qh.y - HU_VENT_Y + 1.0f, 0.0056f) - 0.0028f) / 0.0028f;
                a.alb = mul(V(0.03, 0.03, 0.034), 0.25f + 0.75f * s); a.rough = 0.45f; a.refl = 0;
                if (fabsf(qh.x - 0.004f) < 0.005f) { a.alb = V(0.35, 0.36, 0.38); a.metal = 1; a.rough = 0.3f; }
            }
        }
        /* Slats in the side vents; the glovebox's seam in the lower panel. */
        if (fabsf(p.y - SIDE_VENT_Y) < 0.0135f && p.z > 0.624f && p.z < 0.66f && (fabsf(p.x - 0.555f) < 0.15f || fabsf(p.x + 0.705f) < 0.075f)) {
            float sl = fabsf(fmodf(p.y - SIDE_VENT_Y + 1.0f, 0.0056f) - 0.0028f) / 0.0028f;
            a.alb = mul(V(0.03, 0.03, 0.034), 0.2f + 0.8f * sl); a.rough = 0.45f;
        }
        if (p.z < 0.64f && fabsf(sdRRect2(p.x - 0.53f, p.y - 0.735f, 0.24, 0.085, 0.02)) < 0.0016f) a.alb = mul(a.alb, 0.25f);
        /* The driver's display, at the back of its recess. */
        if (fabsf(qc.x) < 0.152f && fabsf(qc.y) < 0.042f && qc.z < 0.006f && qc.z > -0.02f) {
            a.alb = V(0.005, 0.005, 0.006); a.rough = 0.12f; a.refl = 1; a.emis = clusterTex(qc.x, qc.y);
        }
        /* The charging pad: rubber with a faint ring. */
        if (fabsf(p.x + 0.012f) < 0.06f && fabsf(p.z - 0.40f) < 0.086f && p.y < 0.7325f && p.y > 0.70f) {
            a.alb = V(0.022, 0.022, 0.025); a.rough = 0.9f;
            float r = sqrtf((p.x + 0.012f) * (p.x + 0.012f) + (p.z - 0.40f) * (p.z - 0.40f));
            a.alb = add(a.alb, mul(V(0.10, 0.10, 0.11), sstep(0.0018f, 0.0004f, fabsf(r - 0.024f))));
        }
        break;
    }
    case M_GLASS: {
        v3 q = loc(&FH, p);
        a.alb = V(0.004, 0.004, 0.005); a.rough = 0.05f; a.refl = 0.35f;
        if (fabsf(q.x) < HU_DISPLAY_HX && fabsf(q.y) < HU_DISPLAY_HY && q.z > -0.002f) a.emis = screenTex(q.x, q.y);
        break;
    }
    case M_PIANO: a.alb = V(0.012, 0.012, 0.014); a.rough = 0.10f; a.refl = 1; {
        v3 q = loc(&FW, p); float ax = fabsf(q.x) - 0.128f;
        /* Two marks on each thumb pad. */
        a.emis = mul(V(0.85, 0.9, 1.0), (sstep(0.0009f, -0.0009f, sdRRect2(ax + 0.012f, q.y + 0.002f, 0.0022, 0.0075, 0.002)) +
                     sstep(0.0038f, 0.0026f, sqrtf((ax - 0.014f) * (ax - 0.014f) + (q.y + 0.002f) * (q.y + 0.002f)))) * 0.55f);
        break;
    }
    case M_KNOB: {
        a.alb = V(0.035, 0.035, 0.04); a.rough = 0.3f; a.metal = 0.6f; a.refl = 0.6f;
        /* Knurling: fine ridges round the grip. */
        v3 q = loc(&FH, p); float ang = atan2f(q.y - HU_STRIP_Y, q.x - HU_KNOB_X);
        if (p.y < 0.82f) { v3 k = sub(p, V(0.088, 0.748, 0.315)); ang = atan2f(k.z, k.x); }
        float ridge = sinf(ang * 46.0f);
        a.alb = mul(a.alb, 0.75f + 0.5f * (ridge * 0.5f + 0.5f));
        break;
    }
    case M_KEY: {
        v3 q = loc(&FH, p);
        a.alb = V(0.022, 0.022, 0.026); a.rough = 0.28f; a.refl = 0.5f;
        int i = (int)floorf((q.x + 0.094f) / 0.048f);
        float kx = q.x - (-0.070f + 0.048f * i), ky = q.y - HU_STRIP_Y, g = 0;
        if (i == 0) g = sstep(0.0011f, 0.0004f, fabsf(sqrtf(kx * kx + ky * ky) - 0.0046f));                       /* home */
        else if (i == 1) g = sstep(0.0010f, 0.0003f, fabsf(fabsf(ky) - (kx + 0.0030f) * 0.9f)) * (fabsf(kx) < 0.0034f);   /* back */
        else if (i == 2) g = sstep(0.0010f, 0.0003f, fabsf(maxf(fabsf(ky) * 0.9f + kx * 0.52f, -kx) - 0.0030f)); /* play */
        else if (i == 3) g = sstep(0.0009f, 0.0003f, fabsf(fabsf(fabsf(ky) - 0.0027f) - 0.00135f) ) * (fabsf(kx) < 0.0048f) + sstep(0.0008f, 0.0003f, fabsf(ky)) * (fabsf(kx) < 0.0048f);
        else g = sstep(0.0011f, 0.0004f, fabsf(maxf(fabsf(kx) * 0.87f + ky * 0.5f, -ky) - 0.0030f));              /* hazard */
        a.emis = mul(i == 4 ? V(1.0, 0.16, 0.12) : V(0.85, 0.9, 1.0), clampf(g, 0, 1) * (i == 4 ? 1.5f : 0.9f));
        break;
    }
    case M_LEATHER: a.alb = mix3(V(0.060, 0.060, 0.068), V(0.060, 0.058, 0.056), day); a.rough = 0.36f; a.refl = 0.10f; break;
    case M_COLUMN: a.alb = V(0.028, 0.028, 0.032); a.rough = 0.6f; break;
    case M_METAL: a.alb = V(0.62, 0.64, 0.68); a.rough = 0.28f; a.metal = 1; a.refl = 0.8f; break;
    case M_HEADLINER: a.alb = mix3(V(0.085, 0.085, 0.095), V(0.50, 0.48, 0.45), day); a.rough = 0.95f; break;
    case M_VISOR: a.alb = mix3(V(0.10, 0.10, 0.11), V(0.55, 0.53, 0.50), day); a.rough = 0.95f; break;
    case M_PILLAR: a.alb = mix3(V(0.075, 0.075, 0.085), V(0.42, 0.40, 0.38), day); a.rough = 0.85f; break;
    case M_DOOR: a.alb = mix3(V(0.055, 0.055, 0.062), V(0.10, 0.095, 0.09), day); a.rough = 0.75f; break;
    case M_FLOOR: a.alb = V(0.02, 0.02, 0.022); a.rough = 0.95f; break;
    case M_LED: a.alb = V(0, 0, 0); a.emis = mul(V(0.16, 0.42, 1.0), NIGHT ? 1.1f : 0.9f); break;
    case M_PAINT: a.alb = mix3(V(0.035, 0.040, 0.055), V(0.16, 0.18, 0.23), day); a.rough = 0.16f; a.refl = 0.9f; break;
    case M_MIRROR: {
        v3 q = loc(&FM, p);
        a.alb = V(0.010, 0.010, 0.012); a.rough = 0.25f; a.refl = 0.15f;
        if (q.z > -0.004f && fabsf(q.x) < 0.112f && fabsf(q.y) < 0.0275f && sdRRect2(q.x, q.y, 0.112, 0.0275, 0.013) < 0) {
            a.emis = mul(mirrorTex(q.x / 0.112f, q.y / 0.0275f), NIGHT ? 1.0f : 1.4f); a.rough = 0.04f; a.refl = 0;
        }
        break;
    }
    case M_CARAHEAD: {
        v3 c = sub(p, V(0.0, ROAD_Y + 0.66, 27.0));
        a.alb = mix3(V(0.020, 0.022, 0.030), V(0.22, 0.25, 0.30), day); a.rough = 0.25f; a.refl = 0.6f;
        if (c.z < -1.6f) {
            float bar = sstep(0.05f, 0.03f, fabsf(c.y - 0.13f)) * sstep(0.84f, 0.78f, fabsf(c.x));
            float lamp = sstep(0.11f, 0.07f, fabsf(c.y - 0.13f)) * sstep(0.86f, 0.80f, fabsf(c.x)) * sstep(0.42f, 0.50f, fabsf(c.x));
            a.emis = mul(V(1.0, 0.07, 0.04), (bar * 0.9f + lamp * 3.2f) * (NIGHT ? 3.0f : 1.2f));
            if (c.y > 0.42f && c.y < 0.80f && fabsf(c.x) < 0.62f) a.alb = NIGHT ? V(0.006, 0.007, 0.012) : V(0.10, 0.14, 0.20);
        }
        break;
    }
    }
    (void)n;
    return a;
}

/* ---- Lights ---- */
typedef struct { v3 pos; v3 col; float soft; int directional; } Light;
static Light LIGHTS[8]; static int NLIGHT;
static v3 AMBIENT_DIR, AMBIENT, AMBIENT_BACK;
static void buildLights(void) {
    NLIGHT = 0;
    /* The head unit lights the cabin; four points across its glass make its shadows soft. */
    for (int i = 0; i < 4; i++) {
        float x = (i % 2 ? 0.08f : -0.08f), y = (i / 2 ? 0.05f : -0.05f);
        LIGHTS[NLIGHT++] = (Light){world(&FH, V(x, y, 0.06f)), mul(V(0.45, 0.62, 1.0), (NIGHT ? 0.016f : 0.006f)), 7.0f, 0};
    }
    LIGHTS[NLIGHT++] = (Light){world(&FC, V(0, 0, 0.07f)), mul(V(0.40, 0.75, 1.0), NIGHT ? 0.012f : 0.004f), 7.0f, 0};
    if (NIGHT) {
        /* The city's glow through the windscreen, and a little of it through the driver's window. */
        LIGHTS[NLIGHT++] = (Light){norm(V(0.12, 0.50, 0.86)), mul(V(1.0, 0.78, 0.66), 0.15f), 9.0f, 1};
        LIGHTS[NLIGHT++] = (Light){norm(V(-0.85, 0.38, 0.36)), mul(V(0.55, 0.62, 1.0), 0.07f), 9.0f, 1};
        /* The cabin's own bounce, from behind the driver: it models the faces turned to the camera. */
        LIGHTS[NLIGHT++] = (Light){norm(V(0.35, 0.55, -0.76)), mul(V(0.62, 0.70, 1.0), 0.045f), 5.0f, 1};
        AMBIENT_DIR = norm(V(0.0, 0.55, 0.83)); AMBIENT = V(0.040, 0.042, 0.062); AMBIENT_BACK = V(0.008, 0.0085, 0.013);
    } else {
        LIGHTS[NLIGHT++] = (Light){norm(V(-0.42, 0.70, 0.58)), mul(V(1.0, 0.95, 0.86), 2.4f), 14.0f, 1};
        AMBIENT_DIR = norm(V(0.0, 0.62, 0.78)); AMBIENT = V(0.62, 0.70, 0.86); AMBIENT_BACK = V(0.11, 0.11, 0.12);
    }
}

static v3 shade(v3 ro, v3 rd, float t, int m, int depth);

/* Follows a ray to whatever it meets and returns its light. */
static v3 radiance(v3 ro, v3 rd, int depth, float *tOut) {
    int m; float t = trace(ro, rd, 400.0f, &m);
    if (tOut) *tOut = t;
    if (t < 0) return add(skyColor(rd), outsideLights(ro, rd, -1));
    v3 c = shade(ro, rd, t, m, depth);
    if (t > 3.0f) c = add(c, outsideLights(ro, rd, t));
    return c;
}

static v3 shade(v3 ro, v3 rd, float t, int m, int depth) {
    v3 p = add(ro, mul(rd, t));
    if (m == M_ROAD) {
        v3 c = roadColor(p, t);
        if (!NIGHT) c = mul(c, 2.3f);
        /* The road fades into the horizon's haze. */
        v3 haze = NIGHT ? skyColor(norm(V(rd.x, 0.004f, rd.z))) : V(0.50, 0.60, 0.72);
        return mix3(c, haze, 1 - expf(-t * (NIGHT ? 0.012f : 0.0028f)));
    }
    v3 n = normalAt(p);
    Mat a = material(m, p, &n);
    float ao = occlusion(p, n);
    float facing = clampf(dot(n, AMBIENT_DIR) * 0.5f + 0.5f, 0, 1);
    v3 c = mulv(a.alb, mul(mix3(AMBIENT_BACK, AMBIENT, facing * facing), ao));
    v3 spec = V(0, 0, 0);
    float shin = 2.0f / maxf(powf(a.rough, 4), 1e-4f) - 2.0f;
    for (int i = 0; i < NLIGHT; i++) {
        v3 l; float atten = 1, dist = 30;
        if (LIGHTS[i].directional) l = LIGHTS[i].pos;
        else { v3 d = sub(LIGHTS[i].pos, p); dist = len(d); l = mul(d, 1 / dist); atten = 1 / (dist * dist + 0.02f); }
        float nl = dot(n, l);
        if (nl <= 0) continue;
        float sh = softShadow(add(p, mul(n, 0.002f)), l, LIGHTS[i].directional ? 6.0f : dist - 0.03f, LIGHTS[i].soft);
        if (sh <= 0) continue;
        v3 li = mul(LIGHTS[i].col, atten * sh * nl);
        c = add(c, mulv(a.alb, mul(li, 1 - a.metal)));
        if (m == M_GLASS && !LIGHTS[i].directional) continue;
        v3 hv = norm(sub(l, rd));
        float sp = powf(maxf(dot(n, hv), 0), shin) * (shin + 8) / 25.0f;
        spec = add(spec, mul(li, sp));
    }
    float fres = 0.04f + 0.96f * powf(1 - maxf(dot(n, mul(rd, -1)), 0), 5);
    v3 f0 = mix3(V(0.04, 0.04, 0.04), a.alb, a.metal);
    c = add(c, mulv(spec, add(f0, mul(V(1, 1, 1), 0.25f * (1 - a.rough)))));
    if (a.refl > 0 && depth < 2) {
        v3 rr = reflectv(rd, n);
        v3 env = radiance(add(p, mul(n, 0.003f)), rr, depth + 1, NULL);
        v3 k = add(mul(f0, 1 - fres), mul(V(1, 1, 1), fres));
        c = add(c, mul(mulv(env, k), a.refl * (0.35f + 0.65f * ao)));
    }
    c = add(c, a.emis);
    if (t > 3.0f && !NIGHT) c = mix3(c, V(0.50, 0.60, 0.72), 1 - expf(-t * 0.0028f));
    return c;
}

/* ---- Camera and the glass between the cabin and the road ---- */
static v3 EYE, FWD, RIGHT, UP; static float TANH, ASPECT;
static const v3 GLASS_BASE = {0, 1.05f, 1.31f}, GLASS_UP = {0, 0.395f, -0.855f};

static v3 throughGlass(v3 c, v3 ro, v3 rd, float t) {
    v3 nrm = norm(cross(V(1, 0, 0), GLASS_UP));
    float denom = dot(rd, nrm);
    if (fabsf(denom) < 1e-5f) return c;
    float tw = dot(sub(GLASS_BASE, ro), nrm) / denom;
    if (tw <= 0 || (t > 0 && tw > t)) return c;
    v3 pw = add(ro, mul(rd, tw));
    float s = dot(sub(pw, GLASS_BASE), GLASS_UP) / dot(GLASS_UP, GLASS_UP);
    if (s < 0 || s > 1.05f || fabsf(pw.x) > 0.80f) return c;
    c = mul(c, NIGHT ? 0.93f : 0.90f);
    /* The printed band along the top of the glass, thinning out in dots. */
    float band = sstep(0.80f, 0.90f, s + 0.035f * expf(-pw.x * pw.x / 0.02f));
    float cell = 0.0075f, dx = fmodf(pw.x + 10.0f, cell) - cell / 2, dy = fmodf(s * 0.94f + 10.0f, cell) - cell / 2;
    float dot_ = sstep(cell * 0.5f * (band * 1.25f), cell * 0.5f * (band * 1.25f) - 0.0012f, sqrtf(dx * dx + dy * dy));
    c = mul(c, 1 - clampf(maxf(dot_ * sstep(0.0f, 0.2f, band), sstep(0.75f, 1.0f, band)), 0, 1) * 0.94f);
    /* The dash top and the screens' light, faintly in the bottom of the glass; a soft glare across it. */
    c = add(c, mul(NIGHT ? V(0.010, 0.016, 0.034) : V(0.045, 0.045, 0.045), sstep(0.42f, 0.0f, s)));
    float streak = expf(-powf((pw.x * 0.9f - s * 0.55f + 0.32f) / 0.13f, 2));
    c = add(c, mul(NIGHT ? V(0.006, 0.008, 0.014) : V(0.05, 0.05, 0.055), streak));
    return c;
}

static int W, H, SS;
static float *HDR, *DEPTH;
static void *renderRows(void *arg) {
    int id = (int)(intptr_t)arg;
    for (int y = id; y < H; y += 12) {
        for (int x = 0; x < W; x++) {
            v3 sum = V(0, 0, 0); float dsum = 0;
            for (int j = 0; j < SS; j++) for (int i = 0; i < SS; i++) {
                float fx = (x + (i + 0.5f) / SS) / W * 2 - 1, fy = 1 - (y + (j + 0.5f) / SS) / H * 2;
                v3 rd = norm(add(FWD, add(mul(RIGHT, fx * TANH), mul(UP, fy * TANH / ASPECT))));
                float t; v3 c = radiance(EYE, rd, 0, &t);
                if (t < 0 || t > 2.2f) c = throughGlass(c, EYE, rd, t);
                c = V(minf(c.x, 24), minf(c.y, 24), minf(c.z, 24));
                sum = add(sum, c); dsum += t < 0 ? 400 : t;
            }
            float inv = 1.0f / (SS * SS);
            HDR[(y * W + x) * 3] = sum.x * inv; HDR[(y * W + x) * 3 + 1] = sum.y * inv; HDR[(y * W + x) * 3 + 2] = sum.z * inv;
            DEPTH[y * W + x] = dsum * inv;
        }
    }
    return NULL;
}

/* A separable blur of src into dst; weight, when given, keeps what is near out of what is far. */
static void blur(const float *src, float *dst, const float *weight, float sigma) {
    int r = (int)ceilf(sigma * 2.5f); float *k = malloc(sizeof(float) * (2 * r + 1));
    for (int i = -r; i <= r; i++) k[i + r] = expf(-i * i / (2 * sigma * sigma));
    float *tmp = malloc(sizeof(float) * W * H * 4);
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
        float a[4] = {0, 0, 0, 0};
        for (int i = -r; i <= r; i++) {
            int xx = x + i < 0 ? 0 : (x + i >= W ? W - 1 : x + i); float w = k[i + r] * (weight ? weight[y * W + xx] : 1);
            for (int c = 0; c < 3; c++) a[c] += src[(y * W + xx) * 3 + c] * w;
            a[3] += w;
        }
        memcpy(&tmp[(y * W + x) * 4], a, sizeof a);
    }
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
        float a[4] = {0, 0, 0, 0};
        for (int i = -r; i <= r; i++) {
            int yy = y + i < 0 ? 0 : (y + i >= H ? H - 1 : y + i); float w = k[i + r];
            for (int c = 0; c < 4; c++) a[c] += tmp[(yy * W + x) * 4 + c] * w;
        }
        for (int c = 0; c < 3; c++) dst[(y * W + x) * 3 + c] = a[3] > 1e-6f ? a[c] / a[3] : src[(y * W + x) * 3 + c];
    }
    free(tmp); free(k);
}

static float aces(float x) { return clampf(x * (2.51f * x + 0.03f) / (x * (2.43f * x + 0.59f) + 0.14f), 0, 1); }

int main(int argc, char **argv) {
    if (argc < 7) { fprintf(stderr, "usage: render_cabin <night|day> <w> <h> <ss> <icon.rgba> <out.bmp>\n"); return 2; }
    NIGHT = strcmp(argv[1], "day") != 0;
    W = atoi(argv[2]); H = atoi(argv[3]); SS = atoi(argv[4]);
    FILE *fi = fopen(argv[5], "rb");
    if (!fi) { fprintf(stderr, "cannot read %s\n", argv[5]); return 1; }
    int dims[2]; if (fread(dims, 4, 2, fi) != 2) return 1;
    ICON_W = dims[0]; ICON_H = dims[1];
    uint8_t *icon = malloc((size_t)ICON_W * ICON_H * 4);
    if (fread(icon, 4, (size_t)ICON_W * ICON_H, fi) != (size_t)ICON_W * ICON_H) return 1;
    fclose(fi); ICON = icon;

    /* The parts' frames. The wheel leans away at its top; the head unit leans back and turns to the driver. */
    FW = frameOf(V(-0.36, 0.975, 0.43), V(0, sinf(0.42f), -cosf(0.42f)));
    FH = frameOf(V(0.03, 1.04, 0.615), V(-0.135, 0.245, -0.960));
    FC = frameOf(V(-0.36, 1.068, 0.700), V(0, sinf(0.20f), -cosf(0.20f)));
    FM = frameOf(V(0.0, 1.318, 0.565), V(-0.26, -0.10, -0.96));

    /* The driver's eye, a little toward the middle of the car, looking at the dash between wheel and screen. */
    EYE = V(-0.205, 1.240, -0.335);
    v3 target = V(-0.045, 1.050, 0.70);
    FWD = norm(sub(target, EYE)); RIGHT = norm(cross(V(0, 1, 0), FWD)); UP = cross(FWD, RIGHT);
    ASPECT = (float)W / H; TANH = tanf(0.5f * 75.0f * (float)M_PI / 180.0f);
    buildLights(); buildOutside(EYE);

    HDR = malloc(sizeof(float) * W * H * 3); DEPTH = malloc(sizeof(float) * W * H);
    pthread_t th[12];
    for (int i = 0; i < 12; i++) pthread_create(&th[i], NULL, renderRows, (void *)(intptr_t)i);
    for (int i = 0; i < 12; i++) pthread_join(th[i], NULL);

    /* A lens focused on the head unit: what is beyond the glass is soft. Only far pixels feed the blur. */
    float scale = W / 1680.0f;
    float *far = malloc(sizeof(float) * W * H), *soft = malloc(sizeof(float) * W * H * 3);
    for (int i = 0; i < W * H; i++) far[i] = sstep(2.5f, 9.0f, DEPTH[i]);
    blur(HDR, soft, far, 3.2f * scale);
    for (int i = 0; i < W * H; i++) for (int c = 0; c < 3; c++) HDR[i * 3 + c] = mixf(HDR[i * 3 + c], soft[i * 3 + c], far[i] * 0.85f);

    /* Bloom: bright things glow, near and wide. */
    float *bright = malloc(sizeof(float) * W * H * 3), *b1 = malloc(sizeof(float) * W * H * 3), *b2 = malloc(sizeof(float) * W * H * 3);
    for (int i = 0; i < W * H * 3; i++) bright[i] = maxf(HDR[i] - 0.9f, 0);
    blur(bright, b1, NULL, 5.0f * scale); blur(bright, b2, NULL, 22.0f * scale);
    float exposure = NIGHT ? 3.0f : 0.74f;

    /* Tone, gamma, a little noise against banding, and out as a BMP. */
    int row = (W * 3 + 3) & ~3; uint8_t *out = calloc((size_t)row * H, 1);
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
        float fx = (x + 0.5f) / W * 2 - 1, fy = (y + 0.5f) / H * 2 - 1;
        float vig = 1 - 0.20f * powf(fx * fx * 0.8f + fy * fy, 1.2f);
        for (int c = 0; c < 3; c++) {
            int i = (y * W + x) * 3 + c;
            float v = (HDR[i] + 0.20f * b1[i] + 0.10f * b2[i]) * exposure * vig;
            v = powf(aces(v), 1 / 2.2f) * 255.0f + (hash2(x + 13.7f * c, y) - 0.5f) * 1.1f;
            out[(size_t)(H - 1 - y) * row + x * 3 + (2 - c)] = (uint8_t)clampf(v + 0.5f, 0, 255);
        }
    }
    FILE *fo = fopen(argv[6], "wb");
    uint32_t size = 54 + (uint32_t)row * H; uint8_t hd[54] = {'B', 'M'};
    memcpy(hd + 2, &size, 4); hd[10] = 54; hd[14] = 40; memcpy(hd + 18, &W, 4); memcpy(hd + 22, &H, 4); hd[26] = 1; hd[28] = 24;
    fwrite(hd, 1, 54, fo); fwrite(out, 1, (size_t)row * H, fo); fclose(fo);

    /* Where CarPlay's icon lies in the picture, for the app: it turns the icon back after mirroring the picture. */
    const float cs[4][2] = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};
    printf("icon quad (x, y of top-left, top-right, bottom-right, bottom-left, as shares of width and height):\n");
    for (int i = 0; i < 4; i++) {
        v3 d = sub(world(&FH, V(HU_ICON_X + cs[i][0] * HU_ICON_HALF, cs[i][1] * HU_ICON_HALF, 0)), EYE);
        float zc = dot(d, FWD), nx = dot(d, RIGHT) / (zc * TANH), ny = dot(d, UP) * ASPECT / (zc * TANH);
        printf("  %.5ff, %.5ff,\n", nx * 0.5f + 0.5f, 0.5f - ny * 0.5f);
    }
    return 0;
}
