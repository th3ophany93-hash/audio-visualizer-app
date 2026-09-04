#version 300 es
precision mediump float;

in float vLife;
in vec4 vColor;
out vec4 fragColor;

void main() {
    // Round point sprite: discard corners of the point quad, soft-fade the edge.
    vec2 coord = gl_PointCoord - vec2(0.5);
    float dist = length(coord);
    if (dist > 0.5) {
        discard;
    }
    float edgeFade = 1.0 - smoothstep(0.35, 0.5, dist);
    fragColor = vec4(vColor.rgb, vColor.a * vLife * edgeFade);
}
