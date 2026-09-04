#version 300 es
// Renders each particle as a GL_POINTS sprite.
// aPosition: particle position in normalized device coordinates (-1..1).
// aLife: remaining life, 1.0 = just spawned, 0.0 = dead.
layout(location = 0) in vec2 aPosition;
layout(location = 1) in float aLife;

uniform mat4 uMVP;
uniform float uPointSize; // base size in pixels, from Effect.Particles.size
// EffectParams.color, 1-3 gradient stops - sampled by (1 - life): spawn
// color to death color. Plain named uniforms (no arrays, no loops) rather
// than uColorStops[3]/uStopPositions[3] indexed in a loop, which
// reproducibly crashed the SwiftShader software renderer in fog.frag.
uniform vec4 uColor0;
uniform vec4 uColor1;
uniform vec4 uColor2;
uniform float uStopPos0;
uniform float uStopPos1;
uniform float uStopPos2;
uniform int uStopCount;
uniform float uOpacity; // EffectParams.opacity - manual overall opacity multiplier

out float vLife;
out vec4 vColor;

vec4 sampleGradient(float t) {
    if (uStopCount <= 1) return uColor0;
    if (uStopCount == 2 || t <= uStopPos1) {
        float localT = clamp((t - uStopPos0) / max(uStopPos1 - uStopPos0, 0.0001), 0.0, 1.0);
        return mix(uColor0, uColor1, localT);
    }
    float localT = clamp((t - uStopPos1) / max(uStopPos2 - uStopPos1, 0.0001), 0.0, 1.0);
    return mix(uColor1, uColor2, localT);
}

void main() {
    vLife = aLife;
    vColor = sampleGradient(1.0 - aLife);
    vColor.a *= uOpacity;
    gl_Position = uMVP * vec4(aPosition, 0.0, 1.0);
    gl_PointSize = uPointSize * aLife;
}
