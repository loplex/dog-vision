package cz.loplex.dogvision.gl

/**
 * The GLSL ES 3.00 programs that render a view on the GPU, the same on every platform: OpenGL ES 3.0 on Android, and
 * WebGL 2, which runs GLSL ES 3.00 as it is, in a browser.
 *
 * Every offscreen texture holds 8-bit sRGB, with an image's first row at t = 0, and is read with
 * texelFetch at whole pixels. A pass draws one triangle over the whole of its target, so that
 * gl_FragCoord is the pixel it writes. sRGB is decoded and encoded through the same lookup
 * tables as core's CPU pipeline, so that the two agree to the rounding of a float.
 */
object Shaders {
    /** One triangle that covers the viewport; it needs no vertex data. */
    const val FULL_VIEWPORT = """#version 300 es
void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
"""

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D uDecode; // 256 x 1, R32F: the linear value of each 8-bit sRGB value
uniform sampler2D uEncode; // 64 x 64, R8: the 8-bit sRGB value of each of 4096 linear steps
out vec4 outColour;

ivec2 pixel() {
    return ivec2(gl_FragCoord.xy);
}

ivec3 bytes(vec4 texel) {
    return ivec3(texel.rgb * 255.0 + 0.5);
}

vec3 decode(vec4 texel) {
    ivec3 k = bytes(texel);
    return vec3(
        texelFetch(uDecode, ivec2(k.r, 0), 0).r,
        texelFetch(uDecode, ivec2(k.g, 0), 0).r,
        texelFetch(uDecode, ivec2(k.b, 0), 0).r
    );
}

float encodeOne(float linear) {
    int step = int(clamp(linear, 0.0, 1.0) * 4095.0 + 0.5);
    return texelFetch(uEncode, ivec2(step % 64, step / 64), 0).r;
}

vec4 encode(vec3 linear) {
    return vec4(encodeOne(linear.r), encodeOne(linear.g), encodeOne(linear.b), 1.0);
}

// The index a border of reflected pixels reads, as OpenCV's BORDER_REFLECT_101.
int reflect101(int i, int size) {
    if (size == 1) return 0;
    int period = 2 * size - 2;
    i = abs(i) % period;
    return i < size ? i : period - i;
}
"""

    /** The raw frame turned upright, and mirrored if asked. */
    const val UPRIGHT = HEADER + """
uniform sampler2D uRaw;
uniform int uRotation; // degrees clockwise the raw frame needs to stand upright
uniform bool uMirrored;
uniform ivec2 uSize; // upright

void main() {
    ivec2 p = pixel();
    ivec2 raw = textureSize(uRaw, 0);
    if (uMirrored) p.x = uSize.x - 1 - p.x;
    ivec2 q = p;
    if (uRotation == 90) q = ivec2(p.y, raw.y - 1 - p.x);
    else if (uRotation == 180) q = raw - 1 - p;
    else if (uRotation == 270) q = ivec2(raw.x - 1 - p.y, p.x);
    outColour = texelFetch(uRaw, q, 0);
}
"""

    /** A copy of the source: the original, beside a simulation. */
    const val COPY = HEADER + """
uniform sampler2D uSource;

void main() {
    outColour = texelFetch(uSource, pixel(), 0);
}
"""

    /** The simulation's matrix applied in linear light. */
    const val COLOUR = HEADER + """
uniform sampler2D uSource;
uniform mat3 uMatrix;

void main() {
    outColour = encode(uMatrix * decode(texelFetch(uSource, pixel(), 0)));
}
"""

    private const val BLUR = """
uniform sampler2D uSource;
uniform float uSigma;
uniform int uRadius; // the kernel has 2 uRadius + 1 taps
uniform float uNorm; // 1 over the sum of the taps' weights
uniform int uLength; // of the image along the blur

float weight(int k) {
    float d = float(k);
    return exp(-d * d / (2.0 * uSigma * uSigma)) * uNorm;
}
"""

    /** The Gaussian blur across, in linear light. */
    const val BLUR_ACROSS = HEADER + BLUR + """
void main() {
    ivec2 p = pixel();
    vec3 sum = vec3(0.0);
    for (int k = -uRadius; k <= uRadius; k++) {
        sum += weight(k) * decode(texelFetch(uSource, ivec2(reflect101(p.x + k, uLength), p.y), 0));
    }
    outColour = encode(sum);
}
"""

    /** The Gaussian blur down, in linear light, then the simulation's matrix. */
    const val BLUR_DOWN_AND_COLOUR = HEADER + BLUR + """
uniform mat3 uMatrix;

void main() {
    ivec2 p = pixel();
    vec3 sum = vec3(0.0);
    for (int k = -uRadius; k <= uRadius; k++) {
        sum += weight(k) * decode(texelFetch(uSource, ivec2(p.x, reflect101(p.y + k, uLength)), 0));
    }
    outColour = encode(uMatrix * sum);
}
"""

    /**
     * The map of differences, as core's differenceRow draws it. Its alpha is 254/255 where the two
     * images differ noticeably, for COUNT to count; a snapshot makes it opaque.
     */
    const val DIFFERENCE = HEADER + """
uniform sampler2D uLeftImage;
uniform sampler2D uRightImage;

vec3 lab(vec3 rgb) {
    float x = (0.4124 * rgb.r + 0.3576 * rgb.g + 0.1805 * rgb.b) / 0.95047;
    float y = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
    float z = (0.0193 * rgb.r + 0.1192 * rgb.g + 0.9505 * rgb.b) / 1.08883;
    vec3 t = vec3(x, y, z);
    const float edge = 0.008856451679035631; // (6 / 29)^3
    vec3 f = mix(t / 0.12841854934601665 + 4.0 / 29.0, pow(max(t, 0.0), vec3(1.0 / 3.0)), step(edge + 1e-12, t));
    return vec3(116.0 * f.y - 16.0, 500.0 * (f.x - f.y), 200.0 * (f.y - f.z));
}

void main() {
    ivec2 p = pixel();
    vec4 left = texelFetch(uLeftImage, p, 0);
    vec4 right = texelFetch(uRightImage, p, 0);
    float deltaE = distance(lab(decode(left)), lab(decode(right)));
    ivec3 k = bytes(right);
    float grey = float((k.r * 4899 + k.g * 9617 + k.b * 1868 + 8192) >> 14) * 0.6;
    bool noticeable = deltaE > 2.3;
    float w = noticeable ? clamp(deltaE / 10.0, 0.3, 1.0) : 0.0;
    vec3 mixed = floor(vec3(grey) * (1.0 - w) + vec3(255.0, 40.0, 40.0) * w);
    outColour = vec4(mixed / 255.0, noticeable ? 254.0 / 255.0 : 1.0);
}
"""

    /** How many of each 8 x 8 block's pixels of the map differ noticeably, in red. */
    const val COUNT = HEADER + """
uniform sampler2D uSource; // the map

void main() {
    ivec2 size = textureSize(uSource, 0);
    ivec2 block = pixel() * 8;
    int count = 0;
    for (int j = 0; j < 8; j++) for (int i = 0; i < 8; i++) {
        ivec2 q = block + ivec2(i, j);
        if (q.x < size.x && q.y < size.y && texelFetch(uSource, q, 0).a < 0.999) count++;
    }
    outColour = vec4(float(count) / 255.0, 0.0, 0.0, 1.0);
}
"""

    /** Every 8th pixel of every 8th row of the source, for its mean, as core's meanLinearRgb samples it. */
    const val EVERY_EIGHTH = HEADER + """
uniform sampler2D uSource;

void main() {
    outColour = texelFetch(uSource, pixel() * 8, 0);
}
"""

    /** A texture drawn into a rectangle of the framebuffer, its first row at the top. */
    const val SCREEN_VERTEX = """#version 300 es
uniform vec4 uRect; // left, bottom, right, top, in normalised device coordinates
out vec2 vTexture;

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(mix(uRect.xy, uRect.zw, corner), 0.0, 1.0);
    vTexture = vec2(corner.x, 1.0 - corner.y);
}
"""

    const val SCREEN = """#version 300 es
precision highp float;
uniform sampler2D uSource;
in vec2 vTexture;
out vec4 outColour;

void main() {
    outColour = vec4(texture(uSource, vTexture).rgb, 1.0);
}
"""
}
