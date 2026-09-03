#version 300 es
// Renders each particle as a GL_POINTS sprite.
// aPosition: particle position in normalized device coordinates (-1..1).
// aLife: remaining life, 1.0 = just spawned, 0.0 = dead.
layout(location = 0) in vec2 aPosition;
layout(location = 1) in float aLife;

uniform mat4 uMVP;
uniform float uPointSize; // base size in pixels, from Effect.Particles.size

out float vLife;

void main() {
    vLife = aLife;
    gl_Position = uMVP * vec4(aPosition, 0.0, 1.0);
    gl_PointSize = uPointSize * aLife;
}
