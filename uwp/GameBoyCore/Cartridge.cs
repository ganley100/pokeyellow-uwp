using System;
using System.Text;

namespace PokeyellowCore
{
    /// <summary>Cartridge ROM/RAM with MBC1, MBC3 (no RTC) and MBC5 banking.</summary>
    public sealed class Cartridge
    {
        private enum Mbc { None, Mbc1, Mbc3, Mbc5 }

        private readonly byte[] rom;
        private readonly byte[] ram;
        private readonly Mbc mbc;
        private readonly bool battery;
        private readonly int romBanks;
        private readonly int ramBanks;

        private bool ramEnabled;
        private int romBank = 1;
        private int ramBank;
        private int mbc1Mode;
        private bool ramDirty;

        public string Title { get; }
        public bool CgbSupported { get; }
        public bool HasBattery { get { return battery; } }

        public Cartridge(byte[] rom)
        {
            if (rom == null || rom.Length < 0x8000)
                throw new ArgumentException("ROM is too small (" + (rom == null ? 0 : rom.Length) + " bytes)");
            this.rom = rom;
            int type = rom[0x147];
            switch (type)
            {
                case 0x00: case 0x08: case 0x09: mbc = Mbc.None; break;
                case 0x01: case 0x02: case 0x03: mbc = Mbc.Mbc1; break;
                case 0x0F: case 0x10: case 0x11: case 0x12: case 0x13: mbc = Mbc.Mbc3; break;
                case 0x19: case 0x1A: case 0x1B: case 0x1C: case 0x1D: case 0x1E: mbc = Mbc.Mbc5; break;
                default: throw new ArgumentException(string.Format("Unsupported cartridge type 0x{0:X2}", type));
            }
            battery = type == 0x03 || type == 0x09 || type == 0x0F || type == 0x10 || type == 0x13
                    || type == 0x1B || type == 0x1E;
            romBanks = Math.Max(2, rom.Length / 0x4000);
            int ramSizeCode = rom[0x149];
            int[] ramSizes = { 0, 0x800, 0x2000, 0x8000, 0x20000, 0x10000 };
            int ramSize = ramSizeCode < ramSizes.Length ? ramSizes[ramSizeCode] : 0;
            ram = new byte[Math.Max(ramSize, 0x2000)];
            ramBanks = Math.Max(1, ram.Length / 0x2000);

            var sb = new StringBuilder();
            for (int i = 0x134; i < 0x143; i++)
            {
                if (rom[i] == 0) break;
                sb.Append((char)rom[i]);
            }
            Title = sb.ToString().Trim();
            CgbSupported = (rom[0x143] & 0x80) != 0;
        }

        /// <summary>Battery-backed RAM contents, for writing a .sav file.</summary>
        public byte[] GetRam() { return ram; }

        public void LoadRam(byte[] data)
        {
            Array.Copy(data, 0, ram, 0, Math.Min(data.Length, ram.Length));
            ramDirty = false;
        }

        /// <summary>True if cartridge RAM was written since the last call.</summary>
        public bool ConsumeRamDirty()
        {
            bool d = ramDirty;
            ramDirty = false;
            return d;
        }

        internal int Read(int addr)
        {
            if (addr < 0x4000)
            {
                if (mbc == Mbc.Mbc1 && mbc1Mode == 1)
                {
                    int bank0 = ((ramBank & 3) << 5) % romBanks;
                    return rom[bank0 * 0x4000 + addr];
                }
                return rom[addr];
            }
            if (addr < 0x8000)
            {
                int bank = romBank % romBanks;
                return rom[bank * 0x4000 + (addr - 0x4000)];
            }
            if (!ramEnabled) return 0xFF;
            return ram[RamOffset(addr)];
        }

        internal void Write(int addr, int v)
        {
            if (addr >= 0xA000)
            {
                if (!ramEnabled) return;
                int off = RamOffset(addr);
                if (ram[off] != (byte)v)
                {
                    ram[off] = (byte)v;
                    ramDirty = true;
                }
                return;
            }
            switch (mbc)
            {
                case Mbc.None:
                    break;
                case Mbc.Mbc1:
                    if (addr < 0x2000) ramEnabled = (v & 0xF) == 0xA;
                    else if (addr < 0x4000)
                    {
                        int lo = v & 0x1F;
                        if (lo == 0) lo = 1;
                        romBank = (romBank & 0x60) | lo;
                    }
                    else if (addr < 0x6000)
                    {
                        ramBank = v & 3;
                        romBank = (romBank & 0x1F) | ((v & 3) << 5);
                    }
                    else mbc1Mode = v & 1;
                    break;
                case Mbc.Mbc3:
                    if (addr < 0x2000) ramEnabled = (v & 0xF) == 0xA;
                    else if (addr < 0x4000)
                    {
                        romBank = v & 0x7F;
                        if (romBank == 0) romBank = 1;
                    }
                    else if (addr < 0x6000) ramBank = v & 0x0F;
                    break;
                case Mbc.Mbc5:
                    if (addr < 0x2000) ramEnabled = (v & 0xF) == 0xA;
                    else if (addr < 0x3000) romBank = (romBank & 0x100) | v;
                    else if (addr < 0x4000) romBank = (romBank & 0xFF) | ((v & 1) << 8);
                    else if (addr < 0x6000) ramBank = v & 0x0F;
                    break;
            }
        }

        private int RamOffset(int addr)
        {
            int bank = mbc == Mbc.Mbc1 ? (mbc1Mode == 1 ? ramBank : 0) : ramBank;
            return ((bank % ramBanks) * 0x2000 + (addr - 0xA000)) % ram.Length;
        }
    }
}
