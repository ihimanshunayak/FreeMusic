// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - shared HTTP client and User-Agent.
//
// One OkHttp instance backs every outbound call: InnerTubeX for YouTube Music,
// NewPipeExtractor for stream resolution, and artwork. Sharing the pool and the
// thread pool is what keeps a search that fires several thumbnail requests from
// opening several sets of sockets to the same host.

package com.ihimanshunayak.freemusic.desktop.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {

    /**
     * A desktop Chrome UA. YouTube treats an unknown or obviously scripted agent
     * differently from a browser, and InnerTubeX's WEB_REMIX client identity is
     * only consistent when the agent matches.
     */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** The single OkHttp engine shared by Ktor and by anything that needs raw access. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Ktor facade used by InnerTubeX and by the repository layer. */
    val ktor: HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = client }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 25_000
        }
        // Errors are inspected by callers; this keeps the response object
        // available so a 400 from YouTube can be logged with its body instead of
        // surfacing as an exception with no context.
        expectSuccess = false
    }

    fun close() {
        runCatching { ktor.close() }
        runCatching {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
