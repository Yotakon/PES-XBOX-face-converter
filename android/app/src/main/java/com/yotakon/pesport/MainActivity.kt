package com.yotakon.pesport

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Pantalla principal con tres pestañas:
 * 1. Convertir: FACES y HAIRS de PES 2010 PS2 a PES 5 Xbox.
 * 2. Extraer F & H: buscar FACES y HAIRS en el 0_TEXT.AFS de PES 2010 y extraerlos.
 * 3. AFS: extraer un AFS a una carpeta y crear un AFS para PES 5 a partir de una carpeta.
 *
 * El botón de la barra verde busca una versión nueva de la app en GitHub y la instala.
 */
class MainActivity : AppCompatActivity() {
    private class Tab(val title: Int, val create: () -> Fragment)

    private val tabs = listOf(
        Tab(R.string.tab_convert) { ConvertFragment() },
        Tab(R.string.tab_import) { FacesFragment() },
        Tab(R.string.tab_afs_tools) { AfsFragment() },
    )

    private lateinit var updateButton: View
    private var checking = false
    @Volatile
    private var cancelDownload = false
    private var pendingApk: File? = null

    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Vuelve de los ajustes: si ya dio el permiso, sigue con la instalación.
        val apk = pendingApk
        if (apk != null && Updater.canInstall(this)) install(apk)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pager = findViewById<ViewPager2>(R.id.pager)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int) = tabs[position].create()
        }
        // Las tres pestañas siguen vivas al cambiar (no se pierde el trabajo a medias) y solo se
        // cambia tocando la pestaña: deslizar chocaría con las listas y los deslizadores de color.
        pager.offscreenPageLimit = tabs.size - 1
        pager.isUserInputEnabled = false

        val tabLayout = findViewById<TabLayout>(R.id.tabs)
        TabLayoutMediator(tabLayout, pager, false, false) { tab, position ->
            tab.setText(tabs[position].title)
        }.attach()

        updateButton = findViewById(R.id.update_button)
        updateButton.setOnClickListener { checkForUpdate(manual = true) }
        if (savedInstanceState == null) {
            Updater.cleanup(this)   // APK descargado en una actualización anterior
            checkForUpdate(manual = false)
        }
    }

    // ------------------------------------------------------------ actualizar desde la app

    private fun checkForUpdate(manual: Boolean) {
        if (checking) return
        checking = true
        updateButton.isEnabled = false
        if (manual) Snackbar.make(updateButton, R.string.update_checking, Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.fetchLatest() } }
            checking = false
            updateButton.isEnabled = true
            val installed = Updater.installed(this@MainActivity)
            result.onSuccess { r ->
                when {
                    r.versionCode > installed.versionCode ->
                        if (manual) showNewVersion(r, installed)
                        else Snackbar.make(findViewById(R.id.pager), R.string.update_snack, Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.update_snack_action) { showNewVersion(r, installed) }.show()
                    manual -> MaterialAlertDialogBuilder(this@MainActivity)
                        .setMessage(getString(R.string.update_uptodate, installed.versionName, installed.versionCode))
                        .setPositiveButton(R.string.ok, null).show()
                }
            }.onFailure { e ->
                // La comprobación automática no molesta si no hay red.
                if (manual) showError(e.message ?: e.toString())
            }
        }
    }

    private fun showNewVersion(r: Updater.Release, installed: Updater.Installed) {
        val size = if (r.size > 0) Updater.sizeText(r.size) else "?"
        val date = r.published.take(10).ifEmpty { "?" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_title_new)
            .setMessage(getString(R.string.update_new_message, installed.versionName, installed.versionCode,
                r.versionName, r.versionCode, size, date))
            .setPositiveButton(R.string.update_now) { _, _ -> download(r) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun showError(msg: String) {
        MaterialAlertDialogBuilder(this).setMessage(getString(R.string.update_error, msg))
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun download(r: Updater.Release) {
        cancelDownload = false
        val pad = ColorDialog.dp(this, 24)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, ColorDialog.dp(this@MainActivity, 8), pad, 0)
        }
        val label = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            text = getString(R.string.update_downloading)
        }
        val bar = LinearProgressIndicator(this).apply { isIndeterminate = true }
        box.addView(label)
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ColorDialog.dp(this@MainActivity, 12) })
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_now)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelDownload = true }
            .show()

        val ctx = applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Updater.download(ctx, r, { cancelDownload }) { done, total ->
                        bar.post {
                            if (total > 0) {
                                bar.isIndeterminate = false
                                bar.max = 1000
                                bar.progress = (done * 1000 / total).toInt()
                            }
                            label.text = getString(R.string.update_downloading) + "  " + Updater.sizeText(done) +
                                if (total > 0) " / " + Updater.sizeText(total) else ""
                        }
                    }
                }
            }
            dialog.dismiss()
            result.onSuccess { apk -> install(apk) }.onFailure { e ->
                if (!cancelDownload) showError(e.message ?: e.toString())
            }
        }
    }

    private fun install(apk: File) {
        pendingApk = apk
        if (!Updater.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    unknownSources.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        try {
            startActivity(Updater.installIntent(this, apk))
            pendingApk = null
        } catch (e: ActivityNotFoundException) {
            showError("no se encontró el instalador de Android")
        } catch (e: IOException) {
            showError(e.message ?: e.toString())
        }
    }
}
