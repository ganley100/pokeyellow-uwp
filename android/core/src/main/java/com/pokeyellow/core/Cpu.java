package com.pokeyellow.core;

/**
 * Sharp SM83 (Game Boy / Game Boy Color) CPU.
 *
 * Timing is instruction-granular: {@link #step()} executes one instruction
 * (or services one interrupt) and returns the number of T-cycles it took at
 * the current CPU clock.
 */
public final class Cpu {
    static final int FZ = 0x80, FN = 0x40, FH = 0x20, FC = 0x10;

    int a, f, b, c, d, e, h, l, sp, pc;
    boolean ime;
    private int imeDelay;          // EI enables interrupts after the next instruction
    boolean halted;
    boolean stopped;

    private final Mmu mmu;

    Cpu(Mmu mmu) {
        this.mmu = mmu;
    }

    /** Register state after the CGB boot ROM hands control to the cartridge. */
    void resetCgb() {
        a = 0x11; f = 0x80; b = 0x00; c = 0x00; d = 0xFF; e = 0x56; h = 0x00; l = 0x0D;
        sp = 0xFFFE; pc = 0x0100;
        ime = false; imeDelay = 0; halted = false; stopped = false;
    }

    // ---- memory helpers -------------------------------------------------

    private int rd(int addr) { return mmu.read(addr & 0xFFFF); }
    private void wr(int addr, int v) { mmu.write(addr & 0xFFFF, v & 0xFF); }

    private int fetch() {
        int v = mmu.read(pc);
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private int fetch16() {
        int lo = fetch();
        return lo | (fetch() << 8);
    }

    private void push(int v) {
        sp = (sp - 1) & 0xFFFF; wr(sp, v >> 8);
        sp = (sp - 1) & 0xFFFF; wr(sp, v);
    }

    private int pop() {
        int lo = rd(sp); sp = (sp + 1) & 0xFFFF;
        int hi = rd(sp); sp = (sp + 1) & 0xFFFF;
        return lo | (hi << 8);
    }

    int bc() { return (b << 8) | c; }
    int de() { return (d << 8) | e; }
    int hl() { return (h << 8) | l; }
    private void setBc(int v) { b = (v >> 8) & 0xFF; c = v & 0xFF; }
    private void setDe(int v) { d = (v >> 8) & 0xFF; e = v & 0xFF; }
    private void setHl(int v) { h = (v >> 8) & 0xFF; l = v & 0xFF; }

    private int getR(int i) {
        switch (i) {
            case 0: return b;
            case 1: return c;
            case 2: return d;
            case 3: return e;
            case 4: return h;
            case 5: return l;
            case 6: return rd(hl());
            default: return a;
        }
    }

    private void setR(int i, int v) {
        v &= 0xFF;
        switch (i) {
            case 0: b = v; break;
            case 1: c = v; break;
            case 2: d = v; break;
            case 3: e = v; break;
            case 4: h = v; break;
            case 5: l = v; break;
            case 6: wr(hl(), v); break;
            default: a = v; break;
        }
    }

    private boolean cond(int cc) {
        switch (cc) {
            case 0: return (f & FZ) == 0;
            case 1: return (f & FZ) != 0;
            case 2: return (f & FC) == 0;
            default: return (f & FC) != 0;
        }
    }

    // ---- ALU --------------------------------------------------------------

    private void alu(int op, int v) {
        int r;
        switch (op) {
            case 0: // ADD
                r = a + v;
                f = ((r & 0xFF) == 0 ? FZ : 0) | (((a & 0xF) + (v & 0xF)) > 0xF ? FH : 0) | (r > 0xFF ? FC : 0);
                a = r & 0xFF;
                break;
            case 1: { // ADC
                int cy = (f & FC) != 0 ? 1 : 0;
                r = a + v + cy;
                f = ((r & 0xFF) == 0 ? FZ : 0) | (((a & 0xF) + (v & 0xF) + cy) > 0xF ? FH : 0) | (r > 0xFF ? FC : 0);
                a = r & 0xFF;
                break;
            }
            case 2: // SUB
                r = a - v;
                f = FN | ((r & 0xFF) == 0 ? FZ : 0) | ((a & 0xF) < (v & 0xF) ? FH : 0) | (r < 0 ? FC : 0);
                a = r & 0xFF;
                break;
            case 3: { // SBC
                int cy = (f & FC) != 0 ? 1 : 0;
                r = a - v - cy;
                f = FN | ((r & 0xFF) == 0 ? FZ : 0) | (((a & 0xF) - (v & 0xF) - cy) < 0 ? FH : 0) | (r < 0 ? FC : 0);
                a = r & 0xFF;
                break;
            }
            case 4: // AND
                a &= v;
                f = (a == 0 ? FZ : 0) | FH;
                break;
            case 5: // XOR
                a = (a ^ v) & 0xFF;
                f = a == 0 ? FZ : 0;
                break;
            case 6: // OR
                a = (a | v) & 0xFF;
                f = a == 0 ? FZ : 0;
                break;
            default: // CP
                r = a - v;
                f = FN | ((r & 0xFF) == 0 ? FZ : 0) | ((a & 0xF) < (v & 0xF) ? FH : 0) | (r < 0 ? FC : 0);
                break;
        }
    }

    private int inc8(int v) {
        int r = (v + 1) & 0xFF;
        f = (f & FC) | (r == 0 ? FZ : 0) | ((v & 0xF) == 0xF ? FH : 0);
        return r;
    }

    private int dec8(int v) {
        int r = (v - 1) & 0xFF;
        f = (f & FC) | FN | (r == 0 ? FZ : 0) | ((v & 0xF) == 0 ? FH : 0);
        return r;
    }

    private void addHl(int v) {
        int hl = hl();
        int r = hl + v;
        f = (f & FZ) | (((hl & 0xFFF) + (v & 0xFFF)) > 0xFFF ? FH : 0) | (r > 0xFFFF ? FC : 0);
        setHl(r & 0xFFFF);
    }

    /** SP + signed 8-bit immediate, with the flag behaviour of ADD SP,e / LD HL,SP+e. */
    private int spPlusE() {
        int e8 = (byte) fetch();
        int r = (sp + e8) & 0xFFFF;
        f = (((sp & 0xF) + (e8 & 0xF)) > 0xF ? FH : 0) | (((sp & 0xFF) + (e8 & 0xFF)) > 0xFF ? FC : 0);
        return r;
    }

    private void daa() {
        int t = a;
        int corr = 0;
        boolean carry = (f & FC) != 0;
        if ((f & FN) == 0) {
            if ((f & FH) != 0 || (t & 0xF) > 9) corr |= 0x06;
            if (carry || t > 0x99) { corr |= 0x60; carry = true; }
            t += corr;
        } else {
            if ((f & FH) != 0) corr |= 0x06;
            if (carry) corr |= 0x60;
            t -= corr;
        }
        a = t & 0xFF;
        f = (f & FN) | (a == 0 ? FZ : 0) | (carry ? FC : 0);
    }

    // ---- CB-prefixed ------------------------------------------------------

    private int cb() {
        int op = fetch();
        int r = op & 7;
        int v = getR(r);
        int x = op >> 6;
        int y = (op >> 3) & 7;
        int cycles = r == 6 ? 16 : 8;
        if (x == 0) {
            int res;
            switch (y) {
                case 0: res = ((v << 1) | (v >> 7)) & 0xFF; f = (v & 0x80) != 0 ? FC : 0; break;          // RLC
                case 1: res = ((v >> 1) | (v << 7)) & 0xFF; f = (v & 1) != 0 ? FC : 0; break;              // RRC
                case 2: res = ((v << 1) | ((f & FC) != 0 ? 1 : 0)) & 0xFF; f = (v & 0x80) != 0 ? FC : 0; break; // RL
                case 3: res = (v >> 1) | ((f & FC) != 0 ? 0x80 : 0); f = (v & 1) != 0 ? FC : 0; break;     // RR
                case 4: res = (v << 1) & 0xFF; f = (v & 0x80) != 0 ? FC : 0; break;                        // SLA
                case 5: res = (v >> 1) | (v & 0x80); f = (v & 1) != 0 ? FC : 0; break;                     // SRA
                case 6: res = ((v << 4) | (v >> 4)) & 0xFF; f = 0; break;                                  // SWAP
                default: res = v >> 1; f = (v & 1) != 0 ? FC : 0; break;                                   // SRL
            }
            if (res == 0) f |= FZ;
            setR(r, res);
        } else if (x == 1) { // BIT
            f = (f & FC) | FH | ((v & (1 << y)) == 0 ? FZ : 0);
            if (r == 6) cycles = 12;
        } else if (x == 2) { // RES
            setR(r, v & ~(1 << y));
        } else { // SET
            setR(r, v | (1 << y));
        }
        return cycles;
    }

    // ---- main step --------------------------------------------------------

    int step() {
        int pending = mmu.ie & mmu.ifReg & 0x1F;
        if (halted || stopped) {
            if (pending != 0) {
                halted = false;
                stopped = false;
            } else {
                return 4;
            }
        }
        if (ime && pending != 0) {
            int bit = Integer.numberOfTrailingZeros(pending);
            ime = false;
            mmu.ifReg &= ~(1 << bit);
            push(pc);
            pc = 0x40 + bit * 8;
            return 20;
        }
        if (imeDelay > 0 && --imeDelay == 0) ime = true;
        return execute(fetch());
    }

    private int execute(int op) {
        if (op >= 0x40 && op < 0x80) {
            if (op == 0x76) { // HALT
                halted = true;
                return 4;
            }
            int dst = (op >> 3) & 7, src = op & 7;
            setR(dst, getR(src));
            return (dst == 6 || src == 6) ? 8 : 4;
        }
        if (op >= 0x80 && op < 0xC0) {
            int src = op & 7;
            alu((op >> 3) & 7, getR(src));
            return src == 6 ? 8 : 4;
        }
        int t;
        switch (op) {
            case 0x00: return 4; // NOP
            case 0x01: setBc(fetch16()); return 12;
            case 0x11: setDe(fetch16()); return 12;
            case 0x21: setHl(fetch16()); return 12;
            case 0x31: sp = fetch16(); return 12;
            case 0x02: wr(bc(), a); return 8;
            case 0x12: wr(de(), a); return 8;
            case 0x22: { int hl = hl(); wr(hl, a); setHl((hl + 1) & 0xFFFF); return 8; }
            case 0x32: { int hl = hl(); wr(hl, a); setHl((hl - 1) & 0xFFFF); return 8; }
            case 0x0A: a = rd(bc()); return 8;
            case 0x1A: a = rd(de()); return 8;
            case 0x2A: { int hl = hl(); a = rd(hl); setHl((hl + 1) & 0xFFFF); return 8; }
            case 0x3A: { int hl = hl(); a = rd(hl); setHl((hl - 1) & 0xFFFF); return 8; }
            case 0x03: setBc((bc() + 1) & 0xFFFF); return 8;
            case 0x13: setDe((de() + 1) & 0xFFFF); return 8;
            case 0x23: setHl((hl() + 1) & 0xFFFF); return 8;
            case 0x33: sp = (sp + 1) & 0xFFFF; return 8;
            case 0x0B: setBc((bc() - 1) & 0xFFFF); return 8;
            case 0x1B: setDe((de() - 1) & 0xFFFF); return 8;
            case 0x2B: setHl((hl() - 1) & 0xFFFF); return 8;
            case 0x3B: sp = (sp - 1) & 0xFFFF; return 8;
            case 0x09: addHl(bc()); return 8;
            case 0x19: addHl(de()); return 8;
            case 0x29: addHl(hl()); return 8;
            case 0x39: addHl(sp); return 8;
            case 0x04: case 0x0C: case 0x14: case 0x1C: case 0x24: case 0x2C: case 0x34: case 0x3C: {
                int r = (op >> 3) & 7;
                setR(r, inc8(getR(r)));
                return r == 6 ? 12 : 4;
            }
            case 0x05: case 0x0D: case 0x15: case 0x1D: case 0x25: case 0x2D: case 0x35: case 0x3D: {
                int r = (op >> 3) & 7;
                setR(r, dec8(getR(r)));
                return r == 6 ? 12 : 4;
            }
            case 0x06: case 0x0E: case 0x16: case 0x1E: case 0x26: case 0x2E: case 0x36: case 0x3E: {
                int r = (op >> 3) & 7;
                setR(r, fetch());
                return r == 6 ? 12 : 8;
            }
            case 0x07: f = (a & 0x80) != 0 ? FC : 0; a = ((a << 1) | (a >> 7)) & 0xFF; return 4; // RLCA
            case 0x0F: f = (a & 1) != 0 ? FC : 0; a = ((a >> 1) | (a << 7)) & 0xFF; return 4;    // RRCA
            case 0x17: t = (f & FC) != 0 ? 1 : 0; f = (a & 0x80) != 0 ? FC : 0; a = ((a << 1) | t) & 0xFF; return 4; // RLA
            case 0x1F: t = (f & FC) != 0 ? 0x80 : 0; f = (a & 1) != 0 ? FC : 0; a = (a >> 1) | t; return 4;          // RRA
            case 0x08: { int addr = fetch16(); wr(addr, sp); wr(addr + 1, sp >> 8); return 20; }
            case 0x10: // STOP
                fetch();
                if (mmu.prepareSpeedSwitch()) return 4;
                stopped = true;
                return 4;
            case 0x18: t = (byte) fetch(); pc = (pc + t) & 0xFFFF; return 12;
            case 0x20: case 0x28: case 0x30: case 0x38:
                t = (byte) fetch();
                if (cond((op >> 3) & 3)) { pc = (pc + t) & 0xFFFF; return 12; }
                return 8;
            case 0x27: daa(); return 4;
            case 0x2F: a ^= 0xFF; f |= FN | FH; return 4;              // CPL
            case 0x37: f = (f & FZ) | FC; return 4;                    // SCF
            case 0x3F: f = (f & FZ) | ((f & FC) ^ FC); return 4;      // CCF
            case 0xC0: case 0xC8: case 0xD0: case 0xD8:
                if (cond((op >> 3) & 3)) { pc = pop(); return 20; }
                return 8;
            case 0xC9: pc = pop(); return 16;
            case 0xD9: pc = pop(); ime = true; return 16;             // RETI
            case 0xC1: setBc(pop()); return 12;
            case 0xD1: setDe(pop()); return 12;
            case 0xE1: setHl(pop()); return 12;
            case 0xF1: t = pop(); a = t >> 8; f = t & 0xF0; return 12;
            case 0xC5: push(bc()); return 16;
            case 0xD5: push(de()); return 16;
            case 0xE5: push(hl()); return 16;
            case 0xF5: push((a << 8) | f); return 16;
            case 0xC2: case 0xCA: case 0xD2: case 0xDA:
                t = fetch16();
                if (cond((op >> 3) & 3)) { pc = t; return 16; }
                return 12;
            case 0xC3: pc = fetch16(); return 16;
            case 0xE9: pc = hl(); return 4;
            case 0xC4: case 0xCC: case 0xD4: case 0xDC:
                t = fetch16();
                if (cond((op >> 3) & 3)) { push(pc); pc = t; return 24; }
                return 12;
            case 0xCD: t = fetch16(); push(pc); pc = t; return 24;
            case 0xC7: case 0xCF: case 0xD7: case 0xDF: case 0xE7: case 0xEF: case 0xF7: case 0xFF:
                push(pc); pc = op & 0x38; return 16;
            case 0xC6: case 0xCE: case 0xD6: case 0xDE: case 0xE6: case 0xEE: case 0xF6: case 0xFE:
                alu((op >> 3) & 7, fetch()); return 8;
            case 0xCB: return cb();
            case 0xE0: wr(0xFF00 | fetch(), a); return 12;
            case 0xF0: a = rd(0xFF00 | fetch()); return 12;
            case 0xE2: wr(0xFF00 | c, a); return 8;
            case 0xF2: a = rd(0xFF00 | c); return 8;
            case 0xEA: wr(fetch16(), a); return 16;
            case 0xFA: a = rd(fetch16()); return 16;
            case 0xE8: sp = spPlusE(); return 16;
            case 0xF8: setHl(spPlusE()); return 12;
            case 0xF9: sp = hl(); return 8;
            case 0xF3: ime = false; imeDelay = 0; return 4;           // DI
            case 0xFB: if (!ime && imeDelay == 0) imeDelay = 1; return 4; // EI
            default:
                // Illegal opcode: real hardware locks up. Treat as a NOP so a
                // corrupted jump doesn't freeze the whole app.
                return 4;
        }
    }
}
