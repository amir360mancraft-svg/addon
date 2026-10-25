#version 330

// Shader look for the first-person hand and held item: only pixels that are very close to the camera are changed.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform HandConfig {
    vec4 Params; // x = animation phase 0..1, y = look (0 = gradient, 1 = hologram)
};

in vec2 texCoord;

out vec4 fragColor;

const float NEAR = 0.05;
const float FAR = 1024.0;

float lin(float d) {
    return 2.0 * NEAR * FAR / (FAR + NEAR - (2.0 * d - 1.0) * (FAR - NEAR));
}

// the hand is close; the world behind it is far (or the depth was cleared before the hand was drawn)
bool isHand(vec2 uv) {
    float d = texture(DepthSampler, uv).r;
    return d < 0.99999 && lin(d) < 1.6;
}

vec3 hsv(float h, float s, float v) {
    vec3 k = clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return v * mix(vec3(1.0), k, s);
}

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 px = 1.0 / size;
    vec3 col = texture(InSampler, texCoord).rgb;
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    float phase = Params.x;
    bool hand = isHand(texCoord);

    // how close the hand is to this pixel (for the glow that bleeds out of its edge)
    float near = 0.0;
    if (!hand) {
        for (int i = 0; i < 8; i++) {
            float a = 6.2831853 * float(i) / 8.0;
            vec2 o = vec2(cos(a), sin(a));
            if (isHand(texCoord + o * px * 5.0)) near += 0.125;
            if (isHand(texCoord + o * px * 12.0)) near += 0.0625;
        }
    }

    if (Params.y < 0.5) {
        // moving rainbow, keeps the shading of the hand
        vec3 g = hsv(phase + texCoord.x * 0.6 - texCoord.y * 0.35, 0.75, 1.0);
        if (hand) col = mix(col, g * (0.35 + luma * 1.25), 0.82);
        else col += hsv(phase + texCoord.x * 0.6 - texCoord.y * 0.35, 0.8, 1.0) * near * 0.35;
    } else {
        // blue hologram: scan lines, a rim that glows, a little flicker
        float scan = 0.72 + 0.28 * sin(texCoord.y * size.y * 0.45 + phase * 40.0);
        float flick = 0.94 + 0.06 * sin(phase * 125.0);
        vec3 tint = vec3(0.25, 0.85, 1.0);
        if (hand) {
            float edge = 0.0;
            for (int i = 0; i < 4; i++) {
                float a = 6.2831853 * float(i) / 4.0;
                if (!isHand(texCoord + vec2(cos(a), sin(a)) * px * 3.0)) edge = 1.0;
            }
            col = tint * (0.2 + luma * 1.1) * scan * flick + vec3(0.5, 0.95, 1.0) * edge * 0.9;
        } else {
            col += tint * near * 0.45;
        }
    }

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
