package com.gebetszeiten.app

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Himmels-Shader (AGSL, ab Android 13). Volumenwolken, Sonne, Mond mit Phase, Sterne, Regen, Schnee, Nebel. */
private const val SKY_AGSL = """
uniform float2 uRes; uniform float uTime;
uniform float3 uTop; uniform float3 uMid; uniform float3 uHor;
uniform float2 uSun; uniform float uSunVis; uniform float3 uSunCol; uniform float uSunLow;
uniform float2 uMoon; uniform float uMoonVis; uniform float uPhase;
uniform float uStars; uniform float uCloud; uniform float uRain; uniform float uSnow; uniform float uFog;
uniform float uFlash; uniform float uHorizon; uniform float2 uFlashPos; uniform float uWind;
uniform float3 uCloudLit; uniform float3 uCloudShade;

float hash(float2 p){p=fract(p*float2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float noise(float2 p){float2 i=floor(p);float2 f=fract(p);float2 u=f*f*(3.-2.*f);
  return mix(mix(hash(i),hash(i+float2(1,0)),u.x),mix(hash(i+float2(0,1)),hash(i+float2(1,1)),u.x),u.y);}
float fbm(float2 p){float v=0.;float a=.5;float2x2 r=float2x2(.8,.6,-.6,.8);for(int i=0;i<5;i++){v+=a*noise(p);p=r*p*2.03+float2(1.7,9.2);a*=.5;}return v;}
float fbm3(float2 p){float v=0.;float a=.5;float2x2 r=float2x2(.8,.6,-.6,.8);for(int i=0;i<3;i++){v+=a*noise(p);p=r*p*2.03+float2(1.7,9.2);a*=.5;}return v;}
float hash31(float3 p){p=fract(p*.3183099+.1);p*=17.;return fract(p.x*p.y*p.z*(p.x+p.y+p.z));}
float noise3(float3 x){float3 i=floor(x);float3 f=fract(x);f=f*f*(3.-2.*f);
  return mix(mix(mix(hash31(i),hash31(i+float3(1,0,0)),f.x),mix(hash31(i+float3(0,1,0)),hash31(i+float3(1,1,0)),f.x),f.y),
             mix(mix(hash31(i+float3(0,0,1)),hash31(i+float3(1,0,1)),f.x),mix(hash31(i+float3(0,1,1)),hash31(i+float3(1,1,1)),f.x),f.y),f.z);}
float fbm3d(float3 p){float v=0.;float a=.5;for(int i=0;i<4;i++){v+=a*noise3(p);p=p*2.03+float3(1.3,7.1,3.7);a*=.5;}return v;}
float cloudDens(float3 pos, float cov, float t){
  float hf=(pos.y-1.0)/1.6;
  if(hf<0.||hf>1.) return 0.;
  float wm=fbm3(pos.xz*.13+float2(t*.008*uWind,t*.003*uWind));
  float wmask=smoothstep(1.-cov*1.05,1.-cov*1.05+.28,wm);
  if(wmask<=0.) return 0.;
  float top=mix(.45,1.,wmask);
  float prof=smoothstep(0.,.10,hf)*smoothstep(top,top*.55,hf);
  float3 sp=pos*float3(.38,.62,.38)+float3(t*.06*uWind,t*.004,t*.022*uWind);
  float base=fbm3d(sp);
  float shape=clamp((base-(1.-wmask*.85))/(wmask*.85+.001),0.,1.)*prof;
  if(shape<=0.) return 0.;
  float b1=1.-abs(2.*noise3(sp*4.1+float3(0.,t*.02,0.))-1.);
  shape-=(1.-b1)*.42*(1.-shape)*(1.-hf*.3);
  return clamp(shape*2.6,0.,1.);
}
half4 main(float2 fc){
  float2 uv=float2(fc.x/uRes.x,1.-fc.y/uRes.y);
  float asp=uRes.x/uRes.y; float2 p=float2(uv.x*asp,uv.y); float hz=uHorizon;
  float hgt=clamp((uv.y-hz)/(1.-hz),0.,1.);
  float3 col=mix(uHor,uMid,smoothstep(0.,.42,hgt));
  col=mix(col,uTop,smoothstep(.38,1.,hgt));
  float2 sp=float2(uSun.x*asp,uSun.y); float2 dv=p-sp; float d=length(dv);
  col+=uSunCol*(.38*exp(-d*7.5)+.18*exp(-d*2.2))*uSunVis;
  col+=uSunCol*uSunLow*.35*exp(-abs(uv.y-hz)*9.)*exp(-abs(dv.x)*1.6)*uSunVis;
  // Sterne
  float starMask=uStars*smoothstep(hz+.08,hz+.4,uv.y);
  float2 g=floor(p*150.); float h=hash(g); float2 gf=fract(p*150.)-.5;
  col+=float3(.9,.95,1.)*step(.9972,h)*smoothstep(.25,0.,length(gf))*(.55+.45*sin(uTime*(1.1+h*3.)+h*70.))*starMask;
  // Mond als Kugel
  float2 mp=float2(uMoon.x*asp,uMoon.y); float R=.045; float2 q=(p-mp)/R; float r2=dot(q,q);
  col+=float3(.7,.78,1.)*(.10*exp(-length(p-mp)*14.))*uMoonVis;
  if(r2<1.){
    float3 n=float3(q.x,-q.y,sqrt(1.-r2));
    float ang=uPhase*6.2831853;
    float3 L=normalize(float3(sin(ang),0.,-cos(ang)));
    float diff=smoothstep(-.06,.12,dot(n,L));
    float maria=fbm3(q*2.3+3.)*.6+fbm3(q*7.+1.)*.25;
    float3 surf=mix(float3(.62,.63,.66),float3(.98,.97,.94),smoothstep(.35,.75,maria));
    float3 moonCol=mix(float3(.10,.12,.18),surf,diff);
    col=mix(col,moonCol,smoothstep(1.,.96,r2)*uMoonVis);
  }
  // Sonnenscheibe
  float sd=smoothstep(.055,.047,d);
  col=mix(col,min(uSunCol*1.25+.25,float3(1.)),sd*uSunVis);
  // Volumenwolken
  if(uCloud>.01 && uv.y>hz-.01){
    float3 dir=normalize(float3((uv.x-.5)*asp*1.15,(uv.y-hz)*1.15+.004,1.));
    float3 sunDir=normalize(float3((uSun.x-.5)*asp*1.15,(uSun.y-hz)*1.15+.02,1.));
    if(dir.y>.002){
      float t0=1.0/dir.y; float t1=min(2.6/dir.y,t0+9.);
      float dt=(t1-t0)/18.;
      float tt=t0+dt*hash(fc+fract(uTime));
      float T=1.; float3 acc=float3(0.);
      float cosT=dot(dir,sunDir);
      float hgF=(1.-.62*.62)/pow(1.+.62*.62-2.*.62*cosT,1.5);
      float hgB=(1.-.0625)/pow(1.0625+.5*cosT,1.5);
      float hg=mix(hgF,hgB,.3)*.22+.32;
      float toward=smoothstep(-.3,1.,cosT);
      float2 fp=float2(uFlashPos.x*asp,uFlashPos.y);
      float fl=uFlash*(exp(-length(p-fp)*3.2)*1.6+exp(-length(p-fp)*1.1)*.35);
      for(int i=0;i<18;i++){
        if(T<.03) break;
        float3 pos=dir*tt;
        float dn=cloudDens(pos,uCloud,uTime);
        if(dn>.004){
          float ld=cloudDens(pos+sunDir*.12,uCloud,uTime)+cloudDens(pos+sunDir*.45,uCloud,uTime);
          float hf=clamp((pos.y-1.)/1.6,0.,1.);
          float beer=exp(-ld*1.5);
          float multi=exp(-ld*.38)*.45;
          float powder=1.-exp(-dn*2.6);
          float3 skyAmb=mix(uCloudShade,mix(uCloudLit,uTop*.6+.45,.25),.2+.8*hf);
          skyAmb*=mix(float3(1.),float3(.86,.88,1.06),uSunLow*(1.-toward)*.6);
          float3 sunL=uSunCol*uSunVis*(beer*hg*1.9+multi)*mix(.7,1.,powder)*1.7;
          sunL+=uSunCol*uSunVis*pow(max(cosT,0.),10.)*(1.-exp(-dn*1.2))*beer*1.6;
          float3 sc=skyAmb*(.62+.25*beer)+sunL+uSunCol*uSunLow*uSunVis*(1.-hf)*.4
                   +float3(.80,.86,1.)*fl*(1.15-hf*.3)*(.5+.5*powder);
          sc=mix(sc,mix(uHor,uMid,.3),smoothstep(2.5,13.,tt)*.55);
          float a=1.-exp(-dn*dt*3.2);
          acc+=T*a*sc; T*=1.-a;
        }
        tt+=dt;
      }
      float far=smoothstep(2.,12.,t0);
      col=col*T+mix(acc,(1.-T)*mix(uHor,uMid,.35),far*.75);
    }
  }
  // Nebel
  float fogN=fbm(float2(p.x*1.3+uTime*.03,p.y*4.-uTime*.012));
  float fogM=uFog*(smoothstep(.75,hz,uv.y)*.85+.15)*(.45+.55*fogN);
  col=mix(col,mix(float3(.80,.83,.88),uHor*1.1,.35)+uSunCol*exp(-d*2.)*.25*uSunVis,clamp(fogM,0.,.92));
  // Regen
  float rain=0.;
  for(int i=0;i<3;i++){float fi=float(i);
    float2 rp=p; rp.x+=rp.y*(.06+.16*uWind+fi*.03);
    rp*=float2(55.+fi*50.,1.6+fi*.7); rp.y+=uTime*(6.+fi*3.);
    float2 id=floor(rp); float2 f=fract(rp); float r=hash(id+fi*13.);
    float len=.35+.4*hash(id+3.);
    rain+=step(.88,r)*smoothstep(.16,0.,abs(f.x-.5))*smoothstep(1.-len,1.,f.y)*smoothstep(1.,.92,f.y)*(.18+.24*fi)*step(fi*.34,uRain);}
  col=mix(col,col*.82+float3(.04,.05,.07),min(uRain,1.)*.5);
  col+=float3(.8,.85,.95)*rain*min(1.,.4+uRain*.6)*step(.001,uRain);
  // Schnee
  float snow=0.;
  for(int i=0;i<3;i++){float fi=float(i);
    float2 s2=p*(9.-fi*3.); s2.y+=uTime*(.18+fi*.14); s2.x+=sin(uTime*.6+s2.y*1.1+fi*2.)*.3;
    float2 id=floor(s2); float2 f=fract(s2); float2 c=float2(.5)+(float2(hash(id+1.),hash(id+2.))-.5)*.6;
    float size=.05+fi*.04; float soft=.02+fi*.05;
    snow+=smoothstep(size+soft,size*.3,length(f-c))*step(.55,hash(id+fi))*(.55+.2*fi);}
  col=mix(col,col*.92+float3(.05),uSnow*.4);
  col+=float3(.95,.97,1.)*snow*uSnow;
  col+=float3(.5,.56,.78)*uFlash*(.05+.18*exp(-length(p-float2(uFlashPos.x*asp,uFlashPos.y))*1.8));
  col+=(hash(fc+uTime)-.5)/255.;
  return half4(half3(col),1.);
}
"""

private fun hexC(s: String): FloatArray {
    val v = s.removePrefix("#").toLong(16)
    return floatArrayOf(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f)
}
private fun mixC(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }
private fun lum(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

/** Alles, was der Himmel für einen Zeitpunkt braucht (aus echten Gebetszeiten + Wetter). */
private class SkyState(
    val top: FloatArray, val mid: FloatArray, val hor: FloatArray,
    val sunX: Float, val sunY: Float, val sunVis: Float, val sunCol: FloatArray, val sunLow: Float,
    val moonX: Float, val moonY: Float, val moonVis: Float, val phase: Float, val stars: Float,
    val cloud: Float, val rain: Float, val snow: Float, val fog: Float, val wind: Float,
    val lit: FloatArray, val shade: FloatArray, val night: Boolean, val dusk: Boolean, val thunder: Boolean
)

private fun skyState(now: Long, wx: Wx?): SkyState {
    val today = Times.today(now)
    val evs = Times.events(today, 1).filter { it.day == today }
    fun mins(k: Kind, def: Int): Float {
        val e = evs.firstOrNull { it.kind == k } ?: return def.toFloat()
        val z = java.time.Instant.ofEpochMilli(e.at).atZone(Times.zone())
        return (z.hour * 60 + z.minute).toFloat()
    }
    val fajr = mins(Kind.FAJR, 322); val sun = mins(Kind.SUNRISE, 410); val dhuhr = mins(Kind.DHUHR, 698)
    val asr = mins(Kind.ASR, 841); val magh = mins(Kind.MAGHRIB, 985); val isha = mins(Kind.ISHA, 1086)
    val z = java.time.Instant.ofEpochMilli(now).atZone(Times.zone())
    val m = z.hour * 60f + z.minute + z.second / 60f
    val stops = listOf(
        0f to listOf("#050913", "#0B1226", "#151B36"), fajr - 30 to listOf("#070D20", "#121B3C", "#232750"),
        fajr + 25 to listOf("#16224A", "#3A3F7A", "#B56F78"), sun to listOf("#2A4682", "#7A82B8", "#F3A774"),
        sun + 60 to listOf("#3769B6", "#79A6DE", "#F4D2A8"), sun + 150 to listOf("#2D72CC", "#6AAAE8", "#B7DCF8"),
        dhuhr to listOf("#2569C6", "#5EA2EA", "#A7D3FA"), asr to listOf("#2B67B8", "#78A8DD", "#E9D4A6"),
        magh - 35 to listOf("#36518F", "#B07A7E", "#F2A869"), magh to listOf("#2C2F68", "#8A4E78", "#EE8A5C"),
        magh + 35 to listOf("#171C45", "#3E2F62", "#7E4A62"), isha to listOf("#0B1229", "#16204A", "#2A2A55"),
        1440f to listOf("#050913", "#0B1226", "#151B36")
    ).sortedBy { it.first }
    var i = 0
    while (i < stops.size - 2 && m >= stops[i + 1].first) i++
    val (m0, c0) = stops[i]; val (m1, c1) = stops[i + 1]
    var tt = ((m - m0) / max(1f, m1 - m0)).coerceIn(0f, 1f); tt = tt * tt * (3 - 2 * tt)
    var cols = (0..2).map { mixC(hexC(c0[it]), hexC(c1[it]), tt) }
    val w = wx ?: Wx(0.2f, 0f, false, false, 0f, 0.25f, null, 0L)
    val grey = when {
        w.thunder && w.cloud > 0.6f -> 0.75f
        w.rain > 0.05f -> 0.6f
        w.fog > 0.5f -> 0.5f
        w.cloud > 0.5f -> 0.35f * w.cloud
        else -> 0f
    }
    val greyTo = if (w.thunder) listOf(hexC("#2B2F3D"), hexC("#4A4F60")) else if (w.fog > 0.5f) listOf(hexC("#9AA1AE"), hexC("#C3C8D1")) else listOf(hexC("#5B6273"), hexC("#8C93A3"))
    if (grey > 0f) cols = cols.mapIndexed { k, c -> mixC(c, if (k == 0) greyTo[0] else greyTo[1], grey) }
    val horizon = 0.12f
    val pSun = (m - (sun - 25)) / ((magh + 25) - (sun - 25))
    val sunUp = pSun > -0.02f && pSun < 1.02f
    val low = pSun < 0.14f || pSun > 0.86f
    val ps = pSun.coerceIn(0f, 1f)
    val sunX = 0.08f + ps * 0.84f
    val sunY = horizon + sin(ps * PI.toFloat()) * 0.62f - (if (sunUp) 0f else 0.2f)
    val nightLen = 1440 - (magh - sun)
    val mm = if (m >= magh) m - magh else if (m < sun) m + 1440 - magh else -1f
    val pMoon = if (mm < 0) -1f else mm / nightLen
    val moonUp = pMoon > 0.03f && pMoon < 0.97f
    val nightness = if (mm < 0) 0f else (min(mm, nightLen - mm) / 70f).coerceIn(0f, 1f)
    val dusk = abs(m - sun) < 55 || abs(m - magh) < 55
    val night = m > isha || m < fajr
    val syn = 29.530588853
    val age = (((now / 86400000.0 - 10962.5) % syn) + syn) % syn
    var lit = if (night) hexC("#56608A") else if (dusk) hexC("#FFE1CF") else hexC("#FFFFFF")
    var shade = if (night) hexC("#1A1F38") else if (dusk) hexC("#7A5F86") else hexC("#A9B6CC")
    if (w.rain > 0.05f || w.snow) { lit = mixC(lit, hexC("#B8BDC8"), 0.5f); shade = mixC(shade, hexC("#5A6070"), 0.6f) }
    if (w.thunder && w.cloud > 0.6f) { lit = hexC("#6B7183"); shade = hexC("#262A38") }
    val sunVis = if (!sunUp) 0f else when {
        w.rain > 0.05f || w.thunder -> 0.25f
        w.fog > 0.5f -> 0.45f
        w.cloud > 0.5f -> 0.75f
        else -> 1f
    }
    return SkyState(
        cols[0], cols[1], cols[2], sunX, sunY, sunVis,
        if (low) hexC("#FF9A5A") else hexC("#FFE6A8"),
        if (low) 1f else max(0f, 1f - min(abs(m - sun), abs(m - magh)) / 120f),
        0.14f + max(0f, pMoon) * 0.72f, horizon + 0.1f + sin(pMoon.coerceIn(0f, 1f) * PI.toFloat()) * 0.48f,
        if (moonUp) (if (w.cloud < 0.5f && w.rain < 0.05f) 1f else 0.4f) else 0f,
        (age / syn).toFloat(),
        if (w.rain < 0.05f && w.cloud < 0.6f) nightness else nightness * 0.3f,
        max(0.05f, w.cloud), if (w.snow) 0f else w.rain, if (w.snow) min(1f, w.rain * 1.6f + 0.2f) else 0f,
        w.fog, 0.4f + w.wind * 2.2f, lit, shade, night, dusk, w.thunder
    )
}

/** Blitz: zackiger Kanal mit Ästen, flackert 2–4 Mal. */
private class Strike(val paths: List<List<Offset>>, val x: Float, val y: Float, val t0: Long, val pulses: List<Pair<Long, Float>>) {
    fun intensity(now: Long): Float {
        val dt = now - t0
        if (dt > 900) return 0f
        var v = 0f
        for ((at, amp) in pulses) { val x = dt - at; if (x >= 0) v = max(v, amp * exp(-x / 70f)) }
        return v
    }
}

private fun makeStrike(w: Float, h: Float, now: Long): Strike {
    val rnd = java.util.Random()
    fun jag(a: Offset, b: Offset, rough: Float, depth: Int, out: MutableList<Offset>) {
        if (depth == 0) { out.add(b); return }
        val mid = Offset((a.x + b.x) / 2 + (rnd.nextFloat() - 0.5f) * rough, (a.y + b.y) / 2 + (rnd.nextFloat() - 0.5f) * rough * 0.25f)
        jag(a, mid, rough * 0.55f, depth - 1, out); jag(mid, b, rough * 0.55f, depth - 1, out)
    }
    val s = Offset(w * (0.18f + rnd.nextFloat() * 0.64f), h * (0.12f + rnd.nextFloat() * 0.12f))
    val e = Offset(s.x + (rnd.nextFloat() - 0.5f) * w * 0.35f, h * 0.9f)
    val paths = ArrayList<List<Offset>>()
    val sheet = rnd.nextFloat() < 0.3f
    if (!sheet) {
        val main = mutableListOf(s); jag(s, e, w * 0.22f, 7, main); paths.add(main)
        repeat(3 + rnd.nextInt(3)) {
            val a = main[rnd.nextInt((main.size * 0.75f).toInt().coerceAtLeast(1))]
            val len = h * (0.08f + rnd.nextFloat() * 0.16f)
            val ang = (if (rnd.nextBoolean()) -1 else 1) * (0.35f + rnd.nextFloat() * 0.7f)
            val b = Offset(a.x + kotlin.math.sin(ang) * len, a.y + kotlin.math.cos(ang) * len)
            val br = mutableListOf(a); jag(a, b, w * 0.06f, 5, br); paths.add(br)
        }
    }
    val pulses = ArrayList<Pair<Long, Float>>(); var t = 0L
    repeat(2 + rnd.nextInt(3)) { k -> pulses.add(t to (if (k == 0) 1f else 0.45f + rnd.nextFloat() * 0.5f)); t += 55 + rnd.nextInt(120) }
    return Strike(paths, s.x / w, 1f - s.y / h, now, pulses)
}

private val SKYLINE = "M0 120 V96 H18 V88 H30 V96 H48 V80 Q48 70 58 66 Q68 70 68 80 V96 H78 V58 L81 48 L84 58 V96 H96 V90 H120 V84 H134 V92 H150 V74 Q150 52 176 46 Q202 52 202 74 V92 H214 V40 L218 28 L222 40 V92 H236 V86 H254 V94 H272 V82 Q272 72 282 68 Q292 72 292 82 V94 H306 V88 H326 V96 H346 V86 H360 V96 H390 V120 Z"

/**
 * Kopfbereich der Startseite: lebender Himmel nach echtem Sonnenstand und Wetter,
 * darauf nächstes Gebet mit Countdown – immer lesbar.
 */
@Composable
fun SkyHeader(
    now: Long, nextTitle: String, countdown: String, atText: String, progress: Float,
    dateText: String, place: String, onSettings: () -> Unit, height: Dp = 380.dp
) {
    val ctx = LocalContext.current
    var wx by remember { mutableStateOf(Weather.current(ctx)) }
    LaunchedEffect(now / 600_000L) {
        val fresh = Prefs.lastGpsAt > System.currentTimeMillis() - 6 * 3600_000L && Prefs.lastGpsLat != 0.0
        Weather.refreshIfNeeded(ctx, if (fresh) Prefs.lastGpsLat else Prefs.lat, if (fresh) Prefs.lastGpsLng else Prefs.lng) {
            wx = Weather.current(ctx)
        }
    }
    val st = remember(now / 30_000L, wx) { skyState(now, wx) }
    val reduceMotion = remember {
        try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }
    }
    val time = remember { mutableFloatStateOf(0f) }
    val flash = remember { mutableFloatStateOf(0f) }
    var strike by remember { mutableStateOf<Strike?>(null) }
    var skySize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, reduceMotion, st.thunder) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var last = 0L
            var nextBolt = System.currentTimeMillis() + 2500
            while (true) {
                withFrameMillis { ms ->
                    if (ms - last >= 33) { // ~30 Bilder pro Sekunde reichen und schonen den Akku
                        last = ms
                        if (!reduceMotion) time.floatValue = ms / 1000f
                        val wall = System.currentTimeMillis()
                        if (st.thunder && wall > nextBolt && skySize.width > 0f) {
                            strike = makeStrike(skySize.width, skySize.height, wall)
                            nextBolt = wall + 2600 + (Math.random() * 6500).toLong()
                        }
                        flash.floatValue = strike?.intensity(wall) ?: 0f
                    }
                }
            }
        }
    }
    // Lesbarkeit: Helligkeit hinter dem Text abschätzen → Schrift weiß mit Schatten oder dunkel
    val heroLum = run {
        var l = max(lum(st.mid), lum(st.hor) * 0.6f)
        if (!st.night) l = l * (1 - st.cloud * 0.5f) + 0.85f * st.cloud * 0.5f
        val dx = st.sunX - 0.5f; val dy = st.sunY - 0.6f
        l += st.sunVis * exp(-sqrt(dx * dx + dy * dy) * 5f) * 0.6f
        l += st.fog * 0.25f + st.snow * 0.2f
        l.coerceIn(0f, 1f)
    }
    var darkText by remember { mutableStateOf(false) }
    darkText = if (darkText) heroLum > 0.56f else heroLum > 0.66f
    val fg = if (darkText) Color(0xFF14213D) else Color.White
    val shadowStrength = min(0.6f, 0.25f + max(0f, heroLum - 0.35f) * 1.1f)
    val shadow = if (darkText) Shadow(Color.White.copy(alpha = 0.55f), Offset(0f, 1f), 12f)
    else Shadow(Color.Black.copy(alpha = shadowStrength), Offset(0f, 2f), 16f)

    BoxWithConstraints(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(28.dp))) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        if (Build.VERSION.SDK_INT >= 33 && SkyShader.ok()) {
            ShaderSky(st, time, flash, strike, wPx, hPx)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(st.top[0], st.top[1], st.top[2]), Color(st.mid[0], st.mid[1], st.mid[2]), Color(st.hor[0], st.hor[1], st.hor[2])))))
        }
        // Blitzkanal
        Canvas(Modifier.fillMaxSize()) {
            skySize = this.size
            val s = strike ?: return@Canvas
            val v = flash.floatValue
            if (v < 0.03f || s.paths.isEmpty()) return@Canvas
            for ((k, pts) in s.paths.withIndex()) {
                val wgt = if (k == 0) 1f else 0.5f
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (q in pts.drop(1)) lineTo(q.x, q.y) }
                drawPath(path, Color(0xFFBECDFF).copy(alpha = 0.35f * v * wgt), style = Stroke(width = 14f * wgt, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, Color(0xFFDDE4FF).copy(alpha = 0.6f * v * wgt), style = Stroke(width = 6f * wgt, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, Color.White.copy(alpha = v * (0.6f + 0.4f * wgt)), style = Stroke(width = 2.4f * wgt + 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // Silhouette mit Kuppeln und Minaretten
        val sky = remember { PathParser().parsePathString(SKYLINE).toPath() }
        Canvas(Modifier.fillMaxSize()) {
            val sc = this.size.width / 390f
            val skyCol = if (st.night) Color(0xFF0A0F22) else if (st.dusk) Color(0xFF2A2340) else Color(0xFF24324F)
            val tr = androidx.compose.ui.graphics.Matrix().apply { translate(0f, this@Canvas.size.height - 120f * sc); scale(sc, sc) }
            val p2 = Path().apply { addPath(sky); transform(tr) }
            drawPath(p2, skyCol)
            if (st.night || st.dusk) {
                val wc = Color(0xFFFFD27A).copy(alpha = 0.85f + 0.15f * sin(time.floatValue * 1.3f))
                fun win(x: Float, y: Float) = drawCircle(wc, 1.8f * sc, Offset(x * sc, this.size.height - (120f - y) * sc))
                listOf(168f to 83f, 176f to 83f, 184f to 83f, 55f to 87f, 61f to 87f, 279f to 88f, 285f to 88f, 218f to 34f).forEach { (x, y) -> win(x, y) }
            }
        }
        // Text
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(dateText, color = fg.copy(alpha = 0.9f), style = TextStyle(fontSize = 13.sp, shadow = shadow), maxLines = 1)
                    val wtxt = listOfNotNull(place, wx?.temp?.let { "$it°" }, wx?.label()).joinToString(" · ")
                    Text(wtxt, color = fg, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, shadow = shadow), maxLines = 1)
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).tap(scale = 0.9f, onClick = onSettings),
                    contentAlignment = Alignment.Center
                ) { GlyphIcon("M4 7h10M18 7h2M4 17h4M12 17h8M16 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM10 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", fg, 20.dp) }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t("next_up").uppercase(), color = fg.copy(alpha = 0.9f), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, shadow = shadow))
                Text(nextTitle, color = fg, style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, shadow = shadow), maxLines = 1)
                Text(countdown, color = fg, style = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-2).sp, shadow = shadow), maxLines = 1)
                Text(atText, color = fg.copy(alpha = 0.92f), style = TextStyle(fontSize = 15.sp, shadow = shadow))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(180.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(fg.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(5.dp).clip(RoundedCornerShape(3.dp)).background(fg))
                }
            }
            Spacer(Modifier.weight(0.6f))
            if (wx != null) Text(t("weather_src"), color = Color.White.copy(alpha = 0.55f), style = TextStyle(fontSize = 9.sp), modifier = Modifier.align(Alignment.End))
        }
    }
}

@RequiresApi(33)
@Composable
private fun ShaderSky(
    st: SkyState, time: androidx.compose.runtime.MutableFloatState, flash: androidx.compose.runtime.MutableFloatState,
    strike: Strike?, wPx: Float, hPx: Float
) {
    val shader = remember { SkyShader.get()!! }
    val brush = remember(shader) { ShaderBrush(shader) }
    // Himmel in 45 % Auflösung rechnen und weich hochskalieren (spart viel Rechenleistung)
    val scale = 2.2f
    val density = LocalDensity.current
    Canvas(
        Modifier
            .size(with(density) { (wPx / scale).toDp() }, with(density) { (hPx / scale).toDp() })
            .graphicsLayer(scaleX = scale, scaleY = scale, transformOrigin = TransformOrigin(0f, 0f))
    ) {
        shader.setFloatUniform("uRes", size.width, size.height)
        shader.setFloatUniform("uTime", time.floatValue)
        shader.setFloatUniform("uTop", st.top[0], st.top[1], st.top[2])
        shader.setFloatUniform("uMid", st.mid[0], st.mid[1], st.mid[2])
        shader.setFloatUniform("uHor", st.hor[0], st.hor[1], st.hor[2])
        shader.setFloatUniform("uSun", st.sunX, st.sunY)
        shader.setFloatUniform("uSunVis", st.sunVis)
        shader.setFloatUniform("uSunCol", st.sunCol[0], st.sunCol[1], st.sunCol[2])
        shader.setFloatUniform("uSunLow", st.sunLow)
        shader.setFloatUniform("uMoon", st.moonX, st.moonY)
        shader.setFloatUniform("uMoonVis", st.moonVis)
        shader.setFloatUniform("uPhase", st.phase)
        shader.setFloatUniform("uStars", st.stars)
        shader.setFloatUniform("uCloud", st.cloud)
        shader.setFloatUniform("uRain", st.rain)
        shader.setFloatUniform("uSnow", st.snow)
        shader.setFloatUniform("uFog", st.fog)
        shader.setFloatUniform("uFlash", flash.floatValue)
        shader.setFloatUniform("uHorizon", 0.12f)
        shader.setFloatUniform("uFlashPos", strike?.x ?: 0.6f, strike?.y ?: 0.6f)
        shader.setFloatUniform("uWind", st.wind)
        shader.setFloatUniform("uCloudLit", st.lit[0], st.lit[1], st.lit[2])
        shader.setFloatUniform("uCloudShade", st.shade[0], st.shade[1], st.shade[2])
        drawRect(brush)
    }
}


/** Shader einmal erzeugen; schlägt das fehl (z. B. Grafiktreiber), wird ein einfacher Verlauf gezeigt. */
private object SkyShader {
    private var shader: Any? = null
    private var failed = false
    fun ok(): Boolean = get() != null
    @Suppress("UNCHECKED_CAST")
    fun get(): RuntimeShader? {
        if (Build.VERSION.SDK_INT < 33 || failed) return null
        shader?.let { return it as RuntimeShader }
        return try {
            RuntimeShader(SKY_AGSL).also { shader = it }
        } catch (e: Throwable) {
            failed = true
            null
        }
    }
}
