// Snapdragon Game Super Resolution 1 (SGSR), mobile path, derived from
// SnapdragonStudios/snapdragon-gsr sgsr1_shader_mobile.frag.
// Copyright (c) 2023-2025, Qualcomm Innovation Center, Inc. All rights reserved. BSD-3-Clause: sgsr/LICENSE.
// AnixQ GLES 2 port: textureGather is replaced by single texel taps; flat areas (most of an anime
// frame) take 3 taps, only edge pixels take the full 12-tap Lanczos-like reconstruction. One pass.
precision mediump float;
uniform sampler2D uTex;
uniform highp vec2 uInSize;
uniform float uSplit;
varying highp vec2 vUv;

const float EDGE_THRESHOLD = 8.0 / 255.0;
const float EDGE_SHARPNESS = 2.0;

highp vec2 base;
float g(float x, float y) { return texture2D(uTex, (base + vec2(x, y) + 0.5) / uInSize).g; }
float fastLanczos2(float x) {
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}
vec2 weightY(float dx, float dy, float c, float std) {
    float x = (dx * dx + dy * dy) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);
    float w = fastLanczos2(x);
    return vec2(w, w * c);
}
void main() {
    vec3 color = texture2D(uTex, vUv).rgb;
    if (vUv.x < uSplit) { gl_FragColor = vec4(color, 1.0); return; }   // comparison: plain stretch
    highp vec2 p = vUv * uInSize - 0.5;
    base = floor(p);
    vec2 pl = p - base;
    float a = g(0.0, 0.0), c = g(0.0, 1.0);
    if (abs(a - c) + abs(color.g - c) + abs(color.g - a) > EDGE_THRESHOLD) {
        vec4 left = vec4(g(-1.0, 1.0), c, a, g(-1.0, 0.0));
        vec4 right = vec4(g(1.0, 1.0), g(2.0, 1.0), g(2.0, 0.0), g(1.0, 0.0));
        vec4 upDown = vec4(g(0.0, -1.0), g(1.0, -1.0), g(1.0, 2.0), g(0.0, 2.0));
        float mean = (left.y + left.z + right.x + right.w) * 0.25;
        left -= mean; right -= mean; upDown -= mean;
        float centre = color.g - mean;
        vec4 s = abs(left) + abs(right) + abs(upDown);
        float std = 2.181818 / max(s.x + s.y + s.z + s.w, 1e-4);
        vec2 aWY = weightY(pl.x, pl.y + 1.0, upDown.x, std);
        aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, std);
        aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, std);
        aWY += weightY(pl.x, pl.y - 2.0, upDown.w, std);
        aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x, std);
        aWY += weightY(pl.x, pl.y - 1.0, left.y, std);
        aWY += weightY(pl.x, pl.y, left.z, std);
        aWY += weightY(pl.x + 1.0, pl.y, left.w, std);
        aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x, std);
        aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y, std);
        aWY += weightY(pl.x - 2.0, pl.y, right.z, std);
        aWY += weightY(pl.x - 1.0, pl.y, right.w, std);
        float finalY = aWY.y / aWY.x;
        float maxY = max(max(left.y, left.z), max(right.x, right.w));
        float minY = min(min(left.y, left.z), min(right.x, right.w));
        finalY = clamp(EDGE_SHARPNESS * finalY, minY, maxY);
        float deltaY = clamp(finalY - centre, -23.0 / 255.0, 23.0 / 255.0);   // smooth high-contrast input
        color = clamp(color + deltaY, 0.0, 1.0);
    }
    gl_FragColor = vec4(color, 1.0);
}
