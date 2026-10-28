#version 150

// Lights a block face with the flashlight pattern. The pattern is projected from the lens and
// looked up for every pixel, so the edge of the beam stays round however big the spot gets, and
// shadows come from a shadow map (see ShadowMap.java), also checked per pixel and softened.
//   Sampler0: block atlas (the face's own texture)
//   Sampler1: the beam pattern (beam_cookie.png)
//   Sampler2: the shadow map, per ray: distance to the first solid block (16-bit, red/green),
//             light let through leaves or glass (blue), where those start (alpha, 1 = none)
// vertexColor.rgb is the block's tint; vertexColor.a is how much light reaches this point
// (angle, distance and fade, worked out on the CPU), divided by Gain.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform vec3 BeamOrigin;   // lens position, view space
uniform vec3 BeamDir;      // beam axis and its two cross axes, view space
uniform vec3 BeamU;
uniform vec3 BeamV;
uniform float TanSpot;
uniform float LensRadius;
uniform float Contrast;
uniform float EdgeSoften;
uniform float EdgeStart;
uniform float Gain;
uniform float Glow;
uniform vec3 LightColor;

uniform float ShadowEnabled;
uniform vec3 ShadowOrigin;  // where the shadow map was cast from, and its axes, view space
uniform vec3 ShadowDir;
uniform vec3 ShadowU;
uniform vec3 ShadowV;
uniform float ShadowAngle;   // half-angle the shadow map covers, radians
uniform float ShadowRange;
uniform float ShadowSize;

// Minecraft's fog, so the light fades into it like the blocks do (thick underwater, at render distance).
uniform float FogStart;
uniform float FogEnd;

in vec3 viewPos;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

// How much light reaches a point this far along one shadow-map ray: none past the first solid
// block, the share that got through the gaps past any leaves or glass, all of it before them.
float lightAlong(ivec2 texel, float dist, float bias) {
    int size = int(ShadowSize);
    vec4 c = texelFetch(Sampler2, clamp(texel, ivec2(0), ivec2(size - 1)), 0);
    float solid = (floor(c.r * 255.0 + 0.5) * 256.0 + floor(c.g * 255.0 + 0.5)) / 65535.0 * ShadowRange;
    if (dist > solid + bias) return 0.0;
    if (c.a < 0.999 && dist > c.a * ShadowRange + bias + 0.25) return c.b;
    return 1.0;
}

// 1.0 = lit, 0.0 = in shadow, soft in between.
float shadow(vec3 n) {
    if (ShadowEnabled < 0.5) return 1.0;
    vec3 w = viewPos - ShadowOrigin;
    float dist0 = length(w);
    if (dist0 < 0.05) return 1.0;
    float texel = dist0 * 2.0 * ShadowAngle / ShadowSize;  // size of one map sample here

    // Normal offset: look the point up as if nudged off its surface toward the light by about a
    // sample, more on surfaces the light only grazes. Stops a surface shadowing itself (the
    // blotchy dark patches on terrain) without letting light leak round corners.
    vec3 toLight = -w / dist0;
    vec3 nl = dot(n, toLight) < 0.0 ? -n : n;
    float facing = clamp(dot(nl, toLight), 0.0, 1.0);
    w += nl * texel * (0.7 + 0.8 * (1.0 - facing));

    float t = dot(w, ShadowDir);
    if (t <= 0.05) return 1.0;
    // The map's samples are spaced evenly by angle across the beam.
    vec2 sq = vec2(atan(dot(w, ShadowU), t), atan(dot(w, ShadowV), t)) / ShadowAngle;
    if (abs(sq.x) >= 1.0 || abs(sq.y) >= 1.0) return 1.0;
    float dist = length(w);
    float bias = 0.03 + texel * 0.5;

    // Smooth 3x3 filter: each sample weighted by how much of it falls under this pixel's spot.
    vec2 p = (sq * 0.5 + 0.5) * ShadowSize - 0.5;
    vec2 base = floor(p);
    vec2 f = p - base;
    float sum = 0.0;
    for (int y = -1; y <= 2; y++) {
        float wy = y == -1 ? 1.0 - f.y : (y == 2 ? f.y : 1.0);
        for (int x = -1; x <= 2; x++) {
            float wx = x == -1 ? 1.0 - f.x : (x == 2 ? f.x : 1.0);
            float lit = lightAlong(ivec2(base) + ivec2(x, y), dist, bias);
            sum += lit * wx * wy;
        }
    }
    return sum / 9.0;
}

void main() {
    // Surface normal from screen-space derivatives; taken first, before any pixel is discarded.
    vec3 n = normalize(cross(dFdx(viewPos), dFdy(viewPos)));

    vec3 w = viewPos - BeamOrigin;
    float t = dot(w, BeamDir);
    if (t <= 0.05) discard;
    vec2 sq = vec2(dot(w, BeamU), dot(w, BeamV)) / (t * TanSpot + LensRadius);
    if (abs(sq.x) >= 1.0 || abs(sq.y) >= 1.0) discard;

    vec4 tex = texture(Sampler0, texCoord0);
    // Same cut-off as Minecraft's own leaves and plants, so their soft edges don't glow.
    if (tex.a < 0.5) discard;

    float pattern = pow(texture(Sampler1, sq * 0.5 + 0.5).r, Contrast);
    pattern *= 1.0 - EdgeSoften * smoothstep(EdgeStart, 0.95, length(sq));

    float light = pattern * vertexColor.a * Gain * shadow(n);
    light = 1.0 - exp(-1.3 * light);  // soft shoulder: bright centre without flattening the rings
    float fog = clamp((length(viewPos) - FogStart) / max(FogEnd - FogStart, 0.001), 0.0, 1.0);
    light *= 1.0 - fog;
    fragColor = vec4((tex.rgb * vertexColor.rgb + Glow * LightColor) * light, 1.0);
}
