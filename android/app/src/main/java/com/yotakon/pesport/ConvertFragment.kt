package com.yotakon.pesport

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.yotakon.pesport.core.ConversionException
import com.yotakon.pesport.core.Converter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import com.google.android.material.R as MR

/**
 * Pestaña Convertir: el usuario añade .bin de caras/pelos de PES 2010 PS2, elige el color de
 * cada pelo y la carpeta de salida, y la app guarda los .bin convertidos para PES 5 Xbox.
 */
class ConvertFragment : Fragment(R.layout.fragment_convert) {
    /** Un archivo de la lista. */
    private class Item(val name: String) {
        var data: ByteArray? = null
        var info: Converter.Info? = null
        var error: String? = null
        var rgb = DEFAULT_HAIR
        var brightness = 1.0
        var status = ""
        var ok = false
        var failed = false
    }

    private val items = ArrayList<Item>()
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var folderText: TextView
    private lateinit var convertButton: ExtendedFloatingActionButton
    private lateinit var addButton: ExtendedFloatingActionButton
    private lateinit var progress: LinearProgressIndicator
    private var outputTree: Uri? = null
    private var busy = false

    private val openFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) loadFiles(uris)
    }

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Saf.persist(requireContext(), uri)
            outputTree = uri
            prefs().edit().putString(KEY_OUTPUT, uri.toString()).apply()
            refresh()
        }
    }

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        folderText = view.findViewById(R.id.folder)
        convertButton = view.findViewById(R.id.convert)
        addButton = view.findViewById(R.id.add)
        progress = view.findViewById(R.id.progress)

        outputTree = prefs().getString(KEY_OUTPUT, null)?.let(Uri::parse)

        addButton.setOnClickListener { if (!busy) openFiles.launch(arrayOf("*/*")) }
        convertButton.setOnClickListener { convertAll() }
        view.findViewById<View>(R.id.choose_folder).setOnClickListener { if (!busy) chooseFolder.launch(null) }
        refresh()
    }

    // ------------------------------------------------------------ selección de archivos

    private fun loadFiles(uris: List<Uri>) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.map { u ->
                    Item(Saf.displayName(ctx, u)).also { it.load(ctx, u) }
                }
            }
            items.addAll(loaded)
            refresh()
        }
    }

    private fun Item.load(ctx: Context, u: Uri) {
        try {
            val d = Saf.read(ctx, u, MAX_BIN_SIZE, failIfBigger = true)
            data = d
            val i = Converter.inspect(d)
            info = i
            status = ctx.getString(if (i.kind == Converter.Kind.FACE) R.string.kind_face else R.string.kind_hair) +
                " de PES 2010 PS2"
        } catch (e: ConversionException) {
            error = e.message
        } catch (e: IOException) {
            error = "No se pudo leer: ${e.message}"
        } catch (e: RuntimeException) {
            error = "No se pudo leer: ${e.message}"
        }
    }

    // ------------------------------------------------------------ conversión

    private fun message(res: Int) {
        MaterialAlertDialogBuilder(requireContext()).setMessage(res).setPositiveButton(R.string.ok, null).show()
    }

    private fun convertAll() {
        if (busy) return
        val tree = outputTree
        if (tree == null) {
            message(R.string.need_folder)
            return
        }
        val todo = items.filter { it.error == null && !it.ok }
        if (todo.isEmpty()) {
            message(R.string.nothing_to_do)
            return
        }
        busy = true
        refresh()
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            var ok = 0
            var fail = 0
            for (it in todo) {
                withContext(Dispatchers.IO) {
                    try {
                        val info = it.info!!
                        val r = Converter.convert(it.data!!, if (info.needsColor) it.rgb else null, it.brightness)
                        val doc = Saf.writeNew(ctx, Saf.treeRoot(tree), outputName(it.name), r.data)
                        it.ok = true
                        it.failed = false
                        it.status = "Convertido → " + Saf.displayName(ctx, doc)
                        ok++
                    } catch (e: ConversionException) {
                        it.failed = true
                        it.status = "Error: ${e.message}"
                        fail++
                    } catch (e: Exception) {
                        it.failed = true
                        it.status = "Error al guardar: ${e.message}"
                        fail++
                    }
                }
                refresh()
            }
            busy = false
            refresh()
            val msg = "$ok " + (if (ok == 1) "archivo convertido" else "archivos convertidos") +
                (if (fail > 0) ", $fail con error" else "")
            Snackbar.make(convertButton, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------ interfaz

    private fun refresh() {
        folderText.text = outputTree?.let(Saf::folderLabel) ?: getString(R.string.output_none)
        convertButton.isEnabled = !busy
        addButton.isEnabled = !busy
        convertButton.setText(if (busy) R.string.converting else R.string.convert)
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        val inf = LayoutInflater.from(requireContext())
        for (it in items) list.addView(row(inf, list, it))
    }

    private fun row(inf: LayoutInflater, parent: ViewGroup, it: Item): View {
        val v = inf.inflate(R.layout.item_file, parent, false)
        val card = v.findViewById<MaterialCardView>(R.id.card)
        val icon = v.findViewById<ImageView>(R.id.kind_icon)
        val name = v.findViewById<TextView>(R.id.name)
        val status = v.findViewById<TextView>(R.id.status)
        val remove = v.findViewById<MaterialButton>(R.id.remove)
        val colorRow = v.findViewById<View>(R.id.color_row)

        val hair = it.info?.kind == Converter.Kind.HAIR
        val bad = it.error != null || it.failed
        val (containerAttr, onContainerAttr) = when {
            bad -> MR.attr.colorErrorContainer to MR.attr.colorOnErrorContainer
            it.ok -> MR.attr.colorPrimaryContainer to MR.attr.colorOnPrimaryContainer
            hair -> MR.attr.colorSecondaryContainer to MR.attr.colorOnSecondaryContainer
            else -> MR.attr.colorTertiaryContainer to MR.attr.colorOnTertiaryContainer
        }
        icon.setImageResource(if (hair) R.drawable.ic_palette else R.drawable.ic_face)
        icon.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(icon, containerAttr))
        icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(icon, onContainerAttr))
        if (it.ok) card.strokeColor = MaterialColors.getColor(card, MR.attr.colorPrimary)

        name.text = it.name
        status.text = it.error?.let { e -> "No válido: $e" } ?: it.status
        if (bad) status.setTextColor(MaterialColors.getColor(status, MR.attr.colorError))

        val needsColor = it.error == null && it.info?.needsColor == true
        colorRow.visibility = if (needsColor) View.VISIBLE else View.GONE
        if (needsColor) {
            v.findViewById<View>(R.id.swatch).backgroundTintList = ColorStateList.valueOf(0xFF000000.toInt() or it.rgb)
            val color = v.findViewById<MaterialButton>(R.id.color)
            color.text = String.format(Locale.getDefault(), "%s · brillo %.2f", ColorDialog.hex(it.rgb), it.brightness)
            color.isEnabled = !busy
            color.setOnClickListener { _ ->
                ColorDialog.show(requireContext(), it.data!!, it.rgb, it.brightness) { rgb, br ->
                    it.rgb = rgb
                    it.brightness = br
                    it.ok = false
                    it.failed = false
                    it.status = getString(R.string.kind_hair) + " de PES 2010 PS2"
                    refresh()
                }
            }
        }
        remove.isEnabled = !busy
        remove.setOnClickListener { _ ->
            items.remove(it)
            refresh()
        }
        return v
    }

    companion object {
        private const val MAX_BIN_SIZE = 8 * 1024 * 1024
        private const val DEFAULT_HAIR = 0x281E18
        const val PREFS = "pesport"
        private const val KEY_OUTPUT = "outputTree"

        fun outputName(input: String): String {
            val base = if (input.lowercase(Locale.ROOT).endsWith(".bin")) input.substring(0, input.length - 4) else input
            return "$base (PES5 Xbox).bin"
        }
    }
}
