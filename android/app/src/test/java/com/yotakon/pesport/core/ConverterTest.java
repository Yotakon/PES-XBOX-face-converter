package com.yotakon.pesport.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Assume;
import org.junit.Test;

/**
 * Compara la salida del conversor con archivos de referencia generados por la version Python
 * (validada en el juego). Los .bin de muestra estan en la raiz del repositorio.
 */
public class ConverterTest {
    private static File samples() {
        return new File(System.getProperty("samples.dir", "."));
    }

    private static byte[] read(String rel) throws IOException {
        File f = new File(samples(), rel);
        Assume.assumeTrue("Falta " + f, f.exists());
        return Files.readAllBytes(f.toPath());
    }

    @Test
    public void faceMatchesReference() throws IOException {
        byte[] in = read("Ronaldinho FACE PES 2010 PS2.bin");
        Converter.Info info = Converter.inspect(in);
        assertEquals(Converter.Kind.FACE, info.kind);
        assertFalse(info.needsColor);
        Converter.Result r = Converter.convert(in, null, 1.0);
        assertArrayEquals(read("tests/referencia/face.bin"), r.data);
    }

    @Test
    public void hairMatchesReference() throws IOException {
        byte[] in = read("Ronaldinho HAIR PES2010 PS2.bin");
        assertTrue(Converter.inspect(in).needsColor);
        String[][] cases = {{"281e18", "1.0"}, {"e6be5a", "1.0"}, {"808080", "0.75"}, {"103050", "1.3"}};
        for (String[] c : cases) {
            Converter.Result r = Converter.convert(in, Integer.parseInt(c[0], 16), Double.parseDouble(c[1]));
            assertArrayEquals("color " + c[0], read("tests/referencia/hair_" + c[0] + "_" + c[1] + ".bin"), r.data);
        }
    }

    @Test
    public void hairWithoutColorIsRejected() throws IOException {
        try {
            Converter.convert(read("Ronaldinho HAIR PES2010 PS2.bin"), null, 1.0);
            fail("se esperaba error");
        } catch (ConversionException expected) {
            assertTrue(expected.getMessage().contains("color"));
        }
    }

    @Test
    public void xboxFileIsRejected() throws IOException {
        try {
            Converter.convert(read("Ronaldinho Face PES5 XBOX.bin"), null, 1.0);
            fail("se esperaba error");
        } catch (ConversionException expected) {
            assertTrue(expected.getMessage().contains("PES 5"));
        }
    }

    @Test
    public void garbageIsRejected() {
        try {
            Converter.convert(new byte[]{1, 2, 3}, null, 1.0);
            fail("se esperaba error");
        } catch (ConversionException expected) {
            // ok
        }
    }
}
