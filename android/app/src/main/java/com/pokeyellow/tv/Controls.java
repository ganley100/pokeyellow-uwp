package com.pokeyellow.tv;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe Game Boy button state, written by the UI thread and read by the
 * emulator thread. Keys, d-pad hats and analog sticks each own a separate
 * mask so releasing one source doesn't cancel another.
 */
final class Controls {
    private final AtomicInteger keys = new AtomicInteger();
    private final AtomicInteger hat = new AtomicInteger();
    private final AtomicInteger stick = new AtomicInteger();

    int buttons() {
        return keys.get() | hat.get() | stick.get();
    }

    void press(int button) {
        int v;
        do { v = keys.get(); } while (!keys.compareAndSet(v, v | button));
    }

    void release(int button) {
        int v;
        do { v = keys.get(); } while (!keys.compareAndSet(v, v & ~button));
    }

    void setHat(int mask) { hat.set(mask); }

    void setStick(int mask) { stick.set(mask); }

    void releaseAll() {
        keys.set(0);
        hat.set(0);
        stick.set(0);
    }
}
