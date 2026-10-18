package com.cfst.app
import android.app.Activity
import android.content.Intent
import android.os.Looper
import com.cfst.app.documents.DocumentOperationOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class P01ClosureLateCallbackTest {
 private fun await(c:()->Boolean) { val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(!c() && System.nanoTime()<end) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }; assertTrue(c()) }
 @Test fun oldPickerCallbackCannotCancelOrWriteNewExportOperation() {
  val c=Robolectric.buildActivity(MainActivity::class.java).setup().visible(); val a=c.get(); val owner=DocumentOperationOwner.get(a.application)
  a.requestCsvExport(); await { Shadows.shadowOf(a).peekNextStartedActivityForResult()!=null }; val old=Shadows.shadowOf(a).nextStartedActivityForResult
  owner.cancelCurrent(); await { owner.state.value?.stage==DocumentOperationOwner.Stage.CANCELLED }; Shadows.shadowOf(Looper.getMainLooper()).idle()
  a.requestCsvExport(); await { Shadows.shadowOf(a).peekNextStartedActivityForResult()!=null }; val fresh=Shadows.shadowOf(a).nextStartedActivityForResult; val id=owner.state.value!!.id
  a.activityResultRegistry.dispatchResult(old.requestCode,Activity.RESULT_CANCELED,null)
  assertEquals(id,owner.state.value!!.id); assertEquals("late callback must be keyed to original op",DocumentOperationOwner.Stage.PICKER,owner.state.value!!.stage)
  val uri=android.net.Uri.parse("content://test/new-export"); val out=java.io.ByteArrayOutputStream(); Shadows.shadowOf(a.contentResolver).registerOutputStream(uri,out)
  a.activityResultRegistry.dispatchResult(fresh.requestCode,Activity.RESULT_OK,Intent().setData(uri)); await { owner.state.value?.stage==DocumentOperationOwner.Stage.DONE }; assertTrue(out.size()>0); c.pause().stop().destroy()
 }
}
