package com.pokeyellow.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.pokeyellow.core.Cartridge;
import com.pokeyellow.core.GameBoy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Full-screen game activity for Google TV / Android TV.
 *
 * TV remote mapping (most remotes only have a d-pad, OK and Back):
 *   D-pad = D-pad, OK = A, Back = B, hold Back = START, hold OK = menu
 *   (START / SELECT / fast-forward / exit).
 * Gamepads and keyboards map directly; see {@link #mapKey(int)}.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String ASSET_ROM = "pokeyellow.gbc";
    private static final String LOCAL_ROM = "rom.gbc";
    private static final int REQUEST_ROM = 1;

    private static final long BACK_HOLD_MS = 400;
    private static final long OK_HOLD_MS = 900;
    private static final long TAP_PRESS_MS = 120;
    private static final float STICK_THRESHOLD = 0.5f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Controls controls = new Controls();

    private SurfaceView surface;
    private TextView message;
    private GameBoy gb;
    private EmulatorThread emu;
    private SurfaceHolder surfaceHolder;
    private AlertDialog menu;
    private boolean resumed;

    private boolean backDown, backHeld;
    private boolean okDown, okMenuOpened;

    private final Runnable backHoldAction = () -> {
        if (backDown) {
            backHeld = true;
            controls.press(GameBoy.BUTTON_START);
        }
    };

    private final Runnable okHoldAction = () -> {
        if (okDown) {
            okMenuOpened = true;
            controls.release(GameBoy.BUTTON_A);
            showMenu();
        }
    };

    private final Runnable hideMessage = () -> message.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(18);
        message.setBackgroundColor(0xCC000000);
        message.setPadding(32, 20, 32, 20);
        message.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = 48;
        root.addView(message, lp);
        setContentView(root);
        hideSystemUi();

        loadGame();
    }

    // ---- ROM loading ------------------------------------------------------------

    private void loadGame() {
        byte[] rom;
        try {
            rom = findRom();
        } catch (IOException e) {
            showMessage(getString(R.string.rom_error, e.getMessage()), 0);
            return;
        }
        if (rom == null) {
            File ext = getExternalFilesDir(null);
            showMessage(getString(R.string.no_rom,
                    new File(ext != null ? ext : getFilesDir(), ASSET_ROM).getPath()), 0);
            return;
        }
        try {
            Cartridge cart = new Cartridge(rom);
            File save = saveFileFor(cart);
            if (save.exists()) cart.loadRam(Files.readAllBytes(save.toPath()));
            gb = new GameBoy(cart, 48000);
            emu = new EmulatorThread(gb, controls, save);
            emu.setSurface(surfaceHolder);
            emu.setPaused(!resumed);
            emu.start();
            showMessage(getString(R.string.controls_hint), 8000);
        } catch (IOException | IllegalArgumentException e) {
            showMessage(getString(R.string.rom_error, e.getMessage()), 0);
        }
    }

    /** Bundled asset first, then a file copied in by the user. */
    private byte[] findRom() throws IOException {
        try (InputStream in = getAssets().open(ASSET_ROM)) {
            return readAll(in);
        } catch (FileNotFoundException ignored) {
            // not bundled
        }
        File ext = getExternalFilesDir(null);
        File[] candidates = {
                ext != null ? new File(ext, ASSET_ROM) : null,
                new File(getFilesDir(), LOCAL_ROM),
        };
        for (File f : candidates) {
            if (f != null && f.isFile()) return Files.readAllBytes(f.toPath());
        }
        return null;
    }

    private File saveFileFor(Cartridge cart) {
        String name = cart.title.isEmpty() ? "game" : cart.title.replaceAll("[^A-Za-z0-9]+", "_");
        return new File(getFilesDir(), name + ".sav");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private void pickRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_ROM);
        } catch (ActivityNotFoundException e) {
            File ext = getExternalFilesDir(null);
            showMessage("This TV has no file picker. Copy the ROM with adb to:\n"
                    + new File(ext != null ? ext : getFilesDir(), ASSET_ROM).getPath(), 0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        File dest = new File(getFilesDir(), LOCAL_ROM);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("cannot open file");
            out.write(readAll(in));
        } catch (IOException e) {
            showMessage(getString(R.string.rom_error, e.getMessage()), 0);
            return;
        }
        loadGame();
    }

    // ---- lifecycle --------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hideSystemUi();
        if (emu != null && menu == null) emu.setPaused(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        controls.releaseAll();
        if (emu != null) emu.setPaused(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Flush the battery save whenever we leave the foreground (Home button etc).
        if (emu != null) emu.flushSave();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (emu != null) emu.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceHolder = holder;
        if (emu != null) emu.setSurface(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceHolder = holder;
        if (emu != null) emu.setSurface(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceHolder = null;
        if (emu != null) emu.setSurface(null);
    }

    // ---- input ------------------------------------------------------------------

    /** Direct key -> Game Boy button mapping for gamepads and keyboards. 0 = unmapped. */
    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W:
                return GameBoy.BUTTON_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_S:
                return GameBoy.BUTTON_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A:
                return GameBoy.BUTTON_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D:
                return GameBoy.BUTTON_RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_X:
                return GameBoy.BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_Z:
                return GameBoy.BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_SPACE:
                return GameBoy.BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_TAB: case KeyEvent.KEYCODE_DEL:
                return GameBoy.BUTTON_SELECT;
            default:
                return 0;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (emu == null) {
            // No game loaded: OK opens the file picker, everything else behaves normally.
            int k = event.getKeyCode();
            if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A)
                    && event.getAction() == KeyEvent.ACTION_UP) {
                pickRom();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        boolean first = down && event.getRepeatCount() == 0;
        int keyCode = event.getKeyCode();

        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                // Tap = B, hold = START.
                if (first) {
                    backDown = true;
                    backHeld = false;
                    handler.postDelayed(backHoldAction, BACK_HOLD_MS);
                } else if (up && backDown) {
                    backDown = false;
                    handler.removeCallbacks(backHoldAction);
                    if (backHeld) controls.release(GameBoy.BUTTON_START);
                    else tap(GameBoy.BUTTON_B);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Press = A, hold = menu.
                if (first) {
                    okDown = true;
                    okMenuOpened = false;
                    controls.press(GameBoy.BUTTON_A);
                    handler.postDelayed(okHoldAction, OK_HOLD_MS);
                } else if (up && okDown) {
                    okDown = false;
                    handler.removeCallbacks(okHoldAction);
                    if (!okMenuOpened) controls.release(GameBoy.BUTTON_A);
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_SETTINGS:
                if (first) showMenu();
                return true;

            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2:
                // Hold to fast-forward.
                if (first) emu.setFastForward(true);
                else if (up) emu.setFastForward(false);
                return true;

            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (first) emu.setFastForward(!emu.isFastForward());
                return true;

            default:
        }

        int button = mapKey(keyCode);
        if (button != 0) {
            if (down) controls.press(button);
            else if (up) controls.release(button);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Hold a button just long enough for the game to see it. */
    private void tap(int button) {
        controls.press(button);
        handler.postDelayed(() -> controls.release(button), TAP_PRESS_MS);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (emu != null && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            controls.setHat(axesToMask(event.getAxisValue(MotionEvent.AXIS_HAT_X),
                    event.getAxisValue(MotionEvent.AXIS_HAT_Y)));
            controls.setStick(axesToMask(event.getAxisValue(MotionEvent.AXIS_X),
                    event.getAxisValue(MotionEvent.AXIS_Y)));
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private static int axesToMask(float x, float y) {
        int mask = 0;
        if (x < -STICK_THRESHOLD) mask |= GameBoy.BUTTON_LEFT;
        if (x > STICK_THRESHOLD) mask |= GameBoy.BUTTON_RIGHT;
        if (y < -STICK_THRESHOLD) mask |= GameBoy.BUTTON_UP;
        if (y > STICK_THRESHOLD) mask |= GameBoy.BUTTON_DOWN;
        return mask;
    }

    // ---- menu -------------------------------------------------------------------

    private void showMenu() {
        if (menu != null || emu == null) return;
        controls.releaseAll();
        emu.setPaused(true);
        String[] items = {
                getString(R.string.menu_resume),
                getString(R.string.menu_start),
                getString(R.string.menu_select),
                getString(emu.isFastForward() ? R.string.menu_fast_forward_on : R.string.menu_fast_forward_off),
                getString(R.string.menu_controls),
                getString(R.string.menu_exit),
        };
        menu = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.menu_title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 1: handler.postDelayed(() -> tap(GameBoy.BUTTON_START), 50); break;
                        case 2: handler.postDelayed(() -> tap(GameBoy.BUTTON_SELECT), 50); break;
                        case 3: emu.setFastForward(!emu.isFastForward()); break;
                        case 4: showMessage(getString(R.string.controls_hint), 8000); break;
                        case 5: finish(); break;
                        default:
                    }
                })
                .create();
        menu.setOnDismissListener(d -> {
            menu = null;
            okDown = false;
            backDown = false;
            controls.releaseAll();
            if (emu != null && resumed) emu.setPaused(false);
            hideSystemUi();
        });
        menu.show();
    }

    private void showMessage(String text, long durationMs) {
        handler.removeCallbacks(hideMessage);
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        if (durationMs > 0) handler.postDelayed(hideMessage, durationMs);
    }
}
