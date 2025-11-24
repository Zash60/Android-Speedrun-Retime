package com.example.speedruneditor.java;

import android.Manifest;
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
import android.widget.Button;
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
import com.flask.colorpicker.builder.ColorPickerDialogBuilder;
import com.example.speedruneditor.java.databinding.ActivityMainBinding;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class MainActivity extends AppCompatActivity {

    private EditorViewModel viewModel;
    private ActivityMainBinding binding;

    private final Map<String, String> timerFormatMap = new LinkedHashMap<>();
    private final Map<String, Typeface> fontMap = new LinkedHashMap<>();

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 200;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            });

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
                binding.progressBar.setIndeterminate(uiState.screenState != ScreenState.EDITOR);
                binding.progressBar.setProgress(uiState.renderProgress);
            } else {
                binding.progressBar.setVisibility(View.GONE);
            }

            if (uiState.finalVideoPath != null) {
                binding.textFinalPath.setText("Saved to: " + uiState.finalVideoPath);
                binding.textFinalPath.setVisibility(View.VISIBLE);
            } else {
                binding.textFinalPath.setVisibility(View.GONE);
            }

            if (uiState.screenState == ScreenState.EDITOR && uiState.videoProperties != null) {
                binding.textVideoInfo.setText(String.format(java.util.Locale.US,
                        "Info: %dx%d @ %.2ffps",
                        uiState.videoProperties.width, uiState.videoProperties.height, uiState.videoProperties.fps));

                binding.imageViewPreview.setImageBitmap(uiState.currentFrameBitmap);

                int maxFrames = (int) (uiState.videoProperties.duration * uiState.videoProperties.fps);
                if (binding.seekBarFrame.getMax() != maxFrames) {
                    binding.seekBarFrame.setMax(maxFrames);
                }

                if (!binding.seekBarFrame.isPressed()) {
                    binding.seekBarFrame.setProgress(uiState.currentFrame);
                }

                double currentTime = uiState.currentFrame / uiState.videoProperties.fps;
                String timeLabel = String.format(java.util.Locale.US,
                        "Time: %s | Frame: %d",
                        formatTime(currentTime, uiState.timerFormat), uiState.currentFrame);
                String trimLabel = String.format(java.util.Locale.US,
                        "Start: %s (f:%d) | End: %s (f:%d)",
                        formatTime(uiState.startFrame / uiState.videoProperties.fps, uiState.timerFormat),
                        uiState.startFrame,
                        formatTime(uiState.endFrame / uiState.videoProperties.fps, uiState.timerFormat),
                        uiState.endFrame);
                binding.textTimeLabel.setText(timeLabel + "\n" + trimLabel);

                binding.textPositionXLabel.setText("Position X: " + (int) (uiState.timerPositionX * 100) + "%");
                if (!binding.seekBarPositionX.isPressed()) {
                    binding.seekBarPositionX.setProgress((int) (uiState.timerPositionX * 100));
                }

                binding.textPositionYLabel.setText("Position Y: " + (int) (uiState.timerPositionY * 100) + "%");
                if (!binding.seekBarPositionY.isPressed()) {
                    binding.seekBarPositionY.setProgress((int) (uiState.timerPositionY * 100));
                }

                binding.textTimerSizeLabel.setText("Size: " + uiState.timerSize);
                if (!binding.seekBarTimerSize.isPressed()) {
                    binding.seekBarTimerSize.setProgress(uiState.timerSize);
                }

                if (uiState.customFontName != null) {
                    binding.textCustomFontName.setText("Font: " + uiState.customFontName);
                    binding.textCustomFontName.setVisibility(View.VISIBLE);
                } else {
                    binding.textCustomFontName.setVisibility(View.GONE);
                }

                binding.switchOutline.setChecked(uiState.outlineEnabled);
                binding.outlineControls.setVisibility(uiState.outlineEnabled ? View.VISIBLE : View.GONE);

                binding.textOutlineWidthLabel.setText("Outline Width: " + uiState.outlineWidth);
                if (!binding.seekBarOutlineWidth.isPressed()) {
                    binding.seekBarOutlineWidth.setProgress(uiState.outlineWidth);
                }
            }
        });
    }

    private void setupListeners() {
        binding.btnSelectVideo.setOnClickListener(v -> videoPickerLauncher.launch("video/*"));
        binding.btnRender.setOnClickListener(v -> viewModel.startRender(this));
        binding.btnSelectFontFile.setOnClickListener(v -> fontPickerLauncher.launch("*/*"));

        // Navigation Panel
        binding.navPanel.btnSetStart.setOnClickListener(v -> viewModel.setStartFrame());
        binding.navPanel.btnSetEnd.setOnClickListener(v -> viewModel.setEndFrame());
        binding.navPanel.btnGoToStart.setOnClickListener(v -> viewModel.goToStartFrame(this));
        binding.navPanel.btnGoToEnd.setOnClickListener(v -> viewModel.goToEndFrame(this));

        // Main Seek Bar
        binding.seekBarFrame.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) viewModel.updateCurrentFrame(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                // No action needed now
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                viewModel.navigateToFrame(seekBar.getProgress(), MainActivity.this);
            }
        });

        SeekBar.OnSeekBarChangeListener debouncedSeekBarListener = new SeekBar.OnSeekBarChangeListener() {
            private Runnable debounceRunnable;

            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;

                int id = seekBar.getId();
                if (id == binding.seekBarPositionX.getId()) {
                    viewModel.updateTimerPositionX(progress / 100.0f);
                } else if (id == binding.seekBarPositionY.getId()) {
                    viewModel.updateTimerPositionY(progress / 100.0f);
                } else if (id == binding.seekBarTimerSize.getId()) {
                    viewModel.setTimerSize(progress);
                } else if (id == binding.seekBarOutlineWidth.getId()) {
                    viewModel.setOutlineWidth(progress);
                }

                debounceHandler.removeCallbacks(debounceRunnable);
                debounceRunnable = () -> viewModel.refreshPreview(MainActivity.this);
                debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                debounceHandler.removeCallbacks(debounceRunnable);
                viewModel.refreshPreview(MainActivity.this);
            }
        };

        binding.seekBarPositionX.setOnSeekBarChangeListener(debouncedSeekBarListener);
        binding.seekBarPositionY.setOnSeekBarChangeListener(debouncedSeekBarListener);
        binding.seekBarTimerSize.setOnSeekBarChangeListener(debouncedSeekBarListener);
        binding.seekBarOutlineWidth.setOnSeekBarChangeListener(debouncedSeekBarListener);

        binding.switchOutline.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.toggleOutline(isChecked, this));

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
        Button[] navButtons = {
                binding.navPanel.btnFrameMinus10, binding.navPanel.btnFrameMinus5, binding.navPanel.btnFrameMinus1,
                binding.navPanel.btnFramePlus1, binding.navPanel.btnFramePlus5, binding.navPanel.btnFramePlus10,
                binding.navPanel.btnSecMinus10, binding.navPanel.btnSecMinus5, binding.navPanel.btnSecMinus1,
                binding.navPanel.btnSecPlus1, binding.navPanel.btnSecPlus5, binding.navPanel.btnSecPlus10,
                binding.navPanel.btnMinMinus10, binding.navPanel.btnMinMinus5, binding.navPanel.btnMinMinus1,
                binding.navPanel.btnMinPlus1, binding.navPanel.btnMinPlus5, binding.navPanel.btnMinPlus10
        };
        for(Button btn : navButtons) {
            btn.setOnClickListener(navListener);
        }
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
        fontMap.put("Default (Bold)", Typeface.DEFAULT_BOLD);
        fontMap.put("Monospace (Bold)", Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        fontMap.put("Serif (Bold)", Typeface.create(Typeface.SERIF, Typeface.BOLD));

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
        int[] colors = {Color.WHITE, Color.BLACK, Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, 0xFFFF00FF};
        for (int color : colors) {
            binding.timerColorPicker.colorContainer.addView(createColorView(color, true));
            binding.outlineColorPicker.colorContainer.addView(createColorView(color, false));
        }

        binding.timerColorPicker.btnCustomColor.setOnClickListener(v -> showColorPickerDialog(true));
        binding.outlineColorPicker.btnCustomColor.setOnClickListener(v -> showColorPickerDialog(false));
    }

    private void showColorPickerDialog(boolean isForTimer) {
        UiState state = viewModel.getUiState().getValue();
        if (state == null) return;

        ColorPickerDialogBuilder
                .with(this)
                .setTitle("Choose Color")
                .initialColor(isForTimer ? state.timerColor : state.outlineColor)
                .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
                .density(12)
                .setPositiveButton("OK", (dialog, selectedColor, allColors) -> {
                    if (isForTimer) {
                        viewModel.updateTimerColor(selectedColor, MainActivity.this);
                    } else {
                        viewModel.updateOutlineColor(selectedColor, MainActivity.this);
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> {})
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
        String permission = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ?
                Manifest.permission.READ_MEDIA_VIDEO :
                Manifest.permission.READ_EXTERNAL_STORAGE;

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
            case "HHMMSSmmm": return String.format(java.util.Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis);
            case "SSmmm": return String.format(java.util.Locale.US, "%.3f", totalSeconds);
            case "HHMMSS": return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
            case "MMSS": return String.format(java.util.Locale.US, "%d:%02d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds);
            case "MMSScc_pad": return String.format(java.util.Locale.US, "%02d:%02d.%02d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, centis);
            case "MMSScc": return String.format(java.util.Locale.US, "%d:%02d.%02d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, centis);
            case "MMSSmmm": default: return String.format(java.util.Locale.US, "%d:%02d.%03d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, millis);
        }
    }
}
