package com.yotakon.pesport.core

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Reparte tareas entre varios hilos. Sirve para operaciones que casi todo el tiempo esperan al sistema. */
object Parallel {
    /** Hilos para el acceso a carpetas de Android (SAF): esperan al sistema, no a la CPU. */
    val IO_THREADS: Int = (Runtime.getRuntime().availableProcessors() + 2).coerceIn(4, 8)

    /**
     * Ejecuta [task] para cada elemento de [items] con hasta [threads] hilos. Al primer error deja
     * de empezar tareas nuevas, espera a las que están en marcha y lanza ese error.
     */
    fun forEach(items: List<Int>, threads: Int = IO_THREADS, task: (Int) -> Unit) {
        if (items.isEmpty()) return
        val workers = minOf(threads, items.size)
        if (workers <= 1) {
            items.forEach(task)
            return
        }
        val next = AtomicInteger(0)
        val error = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val jobs = (0 until workers).map {
                pool.submit {
                    while (error.get() == null) {
                        val k = next.getAndIncrement()
                        if (k >= items.size) break
                        try {
                            task(items[k])
                        } catch (t: Throwable) {
                            error.compareAndSet(null, t)
                        }
                    }
                }
            }
            jobs.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        error.get()?.let { throw it }
    }
}
