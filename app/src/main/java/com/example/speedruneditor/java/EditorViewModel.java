package com.example.speedruneditor.java;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextPaint;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public class EditorViewModel extends ViewModel {

    private static final String TAG = "EditorViewModel";
    private final MutableLiveData<UiState> _uiState = new MutableLiveData<>(new UiState());
    public LiveData<UiState> getUiState() {
        return _uiState;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private final AtomicBoolean isFetchingFrame = new AtomicBoolean(false);

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown();
        if (transformer != null) {
            transformer.cancel();
        }
    }

    public void startRender(Context context) {
        UiState state = _uiState.getValue();
        if (state == null || state.selectedVideoUri == null || state.videoProperties == null || transformer != null) {
            return;
        }
        updateState(s -> s.buildUpon().setIsLoading(true).setStatusMessage("Preparing render...").setRenderProgress(0).build());

        final String tempFileName = "render_" + System.currentTimeMillis() + ".mp4";
        final File tempVideoFile = new File(context.getExternalCacheDir(), tempFileName);

        MediaItem mediaItem = MediaItem.fromUri(state.selectedVideoUri);
        TextureOverlay timerOverlay = createTimerOverlay();
        Effects effects = new Effects(Collections.emptyList(), ImmutableList.of(new OverlayEffect(ImmutableList.of(timerOverlay))));
        EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).setEffects(effects).build();

        transformer = new Transformer.Builder(context)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(@NonNull Composition composition, @NonNull ExportResult exportResult) {
                        copyFileToPublicFolder(context, tempVideoFile);
                        transformer = null;
                    }

                    @Override
                    public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult, @NonNull ExportException exportException) {
                        Log.e(TAG, "Transformer error", exportException);
                        if (tempVideoFile.exists()) {
                            tempVideoFile.delete();
                        }
                        mainHandler.post(() -> {
                            updateState(s -> s.buildUpon()
                                    .setIsLoading(false)
                                    .setStatusMessage("Error: " + exportException.getErrorCodeName())
                                    .build());
                            transformer = null;
                        });
                    }
                })
                .build();

        transformer.start(editedMediaItem, tempVideoFile.getAbsolutePath());

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (transformer != null) {
                    ProgressHolder progressHolder = new ProgressHolder();
                    if (transformer.getProgress(progressHolder) != Transformer.PROGRESS_STATE_NO_TRANSFORMATION) {
                        final int progress = progressHolder.progress;
                        mainHandler.post(() -> updateState(s -> s.buildUpon().setRenderProgress(progress).setStatusMessage("Rendering... " + progress + "%").build()));
                    }
                    mainHandler.postDelayed(this, 500);
                }
            }
        });
    }

    private void copyFileToPublicFolder(Context context, File sourceFile) {
        mainHandler.post(() -> updateState(s -> s.buildUpon().setStatusMessage("Saving to Movies folder...").build()));

        ContentResolver resolver = context.getContentResolver();
        ContentValues contentValues = new ContentValues();
        String fileName = "render_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);

        Uri videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri newVideoUri = resolver.insert(videoCollection, contentValues);

        if (newVideoUri != null) {
            try (OutputStream out = resolver.openOutputStream(newVideoUri);
                 InputStream in = new FileInputStream(sourceFile)) {
                if (out != null) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    mainHandler.post(() -> updateState(s -> s.buildUpon()
                            .setIsLoading(false)
                            .setStatusMessage("Render Complete!")
                            .setFinalVideoPath("Movies/" + fileName)
                            .build()));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error copying file to Movies folder", e);
                mainHandler.post(() -> updateState(s -> s.buildUpon().setIsLoading(false).setStatusMessage("Error saving file.").build()));
            }
        }
        if (sourceFile.exists()) {
            sourceFile.delete();
        }
    }

    public void loadVideoFromUri(Uri uri, Context context) {
        updateState(currentState -> currentState.buildUpon().setIsLoading(true).setStatusMessage("Analyzing video...").build());
        executor.execute(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                extractor.setDataSource(context, uri, null);
                retriever.setDataSource(context, uri);

                MediaFormat format = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat trackFormat = extractor.getTrackFormat(i);
                    String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        format = trackFormat;
                        break;
                    }
                }
                if (format == null) throw new IOException("No video track found.");

                int width = format.getInteger(MediaFormat.KEY_WIDTH);
                int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                double duration = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0;

                double fps = 30.0;
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                } else {
                    String frameCountStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                    if (frameCountStr != null && duration > 0) {
                        fps = Integer.parseInt(frameCountStr) / duration;
                    }
                }

                VideoProperties props = new VideoProperties(width, height, fps, duration);
                int totalFrames = (int) (props.duration * props.fps);

                mainHandler.post(() -> {
                    updateState(currentState -> currentState.buildUpon()
                            .setIsLoading(false)
                            .setScreenState(ScreenState.EDITOR)
                            .setSelectedVideoUri(uri)
                            .setVideoProperties(props)
                            .setEndFrame(totalFrames)
                            .setStatusMessage("Video loaded: " + props.width + "x" + props.height)
                            .build()
                    );
                    navigateToFrame(0, context);
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading video", e);
                mainHandler.post(() -> updateState(currentState -> currentState.buildUpon()
                        .setIsLoading(false)
                        .setStatusMessage("Error loading: " + e.getMessage())
                        .build())
                );
            } finally {
                extractor.release();
                try {
                    retriever.release();
                } catch (IOException e) {
                   Log.e(TAG, "Failed to release retriever", e);
                }
            }
        });
    }

    public void loadCustomFont(Uri uri, Context context) {
        updateState(s -> s.buildUpon().setStatusMessage("Importing font...").build());
        executor.execute(() -> {
            File fontsDir = new File(context.getFilesDir(), "fonts");
            if (!fontsDir.exists()) {
                fontsDir.mkdirs();
            }
            String fileName = getFileNameFromUri(context, uri);
            if (fileName == null) {
                fileName = "custom_font_" + System.currentTimeMillis();
            }
            final String finalFileName = fileName;
            File finalFontFile = new File(fontsDir, finalFileName);
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                 OutputStream outputStream = new FileOutputStream(finalFontFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
                Typeface customTypeface = Typeface.createFromFile(finalFontFile);
                mainHandler.post(() -> {
                    updateState(s -> s.buildUpon()
                            .setCustomFont(customTypeface, finalFileName)
                            .setStatusMessage("Font '" + finalFileName + "' loaded.")
                            .build());
                    refreshPreview(context);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading custom font", e);
                mainHandler.post(() -> updateState(s -> s.buildUpon()
                        .setStatusMessage("Error importing font.")
                        .build()));
            }
        });
    }

    private String getFileNameFromUri(Context context, Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private void fetchFrameForPreview(int frame, Context context) {
        if (!isFetchingFrame.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                UiState currentState = _uiState.getValue();
                if (currentState == null || currentState.selectedVideoUri == null || currentState.videoProperties == null)
                    return;
                long timeUs = (long) (frame / currentState.videoProperties.fps * 1_000_000);
                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                    retriever.setDataSource(context, currentState.selectedVideoUri);
                    Bitmap rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (rawBitmap != null) {
                        Bitmap finalBitmap = drawTimerOnBitmap(rawBitmap, frame, currentState);
                        mainHandler.post(() -> {
                            UiState latestState = _uiState.getValue();
                            if (latestState != null && latestState.currentFrameBitmap != null && !latestState.currentFrameBitmap.isRecycled()) {
                                latestState.currentFrameBitmap.recycle();
                            }
                            updateState(s -> s.buildUpon().setCurrentFrameBitmap(finalBitmap).build());
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error getting frame for preview", e);
                }
            } finally {
                isFetchingFrame.set(false);
            }
        });
    }

    private void drawTimerOnCanvas(Canvas canvas, String timerText, UiState state) {
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(state.timerTypeface);
        textPaint.setTextSize(state.timerSize);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float x = canvas.getWidth() * state.timerPositionX;
        float y = canvas.getHeight() * state.timerPositionY;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        y -= (fm.descent + fm.ascent) / 2f;
        if (state.outlineEnabled && state.outlineWidth > 0) {
            TextPaint outlinePaint = new TextPaint(textPaint);
            outlinePaint.setColor(state.outlineColor);
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(state.outlineWidth * 2);
            outlinePaint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawText(timerText, x, y, outlinePaint);
        }
        textPaint.setColor(state.timerColor);
        textPaint.setStyle(Paint.Style.FILL);
        canvas.drawText(timerText, x, y, textPaint);
    }

    private double calculateElapsedTime(UiState state, int currentFrame) {
        if (state.videoProperties == null) return 0.0;
        double startTime = (double) state.startFrame / state.videoProperties.fps;
        double currentTime;
        if (currentFrame > state.endFrame) {
            currentTime = (double) state.endFrame / state.videoProperties.fps;
        } else {
            currentTime = (double) currentFrame / state.videoProperties.fps;
        }
        return currentTime - startTime;
    }

    private Bitmap drawTimerOnBitmap(Bitmap sourceBitmap, int currentFrame, UiState state) {
        Bitmap mutableBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true);
        sourceBitmap.recycle();
        Canvas canvas = new Canvas(mutableBitmap);
        String timerText;
        if (currentFrame < state.startFrame) {
            timerText = formatTime(0, state.timerFormat);
        } else {
            double elapsedSeconds = calculateElapsedTime(state, currentFrame);
            timerText = formatTime(elapsedSeconds, state.timerFormat);
        }
        drawTimerOnCanvas(canvas, timerText, state);
        return mutableBitmap;
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
            case "HHMMSSmmm": return String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis);
            case "SSmmm": return String.format(Locale.US, "%.3f", totalSeconds);
            case "HHMMSS": return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
            case "MMSS": return String.format(Locale.US, "%d:%02d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds);
            case "MMSScc_pad": return String.format(Locale.US, "%02d:%02d.%02d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, centis);
            case "MMSScc": return String.format(Locale.US, "%d:%02d.%02d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, centis);
            case "MMSSmmm": default: return String.format(Locale.US, "%d:%02d.%03d", TimeUnit.MILLISECONDS.toMinutes(totalMillis), seconds, millis);
        }
    }

    private TextureOverlay createTimerOverlay() {
        return new BitmapOverlay() {
            @Override
            public Bitmap getBitmap(long presentationTimeUs) {
                UiState state = _uiState.getValue();
                if (state == null || state.videoProperties == null) {
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                }
                Bitmap bitmap = Bitmap.createBitmap(state.videoProperties.width, state.videoProperties.height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                double startSeconds = (double) state.startFrame / state.videoProperties.fps;
                double endSeconds = (double) state.endFrame / state.videoProperties.fps;
                double currentSeconds = presentationTimeUs / 1_000_000.0;
                double elapsedSeconds = 0;
                if (currentSeconds >= startSeconds) {
                    elapsedSeconds = Math.min(currentSeconds, endSeconds) - startSeconds;
                }
                String timerText = formatTime(elapsedSeconds, state.timerFormat);
                drawTimerOnCanvas(canvas, timerText, state);
                return bitmap;
            }
        };
    }

    public void updateCurrentFrame(int frame) {
        UiState s = _uiState.getValue();
        if (s == null || s.videoProperties == null) return;
        int maxFrames = (int) (s.videoProperties.duration * s.videoProperties.fps);
        int clampedFrame = Math.max(0, Math.min(frame, maxFrames > 0 ? maxFrames - 1 : 0));
        if (s.currentFrame != clampedFrame) {
            updateState(state -> state.buildUpon().setCurrentFrame(clampedFrame).build());
        }
    }

    public void navigateToFrame(int newFrame, Context context) {
        UiState s = _uiState.getValue();
        if (s == null || s.videoProperties == null) return;
        int maxFrames = (int) (s.videoProperties.duration * s.videoProperties.fps);
        int clampedFrame = Math.max(0, Math.min(newFrame, maxFrames > 0 ? maxFrames - 1 : 0));
        updateState(state -> state.buildUpon().setCurrentFrame(clampedFrame).build());
        fetchFrameForPreview(clampedFrame, context);
    }

    public void navigateFrames(int frameDelta, Context context) {
        UiState s = _uiState.getValue();
        if (s != null) navigateToFrame(s.currentFrame + frameDelta, context);
    }

    public void navigateSeconds(int secDelta, Context context) {
        UiState s = _uiState.getValue();
        if (s != null && s.videoProperties != null) {
            int frameDelta = (int) (secDelta * s.videoProperties.fps);
            navigateToFrame(s.currentFrame + frameDelta, context);
        }
    }

    public void navigateMinutes(int minDelta, Context context) {
        UiState s = _uiState.getValue();
        if (s != null && s.videoProperties != null) {
            int frameDelta = (int) (minDelta * 60 * s.videoProperties.fps);
            navigateToFrame(s.currentFrame + frameDelta, context);
        }
    }

    public void setStartFrame() {
        updateState(s -> s.buildUpon().setStartFrame(s.currentFrame).build());
    }

    public void setEndFrame() {
        updateState(s -> s.buildUpon().setEndFrame(s.currentFrame).build());
    }

    public void goToStartFrame(Context context) {
        UiState s = _uiState.getValue();
        if (s != null) navigateToFrame(s.startFrame, context);
    }

    public void goToEndFrame(Context context) {
        UiState s = _uiState.getValue();
        if (s != null) navigateToFrame(s.endFrame, context);
    }

    public void refreshPreview(Context context) {
        UiState s = _uiState.getValue();
        if (s != null) fetchFrameForPreview(s.currentFrame, context);
    }

    public void updateTimerPositionX(float positionX) {
        updateState(s -> s.buildUpon().setTimerPositionX(positionX).build());
    }

    public void updateTimerPositionY(float positionY) {
        updateState(s -> s.buildUpon().setTimerPositionY(positionY).build());
    }

    public void updateTimerFormat(String format, Context context) {
        updateState(s -> s.buildUpon().setTimerFormat(format).build());
        refreshPreview(context);
    }

    public void updateTimerFont(Typeface typeface, Context context) {
        updateState(s -> s.buildUpon().setCustomFont(typeface, null).build());
        refreshPreview(context);
    }

    public void setTimerSize(int size) {
        updateState(s -> s.buildUpon().setTimerSize(size).build());
    }

    public void setOutlineWidth(int width) {
        updateState(s -> s.buildUpon().setOutlineWidth(width).build());
    }

    public void updateTimerColor(int color, Context context) {
        updateState(s -> s.buildUpon().setTimerColor(color).build());
        refreshPreview(context);
    }

    public void toggleOutline(boolean isEnabled, Context context) {
        updateState(s -> s.buildUpon().setOutlineEnabled(isEnabled).build());
        refreshPreview(context);
    }

    public void updateOutlineColor(int color, Context context) {
        updateState(s -> s.buildUpon().setOutlineColor(color).build());
        refreshPreview(context);
    }

    @FunctionalInterface
    private interface StateUpdater {
        UiState update(UiState currentState);
    }

    private void updateState(StateUpdater updater) {
        UiState currentState = _uiState.getValue();
        if (currentState != null) {
            mainHandler.post(() -> _uiState.setValue(updater.update(currentState)));
        }
    }
}
