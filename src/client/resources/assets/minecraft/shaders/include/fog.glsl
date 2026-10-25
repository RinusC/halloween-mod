#version 330

// Vanilla's fog, plus Halloween's match fog (halloween:atmos.glsl) while the fog colour's alpha is below 1. Then
// the cylindrical distance carries the optical distance the fog goes by (the render distance's fog uses the plain
// spherical one), and the sky gets its own curve.

#moj_import <minecraft:globals.glsl>

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

#moj_import <halloween:atmos.glsl>

float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }

    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

float hw_total_fog(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd, float glow) {
    // The sky passes its end as all three ends (see core/sky.fsh).
    if (renderDistanceStart == renderDistanceEnd) {
        return hw_sky_value(sphericalVertexDistance, environmantalEnd);
    }
    return hw_fog_value(cylindricalVertexDistance, sphericalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd, FogColor.a, glow);
}

float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {
    if (hw_fog_on(FogColor)) {
        return hw_total_fog(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd, 0.0);
    }
    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
}

vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
    if (hw_fog_on(FogColor)) {
        float fog = hw_total_fog(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd, hw_glow(inColor.rgb));
        return vec4(mix(inColor.rgb, fogColor.rgb, fog), inColor.a);
    }
    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd);
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

float fog_spherical_distance(vec3 pos) {
    return length(pos);
}

float fog_cylindrical_distance(vec3 pos) {
    if (hw_fog_on(FogColor)) {
        return hw_optical(pos);
    }
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}
