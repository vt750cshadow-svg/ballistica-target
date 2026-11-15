package ru.ballistica.target

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Поиск НОВЫХ пробоин относительно запомненной базы.
 *
 * Порядок: точное выравнивание по сдвигу, лёгкое размытие (убирает шум матрицы
 * и ореолы от резкости), подгонка яркости, разность, отсечка краёв и того, что
 * и в базе было тёмным, поиск компактных пятен.
 */
object Detector {

    var mmPerPx = 0.5
    var minDmm = 2.0
    var maxDmm = 40.0
    var distanceM = 500.0
    var clickMm = 50.0
    var thrDark = 40
    var thrAbs = 130
    var minAreaPx = 30

    class Gray(val w: Int, val h: Int, val p: IntArray)

    data class Hole(val x: Double, val y: Double, val area: Int, val dmm: Double)

    data class Result(
        val count: Int,
        val holes: List<Hole>,
        val groupMm: Double,
        val moa: Double?,
        val offX: Double?,
        val offY: Double?,
        val shiftX: Int,
        val shiftY: Int,
        val cost: Int
    )

    fun gray(bmp: Bitmap): Gray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val off = y * w
            for (x in 0 until w) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                px[off + x] = (r * 299 + g * 587 + b * 114) / 1000
            }
        }
        return Gray(w, h, px)
    }

    // ---------------------------------------------------------------- размытие

    private fun blur(g: Gray): IntArray {
        val w = g.w
        val h = g.h
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = if (x > 0) g.p[row + x - 1] else g.p[row + x]
                val x1 = g.p[row + x]
                val x2 = if (x < w - 1) g.p[row + x + 1] else g.p[row + x]
                tmp[row + x] = (x0 + 2 * x1 + x2) / 4
            }
        }
        for (y in 0 until h) {
            val row = y * w
            val up = if (y > 0) row - w else row
            val dn = if (y < h - 1) row + w else row
            for (x in 0 until w) {
                out[row + x] = (tmp[up + x] + 2 * tmp[row + x] + tmp[dn + x]) / 4
            }
        }
        return out
    }

    // ---------------------------------------------------------------- выравнивание

    private class Small(val w: Int, val h: Int, val a: FloatArray)

    private fun down4(p: IntArray, w: Int, h: Int): Small {
        val sw = w / 4
        val sh = h / 4
        val out = FloatArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                var s = 0
                for (dy in 0..3) {
                    val row = (y * 4 + dy) * w
                    for (dx in 0..3) s += p[row + x * 4 + dx]
                }
                out[y * sw + x] = s / 16f
            }
        }
        return Small(sw, sh, out)
    }

    private fun coarseShift(bp: IntArray, cp: IntArray, w: Int, h: Int): IntArray {
        val b = down4(bp, w, h)
        val c = down4(cp, w, h)
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        val y0 = b.h / 4
        val y1 = b.h * 3 / 4
        val x0 = b.w / 4
        val x1 = b.w * 3 / 4
        for (dy in -6..6) {
            for (dx in -6..6) {
                var sum = 0L
                var n = 0
                var y = y0
                while (y < y1) {
                    val cy = y + dy
                    if (cy in 0 until b.h) {
                        var x = x0
                        while (x < x1) {
                            val cx = x + dx
                            if (cx in 0 until b.w) {
                                sum += abs(b.a[y * b.w + x] - c.a[cy * c.w + cx]).toLong()
                                n++
                            }
                            x++
                        }
                    }
                    y++
                }
                if (n > 1000) {
                    val d = sum / n
                    if (d < best) {
                        best = d
                        bx = dx
                        by = dy
                    }
                }
            }
        }
        return intArrayOf(bx * 4, by * 4)
    }

    /** Уточнение сдвига до одного пикселя. */
    private fun refineShift(bp: IntArray, cp: IntArray, w: Int, h: Int, cx: Int, cy: Int): IntArray {
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in -4..4) {
            for (dx in -4..4) {
                val sx = cx + dx
                val sy = cy + dy
                var sum = 0L
                var n = 0
                var y = h / 4
                while (y < h * 3 / 4) {
                    val yy = y + sy
                    if (yy in 1 until h - 1) {
                        var x = w / 4
                        while (x < w * 3 / 4) {
                            val xx = x + sx
                            if (xx in 1 until w - 1) {
                                sum += abs(bp[y * w + x] - cp[yy * w + xx]).toLong()
                                n++
                            }
                            x += 3
                        }
                    }
                    y += 3
                }
                if (n > 500) {
                    val d = sum / n
                    if (d < best) {
                        best = d
                        bx = sx
                        by = sy
                    }
                }
            }
        }
        return intArrayOf(bx, by)
    }

    private fun shifted(g: IntArray, w: Int, h: Int, dx: Int, dy: Int): IntArray {
        if (dx == 0 && dy == 0) return g
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y + dy).coerceIn(0, h - 1)
            val srow = sy * w
            val orow = y * w
            for (x in 0 until w) {
                out[orow + x] = g[srow + (x + dx).coerceIn(0, w - 1)]
            }
        }
        return out
    }

    private fun medianBright(p: IntArray): Float {
        val step = maxOf(1, p.size / 40000)
        val list = ArrayList<Int>(40000)
        var i = 0
        while (i < p.size) {
            val v = p[i]
            if (v > 100) list.add(v)
            i += step
        }
        if (list.isEmpty()) return 200f
        list.sort()
        return list[list.size / 2].toFloat()
    }

    // ---------------------------------------------------------------- морфология

    private fun dilate(mask: BooleanArray, w: Int, h: Int, times: Int): BooleanArray {
        var cur = mask
        repeat(times) {
            val out = BooleanArray(cur.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (!cur[y * w + x]) continue
                    val y0 = maxOf(0, y - 1)
                    val y1 = minOf(h - 1, y + 1)
                    val x0 = maxOf(0, x - 1)
                    val x1 = minOf(w - 1, x + 1)
                    for (yy in y0..y1) for (xx in x0..x1) out[yy * w + xx] = true
                }
            }
            cur = out
        }
        return cur
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                var alive = true
                for (dy in -1..1) {
                    if (!alive) break
                    val yy = y + dy
                    if (yy < 0 || yy >= h) { alive = false; break }
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w || !mask[yy * w + xx]) { alive = false; break }
                    }
                }
                out[y * w + x] = alive
            }
        }
        return out
    }

    // ---------------------------------------------------------------- разбор

    fun analyze(base: Gray, curIn: Gray): Result {
        var cur = curIn
        if (cur.w != base.w || cur.h != base.h) cur = resize(cur, base.w, base.h)

        val bb = blur(base)
        val cb = blur(cur)

        var sh = coarseShift(bb, cb, base.w, base.h)
        sh = refineShift(bb, cb, base.w, base.h, sh[0], sh[1])
        val dx = sh[0]
        val dy = sh[1]
        val moved = shifted(cb, base.w, base.h, dx, dy)

        val k = medianBright(bb) / maxOf(medianBright(moved), 1f)
        val w = base.w
        val h = base.h

        // края мишени: там всегда будут расхождения при любом шевелении
        val edge = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = abs(bb[i + 1] - bb[i - 1])
                val gy = abs(bb[i + w] - bb[i - w])
                if (gx > 14 || gy > 14) edge[i] = true
            }
        }
        val edgeZone = dilate(edge, w, h, 2)
        val darkZone = dilate(BooleanArray(w * h) { bb[it] < 80 }, w, h, 3)

        val mask = BooleanArray(w * h)
        for (y in 8 until h - 8) {
            val row = y * w
            for (x in 8 until w - 8) {
                val i = row + x
                if (edgeZone[i] || darkZone[i]) continue
                val cv = (moved[i] * k).toInt().coerceIn(0, 255)
                if (cv >= thrAbs) continue
                if (bb[i] - cv > thrDark) mask[i] = true
            }
        }

        var m = erode(mask, w, h)
        m = dilate(m, w, h, 2)

        val label = IntArray(w * h)
        val holes = ArrayList<Hole>()
        val queue = IntArray(w * h)
        var next = 0
        for (start in 0 until w * h) {
            if (!m[start] || label[start] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            var sumX = 0L
            var sumY = 0L
            var cnt = 0
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                sumX += x
                sumY += y
                cnt++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dyy in -1..1) {
                    val yy = y + dyy
                    if (yy < 0 || yy >= h) continue
                    for (dxx in -1..1) {
                        val xx = x + dxx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        if (m[j] && label[j] == 0) {
                            label[j] = next
                            queue[tail++] = j
                        }
                    }
                }
            }
            if (cnt < minAreaPx) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val fill = cnt.toDouble() / (bw * bh)
            if (fill < 0.35) continue
            if (bw < 4 || bh < 4) continue
            val aspect = maxOf(bw, bh).toDouble() / minOf(bw, bh)
            if (aspect > 2.5) continue
            val dmm = 2.0 * sqrt(cnt / PI) * mmPerPx
            if (dmm < minDmm || dmm > maxDmm) continue
            holes.add(Hole(sumX.toDouble() / cnt, sumY.toDouble() / cnt, cnt, dmm))
        }

        var group = 0.0
        for (i in holes.indices) {
            for (j in i + 1 until holes.size) {
                val gx = (holes[i].x - holes[j].x) * mmPerPx
                val gy = (holes[i].y - holes[j].y) * mmPerPx
                group = maxOf(group, sqrt(gx * gx + gy * gy))
            }
        }

        val cx = if (holes.isEmpty()) null else holes.map { it.x }.average()
        val cy = if (holes.isEmpty()) null else holes.map { it.y }.average()
        val offX = cx?.let { (it - base.w / 2.0) * mmPerPx }
        val offY = cy?.let { (base.h / 2.0 - it) * mmPerPx }
        val moa = if (group > 0) (group / 1000.0) / tan(PI / 180 / 60) / distanceM else null

        return Result(holes.size, holes, group, moa, offX, offY, dx, dy, 0)
    }

    private fun resize(g: Gray, nw: Int, nh: Int): Gray {
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val sy = (y.toLong() * g.h / nh).toInt().coerceIn(0, g.h - 1)
            for (x in 0 until nw) {
                val sx = (x.toLong() * g.w / nw).toInt().coerceIn(0, g.w - 1)
                out[y * nw + x] = g.p[sy * g.w + sx]
            }
        }
        return Gray(nw, nh, out)
    }

    fun medianOf(list: List<Gray>): Gray {
        val w = list[0].w
        val h = list[0].h
        val out = IntArray(w * h)
        val vals = IntArray(list.size)
        for (i in 0 until w * h) {
            for (j in list.indices) vals[j] = list[j].p[i]
            vals.sort()
            out[i] = vals[vals.size / 2]
        }
        return Gray(w, h, out)
    }
}
