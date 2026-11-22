package pt.tiago.zerodroid.ui.sop

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * One ViewModelStore per Workflows route entry, so an editor's or run's ViewModel lives exactly as
 * long as its entry is on the stack. Without this every ViewModel lands in the Activity's store and
 * outlives the screen: discarded drafts come back and "New workflow" reopens the last one created.
 */
class RouteStores {
    private val stores = HashMap<String, ViewModelStore>()

    fun ownerFor(key: String): ViewModelStoreOwner {
        val store = stores.getOrPut(key) { ViewModelStore() }
        return object : ViewModelStoreOwner { override val viewModelStore = store }
    }

    /** Clears (and forgets) every store whose key is not in [keys]. */
    fun retain(keys: Set<String>) {
        val drop = stores.keys - keys
        drop.forEach { stores.remove(it)?.clear() }
    }

    fun clearAll() = retain(emptySet())
}
