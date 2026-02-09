/*
 *
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.upnp.server.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import de.yaacc.util.YaaccLogger;

/**
 * Captures screen as MJPEG stream (Android 10+).
 *
 * @author tobexyz
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class ScreenCastCaptureService {

    private static final int FRAME_RATE = 15;
    private static final int JPEG_QUALITY = 75;

    private final Context context;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Thread captureThread;
    private volatile boolean isCapturing = false;
    private final List<PipedOutputStream> outputStreams = new java.util.concurrent.CopyOnWriteArrayList<>();

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    public ScreenCastCaptureService(Context context) {
        this.context = context;
        initScreenMetrics();
    }

    private void initScreenMetrics() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);

        screenWidth = 1280;
        screenHeight = 720;
        screenDensity = metrics.densityDpi;
    }

    public boolean startCapture(MediaProjection mediaProjection) {
        if (isCapturing) return false;

        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "YAACC-ScreenCapture", screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);

            isCapturing = true;

            captureThread = new Thread(this::captureLoop);
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();

            YaaccLogger.i(getClass().getName(), "Screen capture started (MJPEG)");
            return true;

        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Failed to start capture", e);
            stopCapture();
            return false;
        }
    }

    public void stopCapture() {
        isCapturing = false;

        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
            }
            captureThread = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        for (PipedOutputStream stream : outputStreams) {
            try {
                stream.close();
            } catch (IOException e) {
            }
        }
        outputStreams.clear();

        YaaccLogger.i(getClass().getName(), "Capture stopped");
    }

    public java.io.InputStream createInputStream() throws IOException {
        PipedOutputStream outputStream = new PipedOutputStream();
        PipedInputStream inputStream = new PipedInputStream(outputStream, 1024 * 1024);

        outputStreams.add(outputStream);
        YaaccLogger.i(getClass().getName(), "Created stream, clients: " + outputStreams.size());

        return inputStream;
    }

    public boolean isCapturing() {
        return isCapturing;
    }

    private void captureLoop() {
        long frameInterval = 1000 / FRAME_RATE;
        YaaccLogger.i(getClass().getName(), "Capture loop started");

        while (isCapturing && imageReader != null) {
            long frameStart = System.currentTimeMillis();

            try {
                Image image = imageReader.acquireLatestImage();
                if (image != null) {
                    YaaccLogger.d(getClass().getName(), "Got image");
                    try {
                        byte[] jpegData = imageToJpeg(image);

                        if (jpegData != null && jpegData.length > 0) {
                            List<PipedOutputStream> deadStreams = new java.util.ArrayList<>();

                            for (PipedOutputStream stream : outputStreams) {
                                try {
                                    String header = "--frame\r\n" +
                                            "Content-Type: image/jpeg\r\n" +
                                            "Content-Length: " + jpegData.length + "\r\n\r\n";
                                    stream.write(header.getBytes());
                                    stream.write(jpegData);
                                    stream.write("\r\n".getBytes());
                                    stream.flush();
                                } catch (IOException e) {
                                    deadStreams.add(stream);
                                }
                            }

                            for (PipedOutputStream dead : deadStreams) {
                                outputStreams.remove(dead);
                                try {
                                    dead.close();
                                } catch (IOException e) {
                                }
                            }
                        }
                    } finally {
                        image.close();
                    }
                }

                long elapsed = System.currentTimeMillis() - frameStart;
                long sleep = frameInterval - elapsed;
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (isCapturing) YaaccLogger.e(getClass().getName(), "Capture error", e);
            }
        }
    }

    private byte[] imageToJpeg(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            if (rowPadding != 0) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            bitmap.recycle();

            return out.toByteArray();
        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Error converting image", e);
            return null;
        }
    }
}
