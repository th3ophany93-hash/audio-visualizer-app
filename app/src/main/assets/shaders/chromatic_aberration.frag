#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform float uStrength; // Effect.ChromaticAberration.strength

void main() {
    vec2 direction = vTexCoord - vec2(0.5);
    float r = texture(uTexture, vTexCoord - direction * uStrength).r;
    float g = texture(uTexture, vTexCoord).g;
    float b = texture(uTexture, vTexCoord + direction * uStrength).b;
    float a = texture(uTexture, vTexCoord).a;
    fragColor = vec4(r, g, b, a);
}
