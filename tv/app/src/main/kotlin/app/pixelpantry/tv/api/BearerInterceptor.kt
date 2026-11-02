package app.pixelpantry.tv.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the paired TV's token to requests aimed at the current server. [ApiClient] adds it to its own calls;
 * this covers everything else on the shared OkHttp client: Coil's images and ExoPlayer's stream and sidecars.
 * Requests to any other host (a candidate being probed, an absolute URL from elsewhere) never carry it.
 */
class BearerInterceptor(private val api: ApiClient) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = api.token
        val base = api.baseUrl
        val ours = token != null && base.isNotBlank() && request.url.toString().startsWith("$base/")
        if (!ours || request.header("Authorization") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}
