// 自动生成，请勿手改 —— 见 build-scripts/transpile-anime4k.py
// 源: Anime4K (https://github.com/bloc97/Anime4K)  Anime4K_Restore_CNN_M.glsl
// 说明: Anime4K-v4.0-Restore-CNN-(M)-Conv-3x1x1x56
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
uniform sampler2D uTex_conv2d_tf;
uniform sampler2D uTex_conv2d_1_tf;
uniform sampler2D uTex_conv2d_2_tf;
uniform sampler2D uTex_conv2d_3_tf;
uniform sampler2D uTex_conv2d_4_tf;
uniform sampler2D uTex_conv2d_5_tf;
uniform sampler2D uTex_conv2d_6_tf;
uniform vec2 uSize_MAIN;
uniform vec2 uSize_conv2d_tf;
uniform vec2 uSize_conv2d_1_tf;
uniform vec2 uSize_conv2d_2_tf;
uniform vec2 uSize_conv2d_3_tf;
uniform vec2 uSize_conv2d_4_tf;
uniform vec2 uSize_conv2d_5_tf;
uniform vec2 uSize_conv2d_6_tf;

#define MAIN_pos vTexCoord
#define MAIN_size uSize_MAIN
#define MAIN_pt (vec2(1.0) / uSize_MAIN)
#define MAIN_tex(p) texture2D(uTex_MAIN, (p))
#define MAIN_texOff(o) texture2D(uTex_MAIN, (vTexCoord + (o) * MAIN_pt))
#define conv2d_tf_pos vTexCoord
#define conv2d_tf_size uSize_conv2d_tf
#define conv2d_tf_pt (vec2(1.0) / uSize_conv2d_tf)
#define conv2d_tf_tex(p) (texture2D(uTex_conv2d_tf, (p)) * 2.0 - 1.0)
#define conv2d_tf_texOff(o) (texture2D(uTex_conv2d_tf, (vTexCoord + (o) * conv2d_tf_pt)) * 2.0 - 1.0)
#define conv2d_1_tf_pos vTexCoord
#define conv2d_1_tf_size uSize_conv2d_1_tf
#define conv2d_1_tf_pt (vec2(1.0) / uSize_conv2d_1_tf)
#define conv2d_1_tf_tex(p) (texture2D(uTex_conv2d_1_tf, (p)) * 2.0 - 1.0)
#define conv2d_1_tf_texOff(o) (texture2D(uTex_conv2d_1_tf, (vTexCoord + (o) * conv2d_1_tf_pt)) * 2.0 - 1.0)
#define conv2d_2_tf_pos vTexCoord
#define conv2d_2_tf_size uSize_conv2d_2_tf
#define conv2d_2_tf_pt (vec2(1.0) / uSize_conv2d_2_tf)
#define conv2d_2_tf_tex(p) (texture2D(uTex_conv2d_2_tf, (p)) * 2.0 - 1.0)
#define conv2d_2_tf_texOff(o) (texture2D(uTex_conv2d_2_tf, (vTexCoord + (o) * conv2d_2_tf_pt)) * 2.0 - 1.0)
#define conv2d_3_tf_pos vTexCoord
#define conv2d_3_tf_size uSize_conv2d_3_tf
#define conv2d_3_tf_pt (vec2(1.0) / uSize_conv2d_3_tf)
#define conv2d_3_tf_tex(p) (texture2D(uTex_conv2d_3_tf, (p)) * 2.0 - 1.0)
#define conv2d_3_tf_texOff(o) (texture2D(uTex_conv2d_3_tf, (vTexCoord + (o) * conv2d_3_tf_pt)) * 2.0 - 1.0)
#define conv2d_4_tf_pos vTexCoord
#define conv2d_4_tf_size uSize_conv2d_4_tf
#define conv2d_4_tf_pt (vec2(1.0) / uSize_conv2d_4_tf)
#define conv2d_4_tf_tex(p) (texture2D(uTex_conv2d_4_tf, (p)) * 2.0 - 1.0)
#define conv2d_4_tf_texOff(o) (texture2D(uTex_conv2d_4_tf, (vTexCoord + (o) * conv2d_4_tf_pt)) * 2.0 - 1.0)
#define conv2d_5_tf_pos vTexCoord
#define conv2d_5_tf_size uSize_conv2d_5_tf
#define conv2d_5_tf_pt (vec2(1.0) / uSize_conv2d_5_tf)
#define conv2d_5_tf_tex(p) (texture2D(uTex_conv2d_5_tf, (p)) * 2.0 - 1.0)
#define conv2d_5_tf_texOff(o) (texture2D(uTex_conv2d_5_tf, (vTexCoord + (o) * conv2d_5_tf_pt)) * 2.0 - 1.0)
#define conv2d_6_tf_pos vTexCoord
#define conv2d_6_tf_size uSize_conv2d_6_tf
#define conv2d_6_tf_pt (vec2(1.0) / uSize_conv2d_6_tf)
#define conv2d_6_tf_tex(p) (texture2D(uTex_conv2d_6_tf, (p)) * 2.0 - 1.0)
#define conv2d_6_tf_texOff(o) (texture2D(uTex_conv2d_6_tf, (vTexCoord + (o) * conv2d_6_tf_pt)) * 2.0 - 1.0)

#define g_0 (max((conv2d_tf_tex(conv2d_tf_pos)), 0.0))
#define g_1 (max(-(conv2d_tf_tex(conv2d_tf_pos)), 0.0))
#define g_2 (max((conv2d_1_tf_tex(conv2d_1_tf_pos)), 0.0))
#define g_3 (max(-(conv2d_1_tf_tex(conv2d_1_tf_pos)), 0.0))
#define g_4 (max((conv2d_2_tf_tex(conv2d_2_tf_pos)), 0.0))
#define g_5 (max(-(conv2d_2_tf_tex(conv2d_2_tf_pos)), 0.0))
#define g_6 (max((conv2d_3_tf_tex(conv2d_3_tf_pos)), 0.0))
#define g_7 (max(-(conv2d_3_tf_tex(conv2d_3_tf_pos)), 0.0))
#define g_8 (max((conv2d_4_tf_tex(conv2d_4_tf_pos)), 0.0))
#define g_9 (max(-(conv2d_4_tf_tex(conv2d_4_tf_pos)), 0.0))
#define g_10 (max((conv2d_5_tf_tex(conv2d_5_tf_pos)), 0.0))
#define g_11 (max(-(conv2d_5_tf_tex(conv2d_5_tf_pos)), 0.0))
#define g_12 (max((conv2d_6_tf_tex(conv2d_6_tf_pos)), 0.0))
#define g_13 (max(-(conv2d_6_tf_tex(conv2d_6_tf_pos)), 0.0))
vec4 hook() {
    vec4 result = mat4(-0.08837163, -0.065234736, -0.034704313, 0.0, 0.021405501, 0.013663729, 0.019249594, 0.0, 0.05328863, 0.03580334, 0.046457592, 0.0, -0.12216048, 0.022547891, 0.016400825, 0.0) * g_0;
    result += mat4(0.061996464, 0.05631466, 0.06808407, 0.0, -0.005013109, -0.0044589997, -0.032367796, 0.0, 0.016481603, 0.13721058, 0.14924648, 0.0, 0.020035887, -0.07250003, -0.08034037, 0.0) * g_1;
    result += mat4(0.24078514, 0.081361525, 0.053420708, 0.0, -0.009353794, -0.051077116, -0.058007747, 0.0, -0.14071098, 0.01035966, 0.005308949, 0.0, -0.1489842, -0.06711817, -0.05552926, 0.0) * g_2;
    result += mat4(-0.13002375, 0.012733757, 0.017821986, 0.0, 0.17767483, 0.20204604, 0.1751779, 0.0, 0.12804912, 0.07381453, 0.05655911, 0.0, 0.17044514, 0.07301451, 0.06523978, 0.0) * g_3;
    result += mat4(-0.1170986, -0.05130371, -0.027939914, 0.0, -0.16645707, -0.121526904, -0.09471366, 0.0, -0.04143118, 0.026693767, 0.034615446, 0.0, -0.084318705, -0.064990036, -0.054324172, 0.0) * g_4;
    result += mat4(0.12094524, 0.09518409, 0.07387219, 0.0, 0.062216382, 0.053228356, 0.031372335, 0.0, 0.072797105, 0.026258165, 0.009804673, 0.0, 0.120719045, 0.073281154, 0.056623302, 0.0) * g_5;
    result += mat4(-0.11141495, -0.11566289, -0.10398725, 0.0, -0.0651895, -0.06820691, -0.054204144, 0.0, -0.032746475, -0.008849683, -0.007610222, 0.0, -0.024655705, -0.048778858, -0.041144755, 0.0) * g_6;
    result += mat4(0.058090195, 0.07538767, 0.059722915, 0.0, 0.044788487, 0.04212742, 0.027502589, 0.0, 0.04892866, 0.015416752, 0.008312418, 0.0, -0.011864114, -0.0074752793, -0.0060824654, 0.0) * g_7;
    result += mat4(0.043446552, 0.061971307, 0.05758086, 0.0, -0.06379154, -0.053758245, -0.047204215, 0.0, 0.016307736, 0.03423424, 0.030179083, 0.0, 0.041445345, 0.03843772, 0.033059113, 0.0) * g_8;
    result += mat4(-0.003803544, 0.0008906116, -0.00059585314, 0.0, 0.102071285, 0.11485224, 0.10007254, 0.0, -0.074306004, -0.08803551, -0.07972321, 0.0, -0.030704215, -0.021514274, -0.009049376, 0.0) * g_9;
    result += mat4(0.0066058086, 0.0011408008, 0.0016199006, 0.0, -0.03916473, -0.042929266, -0.04018418, 0.0, -0.03153446, -0.039413508, -0.034767237, 0.0, 0.113516055, 0.12577052, 0.113335624, 0.0) * g_10;
    result += mat4(0.02655948, 0.041905303, 0.03861737, 0.0, 0.048471425, 0.049788587, 0.050447535, 0.0, 0.12092813, 0.13564217, 0.12613249, 0.0, -0.0023508538, 0.0012828974, 0.0028730957, 0.0) * g_11;
    result += mat4(0.0084758485, 0.008800083, 0.008206044, 0.0, -0.056123603, -0.06610845, -0.060320783, 0.0, -0.081793964, -0.101638645, -0.096699014, 0.0, -0.04402356, -0.04177539, -0.03829645, 0.0) * g_12;
    result += mat4(0.10676299, 0.118409514, 0.10618478, 0.0, -0.05880252, -0.06488367, -0.06432695, 0.0, 0.019221924, 0.017602798, 0.017413978, 0.0, -0.07512528, -0.080483615, -0.066218294, 0.0) * g_13;
    result += vec4(-0.010478934, -0.008364784, -0.010246552, 0.0);
    return result + MAIN_tex(MAIN_pos);
}

void main() {
  gl_FragColor = hook() * 0.5 + 0.5;
}
