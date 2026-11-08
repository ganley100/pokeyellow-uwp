package com.pokeyellow.core;

/** Cartridge ROM/RAM with MBC1, MBC3 (no RTC) and MBC5 banking. */
public final class Cartridge {
    private enum Mbc { NONE, MBC1, MBC3, MBC5 }

    private final byte[] rom;
    private final byte[] ram;
    private final Mbc mbc;
    private final boolean battery;
    private final int romBanks;
    private final int ramBanks;

    private boolean ramEnabled;
    private int romBank = 1;
    private int ramBank;
    private int mbc1Mode;
    private boolean ramDirty;

    public final String title;
    public final boolean cgbSupported;

    public Cartridge(byte[] rom) {
        if (rom.length < 0x8000) throw new IllegalArgumentException("ROM is too small (" + rom.length + " bytes)");
        this.rom = rom;
        int type = rom[0x147] & 0xFF;
        switch (type) {
            case 0x00: case 0x08: case 0x09: mbc = Mbc.NONE; break;
            case 0x01: case 0x02: case 0x03: mbc = Mbc.MBC1; break;
            case 0x0F: case 0x10: case 0x11: case 0x12: case 0x13: mbc = Mbc.MBC3; break;
            case 0x19: case 0x1A: case 0x1B: case 0x1C: case 0x1D: case 0x1E: mbc = Mbc.MBC5; break;
            default: throw new IllegalArgumentException(String.format("Unsupported cartridge type 0x%02X", type));
        }
        battery = type == 0x03 || type == 0x09 || type == 0x0F || type == 0x10 || type == 0x13
                || type == 0x1B || type == 0x1E;
        romBanks = Math.max(2, rom.length / 0x4000);
        int ramSizeCode = rom[0x149] & 0xFF;
        int[] ramSizes = {0, 0x800, 0x2000, 0x8000, 0x20000, 0x10000};
        int ramSize = ramSizeCode < ramSizes.length ? ramSizes[ramSizeCode] : 0;
        ram = new byte[Math.max(ramSize, 0x2000)];
        ramBanks = Math.max(1, ram.length / 0x2000);

        StringBuilder sb = new StringBuilder();
        for (int i = 0x134; i < 0x143; i++) {
            int ch = rom[i] & 0xFF;
            if (ch == 0) break;
            sb.append((char) ch);
        }
        title = sb.toString().trim();
        cgbSupported = (rom[0x143] & 0x80) != 0;
    }

    public boolean hasBattery() { return battery; }

    /** Battery-backed RAM contents, for writing a .sav file. */
    public byte[] getRam() { return ram; }

    public void loadRam(byte[] data) {
        System.arraycopy(data, 0, ram, 0, Math.min(data.length, ram.length));
        ramDirty = false;
    }

    /** True if cartridge RAM was written since the last call. */
    public boolean consumeRamDirty() {
        boolean d = ramDirty;
        ramDirty = false;
        return d;
    }

    int read(int addr) {
        if (addr < 0x4000) {
            if (mbc == Mbc.MBC1 && mbc1Mode == 1) {
                int bank = ((ramBank & 3) << 5) % romBanks;
                return rom[bank * 0x4000 + addr] & 0xFF;
            }
            return rom[addr] & 0xFF;
        }
        if (addr < 0x8000) {
            int bank = romBank % romBanks;
            return rom[bank * 0x4000 + (addr - 0x4000)] & 0xFF;
        }
        // 0xA000-0xBFFF
        if (!ramEnabled) return 0xFF;
        return ram[ramOffset(addr)] & 0xFF;
    }

    void write(int addr, int v) {
        if (addr >= 0xA000) {
            if (!ramEnabled) return;
            int off = ramOffset(addr);
            if (ram[off] != (byte) v) {
                ram[off] = (byte) v;
                ramDirty = true;
            }
            return;
        }
        switch (mbc) {
            case NONE:
                break;
            case MBC1:
                if (addr < 0x2000) ramEnabled = (v & 0xF) == 0xA;
                else if (addr < 0x4000) {
                    int lo = v & 0x1F;
                    if (lo == 0) lo = 1;
                    romBank = (romBank & 0x60) | lo;
                } else if (addr < 0x6000) {
                    ramBank = v & 3;
                    romBank = (romBank & 0x1F) | ((v & 3) << 5);
                } else mbc1Mode = v & 1;
                break;
            case MBC3:
                if (addr < 0x2000) ramEnabled = (v & 0xF) == 0xA;
                else if (addr < 0x4000) {
                    romBank = v & 0x7F;
                    if (romBank == 0) romBank = 1;
                } else if (addr < 0x6000) ramBank = v & 0x0F; // 0x08-0x0C would be RTC registers
                break;
            case MBC5:
                if (addr < 0x2000) ramEnabled = (v & 0xF) == 0xA;
                else if (addr < 0x3000) romBank = (romBank & 0x100) | v;
                else if (addr < 0x4000) romBank = (romBank & 0xFF) | ((v & 1) << 8);
                else if (addr < 0x6000) ramBank = v & 0x0F;
                break;
        }
    }

    private int ramOffset(int addr) {
        int bank;
        if (mbc == Mbc.MBC1) bank = mbc1Mode == 1 ? ramBank : 0;
        else bank = ramBank;
        return ((bank % ramBanks) * 0x2000 + (addr - 0xA000)) % ram.length;
    }
}
