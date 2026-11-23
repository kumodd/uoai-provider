package com.uoai.sdk.exceptions

open class UniversalAIException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The provider app is not installed. */
class ProviderNotInstalledException(message: String) : UniversalAIException(message)
/** The provider app is installed but its API server is not running or unreachable. */
class ProviderNotRunningException(message: String, cause: Throwable? = null) : UniversalAIException(message, cause)
/** This app has no credential yet (call registrationIntent()) or the credential was revoked. */
class NotRegisteredException(message: String) : UniversalAIException(message)
class ModelNotReadyException(message: String) : UniversalAIException(message)
class RequestCancelledException(message: String) : UniversalAIException(message)
class PermissionDeniedException(message: String) : UniversalAIException(message)
/** Any other structured error returned by the provider. */
class ApiException(val httpStatus: Int, val code: String, message: String) : UniversalAIException(message)
