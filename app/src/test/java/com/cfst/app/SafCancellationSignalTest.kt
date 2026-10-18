package com.cfst.app

import android.app.Application
import android.content.*
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.*
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.documents.DocumentOperationOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SafCancellationSignalTest {
    @Test fun actualProviderOpenReceivesCancellationSignalAndDescriptorIsClosed() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val source=File(app.filesDir,"provider-source").apply {writeText("192.0.2.1")}
        var signal:CancellationSignal?=null; var descriptor:ParcelFileDescriptor?=null
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="text/plain"
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
            override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
            override fun openTypedAssetFile(uri:Uri,mimeTypeFilter:String,opts:Bundle?,cancellationSignal:CancellationSignal?):AssetFileDescriptor = openAssetFile(uri,"r",cancellationSignal)
            override fun openAssetFile(uri:Uri,mode:String):AssetFileDescriptor = openAssetFile(uri,mode,null)
            override fun openAssetFile(uri:Uri,mode:String,cancellationSignal:CancellationSignal?):AssetFileDescriptor {
                signal=cancellationSignal
                descriptor=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)
                return AssetFileDescriptor(descriptor!!,0,source.length())
            }
        }
        provider.attachInfo(app,android.content.pm.ProviderInfo().apply {authority="signal-provider"; exported=true})
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("signal-provider",provider)
        val owner=DocumentOperationOwner.get(app); val id=owner.picker(0)!!
        owner.read(id,Uri.parse("content://signal-provider/list"))
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(owner.state.value?.stage==DocumentOperationOwner.Stage.READING && System.nanoTime()<deadline) Thread.sleep(10)
        assertEquals(owner.state.value!!.message,DocumentOperationOwner.Stage.PREVIEW,owner.state.value!!.stage)
        assertNotNull("real provider must receive cancellation signal",signal)
        assertEquals("192.0.2.1",owner.preview(id)!!.toString(Charsets.UTF_8))
        try { descriptor!!.fd; fail("descriptor leaked") } catch(_:IllegalStateException) {}
    }
}
