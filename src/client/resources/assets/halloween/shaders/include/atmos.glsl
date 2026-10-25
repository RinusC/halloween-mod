#version 330

// Halloween's match fog (see ClientWeather), shared by the game's own shaders (minecraft:fog.glsl) and Sodium's
// terrain (halloween:sodium_fog.glsl, put in place of sodium:fog.glsl). Needs minecraft:globals.glsl first.
//
// It is on while the fog colour's alpha is below 1, and that alpha is how thick the fog gets (about 0.9: lamps and
// silhouettes still show through). With an alpha of 1 (water, lava, blindness, menus) everything is vanilla's.
//
// The fog is exp2 over an "optical" distance worked out per vertex: the distance, times how thick the air is along
// the way (thicker near the ground, thinner up high, averaged exactly) and a slow drifting patchiness (2D value noise
// over the world's XZ, periodic every 128 blocks, its wind going round once a game day so it never jumps). The
// dense part reaches its limit at about 60% of the fog's end; from 70% to the end (by plain distance) what is left
// fades out, so nothing shows past the end and Sodium's fog culling never pops a thing out of view.

const float HW_EYE = 1.62;      // blocks: the camera over the ground it stands on
const float HW_HAZE = 0.4;      // how thick the air is high up, against at the ground
const float HW_LAYER = 5.0;     // blocks: how quickly the ground fog thins going up
const float HW_DENSE = 7.8;     // exp2's steepness: 95% of the limit at 62% of the end

bool hw_fog_on(vec4 fogColor) {
    return fogColor.a > 0.5 && fogColor.a < 0.9995;
}

float hw_hash(vec2 cell) {
    return fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453);
}

// Smooth value noise, periodic every `period` cells.
float hw_noise(vec2 p, float period) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    vec2 a = mod(i, period);
    vec2 b = mod(i + 1.0, period);
    return mix(mix(hw_hash(a), hw_hash(vec2(b.x, a.y)), f.x), mix(hw_hash(vec2(a.x, b.y)), hw_hash(b), f.x), f.y);
}

// From a camera-relative position (world axes) to the optical distance the fog goes by.
float hw_optical(vec3 pos) {
    float dist = length(pos);
    // The air's thickness along the way, for density = haze + (1 - haze) * e^(-height / layer), against the
    // thickness where the camera stands (so a level look down the street is plain distance).
    float e0 = exp(-HW_EYE / HW_LAYER);
    float h1 = max(HW_EYE + pos.y, 0.0);
    float dh = h1 - HW_EYE;
    float ground = abs(dh) < 0.01 ? e0 : HW_LAYER * (e0 - exp(-h1 / HW_LAYER)) / dh;
    float air = (HW_HAZE + (1.0 - HW_HAZE) * ground) / (HW_HAZE + (1.0 - HW_HAZE) * e0);
    // Drifting patches, two layers moving apart; none right before the eyes, where they would only flicker.
    vec2 world = pos.xz + vec2(CameraBlockPos.xz) - CameraOffset.xz;
    float turn = GameTime * 6.2831853;
    vec2 wind = vec2(cos(turn), sin(turn)) * 64.0;
    float n = hw_noise((world + wind) / 16.0, 8.0) * 0.65 + hw_noise((world - wind * 1.5) / 8.0, 16.0) * 0.35;
    float patches = mix(1.0, 0.7 + 0.6 * n, smoothstep(4.0, 20.0, dist));
    return dist * air * patches;
}

// How bright a colour is, as the fog sees it: 0 for the night's dark, 1 for a lamp.
float hw_glow(vec3 color) {
    return smoothstep(0.2, 0.8, dot(color, vec3(0.3, 0.59, 0.11)));
}

// How much fog (0..1): `optical` from hw_optical, `dist` the plain distance; `limit` is the fog colour's alpha.
// `glow` (hw_glow) keeps lit windows and lamps showing through the dense part from further off.
float hw_fog_value(float optical, float dist, float start, float end, float renderStart, float renderEnd, float limit, float glow) {
    float span = max(end - start, 0.001);
    float x = max(optical - start, 0.0) / span;
    float dense = limit * (1.0 - exp(-HW_DENSE * x * x)) * (1.0 - 0.6 * glow);
    float fog = mix(dense, 1.0, smoothstep(0.7, 1.0, (dist - start) / span));
    float edge = renderEnd > renderStart ? clamp((dist - renderStart) / (renderEnd - renderStart), 0.0, 1.0) : 0.0;
    return max(fog, edge);
}

// The sky (a disc 16 blocks over the camera): the horizon takes the fog's colour, the zenith only in thick fog.
float hw_sky_value(float dist, float end) {
    float x = dist / max(end, 0.001);
    float rise = 16.0 / max(dist, 16.0);
    float horizon = 1.0 - smoothstep(0.0, 0.35, rise);
    return max(horizon, 1.0 - exp(-HW_DENSE * 0.5 * x * x));
}
