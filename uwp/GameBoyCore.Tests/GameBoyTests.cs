using System.IO;
using System.Linq;
using PokeyellowCore;
using Xunit;

namespace GameBoyCore.Tests
{
    public class GameBoyTests
    {
        // Same hand-assembled test program as the Android core's JUnit test.
        private static readonly int[] Program =
        {
            0x31, 0xFE, 0xFF,             // ld sp,$fffe
            0x3E, 0x12, 0xC6, 0x34,       // ld a,$12 ; add $34
            0xEA, 0x00, 0xC0,             // ld [$c000],a      -> $46
            0x3E, 0x99, 0xC6, 0x01, 0x27, // ld a,$99 ; add 1 ; daa
            0xEA, 0x01, 0xC0,             // ld [$c001],a      -> $00
            0xF5, 0xC1, 0x79,             // push af ; pop bc ; ld a,c
            0xEA, 0x02, 0xC0,             // ld [$c002],a      -> $90
            0x21, 0x00, 0xD0, 0x36, 0xAB, // ld hl,$d000 ; ld [hl],$ab
            0x7E, 0xCB, 0x37,             // ld a,[hl] ; swap a
            0xEA, 0x03, 0xC0,             // ld [$c003],a      -> $ba
            0x3E, 0x02, 0xEA, 0x00, 0x20, // MBC5: ROM bank 2
            0xFA, 0x00, 0x40,             // ld a,[$4000]
            0xEA, 0x04, 0xC0,             // ld [$c004],a      -> $5a
            0x3E, 0x04, 0xE0, 0xFF,       // IE = timer
            0x3E, 0x05, 0xE0, 0x07,       // TAC = enabled, fastest
            0xAF, 0xE0, 0x0F,             // IF = 0
            0xFB, 0x76, 0x00,             // ei ; halt ; nop
            0xF3,                         // di
            0xFA, 0x05, 0xC0,
            0xEA, 0x06, 0xC0,             // [$c006] = interrupt count -> 1
            0x3E, 0x80, 0xE0, 0x68,       // BCPS = auto-increment
            0x3E, 0xFF, 0xE0, 0x69,       // colour 0 = $7fff
            0x3E, 0x7F, 0xE0, 0x69,
            0x3E, 0x1F, 0xE0, 0x69,       // colour 1 = $001f (red)
            0x3E, 0x00, 0xE0, 0x69,
            0x21, 0x00, 0x80,             // ld hl,$8000
            0x06, 0x08,                   // ld b,8
            0x3E, 0xFF, 0x22,             // .loop
            0xAF, 0x22,
            0x05, 0x20, 0xF8,
            0x3E, 0x91, 0xE0, 0x40,       // LCDC on
            0x18, 0xFE,                   // jr @
        };

        private static readonly int[] TimerHandler = { 0xF5, 0xFA, 0x05, 0xC0, 0x3C, 0xEA, 0x05, 0xC0, 0xF1, 0xD9 };

        private static byte[] BuildRom()
        {
            var rom = new byte[0x10000];
            rom[0x101] = 0xC3; rom[0x102] = 0x50; rom[0x103] = 0x01;
            rom[0x143] = 0x80;
            rom[0x147] = 0x1B;
            rom[0x148] = 0x01;
            rom[0x149] = 0x03;
            for (int i = 0; i < Program.Length; i++) rom[0x150 + i] = (byte)Program[i];
            for (int i = 0; i < TimerHandler.Length; i++) rom[0x50 + i] = (byte)TimerHandler[i];
            rom[0x8000] = 0x5A;
            return rom;
        }

        [Fact]
        public void RunsProgramAndRendersFrame()
        {
            var gb = new GameBoy(new Cartridge(BuildRom()), 48000);
            for (int i = 0; i < 3; i++) gb.RunFrame();

            Assert.Equal(0x46, gb.ReadMemory(0xC000));
            Assert.Equal(0x00, gb.ReadMemory(0xC001));
            Assert.Equal(0x90, gb.ReadMemory(0xC002));
            Assert.Equal(0xBA, gb.ReadMemory(0xC003));
            Assert.Equal(0x5A, gb.ReadMemory(0xC004));
            Assert.Equal(1, gb.ReadMemory(0xC006));

            int px = gb.FrameBuffer[0];
            int red = (px >> 16) & 0xFF, green = (px >> 8) & 0xFF, blue = px & 0xFF;
            Assert.True(red > 200 && green < 40 && blue < 80, "expected red, got " + px.ToString("X8"));

            var audio = new short[16384];
            Assert.True(gb.ReadAudio(audio) > 1000);
        }

        /// <summary>Boots the real ROM when it has been built with `make` in the repository root.</summary>
        [Fact]
        public void BootsPokemonYellow()
        {
            string path = FindRom();
            if (path == null) return; // ROM not built: nothing to check
            var gb = new GameBoy(new Cartridge(File.ReadAllBytes(path)), 48000);
            Assert.Equal("POKEMON YELLOW", gb.Cartridge.Title);
            for (int i = 0; i < 600; i++) gb.RunFrame();
            Assert.True(gb.FrameBuffer.Distinct().Count() > 1, "screen looks blank");
        }

        private static string FindRom()
        {
            var dir = new DirectoryInfo(Directory.GetCurrentDirectory());
            while (dir != null)
            {
                string p = Path.Combine(dir.FullName, "pokeyellow.gbc");
                if (File.Exists(p)) return p;
                dir = dir.Parent;
            }
            return null;
        }
    }
}
