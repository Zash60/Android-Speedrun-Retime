package com.example.speedruneditor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.example.speedruneditor.databinding.ActivityMainBinding
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

@UnstableApi
class MainActivity : AppCompatActivity() {

    private val viewModel: EditorViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    // Mapas simples
    private val timerFormatMap = linkedMapOf(
        "M:SS.mmm" to "MMSSmmm",
        "H:MM:SS.mmm" to "HHMMSSmmm",
        "S.mmm" to "SSmmm",
        "H:MM:SS" to "HHMMSS",
        "M:SS" to "MMSS",
        "MM:SS.cc" to "MMSScc_pad",
        "M:SS.cc" to "MMSScc"
    )

    private val fontMap = linkedMapOf(
        "Default (Bold)" to Typeface.DEFAULT_BOLD,
        "Monospace (Bold)" to Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
        "Serif (Bold)" to Typeface.create(Typeface.SERIF, Typeface.BOLD)
    )

    // Debounce usando Coroutine Job
    private var debounceJob: Job? = null
    private val debounceDelay = 200L

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.loadVideoFromUri(it, this) }
    }

    private val fontPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.loadCustomFont(it, this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTimerFormatSpinner()
        setupFontSpinner()
        setupColorPickers()
        setupObservers()
        setupListeners()
        requestStoragePermission()
    }

    private fun setupObservers() {
        viewModel.uiState.observe(this) { state ->
            // Visibilidade das Telas
            binding.initialScreenLayout.isVisible = state.screenState == ScreenState.INITIAL
            binding.editorScreenLayout.isVisible = state.screenState == ScreenState.EDITOR

            // Status e Loading
            binding.textStatus.text = state.statusMessage
            binding.btnRender.isEnabled = !state.isLoading
            binding.btnSelectVideo.isEnabled = !state.isLoading
            
            binding.progressBar.isVisible = state.isLoading
            if (state.isLoading) {
                binding.progressBar.isIndeterminate = state.screenState != ScreenState.EDITOR
                binding.progressBar.progress = state.renderProgress
            }

            binding.textFinalPath.isVisible = state.finalVideoPath != null
            state.finalVideoPath?.let { binding.textFinalPath.text = "Saved to: $it" }

            // Atualização da Interface do Editor
            if (state.screenState == ScreenState.EDITOR && state.videoProperties != null) {
                binding.textVideoInfo.text = String.format(Locale.US, "Info: %dx%d @ %.2ffps",
                    state.videoProperties.width, state.videoProperties.height, state.videoProperties.fps)

                binding.imageViewPreview.setImageBitmap(state.currentFrameBitmap)

                val maxFrames = (state.videoProperties.duration * state.videoProperties.fps).toInt()
                if (binding.seekBarFrame.max != maxFrames) {
                    binding.seekBarFrame.max = maxFrames
                }

                if (!binding.seekBarFrame.isPressed) {
                    binding.seekBarFrame.progress = state.currentFrame
                }

                val currentTime = state.currentFrame / state.videoProperties.fps
                val startTime = state.startFrame / state.videoProperties.fps
                val endTime = state.endFrame / state.videoProperties.fps
                
                binding.textTimeLabel.text = """
                    Time: ${formatTime(currentTime, state.timerFormat)} | Frame: ${state.currentFrame}
                    Start: ${formatTime(startTime, state.timerFormat)} (f:${state.startFrame}) | End: ${formatTime(endTime, state.timerFormat)} (f:${state.endFrame})
                """.trimIndent()

                // Atualizar Sliders apenas se não estiverem sendo arrastados
                updateSliderIfNotPressed(binding.seekBarPositionX, (state.timerPositionX * 100).toInt(), binding.textPositionXLabel, "Position X")
                updateSliderIfNotPressed(binding.seekBarPositionY, (state.timerPositionY * 100).toInt(), binding.textPositionYLabel, "Position Y")
                updateSliderIfNotPressed(binding.seekBarTimerSize, state.timerSize, binding.textTimerSizeLabel, "Size")
                updateSliderIfNotPressed(binding.seekBarOutlineWidth, state.outlineWidth, binding.textOutlineWidthLabel, "Outline Width")

                binding.textCustomFontName.isVisible = state.customFontName != null
                state.customFontName?.let { binding.textCustomFontName.text = "Font: $it" }

                binding.switchOutline.isChecked = state.outlineEnabled
                binding.outlineControls.isVisible = state.outlineEnabled
            }
        }
    }

    private fun updateSliderIfNotPressed(seekBar: SeekBar, value: Int, label: TextView, labelPrefix: String) {
        label.text = "$labelPrefix: $value${if (labelPrefix.contains("Position")) "%" else ""}"
        if (!seekBar.isPressed) {
            seekBar.progress = value
        }
    }

    private fun setupListeners() {
        binding.btnSelectVideo.setOnClickListener { videoPickerLauncher.launch("video/*") }
        binding.btnRender.setOnClickListener { viewModel.startRender(this) }
        binding.btnSelectFontFile.setOnClickListener { fontPickerLauncher.launch("*/*") }

        // Painel de Navegação
        with(binding.navPanel) {
            btnSetStart.setOnClickListener { viewModel.setStartFrame() }
            btnSetEnd.setOnClickListener { viewModel.setEndFrame() }
            btnGoToStart.setOnClickListener { viewModel.goToStartFrame(this@MainActivity) }
            btnGoToEnd.setOnClickListener { viewModel.goToEndFrame(this@MainActivity) }
            
            // Botões de navegação agrupados
            val frameMap = mapOf(
                btnFrameMinus10 to -10, btnFrameMinus5 to -5, btnFrameMinus1 to -1,
                btnFramePlus1 to 1, btnFramePlus5 to 5, btnFramePlus10 to 10
            )
            frameMap.forEach { (btn, val) -> btn.setOnClickListener { viewModel.navigateFrames(val, this@MainActivity) } }

            val secMap = mapOf(
                btnSecMinus10 to -10, btnSecMinus5 to -5, btnSecMinus1 to -1,
                btnSecPlus1 to 1, btnSecPlus5 to 5, btnSecPlus10 to 10
            )
            secMap.forEach { (btn, val) -> btn.setOnClickListener { viewModel.navigateSeconds(val, this@MainActivity) } }
            
            val minMap = mapOf(
                btnMinMinus10 to -10, btnMinMinus5 to -5, btnMinMinus1 to -1,
                btnMinPlus1 to 1, btnMinPlus5 to 5, btnMinPlus10 to 10
            )
            minMap.forEach { (btn, val) -> btn.setOnClickListener { viewModel.navigateMinutes(val, this@MainActivity) } }
        }

        // Seekbar Principal
        binding.seekBarFrame.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.updateCurrentFrame(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                viewModel.navigateToFrame(seekBar.progress, this@MainActivity)
            }
        })

        // Listener genérico com Debounce
        val debouncedListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                
                when (seekBar.id) {
                    binding.seekBarPositionX.id -> viewModel.updateTimerPositionX(progress / 100f)
                    binding.seekBarPositionY.id -> viewModel.updateTimerPositionY(progress / 100f)
                    binding.seekBarTimerSize.id -> viewModel.setTimerSize(progress)
                    binding.seekBarOutlineWidth.id -> viewModel.setOutlineWidth(progress)
                }

                debounceJob?.cancel()
                debounceJob = lifecycleScope.launch {
                    delay(debounceDelay)
                    viewModel.refreshPreview(this@MainActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                debounceJob?.cancel()
                viewModel.refreshPreview(this@MainActivity)
            }
        }

        binding.seekBarPositionX.setOnSeekBarChangeListener(debouncedListener)
        binding.seekBarPositionY.setOnSeekBarChangeListener(debouncedListener)
        binding.seekBarTimerSize.setOnSeekBarChangeListener(debouncedListener)
        binding.seekBarOutlineWidth.setOnSeekBarChangeListener(debouncedListener)

        binding.switchOutline.setOnCheckedChangeListener { _, isChecked -> 
            viewModel.toggleOutline(isChecked, this) 
        }
    }

    private fun setupTimerFormatSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timerFormatMap.keys.toList())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTimerFormat.adapter = adapter
        
        binding.spinnerTimerFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val key = parent.getItemAtPosition(position).toString()
                timerFormatMap[key]?.let { viewModel.updateTimerFormat(it, this@MainActivity) }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupFontSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fontMap.keys.toList())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFont.adapter = adapter

        binding.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val key = parent.getItemAtPosition(position).toString()
                fontMap[key]?.let { viewModel.updateTimerFont(it, this@MainActivity) }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupColorPickers() {
        val colors = listOf(Color.WHITE, Color.BLACK, Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, -0xff01)
        colors.forEach { color ->
            binding.timerColorPicker.colorContainer.addView(createColorView(color, true))
            binding.outlineColorPicker.colorContainer.addView(createColorView(color, false))
        }

        binding.timerColorPicker.btnCustomColor.setOnClickListener { showColorPickerDialog(true) }
        binding.outlineColorPicker.btnCustomColor.setOnClickListener { showColorPickerDialog(false) }
    }

    private fun createColorView(color: Int, isForTimer: Boolean): View {
        return FrameLayout(this).apply {
            val size = (32 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                val margin = (4 * resources.displayMetrics.density).toInt()
                setMargins(margin, margin, margin, margin)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(2, Color.DKGRAY)
            }
            setOnClickListener {
                if (isForTimer) viewModel.updateTimerColor(color, this@MainActivity)
                else viewModel.updateOutlineColor(color, this@MainActivity)
            }
        }
    }

    private fun showColorPickerDialog(isForTimer: Boolean) {
        val currentState = viewModel.uiState.value ?: return
        ColorPickerDialogBuilder.with(this)
            .setTitle("Choose Color")
            .initialColor(if (isForTimer) currentState.timerColor else currentState.outlineColor)
            .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
            .density(12)
            .setPositiveButton("OK") { _, selectedColor, _ ->
                if (isForTimer) viewModel.updateTimerColor(selectedColor, this)
                else viewModel.updateOutlineColor(selectedColor, this)
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .build()
            .show()
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permission)
        }
    }
    
    // Utilitário simples para UI (separado do VM pois é formatação visual rápida)
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
            else -> String.format(Locale.US, "%d:%02d.%03d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, millis)
        }
    }
                }
