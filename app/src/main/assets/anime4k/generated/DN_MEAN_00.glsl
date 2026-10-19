// 自动生成，请勿手改 —— 见 build-scripts/transpile-anime4k.py
// 源: Anime4K (https://github.com/bloc97/Anime4K)  Anime4K_Denoise_Bilateral_Mean.glsl
// 说明: Anime4K-v3.2-Denoise-Bilateral-Mean
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

int es_mod(int a, int b) { return a - (a / b) * b; }

#define INTENSITY_SIGMA 0.1 // Intensity window size, higher is stronger denoise, must be a positive real number
#define SPATIAL_SIGMA 1.0 // Spatial window size, higher is stronger denoise, must be a positive real number.

#define INTENSITY_POWER_CURVE 1.0 // Intensity window power curve. Setting it to 0 will make the intensity window treat all intensities equally, while increasing it will make the window narrower in darker intensities and wider in brighter intensities.

#define KERNELSIZE 5 // Kernel size, must be an positive odd integer.
#define KERNELHALFSIZE 2 // Half of the kernel size without remainder. Must be equal to trunc(KERNELSIZE/2).
#define KERNELLEN 25 // Total area of kernel. Must be equal to KERNELSIZE * KERNELSIZE.

#define GETOFFSET(i) vec2((es_mod(i, KERNELSIZE)) - KERNELHALFSIZE, (i / KERNELSIZE) - KERNELHALFSIZE)

vec4 gaussian_vec(vec4 x, vec4 s, vec4 m) {
	vec4 scaled = (x - m) / s;
	return exp(-0.5 * scaled * scaled);
}

float gaussian(float x, float s, float m) {
	float scaled = (x - m) / s;
	return exp(-0.5 * scaled * scaled);
}

vec4 hook() {
	vec4 sum = vec4(0.0);
	vec4 n = vec4(0.0);
	
	vec4 vc = HOOKED_tex(HOOKED_pos);
	
	vec4 is = pow(vc + 0.0001, vec4(INTENSITY_POWER_CURVE)) * INTENSITY_SIGMA;
	float ss = SPATIAL_SIGMA;
	
	for (int i=0; i<KERNELLEN; i++) {
		vec2 ipos = GETOFFSET(i);
		vec4 v = HOOKED_texOff(ipos);
		vec4 d = gaussian_vec(v, is, vc) * gaussian(length(ipos), ss, 0.0);
		sum += d * v;
		n += d;
	}
	
	return sum / n;
}

void main() {
  vec4 r = hook();
  gl_FragColor = vec4(clamp(r.rgb, 0.0, 1.0), 1.0);
}
