package com.example.speedruneditor.java;

import android.Manifest;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import com.flask.colorpicker.ColorPickerView;
import com.flask.colorpicker.OnColorSelectedListener;
import com.flask.colorpicker.builder.ColorPickerClickListener;
import com.flask.colorpicker.builder.ColorPickerDialogBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.example.speedruneditor.java.databinding.ActivityMainBinding;

@UnstableApi
public class MainActivity extends AppCompatActivity {

    private EditorViewModel viewModel;
    private ActivityMainBinding binding;

    private final Map<String, String> timerFormatMap = new LinkedHashMap<>();
    private final Map<String, Typeface> fontMap = new LinkedHashMap<>();

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 200;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {});

    private final ActivityResultLauncher<String> videoPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    viewModel.loadVideoFromUri(uri, this);
                }
            });

    private final ActivityResultLauncher<String> fontPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    viewModel.loadCustomFont(uri, this);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(EditorViewModel.class);

        setupTimerFormatSpinner();
        setupFontSpinner();
        setupColorPickers();
        setupObservers();
        setupListeners();
        requestStoragePermission();
    }

    private void setupObservers() {
        viewModel.getUiState().observe(this, uiState -> {
            binding.initialScreenLayout.setVisibility(uiState.screenState == ScreenState.INITIAL ? View.VISIBLE : View.GONE);
            binding.editorScreenLayout.setVisibility(uiState.screenState == ScreenState.EDITOR ? View.VISIBLE : View.GONE);

            binding.textStatus.setText(uiState.statusMessage);
            binding.btnRender.setEnabled(!uiState.isLoading);
            binding.btnSelectVideo.setEnabled(!uiState.isLoading);

            if (uiState.isLoading) {
                binding.progressBar.setVisibility(View.VISIBLE);
                if (uiState.screenState == ScreenState.EDITOR) {
                    binding.progressBar.setIndeterminate(false);
                    binding.progressBar.setProgress(uiState.renderProgress);
                } else {
                    binding.progressBar.setIndeterminate(true);
                }
            } else {
                binding.progressBar.setVisibility(View.GONE);
            }

            if (uiState.finalVideoPath != null) {
                binding.textFinalPath.setText("Salvo em: " + uiState.finalVideoPath);
                binding.textFinalPath.setVisibility(View.VISIBLE);
            } else {
                binding.textFinalPath.setVisibility(View.GONE);
            }

            if (uiState.screenState == ScreenState.EDITOR && uiState.videoProperties != null) {
                binding.textVideoInfo.setText(String.format(java.util.Locale.US,
                    "Info: %dx%d @ %.2ffps",
                    uiState.videoProperties.width, uiState.videoProperties.height, uiState.videoProperties.fps));

                binding.imageViewPreview.setImageBitmap(uiState.currentFrameBitmap);

                int maxFrames = (int)(uiState.videoProperties.duration * uiState.videoProperties.fps);
                if(binding.seekBarFrame.getMax() != maxFrames) {
                   binding.seekBarFrame.setMax(maxFrames);
                }

                if (!binding.seekBarFrame.isPressed()) {
                    binding.seekBarFrame.setProgress(uiState.currentFrame);
                }

                double currentTime = uiState.currentFrame / uiState.videoProperties.fps;
                String timeLabel = String.format(java.util.Locale.US,
                    "Tempo: %s | Frame: %d",
                    formatTime(currentTime, uiState.timerFormat), uiState.currentFrame);
                String trimLabel = String.format(java.util.Locale.US,
                    "Início: %s (f:%d) | Fim: %s (f:%d)",
                    formatTime(uiState.startFrame / uiState.videoProperties.fps, uiState.timerFormat),
                    uiState.startFrame,
                    formatTime(uiState.endFrame / uiState.videoProperties.fps, uiState.timerFormat),
                    uiState.endFrame);
                binding.textTimeLabel.setText(timeLabel + "\\n" + trimLabel);

                binding.textPositionXLabel.setText("Posição X: " + (int)(uiState.timerPositionX * 100) + "%");
                if (!binding.seekBarPositionX.isPressed()) {
                    binding.seekBarPositionX.setProgress((int)(uiState.timerPositionX * 100));
                }

                binding.textPositionYLabel.setText("Posição Y: " + (int)(uiState.timerPositionY * 100) + "%");
                if (!binding.seekBarPositionY.isPressed()) {
                    binding.seekBarPositionY.setProgress((int)(uiState.timerPositionY * 100));
                }

                binding.textTimerSizeLabel.setText("Tamanho: " + uiState.timerSize);
                if (!binding.seekBarTimerSize.isPressed()) {
                   binding.seekBarTimerSize.setProgress(uiState.timerSize);
                }

                if (uiState.customFontName != null) {
                    binding.textCustomFontName.setText("Fonte: " + uiState.customFontName);
                    binding.textCustomFontName.setVisibility(View.VISIBLE);
                } else {
                    binding.textCustomFontName.setVisibility(View.GONE);
                }

                binding.switchOutline.setChecked(uiState.outlineEnabled);
                binding.outlineControls.setVisibility(uiState.outlineEnabled ? View.VISIBLE : View.GONE);

                binding.textOutlineWidthLabel.setText("Largura Contorno: " + uiState.outlineWidth);
                if(!binding.seekBarOutlineWidth.isPressed()){
                    binding.seekBarOutlineWidth.setProgress(uiState.outlineWidth);
                }
            }
        });
    }

    private void setupListeners() {
        binding.btnSelectVideo.setOnClickListener(v -> videoPickerLauncher.launch("video/*"));
        binding.btnRender.setOnClickListener(v -> viewModel.startRender(this));

        binding.btnSelectFontFile.setOnClickListener(v -> fontPickerLauncher.launch("*/*"));

        binding.navPanel.btnSetStart.setOnClickListener(v -> viewModel.setStartFrame());
        binding.navPanel.btnSetEnd.setOnClickListener(v -> viewModel.setEndFrame());
        binding.navPanel.btnGoToStart.setOnClickListener(v -> viewModel.goToStartFrame(this));
        binding.navPanel.btnGoToEnd.setOnClickListener(v -> viewModel.goToEndFrame(this));

        binding.seekBarFrame.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                 if(fromUser) {
                    viewModel.updateCurrentFrame(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                viewModel.navigateToFrame(seekBar.getProgress(), MainActivity.this);
            }
        });

        SeekBar.OnSeekBarChangeListener positionSeekBarListener = new SeekBar.OnSeekBarChangeListener() {
            private Runnable debounceRunnable;
            private void onPositionChanged(SeekBar seekBar, int progress, boolean isX) {
                 float position = progress / 100.0f;
                 if (isX) {
                     viewModel.updateTimerPositionX(position);
                 } else {
                     viewModel.updateTimerPositionY(position);
                 }
                 debounceHandler.removeCallbacks(debounceRunnable);
                 debounceRunnable = () -> viewModel.refreshPreview(MainActivity.this);
                 debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS);
            }

            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                 if (fromUser) {
                    onPositionChanged(seekBar, progress, seekBar.getId() == binding.seekBarPositionX.getId());
                 }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                 debounceHandler.removeCallbacks(debounceRunnable);
                 viewModel.refreshPreview(MainActivity.this);
            }
        };

        binding.seekBarPositionX.setOnSeekBarChangeListener(positionSeekBarListener);
        binding.seekBarPositionY.setOnSeekBarChangeListener(positionSeekBarListener);

        binding.seekBarTimerSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private Runnable debounceRunnable;
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    viewModel.setTimerSize(progress);
                    debounceHandler.removeCallbacks(debounceRunnable);
                    debounceRunnable = () -> viewModel.refreshPreview(MainActivity.this);
                    debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                 debounceHandler.removeCallbacks(debounceRunnable);
                 viewModel.refreshPreview(MainActivity.this);
            }
        });

        binding.switchOutline.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.toggleOutline(isChecked, this));

        binding.seekBarOutlineWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private Runnable debounceRunnable;
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    viewModel.setOutlineWidth(progress);
                    debounceHandler.removeCallbacks(debounceRunnable);
                    debounceRunnable = () -> viewModel.refreshPreview(MainActivity.this);
                    debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS);
                }
            }
             @Override public void onStartTrackingTouch(SeekBar seekBar) {}
             @Override public void onStopTrackingTouch(SeekBar seekBar) {
                 debounceHandler.removeCallbacks(debounceRunnable);
                 viewModel.refreshPreview(MainActivity.this);
            }
        });

        View.OnClickListener navListener = v -> {
            int id = v.getId();
            if (id == binding.navPanel.btnFrameMinus10.getId()) viewModel.navigateFrames(-10, this);
            else if (id == binding.navPanel.btnFrameMinus5.getId()) viewModel.navigateFrames(-5, this);
            else if (id == binding.navPanel.btnFrameMinus1.getId()) viewModel.navigateFrames(-1, this);
            else if (id == binding.navPanel.btnFramePlus1.getId()) viewModel.navigateFrames(1, this);
            else if (id == binding.navPanel.btnFramePlus5.getId()) viewModel.navigateFrames(5, this);
            else if (id == binding.navPanel.btnFramePlus10.getId()) viewModel.navigateFrames(10, this);
            else if (id == binding.navPanel.btnSecMinus10.getId()) viewModel.navigateSeconds(-10, this);
            else if (id == binding.navPanel.btnSecMinus5.getId()) viewModel.navigateSeconds(-5, this);
            else if (id == binding.navPanel.btnSecMinus1.getId()) viewModel.navigateSeconds(-1, this);
            else if (id == binding.navPanel.btnSecPlus1.getId()) viewModel.navigateSeconds(1, this);
            else if (id == binding.navPanel.btnSecPlus5.getId()) viewModel.navigateSeconds(5, this);
            else if (id == binding.navPanel.btnSecPlus10.getId()) viewModel.navigateSeconds(10, this);
            else if (id == binding.navPanel.btnMinMinus10.getId()) viewModel.navigateMinutes(-10, this);
            else if (id == binding.navPanel.btnMinMinus5.getId()) viewModel.navigateMinutes(-5, this);
            else if (id == binding.navPanel.btnMinMinus1.getId()) viewModel.navigateMinutes(-1, this);
            else if (id == binding.navPanel.btnMinPlus1.getId()) viewModel.navigateMinutes(1, this);
            else if (id == binding.navPanel.btnMinPlus5.getId()) viewModel.navigateMinutes(5, this);
            else if (id == binding.navPanel.btnMinPlus10.getId()) viewModel.navigateMinutes(10, this);
        };

        binding.navPanel.btnFrameMinus10.setOnClickListener(navListener);
        binding.navPanel.btnFrameMinus5.setOnClickListener(navListener);
        binding.navPanel.btnFrameMinus1.setOnClickListener(navListener);
        binding.navPanel.btnFramePlus1.setOnClickListener(navListener);
        binding.navPanel.btnFramePlus5.setOnClickListener(navListener);
        binding.navPanel.btnFramePlus10.setOnClickListener(navListener);
        binding.navPanel.btnSecMinus10.setOnClickListener(navListener);
        binding.navPanel.btnSecMinus5.setOnClickListener(navListener);
        binding.navPanel.btnSecMinus1.setOnClickListener(navListener);
        binding.navPanel.btnSecPlus1.setOnClickListener(navListener);
        binding.navPanel.btnSecPlus5.setOnClickListener(navListener);
        binding.navPanel.btnSecPlus10.setOnClickListener(navListener);
        binding.navPanel.btnMinMinus10.setOnClickListener(navListener);
        binding.navPanel.btnMinMinus5.setOnClickListener(navListener);
        binding.navPanel.btnMinMinus1.setOnClickListener(navListener);
        binding.navPanel.btnMinPlus1.setOnClickListener(navListener);
        binding.navPanel.btnMinPlus5.setOnClickListener(navListener);
        binding.navPanel.btnMinPlus10.setOnClickListener(navListener);
    }

    private void setupTimerFormatSpinner() {
        timerFormatMap.put("M:SS.mmm", "MMSSmmm");
        timerFormatMap.put("H:MM:SS.mmm", "HHMMSSmmm");
        timerFormatMap.put("S.mmm", "SSmmm");
        timerFormatMap.put("H:MM:SS", "HHMMSS");
        timerFormatMap.put("M:SS", "MMSS");
        timerFormatMap.put("MM:SS.cc", "MMSScc_pad");
        timerFormatMap.put("M:SS.cc", "MMSScc");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, timerFormatMap.keySet().toArray(new String[0]));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerTimerFormat.setAdapter(adapter);
        binding.spinnerTimerFormat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedKey = parent.getItemAtPosition(position).toString();
                String selectedValue = timerFormatMap.get(selectedKey);
                if (selectedValue != null) {
                    viewModel.updateTimerFormat(selectedValue, MainActivity.this);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupFontSpinner() {
        fontMap.put("Padrão (Negrito)", Typeface.DEFAULT_BOLD);
        fontMap.put("Monospace (Negrito)", Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        fontMap.put("Serif (Negrito)", Typeface.create(Typeface.SERIF, Typeface.BOLD));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, fontMap.keySet().toArray(new String[0]));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerFont.setAdapter(adapter);
        binding.spinnerFont.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedKey = parent.getItemAtPosition(position).toString();
                Typeface selectedValue = fontMap.get(selectedKey);
                if (selectedValue != null) {
                    viewModel.updateTimerFont(selectedValue, MainActivity.this);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupColorPickers() {
        int[] colors = { Color.WHITE, Color.BLACK, Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, 0xFFFF00FF };
        for (int color : colors) {
            binding.timerColorPicker.colorContainer.addView(createColorView(color, true));
            binding.outlineColorPicker.colorContainer.addView(createColorView(color, false));
        }

        binding.timerColorPicker.btnCustomColor.setOnClickListener(v -> {
            UiState state = viewModel.getUiState().getValue();
            if (state != null) showColorPickerDialog(true, state.timerColor);
        });
        binding.outlineColorPicker.btnCustomColor.setOnClickListener(v -> {
            UiState state = viewModel.getUiState().getValue();
            if (state != null) showColorPickerDialog(false, state.outlineColor);
        });
    }

    private void showColorPickerDialog(boolean isForTimer, int initialColor) {
        ColorPickerDialogBuilder
                .with(this)
                .setTitle("Escolha uma Cor")
                .initialColor(initialColor)
                .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
                .density(12)
                .setPositiveButton("OK", (dialog, selectedColor, allColors) -> {
                    if (isForTimer) {
                        viewModel.updateTimerColor(selectedColor, MainActivity.this);
                    } else {
                        viewModel.updateOutlineColor(selectedColor, MainActivity.this);
                    }
                })
                .setNegativeButton("Cancelar", (dialog, which) -> {})
                .build()
                .show();
    }

    private View createColorView(int color, boolean isForTimer) {
        FrameLayout view = new FrameLayout(this);
        int size = (int) (32 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        int margin = (int) (4 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, margin, margin, margin);
        view.setLayoutParams(params);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        background.setStroke(2, Color.DKGRAY);
        view.setBackground(background);

        view.setOnClickListener(v -> {
            if (isForTimer) {
                viewModel.updateTimerColor(color, this);
            } else {
                viewModel.updateOutlineColor(color, this);
            }
        });
        return view;
    }

    private void requestStoragePermission() {
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_VIDEO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permission);
        }
    }

    private String formatTime(double totalSeconds, String format) {
        if (totalSeconds < 0) totalSeconds = 0;
        long totalMillis = Math.round(totalSeconds * 1000);

        long hours = TimeUnit.MILLISECONDS.toHours(totalMillis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(totalMillis) % 60;
        long millis = totalMillis % 1000;
        long centis = (totalMillis / 10) % 100;

        switch (format) {
            case "HHMMSSmmm":
                return String.format(java.util.Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis);
            case "SSmmm":
                return String.format(java.util.Locale.US, "%.3f", totalSeconds);
            case "HHMMSS":
                return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
            case "MMSS":
                 long totalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis);
                return String.format(java.util.Locale.US, "%d:%02d", totalMinutes, seconds);
            case "MMSScc_pad":
                long totalMinutesPad = TimeUnit.MILLISECONDS.toMinutes(totalMillis);
                return String.format(java.util.Locale.US, "%02d:%02d.%02d", totalMinutesPad, seconds, centis);
            case "MMSScc":
                 long totalMinutesNoPad = TimeUnit.MILLISECONDS.toMinutes(totalMillis);
                return String.format(java.util.Locale.US, "%d:%02d.%02d", totalMinutesNoPad, seconds, centis);
            case "MMSSmmm":
            default:
                 long totalMinutesDefault = TimeUnit.MILLISECONDS.toMinutes(totalMillis);
                return String.format(java.util.Locale.US, "%d:%02d.%03d", totalMinutesDefault, seconds, millis);
        }
    }
}
