#version 330

// Wet world: real screen-space reflections on flat ground (lights, mobs, you, buildings and the sky show in it),
// wet darkening, a glow around bright lights and a little rain haze.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform WetConfig {
    vec4 Params; // x = camera pitch in radians (0 = level, + = looking down), y = tan(fov / 2), z = strength 0..1
};

in vec2 texCoord;

out vec4 fragColor;

const float NEAR = 0.05;
const float FAR = 1024.0;

float lin(float d) {
    return 2.0 * NEAR * FAR / (FAR + NEAR - (2.0 * d - 1.0) * (FAR - NEAR));
}

vec3 viewPos(vec2 uv, float d, float aspect) {
    vec2 ndc = uv * 2.0 - 1.0;
    return vec3(ndc.x * aspect * Params.y, ndc.y * Params.y, -1.0) * lin(d);
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 px = 1.0 / size;
    float aspect = size.x / size.y;
    float strength = Params.z;

    vec4 base = texture(InSampler, texCoord);
    vec3 col = base.rgb;
    float d = texture(DepthSampler, texCoord).r;
    bool sky = d >= 0.99999;
    float l = luma(col);

    if (!sky) {
        vec3 P = viewPos(texCoord, d, aspect);
        float dist = length(P);

        // surface direction from the depth buffer (use the neighbour that is nearer in depth, so edges stay clean)
        float dr = texture(DepthSampler, texCoord + vec2(px.x, 0.0)).r;
        float dl = texture(DepthSampler, texCoord - vec2(px.x, 0.0)).r;
        float du = texture(DepthSampler, texCoord + vec2(0.0, px.y)).r;
        float dd = texture(DepthSampler, texCoord - vec2(0.0, px.y)).r;
        vec3 px3 = (abs(lin(dr) - lin(d)) < abs(lin(dl) - lin(d)))
            ? viewPos(texCoord + vec2(px.x, 0.0), dr, aspect) - P
            : P - viewPos(texCoord - vec2(px.x, 0.0), dl, aspect);
        vec3 py3 = (abs(lin(du) - lin(d)) < abs(lin(dd) - lin(d)))
            ? viewPos(texCoord + vec2(0.0, px.y), du, aspect) - P
            : P - viewPos(texCoord - vec2(0.0, px.y), dd, aspect);
        vec3 N = normalize(cross(px3, py3));
        if (dot(N, P) > 0.0) N = -N;

        vec3 up = vec3(0.0, cos(Params.x), sin(Params.x));
        float gnd = smoothstep(0.80, 0.94, dot(N, up));

        if (gnd > 0.01 && dist < 400.0) {
            float farFade = 1.0 - smoothstep(60.0, 300.0, dist);
            // darker, richer wet ground
            float shine = clamp(1.25 - l * 1.6, 0.25, 1.0); // dark / rough stone shines more than bright sand
            vec3 wetCol = col * mix(1.0, 0.62, strength * gnd * farFade);
            wetCol = mix(vec3(luma(wetCol)), wetCol, 1.0 + 0.2 * strength * gnd);

            // reflection ray, marched through the depth buffer
            vec3 V = normalize(P);
            vec3 R = reflect(V, N);
            float t = 0.0;
            float stp = 0.18 + dist * 0.01;
            vec3 hitCol = vec3(0.0);
            float hitW = 0.0;
            vec2 uvq = texCoord;
            for (int i = 0; i < 40; i++) {
                t += stp;
                stp *= 1.1;
                vec3 Q = P + R * t;
                if (Q.z > -NEAR) break;
                uvq = vec2(Q.x / (-Q.z) / (aspect * Params.y), Q.y / (-Q.z) / Params.y) * 0.5 + 0.5;
                if (uvq.x < 0.0 || uvq.x > 1.0 || uvq.y < 0.0 || uvq.y > 1.0) break;
                float sd = texture(DepthSampler, uvq).r;
                float sl = lin(sd);
                float diff = (-Q.z) - sl;
                if (diff > 0.0 && diff < 0.5 + t * 0.2) {
                    // a thing is in the way: take its color (a few soft taps = a slightly blurry mirror)
                    float blur = (0.0016 + t * 0.0006) * (1.0 - 0.4 * strength);
                    vec3 c = texture(InSampler, uvq).rgb * 0.4
                        + texture(InSampler, uvq + vec2(blur, 0.0)).rgb * 0.15
                        + texture(InSampler, uvq - vec2(blur, 0.0)).rgb * 0.15
                        + texture(InSampler, uvq + vec2(0.0, blur * 1.6)).rgb * 0.15
                        + texture(InSampler, uvq - vec2(0.0, blur * 1.6)).rgb * 0.15;
                    hitCol = c;
                    float edge = smoothstep(0.0, 0.08, min(min(uvq.x, 1.0 - uvq.x), min(uvq.y, 1.0 - uvq.y)));
                    hitW = edge * (1.0 - clamp(t / 40.0, 0.0, 1.0));
                    break;
                }
            }
            // nothing hit: the mirror shows the sky (taken from the sky on screen, or a rainy grey)
            vec3 skyCol = vec3(0.50, 0.56, 0.64) * (0.35 + 0.65 * clamp(l * 1.6, 0.0, 1.0));
            if (hitW <= 0.0) {
                vec3 Q = P + R * 400.0;
                vec2 su = vec2(0.5, 0.97);
                if (Q.z < -NEAR) {
                    su = vec2(Q.x / (-Q.z) / (aspect * Params.y), Q.y / (-Q.z) / Params.y) * 0.5 + 0.5;
                    su = vec2(clamp(su.x, 0.03, 0.97), clamp(su.y, 0.03, 0.97));
                }
                if (texture(DepthSampler, su).r >= 0.99999) {
                    skyCol = texture(InSampler, su).rgb;
                } else if (texture(DepthSampler, vec2(su.x, 0.97)).r >= 0.99999) {
                    skyCol = texture(InSampler, vec2(su.x, 0.97)).rgb;
                }
                hitCol = skyCol;
                hitW = 1.0;
            }

            float cosT = clamp(dot(-V, N), 0.0, 1.0);
            float fres = 0.10 + 0.90 * pow(1.0 - cosT, 4.0);
            float refl = clamp(fres * (0.55 + 0.9 * shine) * strength * gnd, 0.0, 0.95) * hitW * (1.0 - clamp(dist / 160.0, 0.0, 1.0));
            // strong lights and bright things shine through harder
            vec3 mirror = hitCol * (1.0 + 0.8 * smoothstep(0.7, 1.0, luma(hitCol)));
            col = mix(wetCol, mirror, refl);
            col += mirror * 0.08 * strength * gnd * shine * (1.0 - refl);
        } else {
            col = mix(col, col * 0.88, 0.5 * strength); // all other surfaces: slightly damp
        }

        // rain haze that grows with distance
        float haze = (1.0 - exp(-dist * 0.010 * (0.4 + strength))) * 0.45 * strength;
        col = mix(col, vec3(0.50, 0.56, 0.64) * (0.4 + 0.6 * clamp(luma(base.rgb) * 1.5, 0.0, 1.0)), haze);
    } else {
        col = mix(col, vec3(luma(col)) * vec3(0.9, 0.95, 1.05), 0.4 * strength); // grey, heavy sky
    }

    // glow around the very bright things (lamps, torches, lava)
    vec3 glow = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float a = 6.2831853 * float(i) / 12.0;
        vec2 o = vec2(cos(a), sin(a));
        vec3 s1 = texture(InSampler, texCoord + o * px * 7.0).rgb;
        vec3 s2 = texture(InSampler, texCoord + o * px * 18.0).rgb;
        glow += max(s1 - 0.78, 0.0) * 0.9 + max(s2 - 0.78, 0.0) * 0.6;
    }
    col += glow / 12.0 * 1.4 * strength;

    // soft dark corners
    vec2 q = texCoord - 0.5;
    col *= 1.0 - 0.28 * strength * dot(q, q) * 2.0;

    fragColor = vec4(clamp(col, 0.0, 1.0), base.a);
}
