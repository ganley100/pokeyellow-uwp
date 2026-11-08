package com.pokeyellow.core;

/**
 * Game Boy Color picture processing unit. Renders each scanline in one go
 * when the line enters HBlank, which is accurate enough for games that only
 * change scroll/palette registers between lines.
 */
final class Ppu {
    static final int WIDTH = 160, HEIGHT = 144;
    private static final int LINE_DOTS = 456;
    private static final int MODE2_END = 80;
    private static final int MODE3_END = 252;

    private final Mmu mmu;
    private final boolean cgb;

    private final byte[] vram = new byte[0x4000]; // 2 banks
    private final byte[] oam = new byte[0xA0];
    private int vramBank;

    private final byte[] bgPal = new byte[64];
    private final byte[] objPal = new byte[64];
    private int bcps, ocps;

    private int lcdc = 0x91, stat, scy, scx, ly, lyc, bgp = 0xFC, obp0 = 0xFF, obp1 = 0xFF, wy, wx;
    private int mode;
    private int dots;
    private int windowLine;
    private boolean statLine;

    /** ARGB output, written while drawing; copied to {@link #frame} at VBlank. */
    private final int[] back = new int[WIDTH * HEIGHT];
    final int[] frame = new int[WIDTH * HEIGHT];
    boolean frameReady;

    // per-line scratch
    private final int[] lineColorIndex = new int[WIDTH];
    private final boolean[] lineBgPriority = new boolean[WIDTH];

    // Color lookup: 15-bit BGR -> ARGB
    private static final int[] COLOR_LUT = new int[0x8000];
    private static final int[] DMG_SHADES = {0xFFE0F8D0, 0xFF88C070, 0xFF346856, 0xFF081820};

    static {
        for (int i = 0; i < 0x8000; i++) {
            int r = i & 0x1F, g = (i >> 5) & 0x1F, b = (i >> 10) & 0x1F;
            // Light colour correction approximating the GBC LCD, which is less
            // saturated than a modern TV.
            int rr = (r * 26 + g * 4 + b * 2);
            int gg = (g * 24 + b * 8);
            int bb = (r * 6 + g * 4 + b * 22);
            rr = Math.min(960, rr) >> 2;
            gg = Math.min(960, gg) >> 2;
            bb = Math.min(960, bb) >> 2;
            COLOR_LUT[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }
    }

    Ppu(Mmu mmu, boolean cgb) {
        this.mmu = mmu;
        this.cgb = cgb;
        for (int i = 0; i < 64; i++) { bgPal[i] = (byte) 0xFF; objPal[i] = (byte) 0xFF; }
        mode = 2;
    }

    boolean lcdOn() { return (lcdc & 0x80) != 0; }

    // ---- memory -------------------------------------------------------------

    int readVram(int addr) {
        return vram[vramBank * 0x2000 + (addr & 0x1FFF)] & 0xFF;
    }

    void writeVram(int addr, int v) {
        vram[vramBank * 0x2000 + (addr & 0x1FFF)] = (byte) v;
    }

    int readOam(int addr) { return oam[addr - 0xFE00] & 0xFF; }

    void writeOam(int addr, int v) { oam[addr - 0xFE00] = (byte) v; }

    int readIo(int addr) {
        switch (addr) {
            case 0xFF40: return lcdc;
            case 0xFF41: return 0x80 | (stat & 0x78) | (ly == lyc ? 4 : 0) | (lcdOn() ? mode : 0);
            case 0xFF42: return scy;
            case 0xFF43: return scx;
            case 0xFF44: return ly;
            case 0xFF45: return lyc;
            case 0xFF47: return bgp;
            case 0xFF48: return obp0;
            case 0xFF49: return obp1;
            case 0xFF4A: return wy;
            case 0xFF4B: return wx;
            case 0xFF4F: return vramBank | 0xFE;
            case 0xFF68: return bcps | 0x40;
            case 0xFF69: return bgPal[bcps & 0x3F] & 0xFF;
            case 0xFF6A: return ocps | 0x40;
            case 0xFF6B: return objPal[ocps & 0x3F] & 0xFF;
            default: return 0xFF;
        }
    }

    void writeIo(int addr, int v) {
        switch (addr) {
            case 0xFF40: {
                boolean wasOn = lcdOn();
                lcdc = v;
                if (wasOn && !lcdOn()) {
                    ly = 0; dots = 0; mode = 0; windowLine = 0;
                    statLine = false;
                } else if (!wasOn && lcdOn()) {
                    ly = 0; dots = 0; mode = 2; windowLine = 0;
                    updateStat();
                }
                return;
            }
            case 0xFF41: stat = v & 0x78; updateStat(); return;
            case 0xFF42: scy = v; return;
            case 0xFF43: scx = v; return;
            case 0xFF44: return; // read-only
            case 0xFF45: lyc = v; updateStat(); return;
            case 0xFF47: bgp = v; return;
            case 0xFF48: obp0 = v; return;
            case 0xFF49: obp1 = v; return;
            case 0xFF4A: wy = v; return;
            case 0xFF4B: wx = v; return;
            case 0xFF4F: if (cgb) vramBank = v & 1; return;
            case 0xFF68: bcps = v & 0xBF; return;
            case 0xFF69:
                bgPal[bcps & 0x3F] = (byte) v;
                if ((bcps & 0x80) != 0) bcps = 0x80 | ((bcps + 1) & 0x3F);
                return;
            case 0xFF6A: ocps = v & 0xBF; return;
            case 0xFF6B:
                objPal[ocps & 0x3F] = (byte) v;
                if ((ocps & 0x80) != 0) ocps = 0x80 | ((ocps + 1) & 0x3F);
                return;
            default:
        }
    }

    // ---- timing ---------------------------------------------------------------

    private void updateStat() {
        if (!lcdOn()) { statLine = false; return; }
        boolean line = ((stat & 0x40) != 0 && ly == lyc)
                || ((stat & 0x08) != 0 && mode == 0)
                || ((stat & 0x10) != 0 && mode == 1)
                || ((stat & 0x20) != 0 && mode == 2);
        if (line && !statLine) mmu.ifReg |= Mmu.INT_STAT;
        statLine = line;
    }

    /** Advance by PPU dots (= T-cycles at normal speed). */
    void tick(int cycles) {
        if (!lcdOn()) {
            // While the LCD is off, still produce frames so the host keeps pace.
            dots += cycles;
            if (dots >= LINE_DOTS * 154) {
                dots -= LINE_DOTS * 154;
                java.util.Arrays.fill(frame, 0xFFFFFFFF);
                frameReady = true;
            }
            return;
        }
        dots += cycles;
        while (true) {
            if (ly < HEIGHT) {
                if (mode == 2 && dots >= MODE2_END) {
                    mode = 3;
                    updateStat();
                } else if (mode == 3 && dots >= MODE3_END) {
                    mode = 0;
                    renderLine();
                    updateStat();
                    mmu.onHBlank();
                } else if (mode == 0 && dots >= LINE_DOTS) {
                    dots -= LINE_DOTS;
                    ly++;
                    if (ly == HEIGHT) {
                        mode = 1;
                        mmu.ifReg |= Mmu.INT_VBLANK;
                        System.arraycopy(back, 0, frame, 0, back.length);
                        frameReady = true;
                    } else {
                        mode = 2;
                    }
                    updateStat();
                } else {
                    break;
                }
            } else {
                if (dots >= LINE_DOTS) {
                    dots -= LINE_DOTS;
                    ly++;
                    if (ly > 153) {
                        ly = 0;
                        windowLine = 0;
                        mode = 2;
                    }
                    updateStat();
                } else {
                    break;
                }
            }
        }
    }

    // ---- rendering ----------------------------------------------------------

    private int cgbColor(byte[] pal, int palette, int index) {
        int off = palette * 8 + index * 2;
        int c = (pal[off] & 0xFF) | ((pal[off + 1] & 0x7F) << 8);
        return COLOR_LUT[c];
    }

    private void renderLine() {
        int base = ly * WIDTH;
        boolean bgEnabled = (lcdc & 0x01) != 0;

        if (cgb || bgEnabled) {
            renderBackground(base);
        } else {
            for (int x = 0; x < WIDTH; x++) {
                back[base + x] = DMG_SHADES[0];
                lineColorIndex[x] = 0;
                lineBgPriority[x] = false;
            }
        }
        if ((lcdc & 0x02) != 0) renderSprites(base);
    }

    private void renderBackground(int base) {
        boolean windowEnabled = (lcdc & 0x20) != 0 && wy <= ly && wx <= 166;
        int winX = wx - 7;
        boolean usedWindow = false;
        boolean signedTiles = (lcdc & 0x10) == 0;

        for (int x = 0; x < WIDTH; x++) {
            int mapBase, px, py;
            if (windowEnabled && x >= winX) {
                mapBase = (lcdc & 0x40) != 0 ? 0x1C00 : 0x1800;
                px = x - winX;
                py = windowLine;
                usedWindow = true;
            } else {
                mapBase = (lcdc & 0x08) != 0 ? 0x1C00 : 0x1800;
                px = (x + scx) & 0xFF;
                py = (ly + scy) & 0xFF;
            }
            int mapAddr = mapBase + ((py >> 3) << 5) + (px >> 3);
            int tile = vram[mapAddr] & 0xFF;
            int attr = cgb ? vram[0x2000 + mapAddr] & 0xFF : 0;

            int tileAddr = signedTiles ? 0x1000 + ((byte) tile) * 16 : tile * 16;
            if ((attr & 0x08) != 0) tileAddr += 0x2000;
            int row = py & 7;
            if ((attr & 0x40) != 0) row = 7 - row;
            int col = px & 7;
            if ((attr & 0x20) != 0) col = 7 - col;
            int lo = vram[tileAddr + row * 2] & 0xFF;
            int hi = vram[tileAddr + row * 2 + 1] & 0xFF;
            int bit = 7 - col;
            int ci = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);

            lineColorIndex[x] = ci;
            lineBgPriority[x] = (attr & 0x80) != 0;
            if (cgb) {
                back[base + x] = cgbColor(bgPal, attr & 7, ci);
            } else {
                back[base + x] = DMG_SHADES[(bgp >> (ci * 2)) & 3];
            }
        }
        if (usedWindow) windowLine++;
    }

    private void renderSprites(int base) {
        int height = (lcdc & 0x04) != 0 ? 16 : 8;
        boolean bgMaster = (lcdc & 0x01) != 0; // CGB: when clear, sprites always on top
        int[] chosen = new int[10];
        int count = 0;
        for (int i = 0; i < 40 && count < 10; i++) {
            int y = (oam[i * 4] & 0xFF) - 16;
            if (ly >= y && ly < y + height) chosen[count++] = i;
        }
        if (!cgb) {
            // DMG priority: lower X wins, then lower OAM index. Simple insertion sort.
            for (int i = 1; i < count; i++) {
                int k = chosen[i];
                int kx = oam[k * 4 + 1] & 0xFF;
                int j = i - 1;
                while (j >= 0 && (oam[chosen[j] * 4 + 1] & 0xFF) > kx) {
                    chosen[j + 1] = chosen[j];
                    j--;
                }
                chosen[j + 1] = k;
            }
        }
        boolean[] drawn = new boolean[WIDTH];
        for (int n = 0; n < count; n++) {
            int i = chosen[n];
            int y = (oam[i * 4] & 0xFF) - 16;
            int x = (oam[i * 4 + 1] & 0xFF) - 8;
            int tile = oam[i * 4 + 2] & 0xFF;
            int attr = oam[i * 4 + 3] & 0xFF;
            if (height == 16) tile &= 0xFE;
            int row = ly - y;
            if ((attr & 0x40) != 0) row = height - 1 - row;
            int tileAddr = tile * 16 + row * 2;
            if (cgb && (attr & 0x08) != 0) tileAddr += 0x2000;
            int lo = vram[tileAddr] & 0xFF;
            int hi = vram[tileAddr + 1] & 0xFF;
            for (int col = 0; col < 8; col++) {
                int sx = x + col;
                if (sx < 0 || sx >= WIDTH || drawn[sx]) continue;
                int bit = (attr & 0x20) != 0 ? col : 7 - col;
                int ci = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
                if (ci == 0) continue;
                drawn[sx] = true;
                int bgci = lineColorIndex[sx];
                if (cgb) {
                    if (bgMaster && bgci != 0 && (lineBgPriority[sx] || (attr & 0x80) != 0)) continue;
                    back[base + sx] = cgbColor(objPal, attr & 7, ci);
                } else {
                    if ((attr & 0x80) != 0 && bgci != 0) continue;
                    int pal = (attr & 0x10) != 0 ? obp1 : obp0;
                    back[base + sx] = DMG_SHADES[(pal >> (ci * 2)) & 3];
                }
            }
        }
    }
}
