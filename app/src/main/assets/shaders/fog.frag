#version 300 es
precision mediump float;

// Fog/smoke is its own generative layer (not a post-effect sampling
// another layer's texture): a drifting fractal-noise cloud, confined to a
// zone, composited via the layer's own blend mode like any other layer.
//
// uOffset - where the noise pattern is sampled from - is computed on the
// CPU side by FogDriftAnimator: its own ambient heading/speed cycle at all
// times, plus a smoothed (multi-second, never per-beat) bass/mid/treble
// nudge. uIntensity is a separate audio-influenced input carrying the
// ambient-wander + rare-climax-boost blend from LayerAnimator, so by the
// time either reaches this shader it's already a slow-moving value.

in vec2 vTexCoord;
out vec4 fragColor;

uniform float uDensity;    // Effect.Fog.density - manual overall opacity
uniform float uScale;      // Effect.Fog.noiseScale - manual spatial size of the cloud pattern
// EffectParams.color, 1-3 gradient stops - plain named uniforms (no arrays,
// no loops) rather than uColorStops[3]/uStopPositions[3] indexed in a loop,
// which reproducibly crashed the SwiftShader software renderer.
uniform vec4 uColor0;
uniform vec4 uColor1;
uniform vec4 uColor2;
uniform float uStopPos0;
uniform float uStopPos1;
uniform float uStopPos2;
uniform int uStopCount;
uniform vec2 uOffset;      // this layer's fbm noise-space drift offset, from FogDriftAnimator
uniform float uIntensity;  // 0..1 ambient-wander + rare-climax value from LayerAnimator
uniform float uOpacity;    // EffectParams.opacity - manual overall opacity multiplier

uniform int uZoneType;     // 0 = full screen, 1 = rect, 2 = circle
uniform vec4 uZoneRect;    // x, y, width, height - normalized, y=0 at the bottom
uniform vec3 uZoneCircle;  // centerX, centerY, radius - normalized, y=0 at the bottom

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

vec4 sampleGradient(float t) {
    if (uStopCount <= 1) return uColor0;
    if (uStopCount == 2 || t <= uStopPos1) {
        float localT = clamp((t - uStopPos0) / max(uStopPos1 - uStopPos0, 0.0001), 0.0, 1.0);
        return mix(uColor0, uColor1, localT);
    }
    float localT = clamp((t - uStopPos1) / max(uStopPos2 - uStopPos1, 0.0001), 0.0, 1.0);
    return mix(uColor1, uColor2, localT);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i++) {
        value += amplitude * noise(p);
        p *= 2.0;
        amplitude *= 0.5;
    }
    return value;
}

float zoneMask(vec2 uv) {
    if (uZoneType == 1) {
        vec2 lo = uZoneRect.xy;
        vec2 hi = uZoneRect.xy + uZoneRect.zw;
        vec2 edge = min(smoothstep(lo, lo + 0.05, uv), smoothstep(hi, hi - 0.05, uv));
        return edge.x * edge.y;
    } else if (uZoneType == 2) {
        float d = distance(uv, uZoneCircle.xy);
        return 1.0 - smoothstep(uZoneCircle.z * 0.7, uZoneCircle.z, d);
    }
    return 1.0;
}

void main() {
    vec2 zoneUv = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
    vec2 driftedUv = vTexCoord * (4.0 * max(uScale, 0.05)) + uOffset;
    float n = fbm(driftedUv);

    float fogAmount = smoothstep(0.35, 0.85, n) * uDensity * (0.4 + 0.6 * uIntensity);
    fogAmount *= zoneMask(zoneUv);

    vec4 color = sampleGradient(n);
    fragColor = vec4(color.rgb, fogAmount * color.a * uOpacity);
}
