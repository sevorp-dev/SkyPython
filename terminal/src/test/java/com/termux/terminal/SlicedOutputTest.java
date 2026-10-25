package com.termux.terminal;

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SkyPython: output reaches the emulator in small slices (the session's per-append bound), cut
 * anywhere: inside an escape sequence or a UTF-8 character. The screen must come out the same as
 * when everything arrives at once.
 */
public class SlicedOutputTest extends TestCase {

    private static byte[] sample() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 40; i++) {
            String line = "\033#8" + "\033[1;3" + (i % 8) + "mрядок " + i + " ✓ 日本\033[0m"
                + "\033[" + (i % 5 + 1) + ";" + (i % 7 + 1) + "H" + "0123456789".repeat(3)
                + "\033]0;title " + i + "\007" + "\r\n";
            byte[] b = line.getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    private static TerminalEmulator feed(byte[] data, int slice) {
        TerminalEmulator emulator = new TerminalEmulator(new TerminalTestCase.MockTerminalOutput(), 30, 8, 13, 15, 100, null);
        byte[] buffer = new byte[slice];
        for (int offset = 0; offset < data.length; offset += slice) {
            int n = Math.min(slice, data.length - offset);
            System.arraycopy(data, offset, buffer, 0, n);
            emulator.append(buffer, n);
        }
        return emulator;
    }

    private static String render(TerminalEmulator emulator) {
        StringBuilder sb = new StringBuilder(emulator.getScreen().getTranscriptText());
        TerminalBuffer screen = emulator.getScreen();
        for (int row = 0; row < emulator.mRows; row++) {
            for (int col = 0; col < emulator.mColumns; col++) sb.append(' ').append(screen.getStyleAt(row, col));
        }
        sb.append(" cursor=").append(emulator.getCursorRow()).append(',').append(emulator.getCursorCol());
        sb.append(" title=").append(emulator.getTitle());
        return sb.toString();
    }

    public void testSlicesRenderLikeOneAppend() {
        byte[] data = sample();
        String whole = render(feed(data, data.length));
        assertEquals(whole, render(feed(data, 256)));
        assertEquals(whole, render(feed(data, 7)));
        assertEquals(whole, render(feed(data, 1)));
    }
}
