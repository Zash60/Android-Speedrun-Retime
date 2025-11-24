package com.example.speedruneditor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri

enum class ScreenState { INITIAL, EDITOR }

enum class TimerMode { RTA, LRT, BOTH }

data class LoadSegment(val startFrame: Int, val endFrame: Int)

data class UiState(
    val screenState: ScreenState = ScreenState.INITIAL,
    val isLoading: Boolean = false,
    val statusMessage: String = "Select a video to begin.",
    val renderProgress: Int = 0,
    val finalVideoPath: String? = null,
    val selectedVideoUri: Uri? = null,
    val videoProperties: VideoProperties? = null,
    
    // Frames e Navegação
    val currentFrame: Int = 0,
    val startFrame: Int = 0,
    val endFrame: Int = 0,
    val currentFrameBitmap: Bitmap? = null,

    // LRT (Load Removed Time)
    val loadSegments: List<LoadSegment> = emptyList(),
    val currentLoadStartFrame: Int? = null, // Se != null, estamos marcando um load agora
    
    // Estilo
    val timerMode: TimerMode = TimerMode.RTA,
    val timerPositionX: Float = 0.5f,
    val timerPositionY: Float = 0.8f,
    val timerSize: Int = 80,
    val timerColor: Int = Color.WHITE,
    val timerFormat: String = "MMSSmmm",
    val timerTypeface: Typeface = Typeface.DEFAULT_BOLD,
    val customFontName: String? = null,
    val outlineEnabled: Boolean = true,
    val outlineWidth: Int = 3,
    val outlineColor: Int = Color.BLACK
)
