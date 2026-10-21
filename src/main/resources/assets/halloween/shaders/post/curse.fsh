#version 330

// Vecna's curse at its last stage: the world swims. Blurred (worse towards the edges), doubled a little to one side,
// drained and reddened, dark at the rim.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 px = 1.0 / vec2(textureSize(InSampler, 0));
    vec2 c = texCoord - 0.5;
    float edge = clamp(length(c * vec2(1.0, 1.3)) * 1.6, 0.0, 1.0);
    float radius = mix(1.5, 4.5, edge);
    vec3 sum = texture(InSampler, texCoord).rgb;
    float n = 1.0;
    for (int i = 0; i < 12; i++) {
        float a = float(i) * 0.5236;
        float r = radius * (i % 2 == 0 ? 1.0 : 0.55);
        sum += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * r * px).rgb;
        n += 1.0;
    }
    vec3 col = sum / n;
    // Seen twice: a ghost of it a little to the right.
    vec3 ghost = texture(InSampler, texCoord + vec2(7.0, 1.5) * px).rgb;
    col = mix(col, ghost, 0.28);
    float luma = dot(col, vec3(0.3, 0.59, 0.11));
    col = mix(col, vec3(luma), 0.45) * vec3(1.12, 0.82, 0.8);
    float vignette = smoothstep(0.75, 0.25, length(c * vec2(1.0, 1.25)));
    fragColor = vec4(col * mix(0.25, 1.0, vignette), 1.0);
}
