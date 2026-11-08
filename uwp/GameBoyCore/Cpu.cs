namespace PokeyellowCore
{
    /// <summary>
    /// Sharp SM83 (Game Boy / Game Boy Color) CPU. Timing is per instruction:
    /// <see cref="Step"/> runs one instruction (or services one interrupt) and
    /// returns the T-cycles it took at the current CPU clock.
    /// </summary>
    internal sealed class Cpu
    {
        private const int FZ = 0x80, FN = 0x40, FH = 0x20, FC = 0x10;

        internal int a, f, b, c, d, e, h, l, sp, pc;
        internal bool ime;
        private int imeDelay;
        internal bool halted;
        internal bool stopped;

        private readonly Mmu mmu;

        internal Cpu(Mmu mmu)
        {
            this.mmu = mmu;
        }

        /// <summary>Register state after the CGB boot ROM hands over to the cartridge.</summary>
        internal void ResetCgb()
        {
            a = 0x11; f = 0x80; b = 0x00; c = 0x00; d = 0xFF; e = 0x56; h = 0x00; l = 0x0D;
            sp = 0xFFFE; pc = 0x0100;
            ime = false; imeDelay = 0; halted = false; stopped = false;
        }

        // ---- memory helpers ----

        private int Rd(int addr) { return mmu.Read(addr & 0xFFFF); }
        private void Wr(int addr, int v) { mmu.Write(addr & 0xFFFF, v & 0xFF); }

        private int Fetch()
        {
            int v = mmu.Read(pc);
            pc = (pc + 1) & 0xFFFF;
            return v;
        }

        private int Fetch16()
        {
            int lo = Fetch();
            return lo | (Fetch() << 8);
        }

        private void Push(int v)
        {
            sp = (sp - 1) & 0xFFFF; Wr(sp, v >> 8);
            sp = (sp - 1) & 0xFFFF; Wr(sp, v);
        }

        private int Pop()
        {
            int lo = Rd(sp); sp = (sp + 1) & 0xFFFF;
            int hi = Rd(sp); sp = (sp + 1) & 0xFFFF;
            return lo | (hi << 8);
        }

        private int BC { get { return (b << 8) | c; } set { b = (value >> 8) & 0xFF; c = value & 0xFF; } }
        private int DE { get { return (d << 8) | e; } set { d = (value >> 8) & 0xFF; e = value & 0xFF; } }
        private int HL { get { return (h << 8) | l; } set { h = (value >> 8) & 0xFF; l = value & 0xFF; } }

        private int GetR(int i)
        {
            switch (i)
            {
                case 0: return b;
                case 1: return c;
                case 2: return d;
                case 3: return e;
                case 4: return h;
                case 5: return l;
                case 6: return Rd(HL);
                default: return a;
            }
        }

        private void SetR(int i, int v)
        {
            v &= 0xFF;
            switch (i)
            {
                case 0: b = v; break;
                case 1: c = v; break;
                case 2: d = v; break;
                case 3: e = v; break;
                case 4: h = v; break;
                case 5: l = v; break;
                case 6: Wr(HL, v); break;
                default: a = v; break;
            }
        }

        private bool Cond(int cc)
        {
            switch (cc)
            {
                case 0: return (f & FZ) == 0;
                case 1: return (f & FZ) != 0;
                case 2: return (f & FC) == 0;
                default: return (f & FC) != 0;
            }
        }

        // ---- ALU ----

        private void Alu(int op, int v)
        {
            int r, cy;
            switch (op)
            {
                case 0: // ADD
                    r = a + v;
                    f = ((r & 0xFF) == 0 ? FZ : 0) | (((a & 0xF) + (v & 0xF)) > 0xF ? FH : 0) | (r > 0xFF ? FC : 0);
                    a = r & 0xFF;
                    break;
                case 1: // ADC
                    cy = (f & FC) != 0 ? 1 : 0;
                    r = a + v + cy;
                    f = ((r & 0xFF) == 0 ? FZ : 0) | (((a & 0xF) + (v & 0xF) + cy) > 0xF ? FH : 0) | (r > 0xFF ? FC : 0);
                    a = r & 0xFF;
                    break;
                case 2: // SUB
                    r = a - v;
                    f = FN | ((r & 0xFF) == 0 ? FZ : 0) | ((a & 0xF) < (v & 0xF) ? FH : 0) | (r < 0 ? FC : 0);
                    a = r & 0xFF;
                    break;
                case 3: // SBC
                    cy = (f & FC) != 0 ? 1 : 0;
                    r = a - v - cy;
                    f = FN | ((r & 0xFF) == 0 ? FZ : 0) | (((a & 0xF) - (v & 0xF) - cy) < 0 ? FH : 0) | (r < 0 ? FC : 0);
                    a = r & 0xFF;
                    break;
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

        private int Inc8(int v)
        {
            int r = (v + 1) & 0xFF;
            f = (f & FC) | (r == 0 ? FZ : 0) | ((v & 0xF) == 0xF ? FH : 0);
            return r;
        }

        private int Dec8(int v)
        {
            int r = (v - 1) & 0xFF;
            f = (f & FC) | FN | (r == 0 ? FZ : 0) | ((v & 0xF) == 0 ? FH : 0);
            return r;
        }

        private void AddHl(int v)
        {
            int hl = HL;
            int r = hl + v;
            f = (f & FZ) | (((hl & 0xFFF) + (v & 0xFFF)) > 0xFFF ? FH : 0) | (r > 0xFFFF ? FC : 0);
            HL = r & 0xFFFF;
        }

        private int SpPlusE()
        {
            int e8 = (sbyte)Fetch();
            int r = (sp + e8) & 0xFFFF;
            f = (((sp & 0xF) + (e8 & 0xF)) > 0xF ? FH : 0) | (((sp & 0xFF) + (e8 & 0xFF)) > 0xFF ? FC : 0);
            return r;
        }

        private void Daa()
        {
            int t = a;
            int corr = 0;
            bool carry = (f & FC) != 0;
            if ((f & FN) == 0)
            {
                if ((f & FH) != 0 || (t & 0xF) > 9) corr |= 0x06;
                if (carry || t > 0x99) { corr |= 0x60; carry = true; }
                t += corr;
            }
            else
            {
                if ((f & FH) != 0) corr |= 0x06;
                if (carry) corr |= 0x60;
                t -= corr;
            }
            a = t & 0xFF;
            f = (f & FN) | (a == 0 ? FZ : 0) | (carry ? FC : 0);
        }

        // ---- CB prefix ----

        private int Cb()
        {
            int op = Fetch();
            int r = op & 7;
            int v = GetR(r);
            int x = op >> 6;
            int y = (op >> 3) & 7;
            int cycles = r == 6 ? 16 : 8;
            if (x == 0)
            {
                int res;
                switch (y)
                {
                    case 0: res = ((v << 1) | (v >> 7)) & 0xFF; f = (v & 0x80) != 0 ? FC : 0; break;
                    case 1: res = ((v >> 1) | (v << 7)) & 0xFF; f = (v & 1) != 0 ? FC : 0; break;
                    case 2: res = ((v << 1) | ((f & FC) != 0 ? 1 : 0)) & 0xFF; f = (v & 0x80) != 0 ? FC : 0; break;
                    case 3: res = (v >> 1) | ((f & FC) != 0 ? 0x80 : 0); f = (v & 1) != 0 ? FC : 0; break;
                    case 4: res = (v << 1) & 0xFF; f = (v & 0x80) != 0 ? FC : 0; break;
                    case 5: res = (v >> 1) | (v & 0x80); f = (v & 1) != 0 ? FC : 0; break;
                    case 6: res = ((v << 4) | (v >> 4)) & 0xFF; f = 0; break;
                    default: res = v >> 1; f = (v & 1) != 0 ? FC : 0; break;
                }
                if (res == 0) f |= FZ;
                SetR(r, res);
            }
            else if (x == 1)
            {
                f = (f & FC) | FH | ((v & (1 << y)) == 0 ? FZ : 0);
                if (r == 6) cycles = 12;
            }
            else if (x == 2)
            {
                SetR(r, v & ~(1 << y));
            }
            else
            {
                SetR(r, v | (1 << y));
            }
            return cycles;
        }

        // ---- main step ----

        internal int Step()
        {
            int pending = mmu.ie & mmu.ifReg & 0x1F;
            if (halted || stopped)
            {
                if (pending != 0)
                {
                    halted = false;
                    stopped = false;
                }
                else
                {
                    return 4;
                }
            }
            if (ime && pending != 0)
            {
                int bit = 0;
                while ((pending & (1 << bit)) == 0) bit++;
                ime = false;
                mmu.ifReg &= ~(1 << bit);
                Push(pc);
                pc = 0x40 + bit * 8;
                return 20;
            }
            if (imeDelay > 0 && --imeDelay == 0) ime = true;
            return Execute(Fetch());
        }

        private int Execute(int op)
        {
            if (op >= 0x40 && op < 0x80)
            {
                if (op == 0x76)
                {
                    halted = true;
                    return 4;
                }
                int dst = (op >> 3) & 7, src = op & 7;
                SetR(dst, GetR(src));
                return (dst == 6 || src == 6) ? 8 : 4;
            }
            if (op >= 0x80 && op < 0xC0)
            {
                int src = op & 7;
                Alu((op >> 3) & 7, GetR(src));
                return src == 6 ? 8 : 4;
            }
            int t, r, hl;
            switch (op)
            {
                case 0x00: return 4;
                case 0x01: BC = Fetch16(); return 12;
                case 0x11: DE = Fetch16(); return 12;
                case 0x21: HL = Fetch16(); return 12;
                case 0x31: sp = Fetch16(); return 12;
                case 0x02: Wr(BC, a); return 8;
                case 0x12: Wr(DE, a); return 8;
                case 0x22: hl = HL; Wr(hl, a); HL = (hl + 1) & 0xFFFF; return 8;
                case 0x32: hl = HL; Wr(hl, a); HL = (hl - 1) & 0xFFFF; return 8;
                case 0x0A: a = Rd(BC); return 8;
                case 0x1A: a = Rd(DE); return 8;
                case 0x2A: hl = HL; a = Rd(hl); HL = (hl + 1) & 0xFFFF; return 8;
                case 0x3A: hl = HL; a = Rd(hl); HL = (hl - 1) & 0xFFFF; return 8;
                case 0x03: BC = (BC + 1) & 0xFFFF; return 8;
                case 0x13: DE = (DE + 1) & 0xFFFF; return 8;
                case 0x23: HL = (HL + 1) & 0xFFFF; return 8;
                case 0x33: sp = (sp + 1) & 0xFFFF; return 8;
                case 0x0B: BC = (BC - 1) & 0xFFFF; return 8;
                case 0x1B: DE = (DE - 1) & 0xFFFF; return 8;
                case 0x2B: HL = (HL - 1) & 0xFFFF; return 8;
                case 0x3B: sp = (sp - 1) & 0xFFFF; return 8;
                case 0x09: AddHl(BC); return 8;
                case 0x19: AddHl(DE); return 8;
                case 0x29: AddHl(HL); return 8;
                case 0x39: AddHl(sp); return 8;
                case 0x04: case 0x0C: case 0x14: case 0x1C: case 0x24: case 0x2C: case 0x34: case 0x3C:
                    r = (op >> 3) & 7;
                    SetR(r, Inc8(GetR(r)));
                    return r == 6 ? 12 : 4;
                case 0x05: case 0x0D: case 0x15: case 0x1D: case 0x25: case 0x2D: case 0x35: case 0x3D:
                    r = (op >> 3) & 7;
                    SetR(r, Dec8(GetR(r)));
                    return r == 6 ? 12 : 4;
                case 0x06: case 0x0E: case 0x16: case 0x1E: case 0x26: case 0x2E: case 0x36: case 0x3E:
                    r = (op >> 3) & 7;
                    SetR(r, Fetch());
                    return r == 6 ? 12 : 8;
                case 0x07: f = (a & 0x80) != 0 ? FC : 0; a = ((a << 1) | (a >> 7)) & 0xFF; return 4;
                case 0x0F: f = (a & 1) != 0 ? FC : 0; a = ((a >> 1) | (a << 7)) & 0xFF; return 4;
                case 0x17: t = (f & FC) != 0 ? 1 : 0; f = (a & 0x80) != 0 ? FC : 0; a = ((a << 1) | t) & 0xFF; return 4;
                case 0x1F: t = (f & FC) != 0 ? 0x80 : 0; f = (a & 1) != 0 ? FC : 0; a = (a >> 1) | t; return 4;
                case 0x08: t = Fetch16(); Wr(t, sp); Wr(t + 1, sp >> 8); return 20;
                case 0x10:
                    Fetch();
                    if (mmu.PrepareSpeedSwitch()) return 4;
                    stopped = true;
                    return 4;
                case 0x18: t = (sbyte)Fetch(); pc = (pc + t) & 0xFFFF; return 12;
                case 0x20: case 0x28: case 0x30: case 0x38:
                    t = (sbyte)Fetch();
                    if (Cond((op >> 3) & 3)) { pc = (pc + t) & 0xFFFF; return 12; }
                    return 8;
                case 0x27: Daa(); return 4;
                case 0x2F: a ^= 0xFF; f |= FN | FH; return 4;
                case 0x37: f = (f & FZ) | FC; return 4;
                case 0x3F: f = (f & FZ) | ((f & FC) ^ FC); return 4;
                case 0xC0: case 0xC8: case 0xD0: case 0xD8:
                    if (Cond((op >> 3) & 3)) { pc = Pop(); return 20; }
                    return 8;
                case 0xC9: pc = Pop(); return 16;
                case 0xD9: pc = Pop(); ime = true; return 16;
                case 0xC1: BC = Pop(); return 12;
                case 0xD1: DE = Pop(); return 12;
                case 0xE1: HL = Pop(); return 12;
                case 0xF1: t = Pop(); a = t >> 8; f = t & 0xF0; return 12;
                case 0xC5: Push(BC); return 16;
                case 0xD5: Push(DE); return 16;
                case 0xE5: Push(HL); return 16;
                case 0xF5: Push((a << 8) | f); return 16;
                case 0xC2: case 0xCA: case 0xD2: case 0xDA:
                    t = Fetch16();
                    if (Cond((op >> 3) & 3)) { pc = t; return 16; }
                    return 12;
                case 0xC3: pc = Fetch16(); return 16;
                case 0xE9: pc = HL; return 4;
                case 0xC4: case 0xCC: case 0xD4: case 0xDC:
                    t = Fetch16();
                    if (Cond((op >> 3) & 3)) { Push(pc); pc = t; return 24; }
                    return 12;
                case 0xCD: t = Fetch16(); Push(pc); pc = t; return 24;
                case 0xC7: case 0xCF: case 0xD7: case 0xDF: case 0xE7: case 0xEF: case 0xF7: case 0xFF:
                    Push(pc); pc = op & 0x38; return 16;
                case 0xC6: case 0xCE: case 0xD6: case 0xDE: case 0xE6: case 0xEE: case 0xF6: case 0xFE:
                    Alu((op >> 3) & 7, Fetch()); return 8;
                case 0xCB: return Cb();
                case 0xE0: Wr(0xFF00 | Fetch(), a); return 12;
                case 0xF0: a = Rd(0xFF00 | Fetch()); return 12;
                case 0xE2: Wr(0xFF00 | c, a); return 8;
                case 0xF2: a = Rd(0xFF00 | c); return 8;
                case 0xEA: Wr(Fetch16(), a); return 16;
                case 0xFA: a = Rd(Fetch16()); return 16;
                case 0xE8: sp = SpPlusE(); return 16;
                case 0xF8: HL = SpPlusE(); return 12;
                case 0xF9: sp = HL; return 8;
                case 0xF3: ime = false; imeDelay = 0; return 4;
                case 0xFB: if (!ime && imeDelay == 0) imeDelay = 1; return 4;
                default:
                    // Illegal opcode: hardware locks up; treat as NOP instead of freezing the app.
                    return 4;
            }
        }
    }
}
