package com.yotakon.pesport.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Archivo AFS (por ejemplo 0_text.afs): "AFS\0", u32 número de archivos y tabla de
 * (u32 offset, u32 tamaño). Se lee por posiciones: el AFS (cientos de MB) nunca se carga entero.
 *
 * Los AFS de PES no llevan nombres de archivo: cada archivo se identifica por su posición.
 */
class Afs private constructor(
    private val channel: FileChannel,
    private val offsets: LongArray,
    private val sizes: LongArray,
) {
    fun count(): Int = sizes.size

    fun size(i: Int): Long = sizes[i]

    fun offset(i: Int): Long = offsets[i]

    /** Inicio del primer archivo con datos: tamaño de la cabecera del AFS original. */
    fun headerSize(): Long {
        var min = Long.MAX_VALUE
        for (i in sizes.indices) {
            if (sizes[i] > 0 && offsets[i] in 1 until min) min = offsets[i]
        }
        return if (min == Long.MAX_VALUE) 0 else min
    }

    /** Contenido completo del archivo [i]. */
    fun read(i: Int): ByteArray {
        if (sizes[i] > Int.MAX_VALUE) throw IOException("el archivo $i es demasiado grande")
        return readAt(channel, offsets[i], sizes[i].toInt())
    }

    /** Primeros [max] bytes (o menos) del archivo [i]. */
    fun readHead(i: Int, max: Int): ByteArray = readAt(channel, offsets[i], minOf(max.toLong(), sizes[i]).toInt())

    /** Copia el archivo [i] a [out] por bloques (sirve para archivos de cualquier tamaño). */
    fun copyTo(i: Int, out: OutputStream, buffer: ByteArray) {
        var pos = offsets[i]
        var left = sizes[i]
        val bb = ByteBuffer.wrap(buffer)
        while (left > 0) {
            bb.clear()
            bb.limit(minOf(buffer.size.toLong(), left).toInt())
            val n = channel.read(bb, pos)
            if (n < 0) throw IOException("fin de archivo inesperado")
            out.write(buffer, 0, n)
            pos += n
            left -= n
        }
    }

    companion object {
        private const val MAX_FILES = 100000

        fun open(channel: FileChannel): Afs {
            val total = channel.size()
            if (total < 8) throw ConversionException("No es un archivo AFS")
            val head = readAt(channel, 0, 8)
            if (head[0] != 'A'.code.toByte() || head[1] != 'F'.code.toByte() || head[2] != 'S'.code.toByte() || head[3].toInt() != 0) {
                throw ConversionException("No es un archivo AFS")
            }
            val n = Bytes.u32i(head, 4)
            if (n < 1 || n > MAX_FILES || 8 + 8L * n > total) throw ConversionException("Tabla del AFS no válida")
            val table = readAt(channel, 8, 8 * n)
            val offsets = LongArray(n)
            val sizes = LongArray(n)
            for (i in 0 until n) {
                val off = Bytes.u32(table, 8 * i)
                val size = Bytes.u32(table, 8 * i + 4)
                if (off + size > total) {
                    throw ConversionException("El AFS está incompleto: el archivo $i termina fuera")
                }
                offsets[i] = off
                sizes[i] = size
            }
            return Afs(channel, offsets, sizes)
        }

        private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray {
            val b = ByteBuffer.allocate(len)
            while (b.hasRemaining()) {
                if (ch.read(b, pos + b.position()) < 0) throw IOException("fin de archivo inesperado")
            }
            return b.array()
        }
    }
}

/**
 * Creación de AFS para PES 5 a partir de archivos sueltos.
 *
 * Formato que se escribe (el mismo que usan los AFS de PES, sin nombres de archivo):
 * - "AFS\0", u32 número de archivos, tabla de (u32 offset, u32 tamaño).
 * - 8 bytes a cero después de la tabla (no hay tabla de nombres).
 * - Cabecera rellena con ceros hasta un múltiplo de 2048 bytes (o hasta el tamaño de la cabecera
 *   del AFS original, si es mayor).
 * - Cada archivo empieza en un múltiplo de 2048 bytes; el hueco hasta el siguiente va a cero.
 * - Un archivo vacío se guarda como (offset 0, tamaño 0).
 */
object AfsWriter {
    const val SECTOR = 0x800L
    private const val MAX_U32 = 0xFFFFFFFFL

    class Layout(val header: Long, val offsets: LongArray, val total: Long)

    fun pad(v: Long, to: Long = SECTOR): Long = (v + to - 1) / to * to

    /** Posición de cada archivo y tamaño final del AFS. */
    fun layout(sizes: LongArray, minHeader: Long = 0): Layout {
        val n = sizes.size
        if (n < 1) throw ConversionException("No hay archivos para crear el AFS")
        val header = pad(maxOf(8L + 8L * n + 8L, minHeader))
        val offsets = LongArray(n)
        var at = header
        for (i in 0 until n) {
            if (sizes[i] < 0) throw ConversionException("Tamaño no válido en el archivo $i")
            if (sizes[i] == 0L) continue
            offsets[i] = at
            at = pad(at + sizes[i])
        }
        if (at > MAX_U32) {
            throw ConversionException("El AFS ocuparía ${at / (1024 * 1024)} MB: el formato AFS no admite más de 4 GB")
        }
        return Layout(header, offsets, at)
    }

    /** Cabecera completa (tabla y relleno) de [layout.header] bytes. */
    fun header(sizes: LongArray, layout: Layout): ByteArray {
        val out = ByteBuf()
        out.put(byteArrayOf('A'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte(), 0))
        out.u32(sizes.size.toLong())
        for (i in sizes.indices) {
            out.u32(layout.offsets[i])
            out.u32(sizes[i])
        }
        out.zeros((layout.header - out.size).toInt())
        return out.toArray()
    }

    /**
     * Escribe el AFS en [out]. [open] abre el archivo i (solo se llama si su tamaño es > 0); se
     * comprueba que cada archivo tenga exactamente el tamaño anunciado en [sizes].
     *
     * Abrir un archivo de una carpeta de Android tarda mucho más que copiarlo, así que con
     * [prefetch] > 1 se abren por adelantado los siguientes archivos en otros hilos mientras se
     * copia el actual. El AFS se escribe siempre en orden, con el mismo resultado.
     */
    fun write(
        out: OutputStream,
        sizes: LongArray,
        minHeader: Long,
        open: (Int) -> InputStream,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        prefetch: Int = 1,
    ): Layout {
        val layout = layout(sizes, minHeader)
        out.write(header(sizes, layout))
        var pos = layout.header
        val buf = ByteArray(1 shl 20)
        val pool = if (prefetch > 1) Executors.newFixedThreadPool(prefetch) else null
        val opened = arrayOfNulls<Future<InputStream>>(sizes.size)
        var submitted = -1
        fun submitThrough(last: Int) {
            while (pool != null && submitted < minOf(last, sizes.size - 1)) {
                submitted++
                val k = submitted
                if (sizes[k] > 0) opened[k] = pool.submit(Callable { open(k) })
            }
        }
        try {
            for (i in sizes.indices) {
                if (sizes[i] > 0) {
                    submitThrough(i + prefetch)
                    val stream = try {
                        opened[i]?.get() ?: open(i)
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                    opened[i] = null
                    var copied = 0L
                    stream.use { input ->
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            copied += r
                            if (copied > sizes[i]) break
                            out.write(buf, 0, r)
                        }
                    }
                    if (copied != sizes[i]) {
                        throw IOException("el archivo $i ha cambiado de tamaño mientras se creaba el AFS")
                    }
                    pos += copied
                    val end = pad(pos)
                    writeZeros(out, end - pos, buf)
                    pos = end
                }
                progress(i + 1, sizes.size)
            }
        } finally {
            pool?.shutdownNow()
            // Cierra los archivos abiertos por adelantado que ya no se van a usar.
            for (f in opened) {
                if (f != null && f.isDone && !f.isCancelled) {
                    try {
                        f.get().close()
                    } catch (ignored: Exception) {
                        // ya no importa
                    }
                }
            }
        }
        return layout
    }

    private fun writeZeros(out: OutputStream, n: Long, buf: ByteArray) {
        if (n <= 0) return
        buf.fill(0, 0, minOf(n, buf.size.toLong()).toInt())
        var left = n
        while (left > 0) {
            val k = minOf(left, buf.size.toLong()).toInt()
            out.write(buf, 0, k)
            left -= k
        }
    }
}

/**
 * Carpeta con los archivos de un AFS: nombres por índice ("00000.bin", "00001.bin"…) y el archivo
 * [INFO_NAME] con los datos del AFS original para poder crearlo igual.
 */
object AfsFolder {
    const val INFO_NAME = "AFS_INFO.txt"
    private val INDEX_NAME = Regex("^(?:unnamed_)?(\\d{1,6})(?:\\.[A-Za-z0-9]{1,8})?$", RegexOption.IGNORE_CASE)

    /** "01701.bin": al menos 5 cifras para que el explorador de archivos los ordene bien. */
    fun entryName(index: Int, count: Int): String {
        val digits = maxOf(5, (count - 1).toString().length)
        return index.toString().padStart(digits, '0') + ".bin"
    }

    /**
     * Índice del AFS que corresponde a un nombre: "01701.bin", "1701.bin" o "unnamed_1701.bin".
     * Cualquier otro nombre (por ejemplo «1701 FACE - RONALDINHO.bin», cuyo número es de PES 2010)
     * devuelve null y no se mete en el AFS.
     */
    fun indexOf(name: String): Int? = INDEX_NAME.matchEntire(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Datos del AFS original guardados al extraer. */
    class Info(val count: Int, val header: Long, val sizes: LongArray, val name: String = "") {
        fun serialize(): String {
            val sb = StringBuilder()
            sb.append("# AFS extraído por PES Face Converter. No lo borres: sirve para crear el AFS otra vez.\n")
            sb.append("# Cada archivo es <índice>.bin. Para reemplazar uno, pon el nuevo con el mismo nombre.\n")
            if (name.isNotEmpty()) sb.append("nombre=").append(name.replace('\n', ' ')).append('\n')
            sb.append("archivos=").append(count).append('\n')
            sb.append("cabecera=").append(header).append('\n')
            sb.append("# tamaños originales (índice=bytes; 0 = vacío)\n")
            for (i in sizes.indices) sb.append(i).append('=').append(sizes[i]).append('\n')
            return sb.toString()
        }

        companion object {
            fun parse(text: String): Info? {
                var count = -1
                var header = 0L
                var name = ""
                val sizes = HashMap<Int, Long>()
                for (line in text.lineSequence()) {
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) continue
                    val eq = t.indexOf('=')
                    if (eq < 0) continue
                    val k = t.substring(0, eq).trim()
                    val v = t.substring(eq + 1).trim()
                    when {
                        k == "nombre" -> name = v
                        k == "archivos" -> count = v.toIntOrNull() ?: return null
                        k == "cabecera" -> header = v.toLongOrNull() ?: return null
                        else -> {
                            val i = k.toIntOrNull() ?: continue
                            sizes[i] = v.toLongOrNull() ?: continue
                        }
                    }
                }
                if (count < 1 || count > 100000 || header < 0) return null
                return Info(count, header, LongArray(count) { sizes[it] ?: -1L }, name)
            }
        }
    }

    /** Un archivo de la carpeta: nombre, tamaño y lo que haga falta para abrirlo (uri, ruta…). */
    class File<T>(val name: String, val size: Long, val ref: T)

    /** Resultado de revisar la carpeta antes de crear el AFS. */
    class Plan<T>(
        /** Archivo de cada índice (null = vacío). */
        val entries: List<File<T>?>,
        val sizes: LongArray,
        val header: Long,
        /** Nombres que no siguen el formato «índice.bin» y no se usan. */
        val ignored: List<String>,
        /** Índices cuyo tamaño es distinto del original (vacío si no hay [INFO_NAME]). */
        val changed: List<Int>,
    )

    /**
     * Decide qué archivo va en cada índice. Falla (con un mensaje para el usuario) si falta algún
     * índice, si hay dos archivos para el mismo índice o si sobran índices respecto al original.
     */
    fun <T> plan(files: List<File<T>>, info: Info?): Plan<T> {
        val byIndex = HashMap<Int, File<T>>()
        val ignored = ArrayList<String>()
        val dupes = ArrayList<String>()
        for (f in files) {
            if (f.name.equals(INFO_NAME, ignoreCase = true)) continue
            val i = indexOf(f.name)
            if (i == null) {
                ignored.add(f.name)
                continue
            }
            val prev = byIndex.put(i, f)
            if (prev != null) dupes.add("$i («${prev.name}» y «${f.name}»)")
        }
        if (dupes.isNotEmpty()) {
            throw ConversionException("Hay dos archivos para el mismo índice: " + shortList(dupes) + ". Deja solo uno.")
        }
        if (byIndex.isEmpty()) {
            throw ConversionException("La carpeta no tiene archivos con nombre de índice (00000.bin, 00001.bin…)")
        }
        val maxIndex = byIndex.keys.max()
        val count = info?.count ?: (maxIndex + 1)
        if (maxIndex >= count) {
            throw ConversionException("El AFS original tenía $count archivos (0–${count - 1}) y hay un archivo con índice $maxIndex")
        }
        val missing = ArrayList<Int>()
        val entries = ArrayList<File<T>?>(count)
        val sizes = LongArray(count)
        val changed = ArrayList<Int>()
        for (i in 0 until count) {
            val f = byIndex[i]
            val orig = info?.sizes?.get(i) ?: -1L
            if (f == null) {
                // Solo puede faltar un archivo que en el original estaba vacío.
                if (orig != 0L) missing.add(i)
                entries.add(null)
                continue
            }
            entries.add(if (f.size > 0) f else null)
            sizes[i] = f.size
            if (orig >= 0 && orig != f.size) changed.add(i)
        }
        if (missing.isNotEmpty()) {
            throw ConversionException("Faltan ${missing.size} archivos: " + shortList(missing.map { entryName(it, count) }))
        }
        return Plan(entries, sizes, info?.header ?: 0L, ignored, changed)
    }

    private fun shortList(items: List<String>): String =
        if (items.size <= 5) items.joinToString(", ") else items.take(5).joinToString(", ") + "… (${items.size} en total)"
}
