package com.unsupportedpastels.mercury.core.origin

import java.net.IDN

internal actual fun platformIdnToAscii(host: String): String? = runCatching {
    IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
}.getOrNull()
