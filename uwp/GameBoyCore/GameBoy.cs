using System.Runtime.CompilerServices;

[assembly: InternalsVisibleTo("GameBoyCore.Tests")]

namespace PokeyellowCore
{
    /// <summary>
    /// Emulator entry point. The host sets button state, calls <see cref="RunFrame"/>
    /// about 59.7 times a second, and pulls video and audio out.
    /// </summary>
    public sealed class GameBoy
    {
        public const int Width = Ppu.Width;
        public const int Height = Ppu.Height;
        /// <summary>Frames per second of the real hardware (4194304 / 70224).</summary>
        public const double Fps = 4194304.0 / 70224.0;

        public const int ButtonA = 0x01;
        public const int ButtonB = 0x02;
        public const int ButtonSelect = 0x04;
        public const int ButtonStart = 0x08;
        public const int ButtonRight = 0x10;
        public const int ButtonLeft = 0x20;
        public const int ButtonUp = 0x40;
        public const int ButtonDown = 0x80;

        private readonly Mmu mmu;
        private readonly Cpu cpu;
        private readonly Ppu ppu;
        private readonly Apu apu;

        public Cartridge Cartridge { get; }

        public GameBoy(Cartridge cart, int audioSampleRate)
        {
            Cartridge = cart;
            mmu = new Mmu(cart);
            ppu = new Ppu(mmu, cart.CgbSupported);
            apu = new Apu(audioSampleRate);
            mmu.ppu = ppu;
            mmu.apu = apu;
            cpu = new Cpu(mmu);
            cpu.ResetCgb();
            mmu.Write(0xFF40, 0x91);
            mmu.Write(0xFF47, 0xFC);
        }

        /// <summary>Set the held buttons (a mask of the Button* constants).</summary>
        public void SetButtons(int mask)
        {
            // Opposite directions can't be held on a real d-pad.
            if ((mask & (ButtonLeft | ButtonRight)) == (ButtonLeft | ButtonRight)) mask &= ~(ButtonLeft | ButtonRight);
            if ((mask & (ButtonUp | ButtonDown)) == (ButtonUp | ButtonDown)) mask &= ~(ButtonUp | ButtonDown);
            mmu.SetButtons(mask);
        }

        /// <summary>Run until the next frame is complete.</summary>
        public void RunFrame()
        {
            ppu.FrameReady = false;
            int budget = 70224 * 4;
            while (!ppu.FrameReady && budget > 0)
            {
                int cycles = cpu.Step();
                mmu.TickTimer(cycles);
                int dots = mmu.doubleSpeed ? cycles >> 1 : cycles;
                ppu.Tick(dots);
                apu.Tick(dots);
                budget -= dots;
            }
        }

        /// <summary>The last completed frame: 160x144 pixels, 0xAARRGGBB (BGRA bytes in memory).</summary>
        public int[] FrameBuffer { get { return ppu.Frame; } }

        public int AudioSampleRate { get { return apu.SampleRate; } }

        /// <summary>Drain interleaved stereo 16-bit samples. Returns the number of shorts copied.</summary>
        public int ReadAudio(short[] output) { return apu.DrainSamples(output); }

        public void DiscardAudio() { apu.DiscardSamples(); }

        internal int ReadMemory(int addr) { return mmu.Read(addr); }
    }
}
