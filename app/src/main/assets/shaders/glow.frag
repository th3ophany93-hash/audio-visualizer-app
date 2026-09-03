#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform float uIntensity;  // Effect.Glow.intensity
uniform float uRadius;     // Effect.Glow.radius, in texels
uniform vec2 uTexelSize;   // 1.0 / texture resolution

void main() {
    vec4 baseColor = texture(uTexture, vTexCoord);
    vec4 sum = vec4(0.0);
    float totalWeight = 0.0;

    for (int x = -4; x <= 4; x++) {
        for (int y = -4; y <= 4; y++) {
            vec2 offset = vec2(float(x), float(y)) * uTexelSize * uRadius;
            float weight = 1.0 / (1.0 + float(x * x + y * y));
            sum += texture(uTexture, vTexCoord + offset) * weight;
            totalWeight += weight;
        }
    }

    vec4 blurred = sum / totalWeight;
    fragColor = baseColor + blurred * uIntensity;
}
