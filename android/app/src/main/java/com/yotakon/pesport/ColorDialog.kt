package com.yotakon.pesport

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.yotakon.pesport.core.Converter
import java.util.Locale
import kotlin.math.roundToLong

/** Selector de color y brillo del pelo con vista previa de la textura teñida. */
object ColorDialog {
    private val PRESETS = intArrayOf(0x1A1410, 0x281E18, 0x4A3020, 0x7A5030, 0xC8A060, 0xE6C88C, 0x8C3818, 0x808080, 0xE0E0E0)
    private val PRESET_NAMES = arrayOf("Negro", "Castaño oscuro", "Castaño", "Castaño claro", "Rubio", "Rubio claro",
        "Pelirrojo", "Gris", "Canoso")

    private const val BRIGHT_MIN = 0.5f
    private const val BRIGHT_MAX = 2.0f
    private const val BRIGHT_STEP = 0.05f

    fun hex(rgb: Int): String = String.format(Locale.ROOT, "#%06X", rgb and 0xFFFFFF)

    private fun snapBrightness(b: Double): Float {
        val clamped = b.coerceIn(BRIGHT_MIN.toDouble(), BRIGHT_MAX.toDouble())
        val steps = ((clamped - BRIGHT_MIN) / BRIGHT_STEP).roundToLong()
        return BRIGHT_MIN + steps * BRIGHT_STEP
    }

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun show(ctx: Context, ps2Bin: ByteArray, rgb: Int, brightness: Double, onColor: (rgb: Int, brightness: Double) -> Unit) {
        var color = rgb and 0xFFFFFF
        var bright = snapBrightness(brightness).toDouble()
        var updating = false

        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_color, null)
        val preview = root.findViewById<ImageView>(R.id.preview)
        val swatch = root.findViewById<android.view.View>(R.id.swatch)
        val hexField = root.findViewById<TextInputEditText>(R.id.hex)
        val bars = arrayOf<Slider>(root.findViewById(R.id.red), root.findViewById(R.id.green), root.findViewById(R.id.blue))
        val brightBar = root.findViewById<Slider>(R.id.bright)
        val brightLabel = root.findViewById<TextView>(R.id.bright_label)
        val presets = root.findViewById<ChipGroup>(R.id.presets)

        brightBar.valueFrom = BRIGHT_MIN
        brightBar.valueTo = BRIGHT_MAX
        brightBar.stepSize = BRIGHT_STEP

        fun refresh() {
            updating = true
            for (i in 0 until 3) bars[i].value = ((color shr (16 - 8 * i)) and 0xFF).toFloat()
            brightBar.value = snapBrightness(bright)
            brightLabel.text = String.format(Locale.getDefault(), "Brillo: %.2f", bright)
            swatch.backgroundTintList = ColorStateList.valueOf(0xFF000000.toInt() or color)
            val h = hex(color)
            if (!hexField.text.toString().equals(h, ignoreCase = true)) hexField.setText(h)
            try {
                preview.setImageBitmap(Thumbs.bitmap(Converter.previewTexture(ps2Bin, color, bright), 4))
            } catch (e: RuntimeException) {
                preview.setImageDrawable(null)
            }
            updating = false
        }

        for (i in PRESETS.indices) {
            val c = PRESETS[i]
            presets.addView(Chip(ctx).apply {
                text = PRESET_NAMES[i]
                setChipIconResource(R.drawable.circle)
                chipIconTint = ColorStateList.valueOf(0xFF000000.toInt() or c)
                isChipIconVisible = true
                setOnClickListener {
                    color = c
                    refresh()
                }
            })
        }

        hexField.doAfterTextChanged { s ->
            if (updating) return@doAfterTextChanged
            val t = s.toString().trim().replace("#", "")
            if (t.matches(Regex("[0-9a-fA-F]{6}"))) {
                color = t.toInt(16)
                refresh()
            }
        }

        val onBar = Slider.OnChangeListener { slider, value, fromUser ->
            if (!fromUser || updating) return@OnChangeListener
            if (slider === brightBar) {
                bright = value.toDouble()
            } else {
                color = (bars[0].value.toInt() shl 16) or (bars[1].value.toInt() shl 8) or bars[2].value.toInt()
            }
            refresh()
        }
        bars.forEach { it.addOnChangeListener(onBar) }
        brightBar.addOnChangeListener(onBar)
        refresh()

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.hair_color)
            .setIcon(R.drawable.ic_palette)
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ -> onColor(color, bright) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
