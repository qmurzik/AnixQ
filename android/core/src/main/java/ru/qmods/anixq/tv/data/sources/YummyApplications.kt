package ru.qmods.anixq.tv.data.sources

/** Durable ID receipts let a failed detail read resume without another POST /apps. */
interface YummyApplicationReceipts {
    fun get(ownerId: Int): Int?
    fun save(ownerId: Int, applicationId: Int)
}

data class YummyConnection(val publicToken: String, val managedOwnerId: Int?)
