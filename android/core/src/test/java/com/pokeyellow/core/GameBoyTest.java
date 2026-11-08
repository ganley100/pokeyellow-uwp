package com.pokeyellow.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Assume;
import org.junit.Test;

public class GameBoyTest {

    /** Builds a 64 KiB MBC5 CGB ROM with {@code code} at 0x150 and {@code handler} at the timer vector. */
    private static byte[] rom(int[] code, int[] timerHandler) {
        byte[] rom = new byte[0x10000];
        rom[0x100] = 0x00;                 // nop
        rom[0x101] = (byte) 0xC3;          // jp $0150
        rom[0x102] = 0x50;
        rom[0x103] = 0x01;
        rom[0x143] = (byte) 0x80;          // CGB compatible
        rom[0x147] = 0x1B;                 // MBC5+RAM+BATTERY
        rom[0x148] = 0x01;
        rom[0x149] = 0x03;                 // 32 KiB RAM
        for (int i = 0; i < code.length; i++) rom[0x150 + i] = (byte) code[i];
        for (int i = 0; i < timerHandler.length; i++) rom[0x50 + i] = (byte) timerHandler[i];
        rom[0x8000] = 0x5A;                // first byte of bank 2
        return rom;
    }

    private static final int[] PROGRAM = {
            0x31, 0xFE, 0xFF,             // ld sp,$fffe
            0x3E, 0x12, 0xC6, 0x34,       // ld a,$12 ; add $34
            0xEA, 0x00, 0xC0,             // ld [$c000],a      -> $46
            0x3E, 0x99, 0xC6, 0x01, 0x27, // ld a,$99 ; add 1 ; daa
            0xEA, 0x01, 0xC0,             // ld [$c001],a      -> $00
            0xF5, 0xC1, 0x79,             // push af ; pop bc ; ld a,c
            0xEA, 0x02, 0xC0,             // ld [$c002],a      -> flags Z|C = $90
            0x21, 0x00, 0xD0, 0x36, 0xAB, // ld hl,$d000 ; ld [hl],$ab
            0x7E, 0xCB, 0x37,             // ld a,[hl] ; swap a
            0xEA, 0x03, 0xC0,             // ld [$c003],a      -> $ba
            0x3E, 0x02, 0xEA, 0x00, 0x20, // ld a,2 ; ld [$2000],a (MBC5 ROM bank 2)
            0xFA, 0x00, 0x40,             // ld a,[$4000]
            0xEA, 0x04, 0xC0,             // ld [$c004],a      -> $5a
            0x3E, 0x04, 0xE0, 0xFF,       // ld a,4 ; ldh [IE],a
            0x3E, 0x05, 0xE0, 0x07,       // ld a,5 ; ldh [TAC],a
            0xAF, 0xE0, 0x0F,             // xor a ; ldh [IF],a
            0xFB, 0x76, 0x00,             // ei ; halt ; nop
            0xF3,                         // di
            0xFA, 0x05, 0xC0,             // ld a,[$c005]
            0xEA, 0x06, 0xC0,             // ld [$c006],a      -> 1
            0x3E, 0x80, 0xE0, 0x68,       // ld a,$80 ; ldh [BCPS],a
            0x3E, 0xFF, 0xE0, 0x69,       // colour 0 = $7fff
            0x3E, 0x7F, 0xE0, 0x69,
            0x3E, 0x1F, 0xE0, 0x69,       // colour 1 = $001f (red)
            0x3E, 0x00, 0xE0, 0x69,
            0x21, 0x00, 0x80,             // ld hl,$8000
            0x06, 0x08,                   // ld b,8
            0x3E, 0xFF, 0x22,             // .loop: ld a,$ff ; ld [hli],a
            0xAF, 0x22,                   //        xor a ; ld [hli],a
            0x05, 0x20, 0xF8,             //        dec b ; jr nz,.loop
            0x3E, 0x91, 0xE0, 0x40,       // ld a,$91 ; ldh [LCDC],a
            0x18, 0xFE,                   // jr @
    };

    private static final int[] TIMER_HANDLER = {
            0xF5, 0xFA, 0x05, 0xC0, 0x3C, 0xEA, 0x05, 0xC0, 0xF1, 0xD9,
    };

    @Test
    public void runsProgramAndRendersFrame() {
        GameBoy gb = new GameBoy(new Cartridge(rom(PROGRAM, TIMER_HANDLER)), 48000);
        for (int i = 0; i < 3; i++) gb.runFrame();

        assertEquals(0x46, gb.readMemory(0xC000));
        assertEquals(0x00, gb.readMemory(0xC001));
        assertEquals(0x90, gb.readMemory(0xC002));
        assertEquals(0xBA, gb.readMemory(0xC003));
        assertEquals(0x5A, gb.readMemory(0xC004));
        assertEquals(1, gb.readMemory(0xC006));

        int px = gb.frameBuffer()[0];
        int red = (px >> 16) & 0xFF, green = (px >> 8) & 0xFF, blue = px & 0xFF;
        assertTrue("expected a red pixel, got " + Integer.toHexString(px), red > 200 && green < 40 && blue < 80);

        short[] audio = new short[16384];
        int n = gb.readAudio(audio);
        // ~800 stereo samples per frame at 48 kHz; the buffer keeps at most 4096.
        assertTrue("audio samples: " + n, n > 1000);
    }

    /** Boots the real ROM if it has been built with `make` in the repository root. */
    @Test
    public void bootsPokemonYellow() throws Exception {
        File f = new File("../../pokeyellow.gbc");
        Assume.assumeTrue("pokeyellow.gbc not built", f.exists());
        GameBoy gb = new GameBoy(new Cartridge(Files.readAllBytes(f.toPath())), 48000);
        assertEquals("POKEMON YELLOW", gb.cartridge().title);
        // Run ~10 seconds of intro and check the screen isn't a single colour.
        for (int i = 0; i < 600; i++) gb.runFrame();
        int[] fb = gb.frameBuffer();
        int distinct = (int) java.util.Arrays.stream(fb).distinct().count();
        assertTrue("screen looks blank", distinct > 1);
    }
}
