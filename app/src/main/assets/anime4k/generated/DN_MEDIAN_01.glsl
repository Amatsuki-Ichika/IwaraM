// 自动生成，请勿手改 —— 见 build-scripts/transpile-anime4k.py
// 源: Anime4K (https://github.com/bloc97/Anime4K)  Anime4K_Denoise_Bilateral_Median.glsl
// 说明: Anime4K-v3.2-Denoise-Bilateral-Median-Apply
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
uniform sampler2D uTex_LINELUMA;
uniform vec2 uSize_MAIN;
uniform vec2 uSize_HOOKED;
uniform vec2 uSize_LINELUMA;

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
#define LINELUMA_pos vTexCoord
#define LINELUMA_size uSize_LINELUMA
#define LINELUMA_pt (vec2(1.0) / uSize_LINELUMA)
#define LINELUMA_tex(p) (texture2D(uTex_LINELUMA, (p)) * 2.0 - 1.0)
#define LINELUMA_texOff(o) (texture2D(uTex_LINELUMA, (vTexCoord + (o) * LINELUMA_pt)) * 2.0 - 1.0)

int es_mod(int a, int b) { return a - (a / b) * b; }

#define INTENSITY_SIGMA 0.1 // Intensity window size, higher is stronger denoise, must be a positive real number
#define SPATIAL_SIGMA 1.0 // Spatial window size, higher is stronger denoise, must be a positive real number.
#define HISTOGRAM_REGULARIZATION 0.0 // Histogram regularization window size, higher values approximate a bilateral "closest-to-mean" filter.

#define INTENSITY_POWER_CURVE 1.0 // Intensity window power curve. Setting it to 0 will make the intensity window treat all intensities equally, while increasing it will make the window narrower in darker intensities and wider in brighter intensities.

#define KERNELSIZE 3 // Kernel size, must be an positive odd integer.
#define KERNELHALFSIZE 1 // Half of the kernel size without remainder. Must be equal to trunc(KERNELSIZE/2).
#define KERNELLEN 9 // Total area of kernel. Must be equal to KERNELSIZE * KERNELSIZE.

#define GETOFFSET(i) vec2((es_mod(i, KERNELSIZE)) - KERNELHALFSIZE, (i / KERNELSIZE) - KERNELHALFSIZE)

float gaussian(float x, float s, float m) {
	float scaled = (x - m) / s;
	return exp(-0.5 * scaled * scaled);
}

vec4 getMedian(vec4 v[KERNELLEN], float w[KERNELLEN], float n) {
	
	for (int i=0; i<KERNELLEN; i++) {
		float w_above = 0.0;
		float w_below = 0.0;
		for (int j=0; j<KERNELLEN; j++) {
			if (v[j].x > v[i].x) {
				w_above += w[j];
			} else if (v[j].x < v[i].x) {
				w_below += w[j];
			}
		}
		
		if ((n - w_above) / n >= 0.5 && w_below / n <= 0.5) {
			return v[i];
		}
	}
}

vec4 hook() {
	vec4 histogram_v[KERNELLEN];
	float histogram_l[KERNELLEN];
	float histogram_w[KERNELLEN];
	float n = 0.0;
	
	float vc = LINELUMA_tex(HOOKED_pos).x;
	
	float is = pow(vc + 0.0001, INTENSITY_POWER_CURVE) * INTENSITY_SIGMA;
	float ss = SPATIAL_SIGMA;
	
	for (int i=0; i<KERNELLEN; i++) {
		vec2 ipos = GETOFFSET(i);
		histogram_v[i] = HOOKED_texOff(ipos);
		histogram_l[i] = LINELUMA_texOff(ipos).x;
		histogram_w[i] = gaussian(histogram_l[i], is, vc) * gaussian(length(ipos), ss, 0.0);
		n += histogram_w[i];
	}
	
	if (HISTOGRAM_REGULARIZATION > 0.0) {
		float histogram_wn[KERNELLEN];
		n = 0.0;
		
		for (int i=0; i<KERNELLEN; i++) {
			histogram_wn[i] = 0.0;
		}
		
		for (int i=0; i<KERNELLEN; i++) {
			histogram_wn[i] += gaussian(0.0, HISTOGRAM_REGULARIZATION, 0.0) * histogram_w[i];
			for (int j=(i+1); j<KERNELLEN; j++) {
				float d = gaussian(histogram_l[j], HISTOGRAM_REGULARIZATION, histogram_l[i]);
				histogram_wn[j] += d * histogram_w[i];
				histogram_wn[i] += d * histogram_w[j];
			}
			n += histogram_wn[i];
		}
	
		return getMedian(histogram_v, histogram_wn, n);
	}
	
	return getMedian(histogram_v, histogram_w, n);
}

void main() {
  vec4 r = hook();
  gl_FragColor = vec4(clamp(r.rgb, 0.0, 1.0), 1.0);
}
