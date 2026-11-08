package com.pokeyellow.core;

/**
 * Public entry point of the emulator core. Platform code feeds button state
 * in, calls {@link #runFrame()} ~59.7 times a second and pulls video/audio out.
 */
public final class GameBoy {
    public static final int WIDTH = Ppu.WIDTH;
    public static final int HEIGHT = Ppu.HEIGHT;
    /** Frames per second of the real hardware (4194304 / 70224). */
    public static final double FPS = 4194304.0 / 70224.0;

    public static final int BUTTON_A = 0x01;
    public static final int BUTTON_B = 0x02;
    public static final int BUTTON_SELECT = 0x04;
    public static final int BUTTON_START = 0x08;
    public static final int BUTTON_RIGHT = 0x10;
    public static final int BUTTON_LEFT = 0x20;
    public static final int BUTTON_UP = 0x40;
    public static final int BUTTON_DOWN = 0x80;

    private final Cartridge cart;
    private final Mmu mmu;
    private final Cpu cpu;
    private final Ppu ppu;
    private final Apu apu;

    public GameBoy(Cartridge cart, int audioSampleRate) {
        this.cart = cart;
        mmu = new Mmu(cart);
        ppu = new Ppu(mmu, cart.cgbSupported);
        apu = new Apu(audioSampleRate);
        mmu.ppu = ppu;
        mmu.apu = apu;
        cpu = new Cpu(mmu);
        cpu.resetCgb();
        mmu.write(0xFF40, 0x91);
        mmu.write(0xFF47, 0xFC);
    }

    public Cartridge cartridge() { return cart; }

    /** Set the currently held buttons (a mask of the BUTTON_* constants). */
    public void setButtons(int mask) {
        // Opposite directions can't be held on a real d-pad; some games misbehave if they are.
        if ((mask & (BUTTON_LEFT | BUTTON_RIGHT)) == (BUTTON_LEFT | BUTTON_RIGHT)) mask &= ~(BUTTON_LEFT | BUTTON_RIGHT);
        if ((mask & (BUTTON_UP | BUTTON_DOWN)) == (BUTTON_UP | BUTTON_DOWN)) mask &= ~(BUTTON_UP | BUTTON_DOWN);
        mmu.setButtons(mask);
    }

    /** Run until the next frame is complete. */
    public void runFrame() {
        ppu.frameReady = false;
        // Safety bound in case the LCD is in an unusual state.
        int budget = 70224 * 4;
        while (!ppu.frameReady && budget > 0) {
            int cycles = cpu.step();
            mmu.tickTimer(cycles);
            int dots = mmu.doubleSpeed ? cycles >> 1 : cycles;
            ppu.tick(dots);
            apu.tick(dots);
            budget -= dots;
        }
    }

    /** The last completed frame, 160x144 ARGB pixels. */
    public int[] frameBuffer() { return ppu.frame; }

    public int audioSampleRate() { return apu.sampleRate(); }

    /** Drain interleaved stereo 16-bit samples produced so far. Returns number of shorts copied. */
    public int readAudio(short[] out) { return apu.drainSamples(out); }

    /** Throw away buffered audio (e.g. while fast-forwarding). */
    public void discardAudio() { apu.discardSamples(); }

    /** Exposed for tests. */
    int readMemory(int addr) { return mmu.read(addr); }

    int pc() { return cpu.pc; }
}
