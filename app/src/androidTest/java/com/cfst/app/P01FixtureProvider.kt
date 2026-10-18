package com.cfst.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Real test-APK provider; production SAF owner opens its descriptor normally. */
class P01FixtureProvider:ContentProvider() {
    override fun onCreate()=true
    override fun getType(uri:Uri)="text/plain"
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        require(mode=="r")
        val source=File(context!!.filesDir,"p01-fixture-list.txt")
        source.writeText("192.0.2.17\n2001:db8::17")
        return ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=0
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=0
}
