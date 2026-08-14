package com.koyo.screenwarden

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Cheap local checks before a paid generation is admitted to an active pack
 *
 * This does not pretend to identify a face. It catches the failures that can be
 * measured reliably on-device: invalid output, missing alpha, empty cells,
 * opaque rectangles and undersized scene images. Identity remains protected by
 * the approved anchor and the user's explicit review
 */
object CompanionAssetQualityGate {
    data class Result(
        val accepted: Boolean,
        val score: Int,
        val reason: String? = null
    )

    fun inspect(bytes: ByteArray, spec: CompanionAssetSpec): Result {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return Result(false, 0, "生图结果无法解码")
        return try {
            inspect(bitmap, spec)
        } finally {
            bitmap.recycle()
        }
    }

    fun inspect(bitmap: Bitmap, spec: CompanionAssetSpec): Result {
        if (bitmap.width < 512 || bitmap.height < 512) {
            return Result(false, 0, "图片尺寸太小")
        }
        val grid = spec.frameGrid
        if (grid != null) {
            if (grid.columns <= 0 || grid.rows <= 0 ||
                bitmap.width / grid.columns < 220 || bitmap.height / grid.rows < 220
            ) {
                return Result(false, 0, "动作母版无法切成清晰帧")
            }
            val cellWidth = bitmap.width / grid.columns
            val cellHeight = bitmap.height / grid.rows
            var minimumScore = 100
            val signatures = mutableListOf<RegionSignature>()
            for (row in 0 until grid.rows) {
                for (column in 0 until grid.columns) {
                    val cellLeft = column * cellWidth
                    val cellTop = row * cellHeight
                    val cell = inspectRegion(
                        bitmap,
                        cellLeft,
                        cellTop,
                        cellWidth,
                        cellHeight,
                        expectTransparent = spec.transparentBackground
                    )
                    if (!cell.accepted) {
                        return Result(false, cell.score, "第${row * grid.columns + column + 1}帧${cell.reason}")
                    }
                    minimumScore = minOf(minimumScore, cell.score)
                    signatures += signature(bitmap, cellLeft, cellTop, cellWidth, cellHeight)
                }
            }
            val consistency = inspectConsistency(signatures)
            if (!consistency.accepted) return consistency
            return Result(true, minOf(minimumScore, consistency.score))
        }
        return inspectRegion(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            expectTransparent = spec.transparentBackground
        )
    }

    private fun inspectRegion(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        expectTransparent: Boolean
    ): Result {
        val columns = 24
        val rows = 24
        var transparent = 0
        var visible = 0
        var borderTransparent = 0
        var borderSamples = 0
        for (yIndex in 0 until rows) {
            val y = top + ((yIndex + .5f) / rows * height).toInt().coerceIn(0, height - 1)
            for (xIndex in 0 until columns) {
                val x = left + ((xIndex + .5f) / columns * width).toInt().coerceIn(0, width - 1)
                val alpha = bitmap.getPixel(
                    x.coerceIn(0, bitmap.width - 1),
                    y.coerceIn(0, bitmap.height - 1)
                ) ushr 24
                if (alpha < 32) transparent++
                if (alpha > 160) visible++
                val border = xIndex < 2 || xIndex >= columns - 2 || yIndex < 2 || yIndex >= rows - 2
                if (border) {
                    borderSamples++
                    if (alpha < 32) borderTransparent++
                }
            }
        }
        val samples = columns * rows
        val transparentRatio = transparent.toFloat() / samples
        val visibleRatio = visible.toFloat() / samples
        val borderClearRatio = borderTransparent.toFloat() / borderSamples.coerceAtLeast(1)
        if (expectTransparent) {
            if (transparentRatio < .05f || borderClearRatio < .20f) {
                return Result(false, 25, "缺少透明背景")
            }
            if (visibleRatio < .015f) return Result(false, 10, "几乎没有角色内容")
            if (visibleRatio > .82f) return Result(false, 35, "角色或背景挤满画面")
            val score = (70f + borderClearRatio * 20f +
                (1f - kotlin.math.abs(visibleRatio - .36f)) * 10f).toInt().coerceIn(0, 100)
            return Result(true, score)
        }
        if (visibleRatio < .97f) return Result(false, 45, "全屏场景存在透明缺口")
        return Result(true, 100)
    }

    private data class RegionSignature(
        val visibleRatio: Float,
        val red: Float,
        val green: Float,
        val blue: Float
    )

    private fun signature(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ): RegionSignature {
        val columns = 20
        val rows = 20
        var visible = 0
        var red = 0L
        var green = 0L
        var blue = 0L
        for (yIndex in 0 until rows) {
            val y = top + ((yIndex + .5f) / rows * height).toInt().coerceIn(0, height - 1)
            for (xIndex in 0 until columns) {
                val x = left + ((xIndex + .5f) / columns * width).toInt().coerceIn(0, width - 1)
                val pixel = bitmap.getPixel(
                    x.coerceIn(0, bitmap.width - 1),
                    y.coerceIn(0, bitmap.height - 1)
                )
                if (pixel ushr 24 > 160) {
                    visible++
                    red += pixel shr 16 and 0xFF
                    green += pixel shr 8 and 0xFF
                    blue += pixel and 0xFF
                }
            }
        }
        val divisor = visible.coerceAtLeast(1).toFloat()
        return RegionSignature(
            visibleRatio = visible.toFloat() / (columns * rows),
            red = red / divisor,
            green = green / divisor,
            blue = blue / divisor
        )
    }

    private fun inspectConsistency(signatures: List<RegionSignature>): Result {
        if (signatures.size < 2) return Result(true, 100)
        val minVisible = signatures.minOf { it.visibleRatio }.coerceAtLeast(.001f)
        val maxVisible = signatures.maxOf { it.visibleRatio }
        if (maxVisible / minVisible > 2.8f || maxVisible - minVisible > .42f) {
            return Result(false, 48, "动作帧之间角色大小漂移过大")
        }
        var maximumColorDistance = 0f
        for (left in signatures.indices) {
            for (right in left + 1 until signatures.size) {
                val a = signatures[left]
                val b = signatures[right]
                val distance = kotlin.math.sqrt(
                    (a.red - b.red) * (a.red - b.red) +
                        (a.green - b.green) * (a.green - b.green) +
                        (a.blue - b.blue) * (a.blue - b.blue)
                )
                maximumColorDistance = maxOf(maximumColorDistance, distance)
            }
        }
        if (maximumColorDistance > 125f) {
            return Result(false, 52, "动作帧之间服装或配色漂移过大")
        }
        val score = (100f - maximumColorDistance * .16f).toInt().coerceIn(70, 100)
        return Result(true, score)
    }
}
