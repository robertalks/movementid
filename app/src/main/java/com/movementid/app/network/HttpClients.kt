package com.movementid.app.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single shared OkHttpClient for all AI API calls. Re-using one client reuses the connection
 * pool and thread pool across models and retries; creating one per request throws both away.
 *
 * WebDavClient keeps its own client because its timeouts are very different (120s writes for
 * large archive uploads vs 60s calls here), and mixing them in one client would mean the
 * longer timeout applies to every AI request too.
 */
internal val sharedHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .build()
