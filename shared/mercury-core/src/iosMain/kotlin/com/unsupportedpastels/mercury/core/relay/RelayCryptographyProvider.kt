@file:OptIn(dev.whyoleg.cryptography.CryptographyProviderApi::class)

package com.unsupportedpastels.mercury.core.relay

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

actual val relayCryptographyProvider: CryptographyProvider =
    CryptographyProvider.CryptoKit
