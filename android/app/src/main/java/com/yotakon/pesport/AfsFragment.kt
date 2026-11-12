package com.yotakon.pesport

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.tabs.TabLayout
import com.yotakon.pesport.core.Afs
import com.yotakon.pesport.core.AfsFolder
import com.yotakon.pesport.core.AfsWriter
import com.yotakon.pesport.core.ConversionException
import com.yotakon.pesport.core.Parallel
import com.yotakon.pesport.core.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import com.google.android.material.R as MR

/**
 * Pestaña AFS.
 * - Extraer: guarda cada archivo de un AFS como «00000.bin», «00001.bin»… en una carpeta nueva,
 *   junto con [AfsFolder.INFO_NAME] (número de archivos, cabecera y tamaños originales).
 * - Crear: lista la carpeta, deja reemplazar archivos (también por otros más grandes) y crea un
 *   AFS nuevo para PES 5 con todos los archivos en su sitio.
 *   Con el «modo rápido» no se extrae nada: se usa el AFS original directamente y solo se eligen
 *   los archivos a reemplazar; el resto se lee del original al crear el AFS nuevo.
 */
class AfsFragment : Fragment(R.layout.fragment_afs) {
    /** Fila de la lista de la subpestaña Crear: un índice del AFS. */
    private class Entry(val index: Int, val name: String, var child: Saf.Child?, var size: Long, val original: Long) {
        /** Modo rápido: archivo que sustituye al del AFS original. */
        var repl: Repl? = null
    }

    private class Repl(val uri: Uri, val name: String)

    private class Scan(
        val info: AfsFolder.Info?, val entries: List<Entry>, val ignored: Int, val summary: String,
        val fast: Boolean = false,
    )

    private lateinit var progress: LinearProgressIndicator
    private lateinit var srcText: TextView
    private lateinit var dstText: TextView
    private lateinit var extractButton: MaterialButton
    private lateinit var extractLog: TextView
    private lateinit var extractScroll: ScrollView
    private lateinit var buildFolderText: TextView
    private lateinit var buildSummary: TextView
    private lateinit var buildButton: MaterialButton
    private lateinit var tabs: TabLayout
    private lateinit var fastCheck: MaterialCheckBox
    private lateinit var fastHint: View
    private lateinit var cardFastAfs: View
    private lateinit var cardBuildFolder: View
    private lateinit var fastAfsText: TextView
    private val adapter = EntryAdapter()

    private var srcAfs: Uri? = null
    private var dstTree: Uri? = null
    private var buildFolder: Folder? = null
    private var scan: Scan? = null

    // modo rápido
    private var fastMode = false
    private var fastPfd: ParcelFileDescriptor? = null
    private var fastAfs: Afs? = null
    private var fastName = ""
    private var fastScan: Scan? = null
    private var scanSummary = ""
    /** Texto del buscador de la lista de Crear AFS (vacío = se ven todos los archivos). */
    private var query = ""
    private var busy = false

    /** Archivo que se va a reemplazar (índice de la lista) mientras el usuario elige el nuevo. */
    private var replacing = -1
    /** Qué escribir cuando el usuario elija dónde guardar el AFS nuevo. */
    private var pendingWrite: ((Uri) -> Unit)? = null

    private val pickSrc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            srcAfs = uri
            srcText.text = Saf.displayName(requireContext(), uri, "AFS")
        }
    }

    private val pickDst = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Saf.persist(requireContext(), uri)
            dstTree = uri
            prefs().edit().putString(KEY_DST, uri.toString()).apply()
            refreshLabels()
        }
    }

    private val pickBuildFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Saf.persist(requireContext(), uri)
            setBuildFolder(Folder.of(uri))
        }
    }

    private val pickReplacement = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pos = replacing
        replacing = -1
        if (uri != null && pos >= 0) confirmReplace(pos, uri)
    }

    private val createAfs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val job = pendingWrite
        pendingWrite = null
        if (uri != null && job != null) job(uri)
    }

    private val pickFastAfs = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openFastAfs(uri)
    }

    private fun prefs() = requireContext().getSharedPreferences(ConvertFragment.PREFS, Context.MODE_PRIVATE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        progress = view.findViewById(R.id.afs_progress)
        srcText = view.findViewById(R.id.src_afs)
        dstText = view.findViewById(R.id.dst_folder)
        extractButton = view.findViewById(R.id.afs_extract)
        extractLog = view.findViewById(R.id.extract_log)
        extractScroll = view.findViewById(R.id.extract_scroll)
        buildFolderText = view.findViewById(R.id.build_folder)
        buildSummary = view.findViewById(R.id.build_summary)
        buildButton = view.findViewById(R.id.afs_build)

        val pageExtract = view.findViewById<View>(R.id.page_extract)
        val pageBuild = view.findViewById<View>(R.id.page_build)
        tabs = view.findViewById(R.id.afs_tabs)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                pageExtract.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                pageBuild.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        view.findViewById<View>(R.id.pick_src_afs).setOnClickListener { if (!busy) pickSrc.launch(arrayOf("*/*")) }
        view.findViewById<View>(R.id.pick_dst_folder).setOnClickListener { if (!busy) pickDst.launch(null) }
        view.findViewById<View>(R.id.pick_build_folder).setOnClickListener { if (!busy) pickBuildFolder.launch(null) }
        view.findViewById<View>(R.id.pick_fast_afs).setOnClickListener { if (!busy) pickFastAfs.launch(arrayOf("*/*")) }
        fastCheck = view.findViewById(R.id.fast_mode)
        fastHint = view.findViewById(R.id.fast_hint)
        cardFastAfs = view.findViewById(R.id.card_fast_afs)
        cardBuildFolder = view.findViewById(R.id.card_build_folder)
        fastAfsText = view.findViewById(R.id.fast_afs_name)
        fastCheck.setOnCheckedChangeListener { _, on ->
            if (!busy) setFastMode(on) else fastCheck.isChecked = fastMode
        }
        extractButton.setOnClickListener { extract() }
        buildButton.setOnClickListener { prepareBuild() }

        val list = view.findViewById<RecyclerView>(R.id.build_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        list.adapter = adapter

        // Buscador: escribe el número del archivo (1701) y la lista solo muestra ese.
        val search = view.findViewById<TextInputLayout>(R.id.afs_search)
        search.hint = getString(R.string.afs_search_hint)
        search.editText?.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            doAfterTextChanged {
                query = it?.toString().orEmpty()
                adapter.update()
                list.scrollToPosition(0)
            }
            setOnEditorActionListener { v, _, _ ->
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
        }

        dstTree = prefs().getString(KEY_DST, null)?.let(Uri::parse)
        buildFolder = Folder.parse(prefs().getString(KEY_BUILD, null))
        refreshLabels()
        buildFolder?.let(::rescan)
        fastMode = prefs().getBoolean(KEY_FAST, false)
        fastCheck.isChecked = fastMode
        applyMode()
    }

    override fun onDestroy() {
        closeFast()
        super.onDestroy()
    }

    private fun closeFast() {
        try {
            fastPfd?.close()
        } catch (ignored: IOException) {
            // nada que hacer
        }
        fastPfd = null
        fastAfs = null
    }

    // ------------------------------------------------------------ modo rápido

    private fun setFastMode(on: Boolean) {
        fastMode = on
        prefs().edit().putBoolean(KEY_FAST, on).apply()
        applyMode()
    }

    /** Enseña la tarjeta que toca (AFS original o carpeta) y la lista del modo activo. */
    private fun applyMode() {
        fastHint.visibility = if (fastMode) View.VISIBLE else View.GONE
        cardFastAfs.visibility = if (fastMode) View.VISIBLE else View.GONE
        cardBuildFolder.visibility = if (fastMode) View.GONE else View.VISIBLE
        adapter.update()
        refreshSummary()
    }

    private fun current(): Scan? = if (fastMode) fastScan else scan

    private fun refreshSummary() {
        scanSummary = if (fastMode) fastSummary() else (scan?.summary ?: getString(R.string.afs_build_empty))
        status(null)
    }

    private fun fastSummary(): String {
        val s = fastScan ?: return getString(R.string.afs_fast_empty)
        val replaced = s.entries.count { it.repl != null }
        val total = s.entries.sumOf { maxOf(0L, it.size) }
        return buildString {
            append(String.format(Locale.getDefault(), "Archivos: %d · %s · original: %s", s.entries.size, sizeText(total), fastName))
            if (replaced > 0) append("\nReemplazados: $replaced")
            append("\nToca un archivo para reemplazarlo. El AFS original no se modifica.")
        }
    }

    private fun openFastAfs(uri: Uri) {
        setBusy(true)
        val ctx = requireContext().applicationContext
        val name = Saf.displayName(ctx, uri, "AFS")
        fastAfsText.text = name
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var pfd: ParcelFileDescriptor? = null
                try {
                    pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("no se pudo abrir")
                    val afs = Afs.open(FileInputStream(pfd.fileDescriptor).channel)
                    val n = afs.count()
                    val entries = (0 until n).map { i -> Entry(i, AfsFolder.entryName(i, n), null, afs.size(i), afs.size(i)) }
                    Result.success(Triple(pfd, afs, Scan(null, entries, 0, "", fast = true)))
                } catch (e: Exception) {
                    try {
                        pfd?.close()
                    } catch (ignored: IOException) {
                        // nada que hacer
                    }
                    Result.failure(e)
                }
            }
            setBusy(false)
            result.onSuccess { (pfd, afs, sc) ->
                closeFast()
                fastPfd = pfd
                fastAfs = afs
                fastName = name
                fastScan = sc
                adapter.update()
                refreshSummary()
            }.onFailure { e ->
                fastAfsText.text = getString(R.string.none_chosen)
                message(getString(R.string.afs_cannot_build, e.message ?: e.toString()))
            }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        extractButton.isEnabled = !b
        buildButton.isEnabled = !b
    }

    private fun refreshLabels() {
        dstText.text = dstTree?.let(Saf::folderLabel) ?: getString(R.string.none_chosen)
        buildFolderText.text = buildFolder?.label(requireContext()) ?: getString(R.string.none_chosen)
    }

    private fun message(text: String) {
        MaterialAlertDialogBuilder(requireContext()).setMessage(text).setPositiveButton(R.string.ok, null).show()
    }

    /** Se puede llamar desde cualquier hilo. */
    private fun log(line: String) {
        extractLog.post {
            val cur = extractLog.text
            extractLog.text = if (cur.isEmpty() || cur.toString() == getString(R.string.afs_extract_log_empty)) line else "$cur\n$line"
            extractScroll.post { extractScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Línea de estado debajo del resumen de la carpeta. Se puede llamar desde cualquier hilo. */
    private fun status(line: String?) {
        buildSummary.post { buildSummary.text = if (line == null) scanSummary else "$scanSummary\n$line" }
    }

    // ------------------------------------------------------------ extraer

    private fun extract() {
        if (busy) return
        val src = srcAfs
        if (src == null) {
            message(getString(R.string.need_src_afs))
            return
        }
        val tree = dstTree
        if (tree == null) {
            message(getString(R.string.need_extract_folder))
            return
        }
        setBusy(true)
        val ctx = requireContext().applicationContext
        val srcName = Saf.displayName(ctx, src, "AFS")
        log("Extrayendo $srcName…")
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val pfd = ctx.contentResolver.openFileDescriptor(src, "r") ?: throw IOException("no se pudo abrir")
                    pfd.use {
                        val afs = Afs.open(FileInputStream(it.fileDescriptor).channel)
                        val n = afs.count()
                        val sizes = LongArray(n) { i -> afs.size(i) }
                        log(String.format(Locale.getDefault(), "Archivos en el AFS: %d (%s)", n, sizeText(sizes.sum())))
                        val base = srcName.substringBeforeLast('.').ifEmpty { srcName }
                        val dir = Saf.createDir(ctx, Saf.treeRoot(tree), base)
                        log("Carpeta: ${Saf.folderLabel(tree)}/${Saf.displayName(ctx, dir, base)}")
                        // El archivo de datos va primero: si la extracción se corta, al crear el AFS
                        // se sabrá cuántos archivos faltan.
                        val info = AfsFolder.Info(n, afs.headerSize(), sizes, srcName)
                        val infoDoc = Saf.createDocument(ctx.contentResolver, dir, "text/plain", AfsFolder.INFO_NAME)
                        Saf.openOut(ctx, infoDoc).use { o -> o.write(info.serialize().toByteArray(Charsets.UTF_8)) }
                        // Crear cada archivo en la carpeta tarda mucho más que copiarlo: se hace en
                        // varios hilos a la vez (la lectura del AFS por posiciones es segura).
                        val todo = (0 until n).filter { i -> sizes[i] > 0 }
                        val empty = n - todo.size
                        val written = AtomicInteger(0)
                        val buffers = ThreadLocal.withInitial { ByteArray(256 * 1024) }
                        Parallel.forEach(todo) { i ->
                            val doc = Saf.createDocument(ctx.contentResolver, dir, "application/octet-stream",
                                AfsFolder.entryName(i, n))
                            Saf.openOut(ctx, doc).use { o -> afs.copyTo(i, o, buffers.get()!!) }
                            val k = written.incrementAndGet()
                            if (k % 250 == 0) log("  $k de ${todo.size}…")
                        }
                        log("Listo: ${written.get()} archivos" + (if (empty > 0) " ($empty vacíos, sin archivo)" else "") + ".")
                        Result.success(Folder(tree, DocumentsContract.getDocumentId(dir)))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            setBusy(false)
            result.onSuccess { folder ->
                log(getString(R.string.afs_extract_done))
                setBuildFolder(folder)
            }.onFailure { e -> log("Error: ${e.message}") }
        }
    }

    // ------------------------------------------------------------ carpeta para crear

    private fun setBuildFolder(folder: Folder) {
        buildFolder = folder
        prefs().edit().putString(KEY_BUILD, folder.serialize()).apply()
        refreshLabels()
        rescan(folder)
    }

    private fun rescan(folder: Folder) {
        setBusy(true)
        if (!fastMode) {
            scanSummary = getString(R.string.afs_reading_folder)
            status(null)
        }
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { scanFolder(ctx, folder) } }
            result.onSuccess { s ->
                scan = s
                if (!fastMode) adapter.update()
                refreshSummary()
            }.onFailure { e ->
                scan = null
                if (!fastMode) adapter.update()
                refreshSummary()
                if (!fastMode) {
                    scanSummary = "Error: ${e.message}"
                    status(null)
                }
            }
            setBusy(false)
        }
    }

    private fun scanFolder(ctx: Context, folder: Folder): Scan {
        val children = Saf.listChildren(ctx, folder).filter { !it.isDir }
        val info = children.firstOrNull { it.name.equals(AfsFolder.INFO_NAME, ignoreCase = true) }?.let {
            AfsFolder.Info.parse(String(Saf.read(ctx, folder.child(it.documentId), 4 shl 20), Charsets.UTF_8))
        }
        val byIndex = HashMap<Int, Saf.Child>()
        var ignored = 0
        for (c in children) {
            if (c.name.equals(AfsFolder.INFO_NAME, ignoreCase = true)) continue
            val i = AfsFolder.indexOf(c.name)
            if (i == null) ignored++ else byIndex.putIfAbsent(i, c)
        }
        val count = info?.count ?: ((byIndex.keys.maxOrNull() ?: -1) + 1)
        val entries = (0 until count).map { i ->
            val c = byIndex[i]
            Entry(i, c?.name ?: AfsFolder.entryName(i, count), c, c?.let { sizeOf(ctx, folder, it) } ?: -1,
                info?.sizes?.get(i) ?: -1)
        }
        val missing = entries.count { it.child == null && it.original != 0L }
        val changed = entries.count { it.child != null && it.original >= 0 && it.size != it.original }
        val total = entries.sumOf { maxOf(0L, it.size) }
        val sb = StringBuilder()
        if (count == 0) {
            sb.append(ctx.getString(R.string.afs_no_entries))
        } else {
            sb.append(String.format(Locale.getDefault(), "Archivos: %d · %s", count, sizeText(total)))
            if (info == null) sb.append("\nSin ${AfsFolder.INFO_NAME}: se usan los archivos tal cual")
            else if (info.name.isNotEmpty()) sb.append(" · original: ${info.name}")
            if (changed > 0) sb.append("\nCambiados respecto al original: $changed")
            if (missing > 0) sb.append("\nFaltan: $missing")
            if (ignored > 0) sb.append("\nOtros archivos (no van al AFS): $ignored")
            sb.append("\nToca un archivo para reemplazarlo.")
        }
        return Scan(info, entries, ignored, sb.toString())
    }

    /** Tamaño del archivo; si el proveedor no lo da, se pregunta al abrirlo. */
    private fun sizeOf(ctx: Context, folder: Folder, c: Saf.Child): Long {
        if (c.size >= 0) return c.size
        return try {
            ctx.contentResolver.openFileDescriptor(folder.child(c.documentId), "r")?.use { it.statSize } ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    // ------------------------------------------------------------ reemplazar

    private fun onEntryTapped(pos: Int) {
        if (busy) return
        replacing = pos
        pickReplacement.launch(arrayOf("*/*"))
    }

    private fun confirmReplace(pos: Int, uri: Uri) {
        val s = current() ?: return
        val e = s.entries.getOrNull(pos) ?: return
        val ctx = requireContext()
        val newName = Saf.displayName(ctx, uri)
        var newSize = Saf.size(ctx, uri)
        if (s.fast) {
            if (newSize < 0) {
                newSize = try {
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
                } catch (ex: Exception) {
                    -1
                }
            }
            if (newSize < 0) {
                message(getString(R.string.afs_cannot_build, "no se pudo saber el tamaño de «$newName»"))
                return
            }
            val size = newSize
            val builder = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.afs_replace_title)
                .setMessage(getString(R.string.afs_replace_confirm_fast, e.name, sizeText(e.size), newName, sizeText(size)))
                .setPositiveButton(R.string.afs_replace) { _, _ ->
                    e.repl = Repl(uri, newName)
                    e.size = size
                    adapter.changed(e.index)
                    refreshSummary()
                }
                .setNegativeButton(R.string.cancel, null)
            if (e.repl != null) builder.setNeutralButton(R.string.afs_remove_replacement) { _, _ -> removeReplacement(pos) }
            builder.show()
            return
        }
        val folder = buildFolder ?: return
        val now = if (e.child != null) sizeText(e.size) else getString(R.string.afs_entry_missing)
        val text = getString(R.string.afs_replace_confirm, e.name, now, newName,
            if (newSize >= 0) sizeText(newSize) else "?")
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.afs_replace_title)
            .setMessage(text)
            .setPositiveButton(R.string.afs_replace) { _, _ -> replace(folder, e, uri, newName) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeReplacement(pos: Int) {
        val e = fastScan?.entries?.getOrNull(pos) ?: return
        e.repl = null
        e.size = e.original
        adapter.changed(e.index)
        refreshSummary()
    }

    private fun replace(folder: Folder, e: Entry, uri: Uri, newName: String) {
        setBusy(true)
        status("Copiando «$newName» en ${e.name}…")
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val target = e.child?.let { folder.child(it.documentId) }
                        ?: Saf.createDocument(ctx.contentResolver, folder.uri, "application/octet-stream", e.name)
                    var copied = 0L
                    Saf.openIn(ctx, uri).use { input ->
                        Saf.openOut(ctx, target).use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                            }
                        }
                    }
                    Pair(target, copied)
                }
            }
            setBusy(false)
            result.onSuccess { (_, copied) ->
                // Vuelve a leer la carpeta: actualiza la fila y el resumen con el tamaño nuevo.
                rescan(folder)
                Snackbar.make(buildButton, getString(R.string.afs_replaced, e.name, sizeText(copied)), Snackbar.LENGTH_LONG).show()
            }.onFailure { ex -> status("Error al reemplazar ${e.name}: ${ex.message}") }
        }
    }

    // ------------------------------------------------------------ crear el AFS

    private fun prepareBuild() {
        if (busy) return
        if (fastMode) {
            prepareFast()
            return
        }
        val folder = buildFolder
        if (folder == null) {
            message(getString(R.string.need_build_folder))
            return
        }
        setBusy(true)
        status(getString(R.string.afs_checking))
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val children = Saf.listChildren(ctx, folder).filter { !it.isDir }
                    val info = children.firstOrNull { it.name.equals(AfsFolder.INFO_NAME, ignoreCase = true) }?.let {
                        AfsFolder.Info.parse(String(Saf.read(ctx, folder.child(it.documentId), 4 shl 20), Charsets.UTF_8))
                    }
                    val files = children.map { AfsFolder.File(it.name, sizeOf(ctx, folder, it), it.documentId) }
                    val unknown = files.filter { it.size < 0 && AfsFolder.indexOf(it.name) != null }
                    if (unknown.isNotEmpty()) throw IOException("no se pudo saber el tamaño de «${unknown[0].name}»")
                    Pair(AfsFolder.plan(files, info), info)
                }
            }
            setBusy(false)
            result.onSuccess { (plan, info) ->
                status(null)
                val ctx2 = requireContext().applicationContext
                pendingWrite = { out ->
                    writeAfs(out, plan.sizes, plan.header, plan.changed.size) { i ->
                        Saf.openIn(ctx2, folder.child(plan.entries[i]!!.ref))
                    }
                }
                val suggested = info?.name?.takeIf { it.isNotEmpty() } ?: "0_text.afs"
                createAfs.launch(suggested)
            }.onFailure { e ->
                status(null)
                message(getString(R.string.afs_cannot_build, e.message ?: e.toString()))
            }
        }
    }

    /** Modo rápido: el AFS nuevo sale del original más los archivos reemplazados. */
    private fun prepareFast() {
        val afs = fastAfs
        val s = fastScan
        if (afs == null || s == null) {
            message(getString(R.string.need_fast_afs))
            return
        }
        val ctx = requireContext().applicationContext
        val sizes = LongArray(s.entries.size) { i -> s.entries[i].size }
        val changed = s.entries.count { it.repl != null }
        pendingWrite = { out ->
            writeAfs(out, sizes, afs.headerSize(), changed) { i ->
                s.entries[i].repl?.let { Saf.openIn(ctx, it.uri) } ?: afs.inputStream(i)
            }
        }
        createAfs.launch(fastName.ifEmpty { "0_text.afs" })
    }

    private fun writeAfs(out: Uri, sizes: LongArray, header: Long, changed: Int, open: (Int) -> java.io.InputStream) {
        setBusy(true)
        val ctx = requireContext().applicationContext
        val n = sizes.size
        status("Creando el AFS: 0 de $n…")
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BufferedOutputStream(Saf.openOut(ctx, out), 1 shl 20).use { o ->
                        AfsWriter.write(
                            o, sizes, header,
                            open = open,
                            progress = { done, total -> if (done % 250 == 0 || done == total) status("Creando el AFS: $done de $total…") },
                            prefetch = Parallel.IO_THREADS,
                        )
                    }
                }
            }
            setBusy(false)
            result.onSuccess { layout ->
                val name = Saf.displayName(ctx, out, "AFS")
                status(String.format(Locale.getDefault(), "AFS creado: %s · %d archivos · %s%s", name, n, sizeText(layout.total),
                    if (changed > 0) " · $changed cambiados" else ""))
                message(getString(R.string.afs_built, name, n, sizeText(layout.total)))
            }.onFailure { e ->
                // No dejar un AFS a medias que parezca bueno.
                try {
                    DocumentsContract.deleteDocument(ctx.contentResolver, out)
                } catch (ignored: Exception) {
                    // si no se puede borrar, el mensaje ya avisa del error
                }
                val msg = if (e is ConversionException || e is IOException) e.message else e.toString()
                status("Error al crear el AFS: $msg")
                message(getString(R.string.afs_cannot_build, msg))
            }
        }
    }

    // ------------------------------------------------------------ lista

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.Holder>() {
        private var shown: List<Entry> = emptyList()

        /** Vuelve a calcular qué archivos se ven (modo, lista o buscador cambiados). */
        fun update() {
            val all = current()?.entries ?: emptyList()
            val q = query.trim()
            shown = if (q.isEmpty()) all else all.filter { Search.matches(it.index, it.name, q) }.sortedBy { Search.rank(it.index, q) }
            notifyDataSetChanged()
        }

        fun changed(index: Int) {
            val p = shown.indexOfFirst { it.index == index }
            if (p >= 0) notifyItemChanged(p)
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.entry_name)
            val status: TextView = v.findViewById(R.id.entry_status)
            val size: TextView = v.findViewById(R.id.entry_size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_afs_entry, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val sc = current() ?: return
            val e = shown.getOrNull(position) ?: return
            h.name.text = e.name
            val normal = MaterialColors.getColor(h.status, MR.attr.colorOnSurfaceVariant)
            val primary = MaterialColors.getColor(h.status, MR.attr.colorPrimary)
            when {
                sc.fast && e.repl != null -> {
                    h.status.text = getString(R.string.afs_entry_replaced, e.repl!!.name, sizeText(e.original))
                    h.status.setTextColor(primary)
                }
                sc.fast && e.original == 0L -> {
                    h.status.text = getString(R.string.afs_entry_empty)
                    h.status.setTextColor(normal)
                }
                sc.fast -> {
                    h.status.text = ""
                    h.status.setTextColor(normal)
                }
                e.child == null && e.original == 0L -> {
                    h.status.text = getString(R.string.afs_entry_empty)
                    h.status.setTextColor(normal)
                }
                e.child == null -> {
                    h.status.text = getString(R.string.afs_entry_missing)
                    h.status.setTextColor(MaterialColors.getColor(h.status, MR.attr.colorError))
                }
                e.original >= 0 && e.size != e.original -> {
                    h.status.text = getString(R.string.afs_entry_changed, sizeText(e.original))
                    h.status.setTextColor(MaterialColors.getColor(h.status, MR.attr.colorPrimary))
                }
                else -> {
                    h.status.text = ""
                    h.status.setTextColor(normal)
                }
            }
            h.status.visibility = if (h.status.text.isEmpty()) View.GONE else View.VISIBLE
            h.size.text = if (sc.fast) (if (e.size > 0) sizeText(e.size) else "—")
            else if (e.child != null && e.size >= 0) sizeText(e.size) else "—"
            h.itemView.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) shown.getOrNull(p)?.let { onEntryTapped(it.index) }
            }
        }

        override fun getItemCount() = shown.size
    }

    companion object {
        private const val KEY_DST = "afsExtractTree"
        private const val KEY_BUILD = "afsBuildFolder"
        private const val KEY_FAST = "afsFastMode"

        fun sizeText(b: Long): String = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", b / 1024.0)
            else -> String.format(Locale.getDefault(), "%.1f MB", b / (1024.0 * 1024.0))
        }
    }
}
