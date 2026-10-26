package app.pixelpantry.tv.api

/** status 0 means the server could not be reached at all. */
class ApiException(val status: Int, message: String) : Exception(message)
