using System;

namespace PokeyellowCore
{
    /// <summary>
    /// Game Boy audio: two square channels, a wave channel and a noise channel,
    /// mixed to interleaved 16-bit stereo. Each output sample is box-filtered over
    /// its period so Yellow's Pikachu voice clips (made by toggling the wave
    /// channel's volume) stay audible.
    /// </summary>
    internal sealed class Apu
    {
        internal const int Clock = 4194304;

        private static readonly int[,] Duty =
        {
            { 0, 0, 0, 0, 0, 0, 0, 1 },
            { 1, 0, 0, 0, 0, 0, 0, 1 },
            { 1, 0, 0, 0, 0, 1, 1, 1 },
            { 0, 1, 1, 1, 1, 1, 1, 0 },
        };
        private static readonly int[] NoiseDivisors = { 8, 16, 32, 48, 64, 80, 96, 112 };
        private static readonly int[] ReadMask =
        {
            0x80, 0x3F, 0x00, 0xFF, 0xBF,
            0xFF, 0x3F, 0x00, 0xFF, 0xBF,
            0x7F, 0xFF, 0x9F, 0xFF, 0xBF,
            0xFF, 0xFF, 0x00, 0x00, 0xBF,
            0x00, 0x00, 0x70,
        };

        private readonly int[] regs = new int[0x30];
        private readonly int[] wave = new int[16];
        private bool enabled = true;

        private readonly Square ch1 = new Square(true);
        private readonly Square ch2 = new Square(false);
        private readonly Wave ch3 = new Wave();
        private readonly Noise ch4 = new Noise();

        private int frameSeqCounter;
        private int frameSeqStep;

        private readonly int sampleRate;
        private readonly long cyclesPerSampleFixed; // 16.16 fixed point
        private long sampleCounter;
        private double accL, accR;
        private int accCycles;
        private double hpL, hpR;
        private readonly double hpCharge;
        private readonly short[] buffer = new short[8192];
        private int bufferPos;

        internal Apu(int sampleRate)
        {
            this.sampleRate = sampleRate;
            cyclesPerSampleFixed = ((long)Clock << 16) / sampleRate;
            hpCharge = Math.Pow(0.999958, (double)Clock / sampleRate);
            Write(0xFF26, 0xF1);
            Write(0xFF24, 0x77);
            Write(0xFF25, 0xF3);
        }

        internal int SampleRate { get { return sampleRate; } }

        internal int DrainSamples(short[] output)
        {
            int n = Math.Min(output.Length, bufferPos);
            Array.Copy(buffer, 0, output, 0, n);
            Array.Copy(buffer, n, buffer, 0, bufferPos - n);
            bufferPos -= n;
            return n;
        }

        internal void DiscardSamples() { bufferPos = 0; }

        // ---- registers ----

        internal int Read(int addr)
        {
            if (addr >= 0xFF30) return wave[addr - 0xFF30];
            int idx = addr - 0xFF10;
            if (idx >= ReadMask.Length) return 0xFF;
            if (addr == 0xFF26)
            {
                return (enabled ? 0x80 : 0) | 0x70
                        | (ch1.On ? 1 : 0) | (ch2.On ? 2 : 0) | (ch3.On ? 4 : 0) | (ch4.On ? 8 : 0);
            }
            return regs[idx] | ReadMask[idx];
        }

        internal void Write(int addr, int v)
        {
            if (addr >= 0xFF30)
            {
                wave[addr - 0xFF30] = v;
                return;
            }
            int idx = addr - 0xFF10;
            if (addr == 0xFF26)
            {
                bool on = (v & 0x80) != 0;
                if (enabled && !on)
                {
                    for (int i = 0; i < 0x16; i++) regs[i] = 0;
                    ch1.Reset(); ch2.Reset(); ch3.On = false; ch4.On = false;
                    ch3.DacOn = false;
                }
                else if (!enabled && on)
                {
                    frameSeqStep = 0;
                }
                enabled = on;
                return;
            }
            if (!enabled || idx >= 0x17) return;
            regs[idx] = v;
            switch (addr)
            {
                case 0xFF10: ch1.SweepPeriod = (v >> 4) & 7; ch1.SweepNegate = (v & 8) != 0; ch1.SweepShift = v & 7; break;
                case 0xFF11: ch1.DutyIndex = v >> 6; ch1.Length = 64 - (v & 0x3F); break;
                case 0xFF12: ch1.SetEnvelope(v); break;
                case 0xFF13: ch1.Freq = (ch1.Freq & 0x700) | v; break;
                case 0xFF14: ch1.Freq = (ch1.Freq & 0xFF) | ((v & 7) << 8); ch1.LengthEnabled = (v & 0x40) != 0; if ((v & 0x80) != 0) ch1.Trigger(); break;
                case 0xFF16: ch2.DutyIndex = v >> 6; ch2.Length = 64 - (v & 0x3F); break;
                case 0xFF17: ch2.SetEnvelope(v); break;
                case 0xFF18: ch2.Freq = (ch2.Freq & 0x700) | v; break;
                case 0xFF19: ch2.Freq = (ch2.Freq & 0xFF) | ((v & 7) << 8); ch2.LengthEnabled = (v & 0x40) != 0; if ((v & 0x80) != 0) ch2.Trigger(); break;
                case 0xFF1A: ch3.DacOn = (v & 0x80) != 0; if (!ch3.DacOn) ch3.On = false; break;
                case 0xFF1B: ch3.Length = 256 - v; break;
                case 0xFF1C: ch3.VolumeCode = (v >> 5) & 3; break;
                case 0xFF1D: ch3.Freq = (ch3.Freq & 0x700) | v; break;
                case 0xFF1E: ch3.Freq = (ch3.Freq & 0xFF) | ((v & 7) << 8); ch3.LengthEnabled = (v & 0x40) != 0; if ((v & 0x80) != 0) ch3.Trigger(); break;
                case 0xFF20: ch4.Length = 64 - (v & 0x3F); break;
                case 0xFF21: ch4.SetEnvelope(v); break;
                case 0xFF22: ch4.Shift = v >> 4; ch4.Width7 = (v & 8) != 0; ch4.Divisor = NoiseDivisors[v & 7]; break;
                case 0xFF23: ch4.LengthEnabled = (v & 0x40) != 0; if ((v & 0x80) != 0) ch4.Trigger(); break;
                default: break;
            }
        }

        // ---- stepping ----

        /// <summary>Advance by T-cycles at the normal-speed 4.19 MHz clock.</summary>
        internal void Tick(int cycles)
        {
            if (enabled)
            {
                frameSeqCounter += cycles;
                while (frameSeqCounter >= 8192)
                {
                    frameSeqCounter -= 8192;
                    ClockFrameSequencer();
                }
                ch1.Tick(cycles);
                ch2.Tick(cycles);
                ch3.Tick(cycles);
                ch4.Tick(cycles);
            }
            Mix(cycles);
        }

        private void ClockFrameSequencer()
        {
            switch (frameSeqStep)
            {
                case 0: case 4: ClockLength(); break;
                case 2: case 6: ClockLength(); ch1.ClockSweep(); break;
                case 7: ch1.ClockEnvelope(); ch2.ClockEnvelope(); ch4.ClockEnvelope(); break;
                default: break;
            }
            frameSeqStep = (frameSeqStep + 1) & 7;
        }

        private void ClockLength()
        {
            ch1.ClockLength(); ch2.ClockLength(); ch3.ClockLength(); ch4.ClockLength();
        }

        private void Mix(int cycles)
        {
            double l = 0, r = 0;
            if (enabled)
            {
                int nr51 = regs[0x15];
                double o1 = ch1.DacOn() ? ch1.Output() / 7.5 - 1.0 : 0;
                double o2 = ch2.DacOn() ? ch2.Output() / 7.5 - 1.0 : 0;
                double o3 = ch3.DacOn ? ch3.Output(wave) / 7.5 - 1.0 : 0;
                double o4 = ch4.DacOn() ? ch4.Output() / 7.5 - 1.0 : 0;
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
            sampleCounter += (long)cycles << 16;
            while (sampleCounter >= cyclesPerSampleFixed)
            {
                sampleCounter -= cyclesPerSampleFixed;
                double sl = accCycles > 0 ? accL / accCycles : l;
                double sr = accCycles > 0 ? accR / accCycles : r;
                accL = accR = 0;
                accCycles = 0;
                double outL = sl - hpL;
                hpL = sl - outL * hpCharge;
                double outR = sr - hpR;
                hpR = sr - outR * hpCharge;
                Emit(outL, outR);
            }
        }

        private void Emit(double l, double r)
        {
            if (bufferPos + 2 > buffer.Length)
            {
                int half = buffer.Length / 2;
                Array.Copy(buffer, half, buffer, 0, buffer.Length - half);
                bufferPos -= half;
            }
            buffer[bufferPos++] = Clamp(l * 900);
            buffer[bufferPos++] = Clamp(r * 900);
        }

        private static short Clamp(double v)
        {
            if (v > 32767) return 32767;
            if (v < -32768) return -32768;
            return (short)v;
        }

        // ---- channels ----

        private abstract class Envelope
        {
            internal bool On;
            internal int Length;
            internal bool LengthEnabled;
            internal int InitialVolume, Volume, EnvPeriod, EnvTimer;
            internal bool EnvUp;

            internal void SetEnvelope(int v)
            {
                InitialVolume = v >> 4;
                EnvUp = (v & 8) != 0;
                EnvPeriod = v & 7;
                if (!DacOn()) On = false;
            }

            internal bool DacOn() { return InitialVolume != 0 || EnvUp; }

            internal void ClockEnvelope()
            {
                if (EnvPeriod == 0) return;
                if (--EnvTimer <= 0)
                {
                    EnvTimer = EnvPeriod;
                    if (EnvUp && Volume < 15) Volume++;
                    else if (!EnvUp && Volume > 0) Volume--;
                }
            }

            internal void ClockLength()
            {
                if (LengthEnabled && Length > 0 && --Length == 0) On = false;
            }

            protected void TriggerEnvelope()
            {
                On = DacOn();
                if (Length == 0) Length = 64;
                Volume = InitialVolume;
                EnvTimer = EnvPeriod;
            }
        }

        private sealed class Square : Envelope
        {
            private readonly bool hasSweep;
            internal int DutyIndex, Freq;
            private int timer, pos;
            internal int SweepPeriod, SweepShift;
            private int sweepTimer, shadowFreq;
            internal bool SweepNegate;
            private bool sweepEnabled;

            internal Square(bool hasSweep) { this.hasSweep = hasSweep; }

            internal void Reset()
            {
                On = false; DutyIndex = 0; Freq = 0; Length = 0; LengthEnabled = false;
                InitialVolume = 0; EnvUp = false; EnvPeriod = 0; Volume = 0;
                SweepPeriod = 0; SweepShift = 0; SweepNegate = false; sweepEnabled = false;
            }

            internal void Trigger()
            {
                TriggerEnvelope();
                timer = (2048 - Freq) * 4;
                if (hasSweep)
                {
                    shadowFreq = Freq;
                    sweepTimer = SweepPeriod == 0 ? 8 : SweepPeriod;
                    sweepEnabled = SweepPeriod != 0 || SweepShift != 0;
                    if (SweepShift != 0 && SweepCalc() > 2047) On = false;
                }
            }

            private int SweepCalc()
            {
                int delta = shadowFreq >> SweepShift;
                return SweepNegate ? shadowFreq - delta : shadowFreq + delta;
            }

            internal void ClockSweep()
            {
                if (!hasSweep || --sweepTimer > 0) return;
                sweepTimer = SweepPeriod == 0 ? 8 : SweepPeriod;
                if (!sweepEnabled || SweepPeriod == 0) return;
                int nf = SweepCalc();
                if (nf > 2047) { On = false; return; }
                if (SweepShift != 0)
                {
                    shadowFreq = nf;
                    Freq = nf;
                    if (SweepCalc() > 2047) On = false;
                }
            }

            internal void Tick(int cycles)
            {
                int period = (2048 - Freq) * 4;
                timer -= cycles;
                while (timer <= 0)
                {
                    timer += period;
                    pos = (pos + 1) & 7;
                }
            }

            internal int Output() { return On ? Duty[DutyIndex, pos] * Volume : 0; }
        }

        private sealed class Wave
        {
            internal bool On, DacOn, LengthEnabled;
            internal int Length, VolumeCode, Freq;
            private int timer, pos;

            internal void Trigger()
            {
                On = DacOn;
                if (Length == 0) Length = 256;
                timer = (2048 - Freq) * 2;
                pos = 0;
            }

            internal void ClockLength()
            {
                if (LengthEnabled && Length > 0 && --Length == 0) On = false;
            }

            internal void Tick(int cycles)
            {
                int period = (2048 - Freq) * 2;
                timer -= cycles;
                while (timer <= 0)
                {
                    timer += period;
                    pos = (pos + 1) & 31;
                }
            }

            internal int Output(int[] waveRam)
            {
                if (!On || VolumeCode == 0) return 0;
                int b = waveRam[pos >> 1];
                int s = (pos & 1) == 0 ? b >> 4 : b & 0xF;
                return s >> (VolumeCode - 1);
            }
        }

        private sealed class Noise : Envelope
        {
            internal int Shift, Divisor = 8;
            private int timer;
            internal bool Width7;
            private int lfsr = 0x7FFF;

            internal void Trigger()
            {
                TriggerEnvelope();
                lfsr = 0x7FFF;
                timer = Divisor << Shift;
            }

            internal void Tick(int cycles)
            {
                if (Shift >= 14) return;
                int period = Divisor << Shift;
                timer -= cycles;
                while (timer <= 0)
                {
                    timer += period;
                    int x = (lfsr ^ (lfsr >> 1)) & 1;
                    lfsr = (lfsr >> 1) | (x << 14);
                    if (Width7) lfsr = (lfsr & ~0x40) | (x << 6);
                }
            }

            internal int Output() { return On && (lfsr & 1) == 0 ? Volume : 0; }
        }
    }
}
