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
import android.text.TextPaint
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(UnstableApi::class)
class EditorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var transformer: Transformer? = null
    private val isFetchingFrame = AtomicBoolean(false)
    private var progressJob: Job? = null

    // --- LRT LOGIC ---

    fun toggleLoadMark() {
        _uiState.update { state ->
            if (state.currentLoadStartFrame == null) {
                state.copy(currentLoadStartFrame = state.currentFrame, statusMessage = "Marking Load... Navigate to end.")
            } else {
                val start = state.currentLoadStartFrame
                val end = state.currentFrame
                val finalStart = minOf(start, end)
                val finalEnd = maxOf(start, end)
                val newSegment = LoadSegment(finalStart, finalEnd)
                
                state.copy(
                    loadSegments = state.loadSegments + newSegment,
                    currentLoadStartFrame = null,
                    statusMessage = "Load segment added."
                )
            }
        }
    }

    fun clearLastLoad() {
        _uiState.update { 
            if (it.loadSegments.isNotEmpty()) {
                it.copy(loadSegments = it.loadSegments.dropLast(1), statusMessage = "Removed last load segment.")
            } else it
        }
    }
    
    fun setTimerMode(mode: TimerMode) {
        _uiState.update { it.copy(timerMode = mode) }
    }

    // --- CALCULATION LOGIC ---

    private fun calculateTimes(state: UiState, currentFrame: Int): Pair<Double, Double> {
        val props = state.videoProperties ?: return 0.0 to 0.0
        val startFrame = state.startFrame
        val effectiveCurrentFrame = currentFrame.coerceIn(startFrame, state.endFrame)
        
        val rtaFrames = effectiveCurrentFrame - startFrame
        val rtaSeconds = rtaFrames / props.fps

        var totalLoadFrames = 0
        state.loadSegments.forEach { segment ->
            val overlapStart = maxOf(startFrame, segment.startFrame)
            val overlapEnd = minOf(effectiveCurrentFrame, segment.endFrame)
            if (overlapEnd > overlapStart) {
                totalLoadFrames += (overlapEnd - overlapStart)
            }
        }
        
        val lrtFrames = rtaFrames - totalLoadFrames
        val lrtSeconds = lrtFrames / props.fps
        
        return rtaSeconds to lrtSeconds
    }

    // --- DRAWING LOGIC ---

    private fun drawTimerOnCanvas(canvas: Canvas, rtaText: String, lrtText: String, state: UiState) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.timerTypeface
            textSize = state.timerSize.toFloat()
            textAlign = Paint.Align.CENTER
        }

        val x = canvas.width * state.timerPositionX
        var y = canvas.height * state.timerPositionY
        
        val fm = textPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent)
        
        val linesToDraw = mutableListOf<String>()
        if (state.timerMode == TimerMode.RTA || state.timerMode == TimerMode.BOTH) {
            linesToDraw.add(if(state.timerMode == TimerMode.BOTH) "RTA: $rtaText" else rtaText)
        }
        if (state.timerMode == TimerMode.LRT || state.timerMode == TimerMode.BOTH) {
            linesToDraw.add(if(state.timerMode == TimerMode.BOTH) "LRT: $lrtText" else lrtText)
        }

        val totalHeight = linesToDraw.size * lineHeight
        y -= totalHeight / 2f
        y += (lineHeight / 2f) - fm.descent

        for (line in linesToDraw) {
            if (state.outlineEnabled && state.outlineWidth > 0) {
                val outlinePaint = TextPaint(textPaint).apply {
                    color = state.outlineColor
                    style = Paint.Style.STROKE
                    strokeWidth = state.outlineWidth * 2f
                    strokeJoin = Paint.Join.ROUND
                }
                canvas.drawText(line, x, y, outlinePaint)
            }
            textPaint.color = state.timerColor
            textPaint.style = Paint.Style.FILL
            canvas.drawText(line, x, y, textPaint)
            y += lineHeight
        }
    }

    private fun drawTimerOnBitmap(sourceBitmap: Bitmap, currentFrame: Int, state: UiState): Bitmap {
        val mutableBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        sourceBitmap.recycle()
        val canvas = Canvas(mutableBitmap)
        
        val (rta, lrt) = calculateTimes(state, currentFrame)
        val rtaText = formatTime(rta, state.timerFormat)
        val lrtText = formatTime(lrt, state.timerFormat)
        
        drawTimerOnCanvas(canvas, rtaText, lrtText, state)
        
        if (state.currentLoadStartFrame != null) {
            val paint = Paint().apply { 
                color = Color.RED 
                style = Paint.Style.STROKE
                strokeWidth = 20f
            }
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
        }
        
        return mutableBitmap
    }

    private fun createTimerOverlay(): TextureOverlay {
        return object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val state = _uiState.value
                if (state.videoProperties == null) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

                val bitmap = Bitmap.createBitmap(state.videoProperties.width, state.videoProperties.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val currentFrame = (presentationTimeUs / 1_000_000.0 * state.videoProperties.fps).toInt()
                val (rta, lrt) = calculateTimes(state, currentFrame)
                drawTimerOnCanvas(canvas, formatTime(rta, state.timerFormat), formatTime(lrt, state.timerFormat), state)
                return bitmap
            }
        }
    }

    // --- STANDARD OPERATIONS ---

    fun loadVideoFromUri(uri: Uri, context: Context) {
        _uiState.update { it.copy(isLoading = true, statusMessage = "Analyzing...") }
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
                
                if (format == null) throw Exception("No video track")

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
                val totalFrames = (duration * fps).toInt()

                withContext(Dispatchers.Main) {
                    _uiState.update { 
                        it.copy(isLoading = false, screenState = ScreenState.EDITOR, selectedVideoUri = uri, videoProperties = props, endFrame = totalFrames)
                    }
                    navigateToFrame(0, context)
                }
            } catch (e: Exception) {
                Log.e("EditorViewModel", "Error loading", e)
                _uiState.update { it.copy(isLoading = false, statusMessage = "Error: ${e.message}") }
            } finally {
                extractor.release()
                retriever.release()
            }
        }
    }

    fun startRender(context: Context) {
        val state = _uiState.value
        if (state.selectedVideoUri == null || transformer != null) return
        _uiState.update { it.copy(isLoading = true, statusMessage = "Rendering...", renderProgress = 0) }

        val tempFile = File(context.externalCacheDir, "render_${System.currentTimeMillis()}.mp4")
        val mediaItem = MediaItem.fromUri(state.selectedVideoUri)
        val overlayEffect = OverlayEffect(ImmutableList.of(createTimerOverlay()))
        val videoEffects = ImmutableList.of<Effect>(overlayEffect)
        
        val editedMediaItem = EditedMediaItem.Builder(mediaItem).setEffects(Effects(emptyList(), videoEffects)).build()

        transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveToGallery(context, tempFile)
                    transformer = null
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Error: ${exportException.message}") }
                    transformer = null
                }
            })
            .build()
        transformer?.start(editedMediaItem, tempFile.absolutePath)
        
        progressJob = viewModelScope.launch {
            while (isActive && transformer != null) {
                val holder = ProgressHolder()
                if (transformer?.getProgress(holder) != Transformer.PROGRESS_STATE_NO_TRANSFORMATION) {
                    _uiState.update { it.copy(renderProgress = holder.progress) }
                }
                delay(500)
            }
        }
    }

    private fun saveToGallery(context: Context, sourceFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "render_${System.currentTimeMillis()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }
            val newVideoUri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), contentValues)
            
            if (newVideoUri != null) {
                resolver.openOutputStream(newVideoUri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Saved!", finalVideoPath = "Movies") }
                }
            }
            if (sourceFile.exists()) sourceFile.delete()
        }
    }

    // --- NAVIGATION HELPERS ---
    
    fun updateCurrentFrame(frame: Int, context: Context) = navigateToFrame(frame, context)
    
    fun navigateToFrame(newFrame: Int, context: Context) {
        val s = _uiState.value
        val max = s.videoProperties?.let { (it.duration * it.fps).toInt() } ?: 0
        val clamped = newFrame.coerceIn(0, max)
        _uiState.update { it.copy(currentFrame = clamped) }
        fetchFrameForPreview(clamped, context)
    }

    private fun fetchFrameForPreview(frame: Int, context: Context) {
        if (!isFetchingFrame.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                if (state.selectedVideoUri != null && state.videoProperties != null) {
                    val timeUs = (frame / state.videoProperties.fps * 1_000_000).toLong()
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(context, state.selectedVideoUri)
                        val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (rawBitmap != null) {
                            val finalBitmap = drawTimerOnBitmap(rawBitmap, frame, state)
                            withContext(Dispatchers.Main) {
                                _uiState.value.currentFrameBitmap?.recycle()
                                _uiState.update { it.copy(currentFrameBitmap = finalBitmap) }
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.e("VM", "Error", e) } 
            finally { isFetchingFrame.set(false) }
        }
    }

    fun navigateFrames(delta: Int, context: Context) = navigateToFrame(_uiState.value.currentFrame + delta, context)
    
    fun navigateSeconds(delta: Int, context: Context) {
        val fps = _uiState.value.videoProperties?.fps ?: 30.0
        navigateFrames((delta * fps).toInt(), context)
    }
    
    fun setStartFrame() { _uiState.update { it.copy(startFrame = it.currentFrame) } }
    fun setEndFrame() { _uiState.update { it.copy(endFrame = it.currentFrame) } }
    
    // Novas Funções
    fun goToStartFrame(context: Context) = navigateToFrame(_uiState.value.startFrame, context)
    fun goToEndFrame(context: Context) = navigateToFrame(_uiState.value.endFrame, context)
    
    fun updateTimerPositionX(x: Float, ctx: Context) { _uiState.update { it.copy(timerPositionX = x) }; refreshPreview(ctx) }
    fun updateTimerPositionY(y: Float, ctx: Context) { _uiState.update { it.copy(timerPositionY = y) }; refreshPreview(ctx) }
    fun setTimerSize(s: Int, ctx: Context) { _uiState.update { it.copy(timerSize = s) }; refreshPreview(ctx) }
    fun updateTimerColor(c: Int, ctx: Context) { _uiState.update { it.copy(timerColor = c) }; refreshPreview(ctx) }
    
    fun refreshPreview(ctx: Context) = fetchFrameForPreview(_uiState.value.currentFrame, ctx)
    
    private fun formatTime(seconds: Double, format: String): String {
        val s = maxOf(0.0, seconds)
        val ms = (s * 1000).toLong()
        return String.format(Locale.US, "%d:%02d.%03d", 
            TimeUnit.MILLISECONDS.toMinutes(ms), 
            TimeUnit.MILLISECONDS.toSeconds(ms) % 60, 
            ms % 1000)
    }
}
