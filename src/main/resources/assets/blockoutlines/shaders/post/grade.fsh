#version 330

uniform sampler2D InSampler;

layout(std140) uniform GradeConfig {
    vec4 Params; // x = saturation, y = contrast, z = brightness
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 c = texture(InSampler, texCoord);
    float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 rgb = mix(vec3(luma), c.rgb, Params.x);
    rgb = (rgb - 0.5) * Params.y + 0.5 + Params.z;
    fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
}
