#version 330

// Focusing inside Killer Sense: almost everything goes dark except a small window around the reticle.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    float luma = dot(col, vec3(0.3, 0.59, 0.11));
    vec3 washed = mix(col, vec3(luma), 0.85) * vec3(0.85, 1.0, 1.05);
    vec2 c = (texCoord - 0.5) * vec2(1.0, 1.4);
    float d = length(c);
    float vignette = smoothstep(0.32, 0.06, d);
    fragColor = vec4(washed * mix(0.02, 1.0, vignette), 1.0);
}
