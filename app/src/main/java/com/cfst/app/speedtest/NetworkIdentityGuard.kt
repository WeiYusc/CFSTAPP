package com.cfst.app.speedtest

/** Default-network identity guard. Caller feeds Android callbacks and snapshots. */
class NetworkIdentityGuard(private val stop: (String) -> Unit) {
    private var current: String? = null
    private var captured: String? = null
    private var ownerId:String?=null
    private val lostNetworks = mutableSetOf<String>()
    @Synchronized fun update(identity: String?) {
        current = identity?.takeUnless { it.substringBefore(':') in lostNetworks }
        if (captured != null && captured != current) { captured = null; stop("network changed or lost") }
    }
    @Synchronized fun available(identity: String) { lostNetworks.remove(identity.substringBefore(':')); update(identity) }
    @Synchronized fun lost(identity: String) {
        val id = identity.substringBefore(':')
        lostNetworks.add(id)
        if (current?.substringBefore(':') == id) current = null
        if (captured?.substringBefore(':') == id) { captured = null; stop("network lost") }
    }
    @Synchronized fun begin(): Boolean { captured = current; return captured != null }
    @Synchronized fun end() { captured = null }
    @Synchronized fun begin(runId:String):Boolean { ownerId=runId; captured=current; return captured!=null }
    @Synchronized fun end(runId:String) { if(ownerId==runId) { captured=null; ownerId=null } }
}
