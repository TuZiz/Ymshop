package ym.ymshop.service

import java.util.concurrent.ConcurrentHashMap

class TradeLockManager {
    private val locks = ConcurrentHashMap<String, Any>()

    fun <T> withEntryLock(shopId: String, entryId: String, block: () -> T): T {
        val key = "${shopId.lowercase()}:${entryId.lowercase()}"
        val lock = locks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            block()
        }
    }
}
