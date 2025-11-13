package com.example.speedruneditor.java;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class UiState {
    @NonNull public final ScreenState screenState;
    public final boolean isLoading;
    @NonNull public final String statusMessage;
    public final int renderProgress;
    @Nullable public final String finalVideoPath;
    @Nullable public final Uri selectedVideoUri;
    @Nullable public final VideoProperties videoProperties;
    public final int currentFrame;
    public final int startFrame;
    public final int endFrame;
    @Nullable public final Bitmap currentFrameBitmap;
    public final float timerPositionX;
    public final float timerPositionY;
    public final int timerSize;
    public final int timerColor;
    @NonNull public final String timerFormat;
    @NonNull public final Typeface timerTypeface;
    @Nullable public final String customFontName;
    public final boolean outlineEnabled;
    public final int outlineWidth;
    public final int outlineColor;

    public UiState() {
        this.screenState = ScreenState.INITIAL;
        this.isLoading = false;
        this.statusMessage = "Select a video from your device.";
        this.renderProgress = 0;
        this.finalVideoPath = null;
        this.selectedVideoUri = null;
        this.videoProperties = null;
        this.currentFrame = 0;
        this.startFrame = 0;
        this.endFrame = 0;
        this.currentFrameBitmap = null;
        this.timerPositionX = 0.86f;
        this.timerPositionY = 0.95f;
        this.timerSize = 80;
        this.timerColor = 0xFFFFFFFF;
        this.timerFormat = "MMSSmmm";
        this.timerTypeface = Typeface.DEFAULT_BOLD;
        this.customFontName = null;
        this.outlineEnabled = true;
        this.outlineWidth = 3;
        this.outlineColor = 0xFF000000;
    }

    private UiState(Builder builder) {
        this.screenState = builder.screenState;
        this.isLoading = builder.isLoading;
        this.statusMessage = builder.statusMessage;
        this.renderProgress = builder.renderProgress;
        this.finalVideoPath = builder.finalVideoPath;
        this.selectedVideoUri = builder.selectedVideoUri;
        this.videoProperties = builder.videoProperties;
        this.currentFrame = builder.currentFrame;
        this.startFrame = builder.startFrame;
        this.endFrame = builder.endFrame;
        this.currentFrameBitmap = builder.currentFrameBitmap;
        this.timerPositionX = builder.timerPositionX;
        this.timerPositionY = builder.timerPositionY;
        this.timerSize = builder.timerSize;
        this.timerColor = builder.timerColor;
        this.timerFormat = builder.timerFormat;
        this.timerTypeface = builder.timerTypeface;
        this.customFontName = builder.customFontName;
        this.outlineEnabled = builder.outlineEnabled;
        this.outlineWidth = builder.outlineWidth;
        this.outlineColor = builder.outlineColor;
    }

    public Builder buildUpon() {
        return new Builder(this);
    }

    public static class Builder {
        private ScreenState screenState;
        private boolean isLoading;
        private String statusMessage;
        private int renderProgress;
        private String finalVideoPath;
        private Uri selectedVideoUri;
        private VideoProperties videoProperties;
        private int currentFrame;
        private int startFrame;
        private int endFrame;
        private Bitmap currentFrameBitmap;
        private float timerPositionX;
        private float timerPositionY;
        private int timerSize;
        private int timerColor;
        private String timerFormat;
        private Typeface timerTypeface;
        private String customFontName;
        private boolean outlineEnabled;
        private int outlineWidth;
        private int outlineColor;

        public Builder(UiState oldState) {
            this.screenState = oldState.screenState;
            this.isLoading = oldState.isLoading;
            this.statusMessage = oldState.statusMessage;
            this.renderProgress = oldState.renderProgress;
            this.finalVideoPath = oldState.finalVideoPath;
            this.selectedVideoUri = oldState.selectedVideoUri;
            this.videoProperties = oldState.videoProperties;
            this.currentFrame = oldState.currentFrame;
            this.startFrame = oldState.startFrame;
            this.endFrame = oldState.endFrame;
            this.currentFrameBitmap = oldState.currentFrameBitmap;
            this.timerPositionX = oldState.timerPositionX;
            this.timerPositionY = oldState.timerPositionY;
            this.timerSize = oldState.timerSize;
            this.timerColor = oldState.timerColor;
            this.timerFormat = oldState.timerFormat;
            this.timerTypeface = oldState.timerTypeface;
            this.customFontName = oldState.customFontName;
            this.outlineEnabled = oldState.outlineEnabled;
            this.outlineWidth = oldState.outlineWidth;
            this.outlineColor = oldState.outlineColor;
        }

        public Builder setScreenState(ScreenState screenState) { this.screenState = screenState; return this; }
        public Builder setIsLoading(boolean isLoading) { this.isLoading = isLoading; return this; }
        public Builder setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; return this; }
        public Builder setRenderProgress(int renderProgress) { this.renderProgress = renderProgress; return this; }
        public Builder setFinalVideoPath(String finalVideoPath) { this.finalVideoPath = finalVideoPath; return this; }
        public Builder setSelectedVideoUri(Uri selectedVideoUri) { this.selectedVideoUri = selectedVideoUri; return this; }
        public Builder setVideoProperties(VideoProperties videoProperties) { this.videoProperties = videoProperties; return this; }
        public Builder setCurrentFrame(int currentFrame) { this.currentFrame = currentFrame; return this; }
        public Builder setStartFrame(int startFrame) { this.startFrame = startFrame; return this; }
        public Builder setEndFrame(int endFrame) { this.endFrame = endFrame; return this; }
        public Builder setCurrentFrameBitmap(Bitmap bitmap) { this.currentFrameBitmap = bitmap; return this; }
        public Builder setTimerPositionX(float positionX) { this.timerPositionX = positionX; return this; }
        public Builder setTimerPositionY(float positionY) { this.timerPositionY = positionY; return this; }
        public Builder setTimerSize(int timerSize) { this.timerSize = timerSize; return this; }
        public Builder setTimerColor(int timerColor) { this.timerColor = timerColor; return this; }
        public Builder setTimerFormat(String format) { this.timerFormat = format; return this; }
        public Builder setCustomFont(@NonNull Typeface typeface, @Nullable String name) { this.timerTypeface = typeface; this.customFontName = name; return this; }
        public Builder setOutlineEnabled(boolean enabled) { this.outlineEnabled = enabled; return this; }
        public Builder setOutlineWidth(int width) { this.outlineWidth = width; return this; }
        public Builder setOutlineColor(int color) { this.outlineColor = color; return this; }

        public UiState build() {
            return new UiState(this);
        }
    }
}
