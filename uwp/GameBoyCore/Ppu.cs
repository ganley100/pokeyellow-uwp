using System;

namespace PokeyellowCore
{
    /// <summary>
    /// Game Boy Color picture processing unit. Each scanline is rendered in one
    /// pass when it enters HBlank.
    /// </summary>
    internal sealed class Ppu
    {
        internal const int Width = 160, Height = 144;
        private const int LineDots = 456;
        private const int Mode2End = 80;
        private const int Mode3End = 252;

        private readonly Mmu mmu;
        private readonly bool cgb;

        private readonly byte[] vram = new byte[0x4000];
        private readonly byte[] oam = new byte[0xA0];
        private int vramBank;

        private readonly byte[] bgPal = new byte[64];
        private readonly byte[] objPal = new byte[64];
        private int bcps, ocps;

        private int lcdc = 0x91, stat, scy, scx, ly, lyc, bgp = 0xFC, obp0 = 0xFF, obp1 = 0xFF, wy, wx;
        private int mode;
        private int dots;
        private int windowLine;
        private bool statLine;

        private readonly int[] back = new int[Width * Height];
        internal readonly int[] Frame = new int[Width * Height];
        internal bool FrameReady;

        private readonly int[] lineColorIndex = new int[Width];
        private readonly bool[] lineBgPriority = new bool[Width];
        private readonly int[] chosen = new int[10];
        private readonly bool[] drawn = new bool[Width];

        private static readonly int[] ColorLut = new int[0x8000];
        private static readonly int[] DmgShades = { unchecked((int)0xFFE0F8D0), unchecked((int)0xFF88C070), unchecked((int)0xFF346856), unchecked((int)0xFF081820) };

        static Ppu()
        {
            for (int i = 0; i < 0x8000; i++)
            {
                int r = i & 0x1F, g = (i >> 5) & 0x1F, b = (i >> 10) & 0x1F;
                // Light colour correction approximating the GBC LCD.
                int rr = Math.Min(960, r * 26 + g * 4 + b * 2) >> 2;
                int gg = Math.Min(960, g * 24 + b * 8) >> 2;
                int bb = Math.Min(960, r * 6 + g * 4 + b * 22) >> 2;
                ColorLut[i] = unchecked((int)0xFF000000) | (rr << 16) | (gg << 8) | bb;
            }
        }

        internal Ppu(Mmu mmu, bool cgb)
        {
            this.mmu = mmu;
            this.cgb = cgb;
            for (int i = 0; i < 64; i++) { bgPal[i] = 0xFF; objPal[i] = 0xFF; }
            mode = 2;
        }

        internal bool LcdOn { get { return (lcdc & 0x80) != 0; } }

        // ---- memory ----

        internal int ReadVram(int addr) { return vram[vramBank * 0x2000 + (addr & 0x1FFF)]; }
        internal void WriteVram(int addr, int v) { vram[vramBank * 0x2000 + (addr & 0x1FFF)] = (byte)v; }
        internal int ReadOam(int addr) { return oam[addr - 0xFE00]; }
        internal void WriteOam(int addr, int v) { oam[addr - 0xFE00] = (byte)v; }

        internal int ReadIo(int addr)
        {
            switch (addr)
            {
                case 0xFF40: return lcdc;
                case 0xFF41: return 0x80 | (stat & 0x78) | (ly == lyc ? 4 : 0) | (LcdOn ? mode : 0);
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
                case 0xFF69: return bgPal[bcps & 0x3F];
                case 0xFF6A: return ocps | 0x40;
                case 0xFF6B: return objPal[ocps & 0x3F];
                default: return 0xFF;
            }
        }

        internal void WriteIo(int addr, int v)
        {
            switch (addr)
            {
                case 0xFF40:
                    {
                        bool wasOn = LcdOn;
                        lcdc = v;
                        if (wasOn && !LcdOn)
                        {
                            ly = 0; dots = 0; mode = 0; windowLine = 0;
                            statLine = false;
                        }
                        else if (!wasOn && LcdOn)
                        {
                            ly = 0; dots = 0; mode = 2; windowLine = 0;
                            UpdateStat();
                        }
                        return;
                    }
                case 0xFF41: stat = v & 0x78; UpdateStat(); return;
                case 0xFF42: scy = v; return;
                case 0xFF43: scx = v; return;
                case 0xFF44: return;
                case 0xFF45: lyc = v; UpdateStat(); return;
                case 0xFF47: bgp = v; return;
                case 0xFF48: obp0 = v; return;
                case 0xFF49: obp1 = v; return;
                case 0xFF4A: wy = v; return;
                case 0xFF4B: wx = v; return;
                case 0xFF4F: if (cgb) vramBank = v & 1; return;
                case 0xFF68: bcps = v & 0xBF; return;
                case 0xFF69:
                    bgPal[bcps & 0x3F] = (byte)v;
                    if ((bcps & 0x80) != 0) bcps = 0x80 | ((bcps + 1) & 0x3F);
                    return;
                case 0xFF6A: ocps = v & 0xBF; return;
                case 0xFF6B:
                    objPal[ocps & 0x3F] = (byte)v;
                    if ((ocps & 0x80) != 0) ocps = 0x80 | ((ocps + 1) & 0x3F);
                    return;
                default:
                    return;
            }
        }

        // ---- timing ----

        private void UpdateStat()
        {
            if (!LcdOn) { statLine = false; return; }
            bool line = ((stat & 0x40) != 0 && ly == lyc)
                    || ((stat & 0x08) != 0 && mode == 0)
                    || ((stat & 0x10) != 0 && mode == 1)
                    || ((stat & 0x20) != 0 && mode == 2);
            if (line && !statLine) mmu.ifReg |= Mmu.IntStat;
            statLine = line;
        }

        /// <summary>Advance by PPU dots (= T-cycles at normal speed).</summary>
        internal void Tick(int cycles)
        {
            if (!LcdOn)
            {
                // Keep producing (white) frames while the LCD is off so the host keeps pace.
                dots += cycles;
                if (dots >= LineDots * 154)
                {
                    dots -= LineDots * 154;
                    for (int i = 0; i < Frame.Length; i++) Frame[i] = -1;
                    FrameReady = true;
                }
                return;
            }
            dots += cycles;
            while (true)
            {
                if (ly < Height)
                {
                    if (mode == 2 && dots >= Mode2End)
                    {
                        mode = 3;
                        UpdateStat();
                    }
                    else if (mode == 3 && dots >= Mode3End)
                    {
                        mode = 0;
                        RenderLine();
                        UpdateStat();
                        mmu.OnHBlank();
                    }
                    else if (mode == 0 && dots >= LineDots)
                    {
                        dots -= LineDots;
                        ly++;
                        if (ly == Height)
                        {
                            mode = 1;
                            mmu.ifReg |= Mmu.IntVBlank;
                            Array.Copy(back, Frame, back.Length);
                            FrameReady = true;
                        }
                        else
                        {
                            mode = 2;
                        }
                        UpdateStat();
                    }
                    else
                    {
                        break;
                    }
                }
                else
                {
                    if (dots >= LineDots)
                    {
                        dots -= LineDots;
                        ly++;
                        if (ly > 153)
                        {
                            ly = 0;
                            windowLine = 0;
                            mode = 2;
                        }
                        UpdateStat();
                    }
                    else
                    {
                        break;
                    }
                }
            }
        }

        // ---- rendering ----

        private static int CgbColor(byte[] pal, int palette, int index)
        {
            int off = palette * 8 + index * 2;
            int c = pal[off] | ((pal[off + 1] & 0x7F) << 8);
            return ColorLut[c];
        }

        private void RenderLine()
        {
            int lineBase = ly * Width;
            bool bgEnabled = (lcdc & 0x01) != 0;
            if (cgb || bgEnabled)
            {
                RenderBackground(lineBase);
            }
            else
            {
                for (int x = 0; x < Width; x++)
                {
                    back[lineBase + x] = DmgShades[0];
                    lineColorIndex[x] = 0;
                    lineBgPriority[x] = false;
                }
            }
            if ((lcdc & 0x02) != 0) RenderSprites(lineBase);
        }

        private void RenderBackground(int lineBase)
        {
            bool windowEnabled = (lcdc & 0x20) != 0 && wy <= ly && wx <= 166;
            int winX = wx - 7;
            bool usedWindow = false;
            bool signedTiles = (lcdc & 0x10) == 0;

            for (int x = 0; x < Width; x++)
            {
                int mapBase, px, py;
                if (windowEnabled && x >= winX)
                {
                    mapBase = (lcdc & 0x40) != 0 ? 0x1C00 : 0x1800;
                    px = x - winX;
                    py = windowLine;
                    usedWindow = true;
                }
                else
                {
                    mapBase = (lcdc & 0x08) != 0 ? 0x1C00 : 0x1800;
                    px = (x + scx) & 0xFF;
                    py = (ly + scy) & 0xFF;
                }
                int mapAddr = mapBase + ((py >> 3) << 5) + (px >> 3);
                int tile = vram[mapAddr];
                int attr = cgb ? vram[0x2000 + mapAddr] : 0;

                int tileAddr = signedTiles ? 0x1000 + ((sbyte)tile) * 16 : tile * 16;
                if ((attr & 0x08) != 0) tileAddr += 0x2000;
                int row = py & 7;
                if ((attr & 0x40) != 0) row = 7 - row;
                int col = px & 7;
                if ((attr & 0x20) != 0) col = 7 - col;
                int lo = vram[tileAddr + row * 2];
                int hi = vram[tileAddr + row * 2 + 1];
                int bit = 7 - col;
                int ci = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);

                lineColorIndex[x] = ci;
                lineBgPriority[x] = (attr & 0x80) != 0;
                back[lineBase + x] = cgb ? CgbColor(bgPal, attr & 7, ci) : DmgShades[(bgp >> (ci * 2)) & 3];
            }
            if (usedWindow) windowLine++;
        }

        private void RenderSprites(int lineBase)
        {
            int height = (lcdc & 0x04) != 0 ? 16 : 8;
            bool bgMaster = (lcdc & 0x01) != 0;
            int count = 0;
            for (int i = 0; i < 40 && count < 10; i++)
            {
                int y = oam[i * 4] - 16;
                if (ly >= y && ly < y + height) chosen[count++] = i;
            }
            if (!cgb)
            {
                // DMG priority: lower X first, then lower OAM index.
                for (int i = 1; i < count; i++)
                {
                    int k = chosen[i];
                    int kx = oam[k * 4 + 1];
                    int j = i - 1;
                    while (j >= 0 && oam[chosen[j] * 4 + 1] > kx)
                    {
                        chosen[j + 1] = chosen[j];
                        j--;
                    }
                    chosen[j + 1] = k;
                }
            }
            Array.Clear(drawn, 0, drawn.Length);
            for (int n = 0; n < count; n++)
            {
                int i = chosen[n];
                int y = oam[i * 4] - 16;
                int x = oam[i * 4 + 1] - 8;
                int tile = oam[i * 4 + 2];
                int attr = oam[i * 4 + 3];
                if (height == 16) tile &= 0xFE;
                int row = ly - y;
                if ((attr & 0x40) != 0) row = height - 1 - row;
                int tileAddr = tile * 16 + row * 2;
                if (cgb && (attr & 0x08) != 0) tileAddr += 0x2000;
                int lo = vram[tileAddr];
                int hi = vram[tileAddr + 1];
                for (int col = 0; col < 8; col++)
                {
                    int sx = x + col;
                    if (sx < 0 || sx >= Width || drawn[sx]) continue;
                    int bit = (attr & 0x20) != 0 ? col : 7 - col;
                    int ci = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
                    if (ci == 0) continue;
                    drawn[sx] = true;
                    int bgci = lineColorIndex[sx];
                    if (cgb)
                    {
                        if (bgMaster && bgci != 0 && (lineBgPriority[sx] || (attr & 0x80) != 0)) continue;
                        back[lineBase + sx] = CgbColor(objPal, attr & 7, ci);
                    }
                    else
                    {
                        if ((attr & 0x80) != 0 && bgci != 0) continue;
                        int pal = (attr & 0x10) != 0 ? obp1 : obp0;
                        back[lineBase + sx] = DmgShades[(pal >> (ci * 2)) & 3];
                    }
                }
            }
        }
    }
}
