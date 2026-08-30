package com.unsupportedpastels.mercury.core.origin

import platform.Foundation.NSURL

internal actual fun platformIdnToAscii(host: String): String? =
    NSURL(string = "https://$host").host
