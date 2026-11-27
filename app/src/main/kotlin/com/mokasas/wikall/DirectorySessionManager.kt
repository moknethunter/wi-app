package com.mokasas.wikall

/** Locally authenticated signaling realm and persistent missed-call inbox. */
internal class DirectorySessionManager(private val store: CallStore) {
    var account: String? = null
        private set

    fun attach(realm: String, number: String) { account = "$realm:$number" }
    fun detach() { account = null }
    fun missed(account: String): List<IncomingMissed> = store.missed(account)
}
