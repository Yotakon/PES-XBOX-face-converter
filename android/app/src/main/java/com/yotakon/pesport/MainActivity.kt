package com.yotakon.pesport

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Pantalla principal con tres pestañas:
 * 1. Convertir: FACES y HAIRS de PES 2010 PS2 a PES 5 Xbox.
 * 2. Importar 0_TEXT: buscar FACES y HAIRS en el 0_TEXT.AFS de PES 2010 y extraerlos.
 * 3. AFS: extraer un AFS a una carpeta y crear un AFS para PES 5 a partir de una carpeta.
 */
class MainActivity : AppCompatActivity() {
    private class Tab(val title: Int, val create: () -> Fragment)

    private val tabs = listOf(
        Tab(R.string.tab_convert) { ConvertFragment() },
        Tab(R.string.tab_import) { FacesFragment() },
        Tab(R.string.tab_afs_tools) { AfsFragment() },
    )

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
    }
}
