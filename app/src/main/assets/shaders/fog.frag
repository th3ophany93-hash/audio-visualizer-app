#version 300 es
precision mediump float;

// Fog/smoke is its own generative layer (not a post-effect sampling
// another layer's texture): a drifting fractal-noise cloud, confined to a
// zone, composited via the layer's own blend mode like any other layer.
//
// The drift speed and pattern are driven only by uTime - never by audio -
// per the "no beat pulsing" rule. uIntensity is the only audio-influenced
// input, and it already carries the ambient-wander + rare-climax-boost
// blend computed on the CPU side (see LayerAnimator), so by the time it
// reaches this shader it's a slow-moving value, not a per-beat one.

in vec2 vTexCoord;
out vec4 fragColor;

uniform float uDensity;    // Effect.Fog.density - manual overall opacity
uniform float uScale;      // Effect.Fog.scale - manual spatial size of the cloud pattern
uniform vec4 uColor;       // Effect.Fog.color, rgb + alpha strength
uniform float uTime;       // seconds, this layer's own clock - drives drift only
uniform float uIntensity;  // 0..1 ambient-wander + rare-climax value from LayerAnimator

uniform int uZoneType;     // 0 = full screen, 1 = rect, 2 = circle
uniform vec4 uZoneRect;    // x, y, width, height - normalized, y=0 at the bottom
uniform vec3 uZoneCircle;  // centerX, centerY, radius - normalized, y=0 at the bottom

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
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
    vec2 driftedUv = vTexCoord * (4.0 * max(uScale, 0.05)) + vec2(uTime * 0.015, uTime * 0.008);
    float n = fbm(driftedUv);

    float fogAmount = smoothstep(0.35, 0.85, n) * uDensity * (0.4 + 0.6 * uIntensity);
    fogAmount *= zoneMask(zoneUv);

    fragColor = vec4(uColor.rgb, fogAmount * uColor.a);
}
