#version 300 es
precision mediump float;

// Unlike fog/glow (generative, no texture input), chromatic aberration is a
// true post-process: it samples whatever the layer stack has composited so
// far (LayerCompositor renders into a ping-pong FBO and hands this shader
// the accumulated texture) and offsets the red/blue channels outward from
// center. Layers listed after this one in the stack are drawn on top of the
// result, unaffected by the distortion - same back-to-front semantics as
// every other layer type.

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform float uStrength; // Effect.ChromaticAberration.strength * this layer's LayerAnimator intensity
uniform float uOpacity;  // EffectParams.opacity - blends distorted vs original, 1 = fully distorted

uniform int uZoneType;     // 0 = full screen, 1 = rect, 2 = circle
uniform vec4 uZoneRect;    // x, y, width, height - normalized, y=0 at the bottom
uniform vec3 uZoneCircle;  // centerX, centerY, radius - normalized, y=0 at the bottom

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
    vec2 direction = vTexCoord - vec2(0.5);
    vec4 original = texture(uTexture, vTexCoord);

    float r = texture(uTexture, vTexCoord - direction * uStrength).r;
    float g = original.g;
    float b = texture(uTexture, vTexCoord + direction * uStrength).b;

    float mixAmount = clamp(uOpacity, 0.0, 1.0) * zoneMask(zoneUv);
    fragColor = vec4(mix(original.rgb, vec3(r, g, b), mixAmount), original.a);
}
