package com.example.speedruneditor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri

// Convertido para Data Class. O método copy() substitui o Builder pattern antigo.
data class UiState(
    val screenState: ScreenState = ScreenState.INITIAL,
    val isLoading: Boolean = false,
    val statusMessage: String = "Select a video from your device.",
    val renderProgress: Int = 0,
    val finalVideoPath: String? = null,
    val selectedVideoUri: Uri? = null,
    val videoProperties: VideoProperties? = null,
    val currentFrame: Int = 0,
    val startFrame: Int = 0,
    val endFrame: Int = 0,
    val currentFrameBitmap: Bitmap? = null,
    
    // Timer Style
    val timerPositionX: Float = 0.86f,
    val timerPositionY: Float = 0.95f,
    val timerSize: Int = 80,
    val timerColor: Int = Color.WHITE,
    val timerFormat: String = "MMSSmmm",
    val timerTypeface: Typeface = Typeface.DEFAULT_BOLD,
    val customFontName: String? = null,
    
    // Outline
    val outlineEnabled: Boolean = true,
    val outlineWidth: Int = 3,
    val outlineColor: Int = Color.BLACK
)
