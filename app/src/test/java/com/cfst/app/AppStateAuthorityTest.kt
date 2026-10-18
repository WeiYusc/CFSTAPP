package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.AppStateStore
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AppStateAuthorityTest {
    @Test fun realViewModelsShareOneAppScopedAuthoritativeStore() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val a=SpeedTestViewModel(app); val b=SpeedTestViewModel(app)
        val f=a.javaClass.getDeclaredField("settings").apply {isAccessible=true}
        assertSame(f.get(a),f.get(b))
        a.updateTestCount(77)
        assertEquals(77,(f.get(b) as AppStateStore).getInt("testCount",0))
    }
}
