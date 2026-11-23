package com.uoai.providerapi

import io.ktor.http.HttpStatusCode

class ApiException(val status: HttpStatusCode, val type: String, val code: String, message: String) : Exception(message)
