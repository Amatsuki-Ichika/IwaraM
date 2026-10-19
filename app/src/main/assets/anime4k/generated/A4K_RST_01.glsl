// 自动生成，请勿手改 —— 见 build-scripts/transpile-anime4k.py
// 源: Anime4K (https://github.com/bloc97/Anime4K)  Anime4K_Clamp_Highlights.glsl
// 说明: Anime4K-v4.0-De-Ring-Compute-Statistics
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
uniform sampler2D uTex_STATSMAX;
uniform vec2 uSize_MAIN;
uniform vec2 uSize_HOOKED;
uniform vec2 uSize_STATSMAX;

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
#define STATSMAX_pos vTexCoord
#define STATSMAX_size uSize_STATSMAX
#define STATSMAX_pt (vec2(1.0) / uSize_STATSMAX)
#define STATSMAX_tex(p) (texture2D(uTex_STATSMAX, (p)) * 2.0 - 1.0)
#define STATSMAX_texOff(o) (texture2D(uTex_STATSMAX, (vTexCoord + (o) * STATSMAX_pt)) * 2.0 - 1.0)

#define KERNELSIZE 5 // Kernel size, must be an positive odd integer.
#define KERNELHALFSIZE 2 // Half of the kernel size without remainder. Must be equal to trunc(KERNELSIZE/2).

vec4 hook() {

	float gmax = 0.0;
	
	for (int i=0; i<KERNELSIZE; i++) {
		float g = STATSMAX_texOff(vec2(0, i - KERNELHALFSIZE)).x;
		
		gmax = max(g, gmax);
	}
	
	return vec4(gmax, 0.0, 0.0, 0.0);
}

void main() {
  gl_FragColor = hook() * 0.5 + 0.5;
}
