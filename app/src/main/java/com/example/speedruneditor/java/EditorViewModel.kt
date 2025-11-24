package com.example.speedruneditor

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextPaint
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(UnstableApi::class)
class EditorViewModel : ViewModel() {

    private val _uiState = MutableLiveData(UiState())
    val uiState: LiveData<UiState> = _uiState

    private var transformer: Transformer? = null
    private val isFetchingFrame = AtomicBoolean(false)
    private var progressJob: Job? = null

    override fun onCleared() {
        super.onCleared()
        transformer?.cancel()
        progressJob?.cancel()
    }

    // Helper para atualizar estado usando copy
    private fun updateState(update: (UiState) -> UiState) {
        _uiState.value?.let { current ->
            _uiState.postValue(update(current))
        }
    }

    fun startRender(context: Context) {
        val state = _uiState.value ?: return
        if (state.selectedVideoUri == null || state.videoProperties == null || transformer != null) return

        updateState { it.copy(isLoading = true, statusMessage = "Preparing render...", renderProgress = 0) }

        val tempFileName = "render_${System.currentTimeMillis()}.mp4"
        val tempVideoFile = File(context.externalCacheDir, tempFileName)

        val mediaItem = MediaItem.fromUri(state.selectedVideoUri)
        val timerOverlay = createTimerOverlay()
        
        // Configuração dos efeitos Media3
        val effects = Effects(
            emptyList(),
            ImmutableList.of(OverlayEffect(ImmutableList.of(timerOverlay)))
        )
        
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .build()

        transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressJob?.cancel()
                    copyFileToPublicFolder(context, tempVideoFile)
                    transformer = null
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.e(TAG, "Transformer error", exportException)
                    if (tempVideoFile.exists()) tempVideoFile.delete()
                    progressJob?.cancel()
                    
                    updateState {
                        it.copy(isLoading = false, statusMessage = "Error: ${exportException.errorCodeName}")
                    }
                    transformer = null
                }
            })
            .build()

        transformer?.start(editedMediaItem, tempVideoFile.absolutePath)

        // Monitorar progresso com Coroutines
        progressJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive && transformer != null) {
                val progressHolder = ProgressHolder()
                if (transformer?.getProgress(progressHolder) != Transformer.PROGRESS_STATE_NO_TRANSFORMATION) {
                    updateState { 
                        it.copy(renderProgress = progressHolder.progress, statusMessage = "Rendering... ${progressHolder.progress}%") 
                    }
                }
                delay(500)
            }
        }
    }

    private fun copyFileToPublicFolder(context: Context, sourceFile: File) {
        updateState { it.copy(statusMessage = "Saving to Movies folder...") }

        viewModelScope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "render_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }

            val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val newVideoUri = resolver.insert(videoCollection, contentValues)
            var success = false

            if (newVideoUri != null) {
                try {
                    resolver.openOutputStream(newVideoUri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    success = true
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying file", e)
                }
            }

            if (sourceFile.exists()) sourceFile.delete()

            withContext(Dispatchers.Main) {
                if (success) {
                    updateState {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Render Complete!",
                            finalVideoPath = "Movies/${contentValues.get(MediaStore.MediaColumns.DISPLAY_NAME)}"
                        )
                    }
                } else {
                    updateState { it.copy(isLoading = false, statusMessage = "Error saving file.") }
                }
            }
        }
    }

    fun loadVideoFromUri(uri: Uri, context: Context) {
        updateState { it.copy(isLoading = true, statusMessage = "Analyzing video...") }

        viewModelScope.launch(Dispatchers.IO) {
            val extractor = MediaExtractor()
            val retriever = MediaMetadataRetriever()
            try {
                extractor.setDataSource(context, uri, null)
                retriever.setDataSource(context, uri)

                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(i)
                    val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true) {
                        format = trackFormat
                        break
                    }
                }

                if (format == null) throw Exception("No video track found.")

                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val duration = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0

                var fps = 30.0
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                } else {
                    val frameCountStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    if (frameCountStr != null && duration > 0) {
                        fps = frameCountStr.toInt() / duration
                    }
                }

                val props = VideoProperties(width, height, fps, duration)
                val totalFrames = (props.duration * props.fps).toInt()

                withContext(Dispatchers.Main) {
                    updateState {
                        it.copy(
                            isLoading = false,
                            screenState = ScreenState.EDITOR,
                            selectedVideoUri = uri,
                            videoProperties = props,
                            endFrame = totalFrames,
                            statusMessage = "Video loaded: ${props.width}x${props.height}"
                        )
                    }
                    navigateToFrame(0, context)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading video", e)
                withContext(Dispatchers.Main) {
                    updateState { it.copy(isLoading = false, statusMessage = "Error loading: ${e.message}") }
                }
            } finally {
                extractor.release()
                retriever.release()
            }
        }
    }

    fun loadCustomFont(uri: Uri, context: Context) {
        updateState { it.copy(statusMessage = "Importing font...") }
        viewModelScope.launch(Dispatchers.IO) {
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            val fileName = getFileNameFromUri(context, uri) ?: "custom_font_${System.currentTimeMillis()}"
            val fontFile = File(fontsDir, fileName)

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(fontFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val typeface = Typeface.createFromFile(fontFile)
                withContext(Dispatchers.Main) {
                    updateState { 
                        it.copy(timerTypeface = typeface, customFontName = fileName, statusMessage = "Font '$fileName' loaded.") 
                    }
                    refreshPreview(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading font", e)
                updateState { it.copy(statusMessage = "Error importing font.") }
            }
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    private fun fetchFrameForPreview(frame: Int, context: Context) {
        if (!isFetchingFrame.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                if (state?.selectedVideoUri != null && state.videoProperties != null) {
                    val timeUs = (frame / state.videoProperties.fps * 1_000_000).toLong()
                    
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(context, state.selectedVideoUri)
                        val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        
                        if (rawBitmap != null) {
                            val finalBitmap = drawTimerOnBitmap(rawBitmap, frame, state)
                            withContext(Dispatchers.Main) {
                                _uiState.value?.currentFrameBitmap?.recycle()
                                updateState { it.copy(currentFrameBitmap = finalBitmap) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preview", e)
            } finally {
                isFetchingFrame.set(false)
            }
        }
    }

    private fun drawTimerOnBitmap(sourceBitmap: Bitmap, currentFrame: Int, state: UiState): Bitmap {
        val mutableBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        sourceBitmap.recycle() // Reciclar original
        val canvas = Canvas(mutableBitmap)
        
        val elapsedSeconds = if (currentFrame < state.startFrame) 0.0 else calculateElapsedTime(state, currentFrame)
        val timerText = formatTime(elapsedSeconds, state.timerFormat)
        
        drawTimerOnCanvas(canvas, timerText, state)
        return mutableBitmap
    }

    private fun drawTimerOnCanvas(canvas: Canvas, timerText: String, state: UiState) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.timerTypeface
            textSize = state.timerSize.toFloat()
            textAlign = Paint.Align.CENTER
        }

        val x = canvas.width * state.timerPositionX
        var y = canvas.height * state.timerPositionY
        
        val fm = textPaint.fontMetrics
        y -= (fm.descent + fm.ascent) / 2f

        if (state.outlineEnabled && state.outlineWidth > 0) {
            val outlinePaint = TextPaint(textPaint).apply {
                color = state.outlineColor
                style = Paint.Style.STROKE
                strokeWidth = state.outlineWidth * 2f
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawText(timerText, x, y, outlinePaint)
        }

        textPaint.color = state.timerColor
        textPaint.style = Paint.Style.FILL
        canvas.drawText(timerText, x, y, textPaint)
    }

    private fun calculateElapsedTime(state: UiState, currentFrame: Int): Double {
        val props = state.videoProperties ?: return 0.0
        val startTime = state.startFrame / props.fps
        val targetFrame = if (currentFrame > state.endFrame) state.endFrame else currentFrame
        val currentTime = targetFrame / props.fps
        return currentTime - startTime
    }

    private fun formatTime(totalSeconds: Double, format: String): String {
        val s = if (totalSeconds < 0) 0.0 else totalSeconds
        val totalMillis = Math.round(s * 1000)
        val hours = TimeUnit.MILLISECONDS.toHours(totalMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(totalMillis) % 60
        val millis = totalMillis % 1000
        val centis = (totalMillis / 10) % 100

        return when (format) {
            "HHMMSSmmm" -> String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
            "SSmmm" -> String.format(Locale.US, "%.3f", s)
            "HHMMSS" -> String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            "MMSS" -> String.format(Locale.US, "%d:%02d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds)
            "MMSScc_pad" -> String.format(Locale.US, "%02d:%02d.%02d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, centis)
            "MMSScc" -> String.format(Locale.US, "%d:%02d.%02d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, centis)
            else -> String.format(Locale.US, "%d:%02d.%03d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, millis) // MMSSmmm
        }
    }

    private fun createTimerOverlay(): TextureOverlay {
        return object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val state = _uiState.value
                if (state?.videoProperties == null) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

                val bitmap = Bitmap.createBitmap(state.videoProperties.width, state.videoProperties.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val startSeconds = state.startFrame / state.videoProperties.fps
                val endSeconds = state.endFrame / state.videoProperties.fps
                val currentSeconds = presentationTimeUs / 1_000_000.0
                
                var elapsedSeconds = 0.0
                if (currentSeconds >= startSeconds) {
                    elapsedSeconds = Math.min(currentSeconds, endSeconds) - startSeconds
                }

                val timerText = formatTime(elapsedSeconds, state.timerFormat)
                drawTimerOnCanvas(canvas, timerText, state)
                return bitmap
            }
        }
    }

    // --- Navegação e Atualizações de UI ---

    fun updateCurrentFrame(frame: Int) {
        val s = _uiState.value ?: return
        val props = s.videoProperties ?: return
        val maxFrames = (props.duration * props.fps).toInt()
        val clamped = frame.coerceIn(0, if (maxFrames > 0) maxFrames - 1 else 0)
        
        if (s.currentFrame != clamped) {
            updateState { it.copy(currentFrame = clamped) }
        }
    }

    fun navigateToFrame(newFrame: Int, context: Context) {
        val s = _uiState.value ?: return
        val props = s.videoProperties ?: return
        val maxFrames = (props.duration * props.fps).toInt()
        val clamped = newFrame.coerceIn(0, if (maxFrames > 0) maxFrames - 1 else 0)

        updateState { it.copy(currentFrame = clamped) }
        fetchFrameForPreview(clamped, context)
    }

    fun navigateFrames(frameDelta: Int, context: Context) {
        val s = _uiState.value ?: return
        navigateToFrame(s.currentFrame + frameDelta, context)
    }

    fun navigateSeconds(secDelta: Int, context: Context) {
        val s = _uiState.value ?: return
        s.videoProperties?.let { props ->
            val frameDelta = (secDelta * props.fps).toInt()
            navigateToFrame(s.currentFrame + frameDelta, context)
        }
    }

    fun navigateMinutes(minDelta: Int, context: Context) {
        val s = _uiState.value ?: return
        s.videoProperties?.let { props ->
            val frameDelta = (minDelta * 60 * props.fps).toInt()
            navigateToFrame(s.currentFrame + frameDelta, context)
        }
    }

    fun setStartFrame() = updateState { it.copy(startFrame = it.currentFrame) }
    fun setEndFrame() = updateState { it.copy(endFrame = it.currentFrame) }
    fun goToStartFrame(ctx: Context) = _uiState.value?.let { navigateToFrame(it.startFrame, ctx) }
    fun goToEndFrame(ctx: Context) = _uiState.value?.let { navigateToFrame(it.endFrame, ctx) }
    fun refreshPreview(ctx: Context) = _uiState.value?.let { fetchFrameForPreview(it.currentFrame, ctx) }

    fun updateTimerPositionX(pos: Float) = updateState { it.copy(timerPositionX = pos) }
    fun updateTimerPositionY(pos: Float) = updateState { it.copy(timerPositionY = pos) }
    fun updateTimerFormat(fmt: String, ctx: Context) {
        updateState { it.copy(timerFormat = fmt) }
        refreshPreview(ctx)
    }
    fun updateTimerFont(tf: Typeface, ctx: Context) {
        updateState { it.copy(timerTypeface = tf, customFontName = null) }
        refreshPreview(ctx)
    }
    fun setTimerSize(size: Int) = updateState { it.copy(timerSize = size) }
    fun setOutlineWidth(w: Int) = updateState { it.copy(outlineWidth = w) }
    fun updateTimerColor(c: Int, ctx: Context) {
        updateState { it.copy(timerColor = c) }
        refreshPreview(ctx)
    }
    fun toggleOutline(enabled: Boolean, ctx: Context) {
        updateState { it.copy(outlineEnabled = enabled) }
        refreshPreview(ctx)
    }
    fun updateOutlineColor(c: Int, ctx: Context) {
        updateState { it.copy(outlineColor = c) }
        refreshPreview(ctx)
    }

    companion object {
        private const val TAG = "EditorViewModel"
    }
                        }
