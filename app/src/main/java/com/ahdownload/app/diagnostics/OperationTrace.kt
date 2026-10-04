package com.ahdownload.app.diagnostics

import java.util.UUID

object OperationTrace {
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    fun event(operationId: String, name: String): String = "op=$operationId event=$name"
}
