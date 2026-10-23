package com.vyora.music.ui.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Software "liquid glass" for the lyrics card: a thick rounded slab drawn per pixel over whatever background
 * the card uses, so the refraction always follows that background (artwork, the user's photo, a gradient).
 *
 * Model (after Apple's Liquid Glass and Kyant's AndroidLiquidGlass lens shader):
 *  - the slab's shape is a rounded-rectangle signed distance field; its gradient is the surface normal;
 *  - inside a bevel band near the edge the surface curves like a circular lens, so the background is sampled
 *    displaced along the normal, more strongly toward the rim, and each color channel by a slightly different
 *    amount (chromatic dispersion), which gives the rainbow fringe;
 *  - the flat interior shows a frosted (blurred) copy of the background, lifted a little toward white;
 *  - a specular rim lit from the top-left (and a weaker bounce from the bottom-right), a hairline edge and a
 *    soft drop shadow finish it.
 * Everything here works on packed ARGB IntArrays so it runs off the main thread without a GPU.
 */
internal object LyricsGlass {

    data class Params(
        val radius: Float,
        val bevel: Float,
        val refraction: Float,
        val dispersion: Float,
        val tint: Float,
        val dim: Float,
    )

    /** Signed distance from (px, py) (relative to the card center) to a rounded rectangle; negative inside. */
    fun sdf(px: Float, py: Float, halfW: Float, halfH: Float, r: Float): Float {
        val qx = abs(px) - (halfW - r)
        val qy = abs(py) - (halfH - r)
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
    }

    /** Outward unit normal of the same rounded rectangle at (px, py). */
    fun normal(px: Float, py: Float, halfW: Float, halfH: Float, r: Float, out: FloatArray) {
        val qx = abs(px) - (halfW - r)
        val qy = abs(py) - (halfH - r)
        val sx = if (px < 0f) -1f else 1f
        val sy = if (py < 0f) -1f else 1f
        if (qx > 0f && qy > 0f) {
            val len = sqrt(qx * qx + qy * qy).coerceAtLeast(1e-4f)
            out[0] = qx / len * sx
            out[1] = qy / len * sy
        } else if (qx > qy) {
            out[0] = sx; out[1] = 0f
        } else {
            out[0] = 0f; out[1] = sy
        }
    }

    /** Box blur, [passes] times horizontally and vertically (three passes approximate a Gaussian). */
    fun blur(src: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3): IntArray {
        if (radius < 1) return src.copyOf()
        var a = src.copyOf()
        var b = IntArray(src.size)
        repeat(passes) {
            boxPass(a, b, w, h, radius, horizontal = true)
            boxPass(b, a, w, h, radius, horizontal = false)
        }
        return a
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            for (i in -r..r) {
                val c = at(i)
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (i in 0 until len) {
                val idx = if (horizontal) line * w + i else i * w + line
                dst[idx] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = at(i + r + 1)
                val sub = at(i - r)
                sa += (add ushr 24) - (sub ushr 24)
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    /** Bilinear sample of one channel ([shift] 16 = red, 8 = green, 0 = blue) at pixel coordinates (x, y). */
    fun channel(buf: IntArray, w: Int, h: Int, x: Float, y: Float, shift: Int): Float {
        val fx = (x - 0.5f).coerceIn(0f, (w - 1).toFloat())
        val fy = (y - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0; val ty = fy - y0
        val c00 = (buf[y0 * w + x0] shr shift) and 0xFF
        val c10 = (buf[y0 * w + x1] shr shift) and 0xFF
        val c01 = (buf[y1 * w + x0] shr shift) and 0xFF
        val c11 = (buf[y1 * w + x1] shr shift) and 0xFF
        val top = c00 + (c10 - c00) * tx
        val bottom = c01 + (c11 - c01) * tx
        return top + (bottom - top) * ty
    }

    /** Mean luminance (0..1) of [buf] inside the given rectangle, sampled sparsely. */
    fun meanLuma(buf: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): Float {
        var sum = 0.0
        var n = 0
        val step = 6
        var y = t.coerceIn(0, h - 1)
        while (y < b.coerceAtMost(h)) {
            var x = l.coerceIn(0, w - 1)
            while (x < r.coerceAtMost(w)) {
                val c = buf[y * w + x]
                sum += (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    /**
     * Draws the slab into [dst] (which already holds the background, [w] x [h]). [sharp] is the background,
     * [frost] its blurred copy at 1/[frostScale] resolution. The card is the rectangle l/t/r/b.
     */
    fun render(
        dst: IntArray, sharp: IntArray, w: Int, h: Int,
        frost: IntArray, fw: Int, fh: Int, frostScale: Float,
        l: Float, t: Float, r: Float, b: Float, p: Params,
    ) {
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val hw = (r - l) / 2f
        val hh = (b - t) / 2f
        val shadowY = p.radius * 0.22f
        val shadowSoft = p.radius * 0.95f
        val margin = (shadowSoft + shadowY + 4f).toInt()
        val x0 = (l.toInt() - margin).coerceAtLeast(0)
        val x1 = (r.toInt() + margin).coerceAtMost(w - 1)
        val y0 = (t.toInt() - margin).coerceAtLeast(0)
        val y1 = (b.toInt() + margin).coerceAtMost(h - 1)
        val n = FloatArray(2)
        // Key light from the top-left, bounce from the bottom-right.
        val lx = -0.62f; val ly = -0.78f
        val inv = 1f / frostScale
        for (y in y0..y1) {
            val py = y + 0.5f - cy
            for (x in x0..x1) {
                val px = x + 0.5f - cx
                val d = sdf(px, py, hw, hh, p.radius)
                val idx = y * w + x
                val base = dst[idx]
                // Soft shadow under the slab (only shows outside it).
                var br = ((base shr 16) and 0xFF).toFloat()
                var bg = ((base shr 8) and 0xFF).toFloat()
                var bb = (base and 0xFF).toFloat()
                if (d > -1f) {
                    val ds = sdf(px, py - shadowY, hw, hh, p.radius)
                    val s = 0.28f * (1f - smooth(-shadowSoft * 0.2f, shadowSoft, ds))
                    if (s > 0f) { br *= 1f - s; bg *= 1f - s; bb *= 1f - s }
                }
                val coverage = (0.5f - d).coerceIn(0f, 1f)
                if (coverage <= 0f) {
                    dst[idx] = (0xFF shl 24) or (clamp(br) shl 16) or (clamp(bg) shl 8) or clamp(bb)
                    continue
                }
                val depth = max(-d, 0f)
                normal(px, py, hw, hh, p.radius, n)
                var cr: Float; var cg: Float; var cb: Float
                val fxp = (x + 0.5f) * inv
                val fyp = (y + 0.5f) * inv
                if (depth < p.bevel) {
                    // e: 1 at the rim, 0 where the flat top starts. Circular lens profile.
                    val e = 1f - depth / p.bevel
                    val bend = 1f - sqrt((1f - e * e).coerceAtLeast(0f))
                    val disp = p.refraction * bend
                    val spread = p.dispersion * bend
                    // 0 where the bevel meets the flat top so the two blend without a seam.
                    val sharpMix = 0.9f * e * sqrt(e)
                    val dr = disp + spread
                    val db = (disp - spread).coerceAtLeast(0f)
                    cr = mix(
                        channel(frost, fw, fh, fxp + n[0] * dr * inv, fyp + n[1] * dr * inv, 16),
                        channel(sharp, w, h, x + 0.5f + n[0] * dr, y + 0.5f + n[1] * dr, 16), sharpMix,
                    )
                    cg = mix(
                        channel(frost, fw, fh, fxp + n[0] * disp * inv, fyp + n[1] * disp * inv, 8),
                        channel(sharp, w, h, x + 0.5f + n[0] * disp, y + 0.5f + n[1] * disp, 8), sharpMix,
                    )
                    cb = mix(
                        channel(frost, fw, fh, fxp + n[0] * db * inv, fyp + n[1] * db * inv, 0),
                        channel(sharp, w, h, x + 0.5f + n[0] * db, y + 0.5f + n[1] * db, 0), sharpMix,
                    )
                } else {
                    cr = channel(frost, fw, fh, fxp, fyp, 16)
                    cg = channel(frost, fw, fh, fxp, fyp, 8)
                    cb = channel(frost, fw, fh, fxp, fyp, 0)
                }
                // A touch more saturation (glass concentrates color), then lift toward white and dim if needed.
                val luma = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb
                cr = luma + (cr - luma) * 1.12f
                cg = luma + (cg - luma) * 1.12f
                cb = luma + (cb - luma) * 1.12f
                val keep = (1f - p.tint) * (1f - p.dim)
                val lift = 255f * p.tint * (1f - p.dim)
                cr = cr * keep + lift; cg = cg * keep + lift; cb = cb * keep + lift
                // Light: specular rim, bounce, hairline, broad bevel sheen and a faint top-down gradient.
                val facing = n[0] * lx + n[1] * ly
                val lit = max(facing, 0f).let { it * it }
                val key = lit * (0.85f * exp(-depth / 4.5f) + 0.22f * exp(-depth / 20f))
                val bounce = max(-facing, 0f).let { it * it } * (0.42f * exp(-depth / 4f) + 0.08f * exp(-depth / 16f))
                val hair = 0.34f * exp(-depth / 1.4f) + 0.06f * exp(-depth / 8f)
                val sheen = if (depth < p.bevel) 0.07f * (1f - depth / p.bevel).let { it * it * it } else 0f
                val innerShade = -0.02f * exp(-((depth - p.bevel * 0.85f) / 14f).let { it * it })
                val vertical = 0.035f * (1f - ((y - t) / (b - t)).coerceIn(0f, 1f))
                val light = (key + bounce + hair + sheen + innerShade + vertical) * 255f
                cr += light; cg += light; cb += light
                val ar = mix(br, cr, coverage)
                val ag = mix(bg, cg, coverage)
                val ab = mix(bb, cb, coverage)
                dst[idx] = (0xFF shl 24) or (clamp(ar) shl 16) or (clamp(ag) shl 8) or clamp(ab)
            }
        }
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun clamp(v: Float) = v.toInt().coerceIn(0, 255)
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
