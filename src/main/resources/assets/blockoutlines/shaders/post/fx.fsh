#version 330

// Bloom (a soft glow around bright things, used by Loot Beams) and a screen shock wave (used by Kill Effect).

uniform sampler2D InSampler;

layout(std140) uniform FxConfig {
    vec4 Params; // x = bloom strength (0 = off), y = shock progress 0..1 (0 = off), z = shock strength
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 px = 1.0 / size;
    float sc = size.y / 1080.0;
    vec2 uv = texCoord;

    // ---- shock wave: a ring of pushed pixels runs out from the middle, colors split and the picture is pulled in
    float shock = Params.y;
    float amp = 0.0;
    vec2 dir = uv - 0.5;
    dir.x *= size.x / size.y;
    float rad = length(dir);
    vec2 nd = rad > 0.0001 ? dir / rad : vec2(0.0);
    nd.x /= size.x / size.y;
    if (shock > 0.0) {
        float front = shock * 1.1;
        float band = exp(-pow((rad - front) / 0.07, 2.0));
        amp = Params.z * (1.0 - shock);
        uv -= nd * band * 0.035 * amp;          // push the picture at the ring
        uv -= (uv - 0.5) * 0.05 * amp * (1.0 - shock); // and pull it in a little
    }

    vec3 col;
    if (shock > 0.0) {
        float ca = (0.004 + 0.010 * rad) * amp;  // color split grows to the edges
        col = vec3(texture(InSampler, uv + nd * ca).r, texture(InSampler, uv).g, texture(InSampler, uv - nd * ca).b);
        // light zoom blur towards the middle
        vec3 acc = col;
        for (int i = 1; i <= 4; i++) {
            acc += texture(InSampler, uv - (uv - 0.5) * 0.012 * amp * float(i)).rgb;
        }
        col = mix(col, acc / 5.0, 0.6);
        col += vec3(0.85, 0.9, 1.0) * 0.28 * Params.z * pow(1.0 - shock, 4.0);   // white flash at the start
        col *= 1.0 - 0.35 * amp * smoothstep(0.35, 0.9, rad);                     // dark corners
    } else {
        col = texture(InSampler, uv).rgb;
    }

    // ---- bloom
    if (Params.x > 0.0) {
        vec3 glow = vec3(0.0);
        float wsum = 0.0;
        float jitter = fract(sin(dot(texCoord * size, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
        for (int ring = 0; ring < 4; ring++) {
            float r = (ring == 0 ? 8.0 : ring == 1 ? 20.0 : ring == 2 ? 40.0 : 70.0) * sc;
            float w = ring == 0 ? 0.45 : ring == 1 ? 0.35 : ring == 2 ? 0.28 : 0.2;
            for (int i = 0; i < 10; i++) {
                float a = 6.2831853 * float(i) / 10.0 + jitter + float(ring);
                vec3 s = texture(InSampler, uv + vec2(cos(a), sin(a)) * px * r).rgb;
                float m = max(max(s.r, s.g), s.b);
                glow += s * smoothstep(0.55, 0.95, m) * w;
                wsum += w;
            }
        }
        col += glow / wsum * 2.6 * Params.x;
    }

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
