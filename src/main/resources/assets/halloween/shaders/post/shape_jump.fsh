#version 330

// Shape Jump as seen by the Shape: a cold blue-white world with chromatic fringes,
// warm light sources stay orange so he can see where not to go.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 c = texCoord - 0.5;
    float d = length(c);
    vec2 off = c * 0.014 * (0.25 + d * 2.0);
    vec3 col = vec3(
        texture(InSampler, texCoord + off).r,
        texture(InSampler, texCoord).g,
        texture(InSampler, texCoord - off).b
    );
    float luma = dot(col, vec3(0.3, 0.59, 0.11));
    vec3 cold = pow(vec3(luma * 0.82, luma * 0.94, luma * 1.18), vec3(0.75)) * 1.25;
    float warm = clamp((col.r - col.b) * 2.5, 0.0, 1.0) * smoothstep(0.3, 0.75, luma);
    vec3 outColor = mix(cold, col * vec3(1.3, 0.75, 0.4) * 1.4, warm);
    float vignette = smoothstep(0.85, 0.3, d);
    fragColor = vec4(outColor * mix(0.35, 1.0, vignette), 1.0);
}
