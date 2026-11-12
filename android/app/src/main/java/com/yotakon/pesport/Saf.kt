package com.yotakon.pesport

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Lectura y escritura de archivos y carpetas elegidos por el usuario (Storage Access Framework). */
object Saf {
    /** Archivo de una carpeta. */
    class Child(val documentId: String, val name: String, val mime: String?, val size: Long, val modified: Long) {
        val isDir: Boolean get() = DocumentsContract.Document.MIME_TYPE_DIR == mime
    }

    fun persist(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (ignored: SecurityException) {
            // sin permiso persistente: sigue valiendo durante esta sesión
        }
    }

    fun displayName(ctx: Context, u: Uri, fallback: String = "archivo.bin"): String {
        try {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (ignored: RuntimeException) {
            // algunos proveedores no dan nombre
        }
        return u.lastPathSegment ?: fallback
    }

    fun size(ctx: Context, u: Uri): Long {
        try {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (ignored: RuntimeException) {
            // sin tamaño
        }
        return -1
    }

    fun folderLabel(tree: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val colon = id.indexOf(':')
        if (colon < 0) return id
        val vol = id.substring(0, colon)
        return (if (vol == "primary") "Almacenamiento interno" else vol) + "/" + id.substring(colon + 1)
    }

    /** Documento de la propia carpeta elegida. */
    fun treeRoot(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun openIn(ctx: Context, u: Uri): InputStream =
        ctx.contentResolver.openInputStream(u) ?: throw IOException("no se pudo abrir")

    /** Abre para escribir desde cero ("wt" vacía el archivo; algunos proveedores solo aceptan "w"). */
    fun openOut(ctx: Context, u: Uri): OutputStream {
        val cr = ctx.contentResolver
        val out = try {
            cr.openOutputStream(u, "wt")
        } catch (e: IllegalArgumentException) {
            cr.openOutputStream(u, "w")
        } catch (e: UnsupportedOperationException) {
            cr.openOutputStream(u, "w")
        }
        return out ?: throw IOException("no se pudo escribir")
    }

    /** Lee como mucho [max] bytes. Con [failIfBigger], falla si el archivo es más grande. */
    fun read(ctx: Context, u: Uri, max: Int, failIfBigger: Boolean = false): ByteArray {
        openIn(ctx, u).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            if (failIfBigger && out.size() >= max && input.read() >= 0) {
                throw IOException("es demasiado grande para ser un FACE o un HAIR")
            }
            return out.toByteArray()
        }
    }

    fun createDocument(cr: ContentResolver, dir: Uri, mime: String, name: String): Uri {
        val doc = try {
            DocumentsContract.createDocument(cr, dir, mime, name)
        } catch (e: Exception) {
            throw IOException("no se pudo crear «$name» (${e.message})")
        }
        return doc ?: throw IOException("no se pudo crear «$name»")
    }

    fun createDir(ctx: Context, parent: Uri, name: String): Uri =
        createDocument(ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)

    /** Crea un archivo nuevo en [dir] y escribe [data]. Devuelve el nombre que le dio el sistema. */
    fun writeNew(ctx: Context, dir: Uri, name: String, data: ByteArray): Uri {
        val doc = createDocument(ctx.contentResolver, dir, "application/octet-stream", name)
        openOut(ctx, doc).use { it.write(data) }
        return doc
    }

    /**
     * Archivos de la carpeta (sin entrar en subcarpetas). Algunos proveedores devuelven
     * null la primera vez: se reintenta un par de veces.
     */
    fun listChildren(ctx: Context, folder: Folder): List<Child> {
        val cr = ctx.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder.tree, folder.docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        var reason: String? = null
        repeat(3) {
            val c = try {
                cr.query(children, cols, null, null, null)
            } catch (e: RuntimeException) {
                reason = e.message
                null
            }
            if (c == null) {
                Thread.sleep(300)
                return@repeat
            }
            c.use {
                val out = ArrayList<Child>()
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    out.add(
                        Child(
                            it.getString(0), name, it.getString(2),
                            if (it.isNull(3)) -1 else it.getLong(3),
                            if (it.isNull(4)) 0 else it.getLong(4),
                        )
                    )
                }
                return out
            }
        }
        throw IOException(
            "no se pudo leer la carpeta" + (reason?.let { " ($it)" } ?: "") +
                ". Vuelve a pulsar Elegir y selecciónala de nuevo."
        )
    }

    fun childUri(tree: Uri, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
}

/**
 * Carpeta dentro de una carpeta elegida por el usuario: [tree] es la elegida (la que tiene
 * permiso) y [docId] la carpeta concreta (la misma o una subcarpeta creada por la app).
 */
data class Folder(val tree: Uri, val docId: String) {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    fun child(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    /** Ruta legible. Las subcarpetas que crea la app están directamente dentro de la elegida. */
    fun label(ctx: Context): String {
        val root = Saf.folderLabel(tree)
        return if (docId == DocumentsContract.getTreeDocumentId(tree)) root
        else root + "/" + Saf.displayName(ctx, uri, docId)
    }

    fun serialize(): String = "$tree\n$docId"

    companion object {
        fun of(tree: Uri): Folder = Folder(tree, DocumentsContract.getTreeDocumentId(tree))

        fun parse(s: String?): Folder? {
            if (s == null) return null
            val nl = s.indexOf('\n')
            return try {
                if (nl < 0) of(Uri.parse(s)) else Folder(Uri.parse(s.substring(0, nl)), s.substring(nl + 1))
            } catch (e: RuntimeException) {
                null
            }
        }
    }
}
