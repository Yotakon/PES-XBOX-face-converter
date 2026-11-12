package com.yotakon.pesport.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Compara la salida del conversor con archivos de referencia generados por la versión Python
 * (validada en el juego). Los .bin de muestra están en la raíz del repositorio.
 */
class ConverterTest {
    private fun samples() = File(System.getProperty("samples.dir") ?: ".")

    private fun read(rel: String): ByteArray {
        val f = File(samples(), rel)
        Assume.assumeTrue("Falta $f", f.exists())
        return f.readBytes()
    }

    @Test
    fun faceMatchesReference() {
        val input = read("Ronaldinho FACE PES 2010 PS2.bin")
        val info = Converter.inspect(input)
        assertEquals(Converter.Kind.FACE, info.kind)
        assertFalse(info.needsColor)
        val r = Converter.convert(input, null, 1.0)
        assertArrayEquals(read("tests/referencia/face.bin"), r.data)
    }

    @Test
    fun hairMatchesReference() {
        val input = read("Ronaldinho HAIR PES2010 PS2.bin")
        assertTrue(Converter.inspect(input).needsColor)
        val cases = listOf("281e18" to "1.0", "e6be5a" to "1.0", "808080" to "0.75", "103050" to "1.3")
        for ((color, bright) in cases) {
            val r = Converter.convert(input, color.toInt(16), bright.toDouble())
            assertArrayEquals("color $color", read("tests/referencia/hair_${color}_$bright.bin"), r.data)
        }
    }

    @Test
    fun hairWithoutColorIsRejected() {
        try {
            Converter.convert(read("Ronaldinho HAIR PES2010 PS2.bin"), null, 1.0)
            fail("se esperaba error")
        } catch (expected: ConversionException) {
            assertTrue(expected.message!!.contains("color"))
        }
    }

    @Test
    fun xboxFileIsRejected() {
        try {
            Converter.convert(read("Ronaldinho Face PES5 XBOX.bin"), null, 1.0)
            fail("se esperaba error")
        } catch (expected: ConversionException) {
            assertTrue(expected.message!!.contains("PES 5"))
        }
    }

    @Test
    fun garbageIsRejected() {
        try {
            Converter.convert(byteArrayOf(1, 2, 3), null, 1.0)
            fail("se esperaba error")
        } catch (expected: ConversionException) {
            // ok
        }
    }
}
