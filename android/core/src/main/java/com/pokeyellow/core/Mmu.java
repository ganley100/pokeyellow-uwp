package com.pokeyellow.core;

/**
 * Memory bus: routes reads/writes to the cartridge, PPU, APU and internal RAM,
 * and owns the small IO blocks (joypad, timer, interrupts, WRAM banking,
 * CGB speed switch and HDMA).
 */
final class Mmu {
    static final int INT_VBLANK = 0x01, INT_STAT = 0x02, INT_TIMER = 0x04, INT_SERIAL = 0x08, INT_JOYPAD = 0x10;

    private final Cartridge cart;
    Ppu ppu;
    Apu apu;

    private final byte[] wram = new byte[0x8000]; // 8 x 4 KiB banks (CGB)
    private final byte[] hram = new byte[0x7F];
    private int wramBank = 1;

    int ie;
    int ifReg = 0xE1;

    // Joypad
    private int joypSelect = 0x30;
    private int buttons;   // bit set = pressed; bits 0-3: A,B,Select,Start; 4-7: Right,Left,Up,Down

    // Timer
    private int divCounter;   // 16-bit internal divider; DIV is the upper byte
    private int tima, tma, tac;

    // Serial (no link cable: transfers complete immediately with no partner)
    private int sb, sc;

    // CGB speed switch
    boolean doubleSpeed;
    private boolean speedSwitchArmed;

    // HDMA
    private int hdmaSrc, hdmaDst;
    private int hdmaRemaining;   // number of 16-byte blocks remaining
    private boolean hdmaActive;

    Mmu(Cartridge cart) {
        this.cart = cart;
    }

    // ---- input ------------------------------------------------------------

    void setButtons(int newButtons) {
        int pressedNow = newButtons & ~buttons;
        buttons = newButtons;
        if (pressedNow != 0) ifReg |= INT_JOYPAD;
    }

    private int readJoyp() {
        int lo = 0x0F;
        if ((joypSelect & 0x10) == 0) lo &= ~((buttons >> 4) & 0x0F); // directions
        if ((joypSelect & 0x20) == 0) lo &= ~(buttons & 0x0F);        // buttons
        return 0xC0 | joypSelect | lo;
    }

    // ---- timer --------------------------------------------------------------

    /** Advance the divider/timer by CPU T-cycles. */
    void tickTimer(int cycles) {
        if ((tac & 4) == 0) {
            divCounter = (divCounter + cycles) & 0xFFFF;
            return;
        }
        int bit;
        switch (tac & 3) {
            case 0: bit = 9; break;
            case 1: bit = 3; break;
            case 2: bit = 5; break;
            default: bit = 7; break;
        }
        int old = divCounter;
        int now = old + cycles;
        // Number of falling edges of the selected bit between old and now.
        int edges = (now >> (bit + 1)) - (old >> (bit + 1));
        divCounter = now & 0xFFFF;
        for (int i = 0; i < edges; i++) {
            tima++;
            if (tima > 0xFF) {
                tima = tma;
                ifReg |= INT_TIMER;
            }
        }
    }

    // ---- speed switch ----------------------------------------------------

    /** Called on STOP. Returns true if a pending CGB speed switch happened. */
    boolean prepareSpeedSwitch() {
        if (!speedSwitchArmed) return false;
        speedSwitchArmed = false;
        doubleSpeed = !doubleSpeed;
        divCounter = 0;
        return true;
    }

    // ---- HDMA -------------------------------------------------------------

    private void hdmaCopyBlock() {
        for (int i = 0; i < 16; i++) {
            ppu.writeVram(0x8000 | ((hdmaDst + i) & 0x1FFF), read((hdmaSrc + i) & 0xFFFF));
        }
        hdmaSrc = (hdmaSrc + 16) & 0xFFFF;
        hdmaDst = (hdmaDst + 16) & 0x1FFF;
        hdmaRemaining--;
    }

    /** Called by the PPU at the start of each visible-line HBlank. */
    void onHBlank() {
        if (!hdmaActive) return;
        hdmaCopyBlock();
        if (hdmaRemaining <= 0) hdmaActive = false;
    }

    private void writeHdma5(int v) {
        if (hdmaActive && (v & 0x80) == 0) {
            hdmaActive = false; // cancel HBlank DMA
            return;
        }
        hdmaRemaining = (v & 0x7F) + 1;
        if ((v & 0x80) != 0) {
            hdmaActive = true;
            if (!ppu.lcdOn()) onHBlank();
        } else {
            while (hdmaRemaining > 0) hdmaCopyBlock();
        }
    }

    private int readHdma5() {
        if (hdmaActive) return (hdmaRemaining - 1) & 0x7F;
        return 0x80 | ((hdmaRemaining - 1) & 0x7F);
    }

    // ---- bus ----------------------------------------------------------------

    int read(int addr) {
        switch (addr >> 12) {
            case 0x0: case 0x1: case 0x2: case 0x3:
            case 0x4: case 0x5: case 0x6: case 0x7:
                return cart.read(addr);
            case 0x8: case 0x9:
                return ppu.readVram(addr);
            case 0xA: case 0xB:
                return cart.read(addr);
            case 0xC:
                return wram[addr & 0x0FFF] & 0xFF;
            case 0xD:
                return wram[wramBank * 0x1000 + (addr & 0x0FFF)] & 0xFF;
            case 0xE:
                return wram[addr & 0x0FFF] & 0xFF;
            default:
                if (addr < 0xFE00) return wram[wramBank * 0x1000 + (addr & 0x0FFF)] & 0xFF;
                if (addr < 0xFEA0) return ppu.readOam(addr);
                if (addr < 0xFF00) return 0x00;
                if (addr >= 0xFF80) {
                    if (addr == 0xFFFF) return ie;
                    return hram[addr - 0xFF80] & 0xFF;
                }
                return readIo(addr);
        }
    }

    void write(int addr, int v) {
        switch (addr >> 12) {
            case 0x0: case 0x1: case 0x2: case 0x3:
            case 0x4: case 0x5: case 0x6: case 0x7:
                cart.write(addr, v);
                return;
            case 0x8: case 0x9:
                ppu.writeVram(addr, v);
                return;
            case 0xA: case 0xB:
                cart.write(addr, v);
                return;
            case 0xC:
                wram[addr & 0x0FFF] = (byte) v;
                return;
            case 0xD:
                wram[wramBank * 0x1000 + (addr & 0x0FFF)] = (byte) v;
                return;
            case 0xE:
                wram[addr & 0x0FFF] = (byte) v;
                return;
            default:
                if (addr < 0xFE00) { wram[wramBank * 0x1000 + (addr & 0x0FFF)] = (byte) v; return; }
                if (addr < 0xFEA0) { ppu.writeOam(addr, v); return; }
                if (addr < 0xFF00) return;
                if (addr >= 0xFF80) {
                    if (addr == 0xFFFF) ie = v;
                    else hram[addr - 0xFF80] = (byte) v;
                    return;
                }
                writeIo(addr, v);
        }
    }

    private int readIo(int addr) {
        switch (addr) {
            case 0xFF00: return readJoyp();
            case 0xFF01: return sb;
            case 0xFF02: return sc | 0x7E;
            case 0xFF04: return (divCounter >> 8) & 0xFF;
            case 0xFF05: return tima;
            case 0xFF06: return tma;
            case 0xFF07: return tac | 0xF8;
            case 0xFF0F: return ifReg | 0xE0;
            case 0xFF4D: return (doubleSpeed ? 0x80 : 0) | (speedSwitchArmed ? 1 : 0) | 0x7E;
            case 0xFF51: case 0xFF52: case 0xFF53: case 0xFF54: return 0xFF;
            case 0xFF55: return readHdma5();
            case 0xFF70: return wramBank | 0xF8;
            default:
                if (addr >= 0xFF10 && addr < 0xFF40) return apu.read(addr);
                if (addr >= 0xFF40 && addr < 0xFF70) return ppu.readIo(addr);
                return 0xFF;
        }
    }

    private void writeIo(int addr, int v) {
        switch (addr) {
            case 0xFF00: joypSelect = v & 0x30; return;
            case 0xFF01: sb = v; return;
            case 0xFF02:
                sc = v;
                if ((v & 0x81) == 0x81) {
                    // Internal clock with no link partner: shift in 0xFF.
                    sb = 0xFF;
                    sc &= 0x7F;
                    ifReg |= INT_SERIAL;
                }
                return;
            case 0xFF04: divCounter = 0; return;
            case 0xFF05: tima = v; return;
            case 0xFF06: tma = v; return;
            case 0xFF07: tac = v & 7; return;
            case 0xFF0F: ifReg = v & 0x1F; return;
            case 0xFF46: { // OAM DMA (performed instantly)
                int src = v << 8;
                for (int i = 0; i < 0xA0; i++) ppu.writeOam(0xFE00 + i, read(src + i));
                return;
            }
            case 0xFF4D: speedSwitchArmed = (v & 1) != 0; return;
            case 0xFF51: hdmaSrc = (hdmaSrc & 0x00FF) | (v << 8); return;
            case 0xFF52: hdmaSrc = (hdmaSrc & 0xFF00) | (v & 0xF0); return;
            case 0xFF53: hdmaDst = (hdmaDst & 0x00FF) | ((v & 0x1F) << 8); return;
            case 0xFF54: hdmaDst = (hdmaDst & 0x1F00) | (v & 0xF0); return;
            case 0xFF55: writeHdma5(v); return;
            case 0xFF70: wramBank = (v & 7) == 0 ? 1 : (v & 7); return;
            default:
                if (addr >= 0xFF10 && addr < 0xFF40) { apu.write(addr, v); return; }
                if (addr >= 0xFF40 && addr < 0xFF70) ppu.writeIo(addr, v);
        }
    }
}
