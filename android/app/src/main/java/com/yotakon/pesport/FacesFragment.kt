package com.yotakon.pesport

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.yotakon.pesport.core.Afs
import com.yotakon.pesport.core.Converter
import com.yotakon.pesport.core.FaceHair
import com.yotakon.pesport.core.Pairs
import com.yotakon.pesport.core.Parallel
import com.yotakon.pesport.core.PlayerDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger
import com.google.android.material.R as MR

/**
 * Pestaña Importar 0_TEXT: caras y pelos del 0_TEXT.AFS de PES 2010 PS2.
 * Subpestaña 1: busca el AFS, cuenta FACES y HAIRS, empareja cada FACE con su HAIR y los extrae
 * renombrados. Subpestaña 2: abre una carpeta de .bin extraídos y muestra las parejas con previa.
 */
class FacesFragment : Fragment(R.layout.fragment_faces) {
    /** Fila de la tabla FACE | HAIR | Previa | Previa. */
    private class Row {
        var faceSlot = Pairs.NONE
        var faceLabel = ""
        var hairLabel = ""
        var faceKey: String? = null
        var hairKey: String? = null
        var face: Thumbs.Source? = null
        var hair: Thumbs.Source? = null
    }

    private val thumbs = Thumbs()

    // subpestaña 1
    private lateinit var afsName: TextView
    private lateinit var afsLog: TextView
    private lateinit var extractFolder: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var extractButton: MaterialButton
    private val afsRows = ArrayList<Row>()
    private lateinit var afsAdapter: PairAdapter
    private var afsPfd: ParcelFileDescriptor? = null
    private var afs: Afs? = null
    private var faces: List<Int> = emptyList()
    private var hairs: List<Int> = emptyList()
    private var pairs = Pairs()
    private var overrides = TreeMap<Int, Int>()
    private var overridesKey: String? = null
    private var extractTree: Uri? = null

    // subpestaña 2
    private lateinit var binsFolder: TextView
    private lateinit var binsSummary: TextView
    private lateinit var prevFaceLabel: TextView
    private lateinit var prevHairLabel: TextView
    private lateinit var prevFace: ImageView
    private lateinit var prevHair: ImageView
    private val binRows = ArrayList<Row>()
    private lateinit var binsAdapter: PairAdapter
    private var binsTree: Uri? = null

    private lateinit var progress: LinearProgressIndicator
    private var busy = false

    private val pickAfs = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openAfs(uri)
    }

    private val pickExtract = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Saf.persist(requireContext(), uri)
            extractTree = uri
            prefs().edit().putString(KEY_EXTRACT, uri.toString()).apply()
            refreshFolders()
        }
    }

    private val pickBins = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Saf.persist(requireContext(), uri)
            binsTree = uri
            prefs().edit().putString(KEY_BINS, uri.toString()).apply()
            refreshFolders()
            loadBins(uri)
        }
    }

    private fun prefs() = requireContext().getSharedPreferences(ConvertFragment.PREFS, Context.MODE_PRIVATE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        progress = view.findViewById(R.id.progress)

        val tabAfs = view.findViewById<View>(R.id.tab_afs)
        val tabFolder = view.findViewById<View>(R.id.tab_folder)
        view.findViewById<TabLayout>(R.id.tabs).addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabAfs.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                tabFolder.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        afsName = view.findViewById(R.id.afs_name)
        afsLog = view.findViewById(R.id.afs_log)
        logScroll = view.findViewById(R.id.log_scroll)
        extractFolder = view.findViewById(R.id.extract_folder)
        extractButton = view.findViewById(R.id.extract)
        view.findViewById<View>(R.id.pick_afs).setOnClickListener { if (!busy) pickAfs.launch(arrayOf("*/*")) }
        view.findViewById<View>(R.id.pick_extract).setOnClickListener { if (!busy) pickExtract.launch(null) }
        extractButton.setOnClickListener { extractAll() }
        afsAdapter = PairAdapter(afsRows, ::pickHairFor, true)
        setupList(view.findViewById(R.id.afs_list), afsAdapter)

        binsFolder = view.findViewById(R.id.bins_folder)
        binsSummary = view.findViewById(R.id.bins_summary)
        prevFace = view.findViewById(R.id.prev_face)
        prevHair = view.findViewById(R.id.prev_hair)
        prevFaceLabel = view.findViewById(R.id.prev_face_label)
        prevHairLabel = view.findViewById(R.id.prev_hair_label)
        view.findViewById<View>(R.id.pick_bins).setOnClickListener { if (!busy) pickBins.launch(null) }
        binsAdapter = PairAdapter(binRows, ::showPreview, false)
        // En la subpestaña Carpeta la previa va arriba: la lista no lleva columnas de previa.
        val binsHeader = view.findViewById<View>(R.id.bins_header)
        binsHeader.findViewById<View>(R.id.head_prev_face).visibility = View.GONE
        binsHeader.findViewById<View>(R.id.head_prev_hair).visibility = View.GONE
        setupList(view.findViewById(R.id.bins_list), binsAdapter)

        extractTree = prefs().getString(KEY_EXTRACT, null)?.let(Uri::parse)
        binsTree = prefs().getString(KEY_BINS, null)?.let(Uri::parse)
        refreshFolders()
        binsTree?.let(::loadBins)
    }

    override fun onDestroyView() {
        thumbs.clear()
        super.onDestroyView()
    }

    override fun onDestroy() {
        thumbs.shutdown()
        closeAfs(afsPfd)
        afsPfd = null
        super.onDestroy()
    }

    private fun setupList(rv: RecyclerView, adapter: PairAdapter) {
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        rv.adapter = adapter
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        extractButton.isEnabled = !b
        extractButton.setText(if (b) R.string.extracting else R.string.extract)
    }

    private fun refreshFolders() {
        extractFolder.text = extractTree?.let(Saf::folderLabel) ?: getString(R.string.none_chosen)
        binsFolder.text = binsTree?.let(Saf::folderLabel) ?: getString(R.string.none_chosen)
    }

    // ------------------------------------------------------------ registro

    private fun logClear(text: String) {
        afsLog.text = text
    }

    /** Se puede llamar desde cualquier hilo. */
    private fun log(line: String) {
        afsLog.post {
            val cur = afsLog.text
            afsLog.text = if (cur.isEmpty()) line else "$cur\n$line"
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------------ subpestaña 1: 0_TEXT.AFS

    private class Opened(
        val pfd: ParcelFileDescriptor,
        val afs: Afs,
        val faces: List<Int>,
        val hairs: List<Int>,
        val pairs: Pairs,
        val overrides: TreeMap<Int, Int>,
        val key: String,
        val dbLine: String,
    )

    private fun openAfs(uri: Uri) {
        setBusy(true)
        val ctx = requireContext().applicationContext
        val name = Saf.displayName(ctx, uri, "0_TEXT.AFS")
        afsName.text = name
        logClear("Leyendo $name…")
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var pfd: ParcelFileDescriptor? = null
                try {
                    pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("no se pudo abrir")
                    val channel = FileInputStream(pfd.fileDescriptor).channel
                    val size = channel.size()
                    val a = Afs.open(channel)
                    // Cada archivo se descomprime un poco para saber si es FACE o HAIR: se reparte entre hilos.
                    val kinds = arrayOfNulls<FaceHair.Kind>(a.count())
                    val checked = AtomicInteger(0)
                    Parallel.forEach((0 until a.count()).toList(), Runtime.getRuntime().availableProcessors().coerceIn(2, 8)) { i ->
                        if (a.size(i) >= 32) kinds[i] = FaceHair.classify(a.readHead(i, FaceHair.HEAD_BYTES))
                        val k = checked.incrementAndGet()
                        if (k % 2000 == 0) log("Revisados $k de ${a.count()}…")
                    }
                    val f = ArrayList<Int>()
                    val h = ArrayList<Int>()
                    for (i in kinds.indices) {
                        when (kinds[i]) {
                            FaceHair.Kind.FACE -> f.add(i)
                            FaceHair.Kind.HAIR -> h.add(i)
                            null -> {}
                        }
                    }
                    val key = "overrides_${a.count()}_$size"
                    var players: List<PlayerDb.Player> = emptyList()
                    var dbEntry = Pairs.NONE
                    for (e in PlayerDb.AFS_ENTRIES) {
                        if (e < a.count() && a.size(e) > 0 && a.size(e) < MAX_BIN_SIZE) {
                            players = PlayerDb.read(a.read(e))
                            if (players.isNotEmpty()) {
                                dbEntry = e
                                break
                            }
                        }
                    }
                    var starts: IntArray? = null
                    if (PlayerDb.GROUP_TABLE_ENTRY < a.count() && a.size(PlayerDb.GROUP_TABLE_ENTRY) < MAX_BIN_SIZE) {
                        starts = PlayerDb.groupStarts(a.read(PlayerDb.GROUP_TABLE_ENTRY), f.size)
                    }
                    val startsFromAfs = starts != null
                    val pr = if (f.isEmpty() || h.isEmpty()) Pairs()
                    else Pairs.fromDb(players, f[0], h[0], starts ?: PlayerDb.DEFAULT_GROUP_START, f.toHashSet(), h.toHashSet())
                    val ov = loadOverrides(key, f, h)
                    pr.apply(ov)
                    val dbLine = if (dbEntry == Pairs.NONE) {
                        "Base de datos de jugadores: no encontrada (todas las caras quedan Sin HAIR)"
                    } else {
                        String.format(Locale.getDefault(), "Base de datos: %d jugadores (archivo %d)", players.size, dbEntry) +
                            (if (startsFromAfs) "" else "\nGrupos de FACES: valores por defecto (archivo 505 no válido)")
                    }
                    Result.success(Opened(pfd, a, f, h, pr, ov, key, dbLine))
                } catch (e: Exception) {
                    closeAfs(pfd)
                    Result.failure(e)
                }
            }
            result.onSuccess { o ->
                val old = afsPfd
                afsPfd = o.pfd
                afs = o.afs
                faces = o.faces
                hairs = o.hairs
                pairs = o.pairs
                overrides = o.overrides
                overridesKey = o.key
                thumbs.clear()
                closeAfs(old)
                buildAfsRows()
                logClear(
                    String.format(
                        Locale.getDefault(), "Archivos en el AFS: %d\n%s\n%s\n%s\n%s%s",
                        o.afs.count(), range("FACES", o.faces), range("HAIRS", o.hairs), o.dbLine, pairSummary(),
                        if (o.overrides.isEmpty()) "" else "\nCambios a mano: ${o.overrides.size}"
                    )
                )
            }.onFailure { e -> logClear("Error: ${e.message}") }
            setBusy(false)
        }
    }

    private fun range(what: String, l: List<Int>): String =
        if (l.isEmpty()) "$what: 0"
        else String.format(Locale.getDefault(), "%s: %d (archivos %d–%d)", what, l.size, l.first(), l.last())

    private fun pairSummary(): String {
        val lone = hairs.count { !pairs.isHairUsed(it) }
        var s = String.format(
            Locale.getDefault(), "Con HAIR: %d · Sin HAIR: %d · HAIRS sin FACE: %d",
            pairs.size(), faces.size - pairs.size(), lone
        )
        if (pairs.dbCount() > 0) {
            s += String.format(
                Locale.getDefault(), "\nEn la base de datos: %d FACES · No Implementado: %d FACES",
                pairs.dbCount(), faces.size - pairs.dbCount()
            )
        }
        return s
    }

    /** Cambios hechos a mano para este AFS (solo los que siguen siendo FACE/HAIR válidos). */
    private fun loadOverrides(key: String, f: List<Int>, h: List<Int>): TreeMap<Int, Int> {
        val fs = f.toHashSet()
        val hs = h.toHashSet()
        val out = TreeMap<Int, Int>()
        for ((face, hair) in Pairs.parseOverrides(prefs().getString(key, null))) {
            if (face in fs && (hair == Pairs.NONE || hair in hs)) out[face] = hair
        }
        return out
    }

    private fun saveOverrides() {
        overridesKey?.let { prefs().edit().putString(it, Pairs.serializeOverrides(overrides)).apply() }
    }

    private fun closeAfs(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (ignored: IOException) {
            // nada que hacer
        }
    }

    private fun afsFace(a: Afs, slot: Int) = Thumbs.Source { Converter.previewTexture(a.read(slot), null, 1.0) }

    private fun afsHair(a: Afs, slot: Int) = Thumbs.Source { Converter.previewTexture(a.read(slot), HAIR_PREVIEW, 1.0) }

    private fun buildAfsRows() {
        afsRows.clear()
        for (face in faces) {
            afsRows.add(Row().also {
                it.faceSlot = face
                fillAfsRow(it)
            })
        }
        afsAdapter.notifyDataSetChanged()
    }

    private fun fillAfsRow(r: Row) {
        val a = afs ?: return
        val hair = pairs.hairFor(r.faceSlot)
        val who = pairs.label(r.faceSlot)
        r.faceLabel = if (who.isEmpty()) r.faceSlot.toString() else "${r.faceSlot}\n$who"
        r.faceKey = "afs:f:${r.faceSlot}"
        r.face = afsFace(a, r.faceSlot)
        if (hair == Pairs.NONE) {
            val notImplemented = pairs.dbCount() > 0 && !pairs.isInDb(r.faceSlot)
            r.hairLabel = if (notImplemented) Pairs.NOT_IMPLEMENTED else getString(R.string.no_hair)
            r.hairKey = null
            r.hair = null
        } else {
            r.hairLabel = hair.toString()
            r.hairKey = "afs:h:$hair"
            r.hair = afsHair(a, hair)
        }
    }

    /** Selector de HAIR para una FACE: cuadrícula con las previas de todos los HAIRS. */
    private fun pickHairFor(position: Int) {
        val a = afs
        if (busy || a == null) return
        val ctx = requireContext()
        val row = afsRows[position]
        val current = pairs.hairFor(row.faceSlot)
        // "Sin HAIR" y después todos los HAIRS.
        val options = listOf(Pairs.NONE) + hairs

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        val pad = ColorDialog.dp(ctx, 16)
        root.setPadding(pad, ColorDialog.dp(ctx, 8), pad, 0)

        val face = ImageView(ctx)
        face.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(face, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ColorDialog.dp(ctx, 96)))
        thumbs.load(face, row.faceKey, row.face)

        val grid = RecyclerView(ctx)
        grid.layoutManager = GridLayoutManager(ctx, 3)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ColorDialog.dp(ctx, 360))
        lp.topMargin = ColorDialog.dp(ctx, 8)
        root.addView(grid, lp)

        var dialog: AlertDialog? = null
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_hair_cell, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val hair = options[pos]
                val iv = h.itemView.findViewById<ImageView>(R.id.prev)
                val label = h.itemView.findViewById<TextView>(R.id.label)
                label.text = (if (hair == current) "✓ " else "") +
                    (if (hair == Pairs.NONE) getString(R.string.no_hair) else hair.toString())
                label.setTypeface(null, if (hair == current) Typeface.BOLD else Typeface.NORMAL)
                if (hair == Pairs.NONE) thumbs.load(iv, null, null) else thumbs.load(iv, "afs:h:$hair", afsHair(a, hair))
                h.itemView.setOnClickListener {
                    pairs.setManual(row.faceSlot, hair)
                    overrides[row.faceSlot] = hair
                    saveOverrides()
                    fillAfsRow(row)
                    afsAdapter.notifyItemChanged(position)
                    log("FACE ${row.faceSlot} → " + if (hair == Pairs.NONE) getString(R.string.no_hair) else "HAIR $hair")
                    log(pairSummary())
                    dialog?.dismiss()
                }
            }

            override fun getItemCount() = options.size
        }
        grid.scrollToPosition(maxOf(0, if (current != Pairs.NONE) options.indexOf(current) else 0))

        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.pick_hair, row.faceLabel.replace('\n', ' ')))
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Extrae todas las FACES y HAIRS renombrados según su pareja. */
    private fun extractAll() {
        if (busy) return
        val a = afs
        if (a == null) {
            message(R.string.need_afs)
            return
        }
        val tree = extractTree
        if (tree == null) {
            message(R.string.need_extract_folder)
            return
        }
        setBusy(true)
        val ctx = requireContext().applicationContext
        val f = ArrayList(faces)
        val h = ArrayList(hairs)
        val pr = pairs.copy()
        log("Extrayendo en ${Saf.folderLabel(tree)}/$OUT_DIR…")
        viewLifecycleOwner.lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val nf = AtomicInteger(0)
                val nh = AtomicInteger(0)
                val lone = AtomicInteger(0)
                var error: String? = null
                try {
                    val dir = Saf.createDir(ctx, Saf.treeRoot(tree), OUT_DIR)
                    log("Carpeta: " + Saf.displayName(ctx, dir))
                    // Lista de archivos a escribir: (nombre, archivo del AFS, contador). Crearlos en
                    // la carpeta es lo lento, así que se hace en varios hilos a la vez.
                    class Job(val name: String, val slot: Int, val counter: AtomicInteger)
                    val jobs = ArrayList<Job>()
                    for (face in f) {
                        val player = pr.names(face).firstOrNull()
                        jobs.add(Job(Pairs.faceName(face, player), face, nf))
                        val hair = pr.hairFor(face)
                        if (hair != Pairs.NONE) jobs.add(Job(Pairs.hairName(face, hair, player), hair, nh))
                    }
                    for (hair in h) {
                        if (!pr.isHairUsed(hair)) jobs.add(Job(Pairs.loneHairName(hair), hair, lone))
                    }
                    val done = AtomicInteger(0)
                    Parallel.forEach(jobs.indices.toList()) { k ->
                        val j = jobs[k]
                        Saf.writeNew(ctx, dir, j.name, a.read(j.slot))
                        j.counter.incrementAndGet()
                        val d = done.incrementAndGet()
                        if (d % 200 == 0) log("  $d de ${jobs.size}…")
                    }
                } catch (e: Exception) {
                    error = e.message
                }
                String.format(
                    Locale.getDefault(), "%s FACES: %d · HAIRS con su FACE: %d · HAIRS sin FACE: %d",
                    if (error == null) "Listo." else "Error: $error.", nf.get(), nh.get(), lone.get()
                )
            }
            log(msg)
            setBusy(false)
        }
    }

    private fun message(res: Int) {
        MaterialAlertDialogBuilder(requireContext()).setMessage(res).setPositiveButton(R.string.ok, null).show()
    }

    // ------------------------------------------------------------ subpestaña 2: carpeta de .bin

    private class BinFile(val name: String, val uri: Uri, val parsed: Pairs.Name, val kind: FaceHair.Kind) {
        var used = false

        /** Número de FACE que da el nombre del archivo, o NONE. */
        val faceId: Int get() = if (parsed.ours) parsed.face else parsed.slot

        /** Número de HAIR que da el nombre del archivo, o NONE. */
        val hairId: Int get() = if (parsed.ours) parsed.hair else parsed.slot
    }

    private fun loadBins(tree: Uri) {
        setBusy(true)
        binsSummary.text = "Leyendo la carpeta…"
        val ctx = requireContext().applicationContext
        val noHair = getString(R.string.no_hair)
        val noFace = getString(R.string.no_face)
        // Parejas del AFS abierto en la subpestaña 1, para .bin con nombres de otras herramientas.
        val known = pairs.copy()
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = ArrayList<Row>()
            val summary = withContext(Dispatchers.IO) {
                try {
                    val files = listBins(ctx, tree)
                    val hairOfFace = HashMap<Int, BinFile>()
                    val hairBySlot = HashMap<Int, BinFile>()
                    val faceFiles = ArrayList<BinFile>()
                    val hairFiles = ArrayList<BinFile>()
                    for (b in files) {
                        if (b.kind == FaceHair.Kind.FACE) {
                            faceFiles.add(b)
                        } else {
                            hairFiles.add(b)
                            if (b.parsed.ours && b.parsed.face != Pairs.NONE) hairOfFace[b.parsed.face] = b
                            val slot = b.hairId
                            if (slot != Pairs.NONE) hairBySlot.putIfAbsent(slot, b)
                        }
                    }
                    faceFiles.sortBy { it.faceId }
                    var paired = 0
                    for (fb in faceFiles) {
                        val id = fb.faceId
                        var hb = if (id == Pairs.NONE) null else hairOfFace[id]
                        if (hb == null && id != Pairs.NONE && known.hairFor(id) != Pairs.NONE) {
                            hb = hairBySlot[known.hairFor(id)]
                        }
                        val r = Row()
                        var who = when {
                            fb.parsed.player.isNotEmpty() -> fb.parsed.player
                            id != Pairs.NONE -> known.label(id)
                            else -> ""
                        }
                        // esta app pone el jugador en el nombre; si no lo lleva, no está en la base de datos
                        if (who.isEmpty() && fb.parsed.ours) who = Pairs.NOT_IMPLEMENTED
                        r.faceLabel = when {
                            id == Pairs.NONE -> fb.name
                            who.isEmpty() -> id.toString()
                            else -> "$id\n$who"
                        }
                        r.faceKey = "uri:${fb.uri}"
                        r.face = uriSource(ctx, fb.uri, null)
                        if (hb != null) {
                            hb.used = true
                            paired++
                            val hs = hb.hairId
                            r.hairLabel = if (hs != Pairs.NONE) hs.toString() else hb.name
                            r.hairKey = "uri:${hb.uri}"
                            r.hair = uriSource(ctx, hb.uri, HAIR_PREVIEW)
                        } else {
                            r.hairLabel = if (who == Pairs.NOT_IMPLEMENTED) Pairs.NOT_IMPLEMENTED else noHair
                        }
                        rows.add(r)
                    }
                    var lone = 0
                    for (hb in hairFiles) {
                        if (hb.used) continue
                        lone++
                        val hs = hb.hairId
                        rows.add(Row().also { r ->
                            r.faceLabel = noFace
                            r.hairLabel = if (hs != Pairs.NONE) hs.toString() else hb.name
                            r.hairKey = "uri:${hb.uri}"
                            r.hair = uriSource(ctx, hb.uri, HAIR_PREVIEW)
                        })
                    }
                    if (files.isEmpty()) {
                        "No hay FACES ni HAIRS de PES 2010 PS2 en esta carpeta."
                    } else {
                        String.format(
                            Locale.getDefault(),
                            "FACES: %d · HAIRS: %d · Con HAIR: %d · Sin HAIR: %d · HAIRS sin FACE: %d. Toca una fila para ver su previa.",
                            faceFiles.size, hairFiles.size, paired, faceFiles.size - paired, lone
                        )
                    }
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            binRows.clear()
            binRows.addAll(rows)
            binsAdapter.notifyDataSetChanged()
            binsSummary.text = summary
            if (binRows.isNotEmpty()) showPreview(0) else clearPreview()
            setBusy(false)
        }
    }

    private fun listBins(ctx: Context, tree: Uri): List<BinFile> {
        val folder = Folder.of(tree)
        val out = ArrayList<BinFile>()
        // Primero solo la lista de nombres (rápido); los archivos se abren después y solo si hace falta.
        for (c in Saf.listChildren(ctx, folder)) {
            if (c.isDir || !c.name.lowercase(Locale.ROOT).endsWith(".bin") || c.size > MAX_BIN_SIZE) continue
            val doc = folder.child(c.documentId)
            val parsed = Pairs.parseName(c.name)
            val kind = if (parsed.ours) {
                // nombres de esta app: el tipo se sabe por el nombre
                if (parsed.face != Pairs.NONE && parsed.hair == Pairs.NONE) FaceHair.Kind.FACE else FaceHair.Kind.HAIR
            } else {
                try {
                    FaceHair.classify(Saf.read(ctx, doc, FaceHair.HEAD_BYTES))
                } catch (e: IOException) {
                    null
                }
            } ?: continue
            out.add(BinFile(c.name, doc, parsed, kind))
        }
        return out
    }

    private fun uriSource(ctx: Context, u: Uri, tint: Int?) =
        Thumbs.Source { Converter.previewTexture(Saf.read(ctx, u, MAX_BIN_SIZE), tint, 1.0) }

    private fun showPreview(position: Int) {
        val r = binRows[position]
        prevFaceLabel.text = getString(R.string.col_face) + " " + r.faceLabel
        // Con HAIR: "HAIR 4469"; sin HAIR se muestra solo "Sin HAIR" o "No Implementado".
        prevHairLabel.text = if (r.hair != null) getString(R.string.col_hair) + " " + r.hairLabel else r.hairLabel
        thumbs.load(prevFace, r.faceKey, r.face)
        thumbs.load(prevHair, r.hairKey, r.hair)
        binsAdapter.select(position)
    }

    private fun clearPreview() {
        prevFaceLabel.setText(R.string.col_face)
        prevHairLabel.setText(R.string.col_hair)
        thumbs.load(prevFace, null, null)
        thumbs.load(prevHair, null, null)
    }

    // ------------------------------------------------------------ lista

    private inner class PairAdapter(
        private val rows: List<Row>,
        private val onRow: (Int) -> Unit,
        private val previews: Boolean,
    ) : RecyclerView.Adapter<PairAdapter.Holder>() {
        private var selected = RecyclerView.NO_POSITION

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val face: TextView = v.findViewById(R.id.face)
            val hair: TextView = v.findViewById(R.id.hair)
            val facePrev: ImageView = v.findViewById(R.id.face_prev)
            val hairPrev: ImageView = v.findViewById(R.id.hair_prev)
        }

        fun select(position: Int) {
            val old = selected
            selected = position
            if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
            notifyItemChanged(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_pair, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = rows[position]
            h.face.text = r.faceLabel
            h.hair.text = r.hairLabel
            h.hair.alpha = if (r.hair == null) 0.6f else 1f
            h.itemView.isActivated = position == selected
            h.itemView.setBackgroundColor(
                if (position == selected) MaterialColors.getColor(h.itemView, MR.attr.colorSecondaryContainer) else 0
            )
            h.facePrev.visibility = if (previews) View.VISIBLE else View.GONE
            h.hairPrev.visibility = if (previews) View.VISIBLE else View.GONE
            if (previews) {
                thumbs.load(h.facePrev, r.faceKey, r.face)
                thumbs.load(h.hairPrev, r.hairKey, r.hair)
            }
            h.itemView.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onRow(p)
            }
        }

        override fun getItemCount() = rows.size
    }

    companion object {
        private const val MAX_BIN_SIZE = 8 * 1024 * 1024
        /** Color con el que se tiñe la previa del HAIR (en el AFS está en gris). */
        private const val HAIR_PREVIEW = 0x7A5030
        private const val OUT_DIR = "FACES y HAIRS"
        private const val KEY_EXTRACT = "extractTree"
        private const val KEY_BINS = "binsTree"
    }
}
