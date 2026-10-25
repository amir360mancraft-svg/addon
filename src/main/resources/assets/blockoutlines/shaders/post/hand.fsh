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

    int look = int(Params.y + 0.5);
    float edge = 0.0;
    if (hand) {
        for (int i = 0; i < 4; i++) {
            float a = 6.2831853 * float(i) / 4.0;
            if (!isHand(texCoord + vec2(cos(a), sin(a)) * px * 3.0)) edge = 1.0;
        }
    }
    float t = phase * 6.2831853;
    float n = sin(texCoord.x * 18.0 + t) * sin(texCoord.y * 14.0 - t * 2.0) * 0.5 + 0.5;

    if (look == 0) {
        // moving rainbow, keeps the shading of the hand
        vec3 g = hsv(phase + texCoord.x * 0.6 - texCoord.y * 0.35, 0.75, 1.0);
        if (hand) col = mix(col, g * (0.35 + luma * 1.25), 0.82);
        else col += hsv(phase + texCoord.x * 0.6 - texCoord.y * 0.35, 0.8, 1.0) * near * 0.35;
    } else if (look == 1) {
        // blue hologram: scan lines, a rim that glows, a little flicker
        float scan = 0.72 + 0.28 * sin(texCoord.y * size.y * 0.45 + t * 6.0);
        float flick = 0.94 + 0.06 * sin(t * 20.0);
        vec3 tint = vec3(0.25, 0.85, 1.0);
        if (hand) col = tint * (0.2 + luma * 1.1) * scan * flick + vec3(0.5, 0.95, 1.0) * edge * 0.9;
        else col += tint * near * 0.45;
    } else if (look == 2) {
        // fire: hot orange/yellow that flickers upward
        float f = clamp(n * 0.6 + luma * 0.7 + 0.15 * sin(texCoord.y * 30.0 + t * 3.0), 0.0, 1.0);
        vec3 fire = mix(vec3(0.7, 0.08, 0.0), mix(vec3(1.0, 0.5, 0.0), vec3(1.0, 0.95, 0.5), f), f);
        if (hand) col = mix(col, fire * (0.6 + luma), 0.88) + vec3(1.0, 0.7, 0.2) * edge * 0.3;
        else col += vec3(1.0, 0.4, 0.05) * near * (0.4 + 0.25 * n);
    } else if (look == 3) {
        // frost: pale ice blue with sparkles
        float sp = pow(max(0.0, sin(texCoord.x * 90.0 + t) * sin(texCoord.y * 80.0 - t * 1.5)), 12.0);
        vec3 ice = mix(vec3(0.55, 0.8, 1.0), vec3(0.95, 1.0, 1.0), luma);
        if (hand) col = mix(col, ice * (0.5 + luma * 0.8), 0.85) + vec3(sp) * 0.9 + vec3(0.7, 0.95, 1.0) * edge * 0.5;
        else col += vec3(0.5, 0.85, 1.0) * near * 0.35;
    } else if (look == 4) {
        // gold: shiny metal with a sweeping highlight
        float sweep = pow(max(0.0, sin((texCoord.x + texCoord.y * 0.6) * 7.0 - t)), 8.0);
        vec3 gold = mix(vec3(0.55, 0.35, 0.05), vec3(1.0, 0.82, 0.3), luma);
        if (hand) col = mix(col, gold * (0.55 + luma * 0.9), 0.88) + vec3(1.0, 0.95, 0.7) * sweep * 0.55;
        else col += vec3(1.0, 0.8, 0.3) * near * 0.25;
    } else if (look == 6) {
        // crystal glint: see-through blue glass, faceted shading, glowing rim and swirling white glint lines
        vec2 q = texCoord * vec2(size.x / size.y, 1.0);
        float facet = floor(luma * 5.0) / 5.0;
        float w1 = sin(q.x * 14.0 + sin(q.y * 9.0 + t) * 1.8 + q.y * 5.0 - t * 1.5);
        float w2 = sin(q.x * 9.0 - q.y * 11.0 + sin(q.x * 6.0 - t) * 2.0 + t);
        float line = pow(max(0.0, 1.0 - abs(w1) * 3.2), 2.0) * 0.8 + pow(max(0.0, 1.0 - abs(w2) * 4.0), 2.0) * 0.55;
        float sp = pow(max(0.0, sin(q.x * 70.0 + t * 2.0) * sin(q.y * 64.0 - t)), 14.0);
        vec3 glass = mix(vec3(0.08, 0.28, 0.85), vec3(0.45, 0.8, 1.0), facet);
        if (hand) {
            col = mix(col * 0.55, glass, 0.62) + vec3(0.75, 0.92, 1.0) * line * 0.75 + vec3(sp) * 0.7 + vec3(0.6, 0.9, 1.0) * edge * 0.85;
        } else {
            col += vec3(0.25, 0.6, 1.0) * near * 0.55;
        }
    } else {
        // neon outline: dark hand with a bright pulsing colored edge
        float pulse = 0.65 + 0.35 * sin(t * 2.0);
        vec3 neon = hsv(phase, 0.9, 1.0);
        if (hand) col = col * 0.25 + neon * edge * 1.4 * pulse;
        else col += neon * near * 0.7 * pulse;
    }

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
