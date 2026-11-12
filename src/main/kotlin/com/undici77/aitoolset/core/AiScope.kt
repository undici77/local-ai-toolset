package com.undici77.aitoolset.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

@Service(Service.Level.PROJECT)
class AiScope(val project: Project, val cs: CoroutineScope) {
    companion object {
        fun get(p: Project): AiScope = p.service()
    }
}
