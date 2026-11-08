package com.pokeyellow.core;

/**
 * Game Boy audio processing unit: two square channels, a wave channel and a
 * noise channel, mixed to interleaved 16-bit stereo PCM.
 *
 * Output is box-filtered over each output sample period, which keeps the
 * DC-level trick used by Yellow's Pikachu voice clips (toggling the wave
 * channel's volume register) audible.
 */
final class Apu {
    static final int CLOCK = 4194304;

    private static final int[][] DUTY = {
            {0, 0, 0, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 1, 1},
            {0, 1, 1, 1, 1, 1, 1, 0},
    };
    private static final int[] NOISE_DIVISORS = {8, 16, 32, 48, 64, 80, 96, 112};
    private static final int[] READ_MASK = {
            0x80, 0x3F, 0x00, 0xFF, 0xBF, // NR10-NR14
            0xFF, 0x3F, 0x00, 0xFF, 0xBF, // (unused), NR21-NR24
            0x7F, 0xFF, 0x9F, 0xFF, 0xBF, // NR30-NR34
            0xFF, 0xFF, 0x00, 0x00, 0xBF, // (unused), NR41-NR44
            0x00, 0x00, 0x70,             // NR50-NR52
    };

    private final int[] regs = new int[0x30];
    private final int[] wave = new int[16];
    private boolean enabled = true;

    private final Square ch1 = new Square(true);
    private final Square ch2 = new Square(false);
    private final Wave ch3 = new Wave();
    private final Noise ch4 = new Noise();

    private int frameSeqCounter;
    private int frameSeqStep;

    // output
    private final int sampleRate;
    private final long cyclesPerSampleFixed; // 16.16 fixed point
    private long sampleCounter;              // 16.16 fixed point
    private double accL, accR;
    private int accCycles;
    private double hpL, hpR;
    private final double hpCharge;
    private short[] buffer = new short[8192];
    private int bufferPos;

    Apu(int sampleRate) {
        this.sampleRate = sampleRate;
        cyclesPerSampleFixed = ((long) CLOCK << 16) / sampleRate;
        hpCharge = Math.pow(0.999958, (double) CLOCK / sampleRate);
        // Post-boot register values.
        write(0xFF26, 0xF1);
        write(0xFF24, 0x77);
        write(0xFF25, 0xF3);
    }

    int sampleRate() { return sampleRate; }

    /** Copies pending stereo samples into {@code out} and returns the count of shorts written. */
    int drainSamples(short[] out) {
        int n = Math.min(out.length, bufferPos);
        System.arraycopy(buffer, 0, out, 0, n);
        System.arraycopy(buffer, n, buffer, 0, bufferPos - n);
        bufferPos -= n;
        return n;
    }

    void discardSamples() { bufferPos = 0; }

    // ---- registers ------------------------------------------------------------

    int read(int addr) {
        if (addr >= 0xFF30) return wave[addr - 0xFF30] & 0xFF;
        int idx = addr - 0xFF10;
        if (idx >= READ_MASK.length) return 0xFF;
        if (addr == 0xFF26) {
            return (enabled ? 0x80 : 0) | 0x70
                    | (ch1.on ? 1 : 0) | (ch2.on ? 2 : 0) | (ch3.on ? 4 : 0) | (ch4.on ? 8 : 0);
        }
        return regs[idx] | READ_MASK[idx];
    }

    void write(int addr, int v) {
        if (addr >= 0xFF30) {
            wave[addr - 0xFF30] = v;
            return;
        }
        int idx = addr - 0xFF10;
        if (addr == 0xFF26) {
            boolean on = (v & 0x80) != 0;
            if (enabled && !on) {
                for (int i = 0; i < 0x16; i++) regs[i] = 0;
                ch1.reset(); ch2.reset(); ch3.on = false; ch4.on = false;
                ch3.dacOn = false;
            } else if (!enabled && on) {
                frameSeqStep = 0;
            }
            enabled = on;
            return;
        }
        if (!enabled || idx >= 0x17) return;
        regs[idx] = v;
        switch (addr) {
            case 0xFF10: ch1.sweepPeriod = (v >> 4) & 7; ch1.sweepNegate = (v & 8) != 0; ch1.sweepShift = v & 7; break;
            case 0xFF11: ch1.duty = v >> 6; ch1.length = 64 - (v & 0x3F); break;
            case 0xFF12: ch1.setEnvelope(v); break;
            case 0xFF13: ch1.freq = (ch1.freq & 0x700) | v; break;
            case 0xFF14: ch1.freq = (ch1.freq & 0xFF) | ((v & 7) << 8); ch1.lengthEnabled = (v & 0x40) != 0; if ((v & 0x80) != 0) ch1.trigger(); break;
            case 0xFF16: ch2.duty = v >> 6; ch2.length = 64 - (v & 0x3F); break;
            case 0xFF17: ch2.setEnvelope(v); break;
            case 0xFF18: ch2.freq = (ch2.freq & 0x700) | v; break;
            case 0xFF19: ch2.freq = (ch2.freq & 0xFF) | ((v & 7) << 8); ch2.lengthEnabled = (v & 0x40) != 0; if ((v & 0x80) != 0) ch2.trigger(); break;
            case 0xFF1A: ch3.dacOn = (v & 0x80) != 0; if (!ch3.dacOn) ch3.on = false; break;
            case 0xFF1B: ch3.length = 256 - v; break;
            case 0xFF1C: ch3.volumeCode = (v >> 5) & 3; break;
            case 0xFF1D: ch3.freq = (ch3.freq & 0x700) | v; break;
            case 0xFF1E: ch3.freq = (ch3.freq & 0xFF) | ((v & 7) << 8); ch3.lengthEnabled = (v & 0x40) != 0; if ((v & 0x80) != 0) ch3.trigger(); break;
            case 0xFF20: ch4.length = 64 - (v & 0x3F); break;
            case 0xFF21: ch4.setEnvelope(v); break;
            case 0xFF22: ch4.shift = v >> 4; ch4.width7 = (v & 8) != 0; ch4.divisor = NOISE_DIVISORS[v & 7]; break;
            case 0xFF23: ch4.lengthEnabled = (v & 0x40) != 0; if ((v & 0x80) != 0) ch4.trigger(); break;
            default:
        }
    }

    // ---- stepping -------------------------------------------------------------

    /** Advance by T-cycles at the normal-speed 4.19 MHz clock. */
    void tick(int cycles) {
        if (enabled) {
            frameSeqCounter += cycles;
            while (frameSeqCounter >= 8192) {
                frameSeqCounter -= 8192;
                clockFrameSequencer();
            }
            ch1.tick(cycles);
            ch2.tick(cycles);
            ch3.tick(cycles);
            ch4.tick(cycles);
        }
        mix(cycles);
    }

    private void clockFrameSequencer() {
        switch (frameSeqStep) {
            case 0: case 4: clockLength(); break;
            case 2: case 6: clockLength(); ch1.clockSweep(); break;
            case 7: ch1.clockEnvelope(); ch2.clockEnvelope(); ch4.clockEnvelope(); break;
            default:
        }
        frameSeqStep = (frameSeqStep + 1) & 7;
    }

    private void clockLength() {
        ch1.clockLength(); ch2.clockLength(); ch3.clockLength(); ch4.clockLength();
    }

    private void mix(int cycles) {
        double l = 0, r = 0;
        if (enabled) {
            int nr51 = regs[0x15];
            double o1 = ch1.dacOn() ? ch1.output() / 7.5 - 1.0 : 0;
            double o2 = ch2.dacOn() ? ch2.output() / 7.5 - 1.0 : 0;
            double o3 = ch3.dacOn ? ch3.output(wave) / 7.5 - 1.0 : 0;
            double o4 = ch4.dacOn() ? ch4.output() / 7.5 - 1.0 : 0;
            if ((nr51 & 0x01) != 0) r += o1;
            if ((nr51 & 0x02) != 0) r += o2;
            if ((nr51 & 0x04) != 0) r += o3;
            if ((nr51 & 0x08) != 0) r += o4;
            if ((nr51 & 0x10) != 0) l += o1;
            if ((nr51 & 0x20) != 0) l += o2;
            if ((nr51 & 0x40) != 0) l += o3;
            if ((nr51 & 0x80) != 0) l += o4;
            int nr50 = regs[0x14];
            l *= ((nr50 >> 4) & 7) + 1;
            r *= (nr50 & 7) + 1;
        }
        accL += l * cycles;
        accR += r * cycles;
        accCycles += cycles;
        sampleCounter += (long) cycles << 16;
        while (sampleCounter >= cyclesPerSampleFixed) {
            sampleCounter -= cyclesPerSampleFixed;
            double sl = accCycles > 0 ? accL / accCycles : l;
            double sr = accCycles > 0 ? accR / accCycles : r;
            accL = accR = 0;
            accCycles = 0;
            // High-pass filter (removes DC like the real output capacitor).
            double outL = sl - hpL;
            hpL = sl - outL * hpCharge;
            double outR = sr - hpR;
            hpR = sr - outR * hpCharge;
            emit(outL, outR);
        }
    }

    private void emit(double l, double r) {
        if (bufferPos + 2 > buffer.length) {
            // Host isn't draining fast enough; drop the oldest half.
            int half = buffer.length / 2;
            System.arraycopy(buffer, half, buffer, 0, buffer.length - half);
            bufferPos -= half;
        }
        // Max amplitude: 4 channels * 8 volume = 32.
        buffer[bufferPos++] = clamp(l * 900);
        buffer[bufferPos++] = clamp(r * 900);
    }

    private static short clamp(double v) {
        if (v > 32767) return 32767;
        if (v < -32768) return -32768;
        return (short) v;
    }

    // ---- channels ------------------------------------------------------------

    private abstract static class Envelope {
        boolean on;
        int length;
        boolean lengthEnabled;
        int initialVolume, volume, envPeriod, envTimer;
        boolean envUp;

        void setEnvelope(int v) {
            initialVolume = v >> 4;
            envUp = (v & 8) != 0;
            envPeriod = v & 7;
            if (!dacOn()) on = false;
        }

        boolean dacOn() { return initialVolume != 0 || envUp; }

        void clockEnvelope() {
            if (envPeriod == 0) return;
            if (--envTimer <= 0) {
                envTimer = envPeriod;
                if (envUp && volume < 15) volume++;
                else if (!envUp && volume > 0) volume--;
            }
        }

        void clockLength() {
            if (lengthEnabled && length > 0 && --length == 0) on = false;
        }

        void triggerEnvelope() {
            on = dacOn();
            if (length == 0) length = 64;
            volume = initialVolume;
            envTimer = envPeriod;
        }
    }

    private static final class Square extends Envelope {
        final boolean hasSweep;
        int duty, freq, timer, pos;
        int sweepPeriod, sweepShift, sweepTimer, shadowFreq;
        boolean sweepNegate, sweepEnabled;

        Square(boolean hasSweep) { this.hasSweep = hasSweep; }

        void reset() {
            on = false; duty = 0; freq = 0; length = 0; lengthEnabled = false;
            initialVolume = 0; envUp = false; envPeriod = 0; volume = 0;
            sweepPeriod = 0; sweepShift = 0; sweepNegate = false; sweepEnabled = false;
        }

        void trigger() {
            triggerEnvelope();
            timer = (2048 - freq) * 4;
            if (hasSweep) {
                shadowFreq = freq;
                sweepTimer = sweepPeriod == 0 ? 8 : sweepPeriod;
                sweepEnabled = sweepPeriod != 0 || sweepShift != 0;
                if (sweepShift != 0 && sweepCalc() > 2047) on = false;
            }
        }

        private int sweepCalc() {
            int delta = shadowFreq >> sweepShift;
            return sweepNegate ? shadowFreq - delta : shadowFreq + delta;
        }

        void clockSweep() {
            if (!hasSweep || --sweepTimer > 0) return;
            sweepTimer = sweepPeriod == 0 ? 8 : sweepPeriod;
            if (!sweepEnabled || sweepPeriod == 0) return;
            int nf = sweepCalc();
            if (nf > 2047) { on = false; return; }
            if (sweepShift != 0) {
                shadowFreq = nf;
                freq = nf;
                if (sweepCalc() > 2047) on = false;
            }
        }

        void tick(int cycles) {
            int period = (2048 - freq) * 4;
            timer -= cycles;
            while (timer <= 0) {
                timer += period;
                pos = (pos + 1) & 7;
            }
        }

        int output() {
            return on ? DUTY[duty][pos] * volume : 0;
        }
    }

    private static final class Wave {
        boolean on, dacOn, lengthEnabled;
        int length, volumeCode, freq, timer, pos;

        void trigger() {
            on = dacOn;
            if (length == 0) length = 256;
            timer = (2048 - freq) * 2;
            pos = 0;
        }

        void clockLength() {
            if (lengthEnabled && length > 0 && --length == 0) on = false;
        }

        void tick(int cycles) {
            int period = (2048 - freq) * 2;
            timer -= cycles;
            while (timer <= 0) {
                timer += period;
                pos = (pos + 1) & 31;
            }
        }

        int output(int[] wave) {
            if (!on || volumeCode == 0) return 0;
            int b = wave[pos >> 1];
            int s = (pos & 1) == 0 ? b >> 4 : b & 0xF;
            return s >> (volumeCode - 1);
        }
    }

    private static final class Noise extends Envelope {
        int shift, divisor = 8, timer;
        boolean width7;
        int lfsr = 0x7FFF;

        void trigger() {
            triggerEnvelope();
            lfsr = 0x7FFF;
            timer = divisor << shift;
        }

        void tick(int cycles) {
            if (shift >= 14) return;
            int period = divisor << shift;
            timer -= cycles;
            while (timer <= 0) {
                timer += period;
                int x = (lfsr ^ (lfsr >> 1)) & 1;
                lfsr = (lfsr >> 1) | (x << 14);
                if (width7) lfsr = (lfsr & ~0x40) | (x << 6);
            }
        }

        int output() {
            return on && (lfsr & 1) == 0 ? volume : 0;
        }
    }
}
