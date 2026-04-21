package com.itsaky.androidide.lsp.models

/** Client capability for workspace/executeCommand. */
data class ExecuteCommandClientCapabilities(
    var dynamicRegistration: Boolean = false,
)

/** Server capability for workspace/executeCommand. */
data class ExecuteCommandOptions(
    var commands: List<String> = emptyList(),
    var workDoneProgress: Boolean = false,
)

/** Registration options for dynamic workspace/executeCommand registration. */
data class ExecuteCommandRegistrationOptions(
    var commands: List<String> = emptyList(),
    var workDoneProgress: Boolean = false,
)

/** Request params for workspace/executeCommand. */
data class ExecuteCommandParams(
    var command: String,
    var arguments: List<Any?>? = null,
)

/** Response wrapper for workspace/executeCommand. */
data class ExecuteCommandResult(
    var result: Any? = null,
)
