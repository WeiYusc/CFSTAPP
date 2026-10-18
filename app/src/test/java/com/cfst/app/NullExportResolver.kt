package com.cfst.app

/** Model the Android provider-crash contract (null stream), not Robolectric's fallback fake stream. */
@org.robolectric.annotation.Implements(android.content.ContentResolver::class)
class NullExportResolver {
    @org.robolectric.annotation.Implementation
    fun openOutputStream(uri: android.net.Uri): java.io.OutputStream? = null
}
