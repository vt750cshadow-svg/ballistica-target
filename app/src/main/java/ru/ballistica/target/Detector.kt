package ru.ballistica.target

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Поиск НОВЫХ пробоин относительно запомненной базы.
 * Порт питоновского прототипа: выравнивание по сдвигу, подгонка яркости,
 * разность, отсечка того, что и в базе было тёмным, поиск связных пятен.
 */
object Detector {

    var mmPerPx = 0.5
    var minDmm = 2.0
    var maxDmm = 40.0
    var distanceM = 500.0
    var clickMm = 50.0
    var thrDark = 28
    var thrAbs = 140

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
        val shiftY: Int
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

    // ---------------------------------------------------------------- выравнивание

    private class Small(val w: Int, val h: Int, val a: FloatArray)

    private fun down4(g: Gray): Small {
        val sw = g.w / 4
        val sh = g.h / 4
        val out = FloatArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                var s = 0
                for (dy in 0..3) {
                    val row = (y * 4 + dy) * g.w
                    for (dx in 0..3) s += g.p[row + x * 4 + dx]
                }
                out[y * sw + x] = s / 16f
            }
        }
        return Small(sw, sh, out)
    }

    private fun shiftOf(base: Gray, cur: Gray): IntArray {
        val b = down4(base)
        val c = down4(cur)
        val w = minOf(b.w, c.w)
        val h = minOf(b.h, c.h)
        val y0 = h / 4
        val y1 = h * 3 / 4
        val x0 = w / 4
        val x1 = w * 3 / 4
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        for (dy in -6..6) {
            for (dx in -6..6) {
                var sum = 0L
                var n = 0
                var y = y0
                while (y < y1) {
                    val cy = y + dy
                    if (cy in 0 until h) {
                        var x = x0
                        while (x < x1) {
                            val cx = x + dx
                            if (cx in 0 until w) {
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

    private fun shifted(g: Gray, dx: Int, dy: Int): Gray {
        if (dx == 0 && dy == 0) return g
        val out = IntArray(g.w * g.h)
        for (y in 0 until g.h) {
            val sy = (y + dy).coerceIn(0, g.h - 1)
            val srow = sy * g.w
            val orow = y * g.w
            for (x in 0 until g.w) {
                out[orow + x] = g.p[srow + (x + dx).coerceIn(0, g.w - 1)]
            }
        }
        return Gray(g.w, g.h, out)
    }

    private fun medianBright(g: Gray): Float {
        val step = maxOf(1, g.p.size / 40000)
        val list = ArrayList<Int>(40000)
        var i = 0
        while (i < g.p.size) {
            val v = g.p[i]
            if (v > 100) list.add(v)
            i += step
        }
        if (list.isEmpty()) return 200f
        list.sort()
        return list[list.size / 2].toFloat()
    }

    // ---------------------------------------------------------------- морфология

    private fun dilate3(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                val y0 = maxOf(0, y - 1)
                val y1 = minOf(h - 1, y + 1)
                val x0 = maxOf(0, x - 1)
                val x1 = minOf(w - 1, x + 1)
                for (yy in y0..y1) for (xx in x0..x1) out[yy * w + xx] = true
            }
        }
        return out
    }

    private fun erode3(mask: BooleanArray, w: Int, h: Int): BooleanArray {
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

    private fun grow(mask: BooleanArray, w: Int, h: Int, times: Int): BooleanArray {
        var m = mask
        repeat(times) { m = dilate3(m, w, h) }
        return m
    }

    // ---------------------------------------------------------------- разбор

    fun analyze(base: Gray, curIn: Gray): Result {
        var cur = curIn
        if (cur.w != base.w || cur.h != base.h) {
            cur = resize(cur, base.w, base.h)
        }
        val sh = shiftOf(base, cur)
        cur = shifted(cur, sh[0], sh[1])

        val k = medianBright(base) / maxOf(medianBright(cur), 1f)

        val w = base.w
        val h = base.h
        val darkBase = BooleanArray(w * h) { base.p[it] < 90 }
        val baseDarkZone = grow(darkBase, w, h, 3)

        val mask = BooleanArray(w * h)
        for (i in 0 until w * h) {
            if (baseDarkZone[i]) continue
            val cv = (cur.p[i] * k).toInt().coerceIn(0, 255)
            if (cv >= thrAbs) continue
            if (base.p[i] - cv > thrDark) mask[i] = true
        }

        // края кадра не считаем — там артефакты сдвига
        val b = 8
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < b || y < b || x >= w - b || y >= h - b) mask[y * w + x] = false
            }
        }

        var m = erode3(mask, w, h)
        m = dilate3(m, w, h)

        // связные пятна
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
            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                sumX += x
                sumY += y
                cnt++
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        if (m[j] && label[j] == 0) {
                            label[j] = next
                            queue[tail++] = j
                        }
                    }
                }
            }
            val dmm = 2.0 * sqrt(cnt / PI) * mmPerPx
            if (dmm < minDmm || dmm > maxDmm) continue
            holes.add(Hole(sumX.toDouble() / cnt, sumY.toDouble() / cnt, cnt, dmm))
        }

        var group = 0.0
        for (i in holes.indices) {
            for (j in i + 1 until holes.size) {
                val dx = (holes[i].x - holes[j].x) * mmPerPx
                val dy = (holes[i].y - holes[j].y) * mmPerPx
                group = maxOf(group, sqrt(dx * dx + dy * dy))
            }
        }

        val cx = if (holes.isEmpty()) null else holes.map { it.x }.average()
        val cy = if (holes.isEmpty()) null else holes.map { it.y }.average()
        val offX = cx?.let { (it - base.w / 2.0) * mmPerPx }
        val offY = cy?.let { (base.h / 2.0 - it) * mmPerPx }
        val moa = if (group > 0) (group / 1000.0) / tan(PI / 180 / 60) / distanceM else null

        return Result(holes.size, holes, group, moa, offX, offY, sh[0], sh[1])
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
