// Sodium's terrain fog (sodium:fog.glsl, which this replaces: see mixin.ShaderManagerMixin), plus Halloween's match
// fog (halloween:atmos.glsl) while the fog colour's alpha is below 1, the same as minecraft:fog.glsl gives
// everything else. Its terrain pipeline gets the game's Globals for it (mixin.compat.SodiumChunkShaderMixin).

#moj_import <minecraft:globals.glsl>
#moj_import <halloween:atmos.glsl>

const int FOG_SHAPE_SPHERICAL = 0;
const int FOG_SHAPE_CYLINDRICAL = 1;

float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }

    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {
    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
}

vec4 _linearFog(vec4 fragColor, vec2 fragDistance, vec4 fogColor, vec2 environmentFog, vec2 renderFog, float fadeFactor) {
#ifdef USE_FOG
    float fogValue;
    float alpha = fogColor.a;
    if (hw_fog_on(fogColor)) {
        fogValue = hw_fog_value(fragDistance.x, fragDistance.y, environmentFog.x, environmentFog.y, renderFog.x, renderFog.y, fogColor.a, hw_glow(fragColor.rgb));
        alpha = 1.0;
    } else {
        fogValue = total_fog_value(fragDistance.y, fragDistance.x, environmentFog.x, environmentFog.y, renderFog.x, renderFog.y);
    }
    fogValue = max(1.0 - fadeFactor, fogValue);
    return vec4(mix(fragColor.rgb, fogColor.rgb, fogValue * alpha), fragColor.a);
#else
    return fragColor;
#endif
}

// (cylindrical, spherical); with the match fog on, the first is the optical distance.
vec2 getFragDistance(vec3 position) {
    if (hw_fog_on(u_FogColor)) {
        return vec2(hw_optical(position), length(position));
    }
    return vec2(max(length(position.xz), abs(position.y)), length(position));
}
