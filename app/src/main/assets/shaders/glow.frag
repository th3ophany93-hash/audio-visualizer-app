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
uniform float uScale;      // EffectParams scale - manual overall size multiplier
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
uniform float uTime;       // seconds, this layer's own clock - drives drift only
uniform float uIntensity;  // 0..1 ambient-wander + rare-climax value from LayerAnimator
uniform float uDriftSpeed;      // EffectParams.movement.speed, 0 if movement disabled - default 1 = old fixed rate
uniform float uDriftAngleOffset; // EffectParams.movement.direction, radians - default 0 = old heading
uniform float uOpacity;    // EffectParams.opacity - manual overall opacity multiplier

uniform int uZoneType;     // 0 = full screen, 1 = rect, 2 = circle
uniform vec4 uZoneRect;    // x, y, width, height - normalized, y=0 at the bottom
uniform vec3 uZoneCircle;  // centerX, centerY, radius - normalized, y=0 at the bottom

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(41.3, 289.1))) * 43758.5453123);
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
    float driftAngle = uTime * 0.05 * uDriftSpeed + hash(center) * 6.28318 + uDriftAngleOffset;
    vec2 drift = vec2(cos(driftAngle), sin(driftAngle)) * extent * 0.2;

    float dist = distance(zoneUv, center + drift);
    float radius = max(uRadius * 0.02 * max(uScale, 0.05), 0.02);
    float glow = exp(-(dist * dist) / (2.0 * radius * radius));

    float brightness = uBrightness * (0.35 + 0.65 * uIntensity);
    vec4 color = sampleGradient(uIntensity);
    fragColor = vec4(color.rgb, glow * color.a * brightness * uOpacity);
}
