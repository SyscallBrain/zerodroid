package pt.tiago.zerodroid.ui.sop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class RouteStoresTest {
    class Probe : ViewModel() { var cleared = false; override fun onCleared() { cleared = true } }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = Probe() as T
    }

    private fun probe(stores: RouteStores, key: String) = ViewModelProvider.create(stores.ownerFor(key), factory)[Probe::class]

    @Test
    fun poppedRouteClearsItsViewModels() {
        val stores = RouteStores()
        val a = probe(stores, "1:N")
        assertSame(a, probe(stores, "1:N"))
        stores.retain(setOf("0:L"))
        assertTrue(a.cleared)
        val b = probe(stores, "1:N")
        assertNotSame(a, b)
        assertFalse(b.cleared)
    }
}
