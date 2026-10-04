#version 330

// Killer Sense: looking through the mask. Washed-out cold colours and a heavy oval vignette.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    float luma = dot(col, vec3(0.3, 0.59, 0.11));
    vec3 washed = mix(col, vec3(luma), 0.7) * vec3(0.82, 1.0, 1.06);
    vec2 c = (texCoord - 0.5) * vec2(1.0, 1.3);
    float d = length(c);
    float vignette = smoothstep(0.62, 0.22, d);
    fragColor = vec4(washed * mix(0.1, 1.0, vignette), 1.0);
}
