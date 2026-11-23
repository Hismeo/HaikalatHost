#version 460 core

layout(binding = 0) uniform sampler2D frameColor;
in vec2 uv;
layout(location = 0) out vec4 color;

void main() {
    color = texture(frameColor, uv);
}
