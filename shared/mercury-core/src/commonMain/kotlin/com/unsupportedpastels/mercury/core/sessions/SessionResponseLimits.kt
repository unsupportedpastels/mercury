package com.unsupportedpastels.mercury.core.sessions

/** Endpoint-specific response bounds shared by both native clients. */
object SessionResponseLimits {
    /** Maximum UTF-8 body size accepted from `GET /api/profiles/sessions`. */
    const val SESSION_LIST_MAX_BYTES: Int = 5 * 1024 * 1024
}
