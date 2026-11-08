package com.pokeyellow.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;
import android.view.SurfaceHolder;

import com.pokeyellow.core.GameBoy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Runs the emulator, presents frames to the SurfaceView and streams audio.
 * Pacing comes from the blocking AudioTrack writes (falling back to a clock
 * when audio is unavailable or fast-forward is on).
 */
final class EmulatorThread extends Thread {
    private static final String TAG = "EmulatorThread";
    private static final int SAMPLE_RATE = 48000;
    private static final int FAST_FORWARD_SPEED = 4;
    private static final long SAVE_CHECK_INTERVAL_NS = 2_000_000_000L;

    private final GameBoy gb;
    private final Controls controls;
    private final File saveFile;

    private volatile SurfaceHolder holder;
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile boolean fastForward;

    private final Bitmap bitmap = Bitmap.createBitmap(GameBoy.WIDTH, GameBoy.HEIGHT, Bitmap.Config.ARGB_8888);
    private final Paint paint = new Paint();
    private final Rect dst = new Rect();
    private final Object lock = new Object();

    EmulatorThread(GameBoy gb, Controls controls, File saveFile) {
        super("Emulator");
        this.gb = gb;
        this.controls = controls;
        this.saveFile = saveFile;
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
    }

    void setSurface(SurfaceHolder h) { holder = h; }

    void setPaused(boolean p) {
        synchronized (lock) {
            paused = p;
            lock.notifyAll();
        }
    }

    boolean isFastForward() { return fastForward; }

    void setFastForward(boolean ff) { fastForward = ff; }

    /** Stops the thread, waits for it and flushes the save file. */
    void shutdown() {
        synchronized (lock) {
            running = false;
            lock.notifyAll();
        }
        interrupt();
        try {
            join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        writeSave(true);
    }

    @Override
    public void run() {
        AudioTrack track = createAudioTrack();
        short[] audio = new short[8192];
        long frameNs = (long) (1_000_000_000L / GameBoy.FPS);
        long nextFrame = System.nanoTime();
        long lastSaveCheck = System.nanoTime();
        if (track != null) track.play();

        while (running) {
            synchronized (lock) {
                while (paused && running) {
                    if (track != null) track.pause();
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        if (!running) break;
                    }
                    if (track != null && running) track.play();
                    nextFrame = System.nanoTime();
                }
            }
            if (!running) break;

            boolean ff = fastForward;
            int frames = ff ? FAST_FORWARD_SPEED : 1;
            for (int i = 0; i < frames; i++) {
                gb.setButtons(controls.buttons());
                gb.runFrame();
            }
            draw();

            if (track != null && !ff) {
                int n = gb.readAudio(audio);
                if (n > 0) track.write(audio, 0, n); // blocks -> paces emulation
            } else {
                gb.discardAudio();
                nextFrame += frameNs;
                long sleep = nextFrame - System.nanoTime();
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                    } catch (InterruptedException ignored) {
                        // shutting down
                    }
                } else if (sleep < -frameNs * 5) {
                    nextFrame = System.nanoTime(); // fell far behind, don't try to catch up
                }
            }

            long now = System.nanoTime();
            if (now - lastSaveCheck > SAVE_CHECK_INTERVAL_NS) {
                lastSaveCheck = now;
                writeSave(false);
            }
        }

        if (track != null) {
            track.pause();
            track.flush();
            track.release();
        }
    }

    private AudioTrack createAudioTrack() {
        try {
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            // About 3 frames of stereo 16-bit audio: low latency but enough headroom.
            int size = Math.max(min, (int) (SAMPLE_RATE / GameBoy.FPS) * 4 * 3);
            AudioTrack t;
            if (Build.VERSION.SDK_INT >= 23) {
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build())
                        .setBufferSizeInBytes(size)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                t = null;
            }
            if (t != null && t.getState() != AudioTrack.STATE_INITIALIZED) {
                t.release();
                return null;
            }
            return t;
        } catch (RuntimeException e) {
            Log.w(TAG, "Audio unavailable", e);
            return null;
        }
    }

    private void draw() {
        SurfaceHolder h = holder;
        if (h == null) return;
        bitmap.setPixels(gb.frameBuffer(), 0, GameBoy.WIDTH, 0, 0, GameBoy.WIDTH, GameBoy.HEIGHT);
        Canvas c = null;
        try {
            c = Build.VERSION.SDK_INT >= 26 ? h.lockHardwareCanvas() : h.lockCanvas();
            if (c == null) return;
            computeDestination(c.getWidth(), c.getHeight());
            c.drawColor(Color.BLACK);
            c.drawBitmap(bitmap, null, dst, paint);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Surface went away mid-frame.
            c = null;
        } finally {
            if (c != null) {
                try {
                    h.unlockCanvasAndPost(c);
                } catch (IllegalStateException | IllegalArgumentException ignored) {
                    // Surface released.
                }
            }
        }
    }

    /** Largest integer scale that fits (crisp pixels), centred; plain fit if the screen is tiny. */
    private void computeDestination(int w, int h) {
        int scale = Math.min(w / GameBoy.WIDTH, h / GameBoy.HEIGHT);
        int dw, dh;
        if (scale >= 1) {
            dw = GameBoy.WIDTH * scale;
            dh = GameBoy.HEIGHT * scale;
        } else {
            float s = Math.min((float) w / GameBoy.WIDTH, (float) h / GameBoy.HEIGHT);
            dw = (int) (GameBoy.WIDTH * s);
            dh = (int) (GameBoy.HEIGHT * s);
        }
        int left = (w - dw) / 2, top = (h - dh) / 2;
        dst.set(left, top, left + dw, top + dh);
    }

    /** Write the battery save now (called from the UI thread when the app is backgrounded). */
    void flushSave() {
        writeSave(true);
    }

    private synchronized void writeSave(boolean force) {
        if (!gb.cartridge().hasBattery()) return;
        if (!gb.cartridge().consumeRamDirty() && !force) return;
        File tmp = new File(saveFile.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(gb.cartridge().getRam());
            out.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write save", e);
            return;
        }
        if (!tmp.renameTo(saveFile)) Log.e(TAG, "Failed to replace save file");
    }
}
