package com.cfst.app.ui.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.cfst.app.viewmodel.SpeedTestViewModel

/** UI destination owner; configuration remains owned by the real VM. */
class NavigationSession(private val vm:SpeedTestViewModel,initialPage:String) {
    var page by mutableStateOf(initialPage); private set
    var pendingPage by mutableStateOf<String?>(null); private set
    fun navigate(destination:String) {
        if(destination==page) return
        if(vm.draftPending.value) pendingPage=destination else page=destination
    }
    fun resumeEditor() { page="设置";pendingPage=null }
    fun stay() { pendingPage=null }
    fun discardAndLeave() { vm.cancelDraft(); pendingPage?.let { page=it }; pendingPage=null }
    fun saveAndLeave():Boolean {
        if(!vm.saveDraft(vm.beginDraft())) return false
        pendingPage?.let { page=it }; pendingPage=null; return true
    }
}
