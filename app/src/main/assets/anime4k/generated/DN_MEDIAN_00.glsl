// 自动生成，请勿手改 —— 见 build-scripts/transpile-anime4k.py
// 源: Anime4K (https://github.com/bloc97/Anime4K)  Anime4K_Denoise_Bilateral_Median.glsl
// 说明: Anime4K-v3.2-Denoise-Bilateral-Median-Luma
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
uniform sampler2D uTex_HOOKED;
uniform vec2 uSize_MAIN;
uniform vec2 uSize_HOOKED;

#define MAIN_pos vTexCoord
#define MAIN_size uSize_MAIN
#define MAIN_pt (vec2(1.0) / uSize_MAIN)
#define MAIN_tex(p) texture2D(uTex_MAIN, (p))
#define MAIN_texOff(o) texture2D(uTex_MAIN, (vTexCoord + (o) * MAIN_pt))
#define HOOKED_pos vTexCoord
#define HOOKED_size uSize_HOOKED
#define HOOKED_pt (vec2(1.0) / uSize_HOOKED)
#define HOOKED_tex(p) texture2D(uTex_HOOKED, (p))
#define HOOKED_texOff(o) texture2D(uTex_HOOKED, (vTexCoord + (o) * HOOKED_pt))

float get_luma(vec4 rgba) {
	return dot(vec4(0.299, 0.587, 0.114, 0.0), rgba);
}

vec4 hook() {
    return vec4(get_luma(HOOKED_tex(HOOKED_pos)), 0.0, 0.0, 0.0);
}

void main() {
  gl_FragColor = hook() * 0.5 + 0.5;
}
