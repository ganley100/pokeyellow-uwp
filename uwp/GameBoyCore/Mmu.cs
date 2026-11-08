namespace PokeyellowCore
{
    /// <summary>
    /// Memory bus: routes reads/writes to the cartridge, PPU, APU and internal
    /// RAM, and owns joypad, timer, interrupts, WRAM banking, speed switch and HDMA.
    /// </summary>
    internal sealed class Mmu
    {
        internal const int IntVBlank = 0x01, IntStat = 0x02, IntTimer = 0x04, IntSerial = 0x08, IntJoypad = 0x10;

        private readonly Cartridge cart;
        internal Ppu ppu;
        internal Apu apu;

        private readonly byte[] wram = new byte[0x8000];
        private readonly byte[] hram = new byte[0x7F];
        private int wramBank = 1;

        internal int ie;
        internal int ifReg = 0xE1;

        private int joypSelect = 0x30;
        private int buttons;

        private int divCounter;
        private int tima, tma, tac;

        private int sb, sc;

        internal bool doubleSpeed;
        private bool speedSwitchArmed;

        private int hdmaSrc, hdmaDst;
        private int hdmaRemaining;
        private bool hdmaActive;

        internal Mmu(Cartridge cart)
        {
            this.cart = cart;
        }

        // ---- input ----

        internal void SetButtons(int newButtons)
        {
            int pressedNow = newButtons & ~buttons;
            buttons = newButtons;
            if (pressedNow != 0) ifReg |= IntJoypad;
        }

        private int ReadJoyp()
        {
            int lo = 0x0F;
            if ((joypSelect & 0x10) == 0) lo &= ~((buttons >> 4) & 0x0F);
            if ((joypSelect & 0x20) == 0) lo &= ~(buttons & 0x0F);
            return 0xC0 | joypSelect | lo;
        }

        // ---- timer ----

        internal void TickTimer(int cycles)
        {
            if ((tac & 4) == 0)
            {
                divCounter = (divCounter + cycles) & 0xFFFF;
                return;
            }
            int bit;
            switch (tac & 3)
            {
                case 0: bit = 9; break;
                case 1: bit = 3; break;
                case 2: bit = 5; break;
                default: bit = 7; break;
            }
            int old = divCounter;
            int now = old + cycles;
            int edges = (now >> (bit + 1)) - (old >> (bit + 1));
            divCounter = now & 0xFFFF;
            for (int i = 0; i < edges; i++)
            {
                tima++;
                if (tima > 0xFF)
                {
                    tima = tma;
                    ifReg |= IntTimer;
                }
            }
        }

        internal bool PrepareSpeedSwitch()
        {
            if (!speedSwitchArmed) return false;
            speedSwitchArmed = false;
            doubleSpeed = !doubleSpeed;
            divCounter = 0;
            return true;
        }

        // ---- HDMA ----

        private void HdmaCopyBlock()
        {
            for (int i = 0; i < 16; i++)
                ppu.WriteVram(0x8000 | ((hdmaDst + i) & 0x1FFF), Read((hdmaSrc + i) & 0xFFFF));
            hdmaSrc = (hdmaSrc + 16) & 0xFFFF;
            hdmaDst = (hdmaDst + 16) & 0x1FFF;
            hdmaRemaining--;
        }

        internal void OnHBlank()
        {
            if (!hdmaActive) return;
            HdmaCopyBlock();
            if (hdmaRemaining <= 0) hdmaActive = false;
        }

        private void WriteHdma5(int v)
        {
            if (hdmaActive && (v & 0x80) == 0)
            {
                hdmaActive = false;
                return;
            }
            hdmaRemaining = (v & 0x7F) + 1;
            if ((v & 0x80) != 0)
            {
                hdmaActive = true;
                if (!ppu.LcdOn) OnHBlank();
            }
            else
            {
                while (hdmaRemaining > 0) HdmaCopyBlock();
            }
        }

        private int ReadHdma5()
        {
            if (hdmaActive) return (hdmaRemaining - 1) & 0x7F;
            return 0x80 | ((hdmaRemaining - 1) & 0x7F);
        }

        // ---- bus ----

        internal int Read(int addr)
        {
            switch (addr >> 12)
            {
                case 0x0: case 0x1: case 0x2: case 0x3:
                case 0x4: case 0x5: case 0x6: case 0x7:
                    return cart.Read(addr);
                case 0x8: case 0x9:
                    return ppu.ReadVram(addr);
                case 0xA: case 0xB:
                    return cart.Read(addr);
                case 0xC:
                    return wram[addr & 0x0FFF];
                case 0xD:
                    return wram[wramBank * 0x1000 + (addr & 0x0FFF)];
                case 0xE:
                    return wram[addr & 0x0FFF];
                default:
                    if (addr < 0xFE00) return wram[wramBank * 0x1000 + (addr & 0x0FFF)];
                    if (addr < 0xFEA0) return ppu.ReadOam(addr);
                    if (addr < 0xFF00) return 0x00;
                    if (addr >= 0xFF80)
                    {
                        if (addr == 0xFFFF) return ie;
                        return hram[addr - 0xFF80];
                    }
                    return ReadIo(addr);
            }
        }

        internal void Write(int addr, int v)
        {
            switch (addr >> 12)
            {
                case 0x0: case 0x1: case 0x2: case 0x3:
                case 0x4: case 0x5: case 0x6: case 0x7:
                    cart.Write(addr, v);
                    return;
                case 0x8: case 0x9:
                    ppu.WriteVram(addr, v);
                    return;
                case 0xA: case 0xB:
                    cart.Write(addr, v);
                    return;
                case 0xC:
                    wram[addr & 0x0FFF] = (byte)v;
                    return;
                case 0xD:
                    wram[wramBank * 0x1000 + (addr & 0x0FFF)] = (byte)v;
                    return;
                case 0xE:
                    wram[addr & 0x0FFF] = (byte)v;
                    return;
                default:
                    if (addr < 0xFE00) { wram[wramBank * 0x1000 + (addr & 0x0FFF)] = (byte)v; return; }
                    if (addr < 0xFEA0) { ppu.WriteOam(addr, v); return; }
                    if (addr < 0xFF00) return;
                    if (addr >= 0xFF80)
                    {
                        if (addr == 0xFFFF) ie = v;
                        else hram[addr - 0xFF80] = (byte)v;
                        return;
                    }
                    WriteIo(addr, v);
                    return;
            }
        }

        private int ReadIo(int addr)
        {
            switch (addr)
            {
                case 0xFF00: return ReadJoyp();
                case 0xFF01: return sb;
                case 0xFF02: return sc | 0x7E;
                case 0xFF04: return (divCounter >> 8) & 0xFF;
                case 0xFF05: return tima;
                case 0xFF06: return tma;
                case 0xFF07: return tac | 0xF8;
                case 0xFF0F: return ifReg | 0xE0;
                case 0xFF4D: return (doubleSpeed ? 0x80 : 0) | (speedSwitchArmed ? 1 : 0) | 0x7E;
                case 0xFF51: case 0xFF52: case 0xFF53: case 0xFF54: return 0xFF;
                case 0xFF55: return ReadHdma5();
                case 0xFF70: return wramBank | 0xF8;
                default:
                    if (addr >= 0xFF10 && addr < 0xFF40) return apu.Read(addr);
                    if (addr >= 0xFF40 && addr < 0xFF70) return ppu.ReadIo(addr);
                    return 0xFF;
            }
        }

        private void WriteIo(int addr, int v)
        {
            switch (addr)
            {
                case 0xFF00: joypSelect = v & 0x30; return;
                case 0xFF01: sb = v; return;
                case 0xFF02:
                    sc = v;
                    if ((v & 0x81) == 0x81)
                    {
                        // No link partner: the transfer completes at once and shifts in 0xFF.
                        sb = 0xFF;
                        sc &= 0x7F;
                        ifReg |= IntSerial;
                    }
                    return;
                case 0xFF04: divCounter = 0; return;
                case 0xFF05: tima = v; return;
                case 0xFF06: tma = v; return;
                case 0xFF07: tac = v & 7; return;
                case 0xFF0F: ifReg = v & 0x1F; return;
                case 0xFF46:
                    {
                        int src = v << 8;
                        for (int i = 0; i < 0xA0; i++) ppu.WriteOam(0xFE00 + i, Read(src + i));
                        return;
                    }
                case 0xFF4D: speedSwitchArmed = (v & 1) != 0; return;
                case 0xFF51: hdmaSrc = (hdmaSrc & 0x00FF) | (v << 8); return;
                case 0xFF52: hdmaSrc = (hdmaSrc & 0xFF00) | (v & 0xF0); return;
                case 0xFF53: hdmaDst = (hdmaDst & 0x00FF) | ((v & 0x1F) << 8); return;
                case 0xFF54: hdmaDst = (hdmaDst & 0x1F00) | (v & 0xF0); return;
                case 0xFF55: WriteHdma5(v); return;
                case 0xFF70: wramBank = (v & 7) == 0 ? 1 : (v & 7); return;
                default:
                    if (addr >= 0xFF10 && addr < 0xFF40) { apu.Write(addr, v); return; }
                    if (addr >= 0xFF40 && addr < 0xFF70) ppu.WriteIo(addr, v);
                    return;
            }
        }
    }
}
