#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform float uDensity;  // Effect.Fog.density
uniform vec4 uColor;     // Effect.Fog.color, rgb + alpha strength
uniform float uTime;     // seconds, for drifting motion

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

void main() {
    vec4 baseColor = texture(uTexture, vTexCoord);
    float drift = uTime * 0.05;
    float n = noise(vTexCoord * 6.0 + drift) * 0.6 + noise(vTexCoord * 12.0 - drift) * 0.4;
    float fogAmount = smoothstep(0.3, 0.9, n) * uDensity;
    vec3 blended = mix(baseColor.rgb, uColor.rgb, fogAmount * uColor.a);
    fragColor = vec4(blended, baseColor.a);
}
