// 自动生成，请勿手改 —— 见 build-scripts/transpile-anime4k.py
// 源: Anime4K (https://github.com/bloc97/Anime4K)  Anime4K_3DGraphics_AA_Upscale_x2_US.glsl
// 说明: Anime4K-v4.0-3D-AA-Upscale-CNN-x2-(US)-Depth-to-Space
// GLSL ES 1.00 片元着色器（media3 视频特效管线只提供 GLES2 上下文）
precision highp float;

varying vec2 vTexCoord;

float a4kComp(vec4 v, float idx) {
  float i = floor(idx + 0.5);
  if (i < 0.5) return v.x;
  if (i < 1.5) return v.y;
  if (i < 2.5) return v.z;
  return v.w;
}
uniform sampler2D uTex_MAIN;
uniform sampler2D uTex_conv2d_last_tf;
uniform vec2 uSize_MAIN;
uniform vec2 uSize_conv2d_last_tf;

#define MAIN_pos vTexCoord
#define MAIN_size uSize_MAIN
#define MAIN_pt (vec2(1.0) / uSize_MAIN)
#define MAIN_tex(p) (texture2D(uTex_MAIN, (p)) * 2.0 - 1.0)
#define MAIN_texOff(o) (texture2D(uTex_MAIN, (vTexCoord + (o) * MAIN_pt)) * 2.0 - 1.0)
#define conv2d_last_tf_pos vTexCoord
#define conv2d_last_tf_size uSize_conv2d_last_tf
#define conv2d_last_tf_pt (vec2(1.0) / uSize_conv2d_last_tf)
#define conv2d_last_tf_tex(p) (texture2D(uTex_conv2d_last_tf, (p)) * 2.0 - 1.0)
#define conv2d_last_tf_texOff(o) (texture2D(uTex_conv2d_last_tf, (vTexCoord + (o) * conv2d_last_tf_pt)) * 2.0 - 1.0)

vec4 hook() {
    vec2 f0 = fract(conv2d_last_tf_pos * conv2d_last_tf_size);
    ivec2 i0 = ivec2(f0 * vec2(2.0));
    float c0 = a4kComp(conv2d_last_tf_tex((vec2(0.5) - f0) * conv2d_last_tf_pt + conv2d_last_tf_pos), float(i0.y * 2 + i0.x));
    float c1 = c0;
    float c2 = c1;
    float c3 = c2;
    return vec4(c0, c1, c2, c3) + MAIN_tex(MAIN_pos);
}

void main() {
  vec4 r = hook();
  gl_FragColor = vec4(clamp(r.rgb, 0.0, 1.0), 1.0);
}
