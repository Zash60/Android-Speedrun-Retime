package com.example.speedruneditor.java;

public class VideoProperties {
    public final int width;
    public final int height;
    public final double fps;
    public final double duration;

    public VideoProperties(int width, int height, double fps, double duration) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.duration = duration;
    }
}
