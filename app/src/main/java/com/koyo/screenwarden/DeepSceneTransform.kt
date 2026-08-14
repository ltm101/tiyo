package com.koyo.screenwarden

import kotlin.math.max

internal object DeepSceneTransform {
    enum class Mode { COVER, FIT_WIDTH }
    data class Result(val scale: Float, val offsetX: Float, val offsetY: Float)
    data class SourceSize(val width: Float, val height: Float)

    fun mode(scene: DeepCompanionHostView.Scene): Mode = Mode.COVER

    fun sourceSize(scene: DeepCompanionHostView.Scene): SourceSize = when (scene) {
        DeepCompanionHostView.Scene.DESK -> SourceSize(842f, 1868f)
        DeepCompanionHostView.Scene.SHELF -> SourceSize(845f, 1860f)
        DeepCompanionHostView.Scene.DIARY -> SourceSize(841f, 1870f)
        DeepCompanionHostView.Scene.ROOM -> SourceSize(1024f, 1536f)
    }

    fun calculate(viewWidth: Float, viewHeight: Float, sourceWidth: Float, sourceHeight: Float, mode: Mode): Result {
        val scale = when (mode) {
            Mode.COVER -> max(viewWidth / sourceWidth, viewHeight / sourceHeight) * 1.006f
            Mode.FIT_WIDTH -> viewWidth / sourceWidth
        }
        return Result(
            scale,
            (viewWidth - sourceWidth * scale) / 2f,
            (viewHeight - sourceHeight * scale) / 2f
        )
    }
}
