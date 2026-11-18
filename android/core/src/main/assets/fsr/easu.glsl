// FidelityFX FSR 1 EASU, derived from GPUOpen-Effects/FidelityFX-FSR/ffx-fsr/ffx_fsr1.h.
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved. MIT: fsr/LICENSE.
// AnixQ GLES port: 12 ordinary texture taps replace gather; full-precision reciprocal/sqrt
// replace platform bit approximations. No temporal synthesis or invented neural model.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uInSize;
uniform float uSplit;
varying vec2 vUv;
float luma(vec3 c) { return c.g + 0.5 * (c.r + c.b); }
vec3 tap(vec2 p) { return texture2D(uTex, clamp((p + 0.5) / uInSize, 0.5 / uInSize, 1.0 - 0.5 / uInSize)).rgb; }
void edge(inout vec2 dir, inout float len, float w, float a, float b, float c, float d, float e) {
    float dx = d - b;
    float dy = e - a;
    dir += vec2(dx, dy) * w;
    float lx = clamp(abs(dx) / max(max(abs(d - c), abs(c - b)), 0.00001), 0.0, 1.0);
    float ly = clamp(abs(dy) / max(max(abs(e - c), abs(c - a)), 0.00001), 0.0, 1.0);
    len += (lx * lx + ly * ly) * w;
}
void accum(inout vec3 color, inout float weight, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v = vec2(dot(off, dir), dot(off, vec2(-dir.y, dir.x))) * len;
    float d2 = min(dot(v, v), clp);
    float wb = 0.4 * d2 - 1.0;
    float wa = lob * d2 - 1.0;
    wb = 1.5625 * wb * wb - 0.5625;
    float w = wb * wa * wa;
    color += c * w;
    weight += w;
}
void main() {
    if (vUv.x < uSplit) { gl_FragColor = texture2D(uTex, vUv); return; }
    vec2 p = vUv * uInSize - 0.5;
    vec2 base = floor(p);
    vec2 f = p - base;
    vec3 b = tap(base + vec2(0.0, -1.0));
    vec3 c = tap(base + vec2(1.0, -1.0));
    vec3 e = tap(base + vec2(-1.0, 0.0));
    vec3 a = tap(base);
    vec3 g = tap(base + vec2(1.0, 0.0));
    vec3 h = tap(base + vec2(2.0, 0.0));
    vec3 i = tap(base + vec2(-1.0, 1.0));
    vec3 j = tap(base + vec2(0.0, 1.0));
    vec3 k = tap(base + vec2(1.0, 1.0));
    vec3 l = tap(base + vec2(2.0, 1.0));
    vec3 n = tap(base + vec2(0.0, 2.0));
    vec3 o = tap(base + vec2(1.0, 2.0));
    vec2 dir = vec2(0.0);
    float len = 0.0;
    edge(dir, len, (1.0-f.x)*(1.0-f.y), luma(b), luma(e), luma(a), luma(g), luma(j));
    edge(dir, len, f.x*(1.0-f.y), luma(c), luma(a), luma(g), luma(h), luma(k));
    edge(dir, len, (1.0-f.x)*f.y, luma(a), luma(i), luma(j), luma(k), luma(n));
    edge(dir, len, f.x*f.y, luma(g), luma(j), luma(k), luma(l), luma(o));
    float d2 = dot(dir, dir);
    dir = d2 < (1.0/32768.0) ? vec2(1.0, 0.0) : dir * inversesqrt(d2);
    len = 0.5 * len;
    len *= len;
    float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch-1.0)*len, 1.0-0.5*len);
    float lob = 0.5 + (0.21-0.5)*len;
    float clp = 1.0 / lob;
    vec3 color = vec3(0.0);
    float weight = 0.0;
    accum(color, weight, vec2(0.0,-1.0)-f,dir,len2,lob,clp,b);
    accum(color, weight, vec2(1.0,-1.0)-f,dir,len2,lob,clp,c);
    accum(color, weight, vec2(-1.0,0.0)-f,dir,len2,lob,clp,e);
    accum(color, weight, vec2(0.0,0.0)-f,dir,len2,lob,clp,a);
    accum(color, weight, vec2(1.0,0.0)-f,dir,len2,lob,clp,g);
    accum(color, weight, vec2(2.0,0.0)-f,dir,len2,lob,clp,h);
    accum(color, weight, vec2(-1.0,1.0)-f,dir,len2,lob,clp,i);
    accum(color, weight, vec2(0.0,1.0)-f,dir,len2,lob,clp,j);
    accum(color, weight, vec2(1.0,1.0)-f,dir,len2,lob,clp,k);
    accum(color, weight, vec2(2.0,1.0)-f,dir,len2,lob,clp,l);
    accum(color, weight, vec2(0.0,2.0)-f,dir,len2,lob,clp,n);
    accum(color, weight, vec2(1.0,2.0)-f,dir,len2,lob,clp,o);
    vec3 lo = min(min(a,g),min(j,k));
    vec3 hi = max(max(a,g),max(j,k));
    gl_FragColor = vec4(clamp(color / max(weight,0.00001), lo, hi), 1.0);
}
