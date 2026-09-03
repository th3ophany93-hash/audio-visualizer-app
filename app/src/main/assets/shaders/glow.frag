#version 300 es
precision mediump float;

// Glow is its own generative layer: a soft radial gaussian-falloff blob
// that drifts slowly within its zone, additively blended (typically) with
// whatever is beneath it. It does not sample another layer's texture -
// see fog.frag for why, and for the same "no beat pulsing" rule: uTime
// alone drives the drift, uIntensity carries an already-slow ambient +
// rare-climax value from LayerAnimator.

in vec2 vTexCoord;
out vec4 fragColor;

uniform float uBrightness; // Effect.Glow.intensity - manual base brightness
uniform float uRadius;     // Effect.Glow.radius - manual blob softness/size
uniform float uScale;      // Effect.Glow.scale - manual overall size multiplier
uniform vec4 uColor;       // Effect.Glow.color
uniform float uTime;       // seconds, this layer's own clock - drives drift only
uniform float uIntensity;  // 0..1 ambient-wander + rare-climax value from LayerAnimator

uniform int uZoneType;     // 0 = full screen, 1 = rect, 2 = circle
uniform vec4 uZoneRect;    // x, y, width, height - normalized, y=0 at the bottom
uniform vec3 uZoneCircle;  // centerX, centerY, radius - normalized, y=0 at the bottom

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(41.3, 289.1))) * 43758.5453123);
}

vec2 zoneCenter() {
    if (uZoneType == 1) return uZoneRect.xy + uZoneRect.zw * 0.5;
    if (uZoneType == 2) return uZoneCircle.xy;
    return vec2(0.5, 0.5);
}

float zoneExtent() {
    if (uZoneType == 1) return max(uZoneRect.z, uZoneRect.w) * 0.5;
    if (uZoneType == 2) return uZoneCircle.z;
    return 0.6;
}

void main() {
    vec2 zoneUv = vec2(vTexCoord.x, 1.0 - vTexCoord.y);

    vec2 center = zoneCenter();
    float extent = zoneExtent();
    // The blob's own center wanders slowly and irregularly within its
    // zone - a long, non-repeating drift, never a snap to the beat.
    float driftAngle = uTime * 0.05 + hash(center) * 6.28318;
    vec2 drift = vec2(cos(driftAngle), sin(driftAngle)) * extent * 0.2;

    float dist = distance(zoneUv, center + drift);
    float radius = max(uRadius * 0.02 * max(uScale, 0.05), 0.02);
    float glow = exp(-(dist * dist) / (2.0 * radius * radius));

    float brightness = uBrightness * (0.35 + 0.65 * uIntensity);
    fragColor = vec4(uColor.rgb, glow * uColor.a * brightness);
}
