package com.unsupportedpastels.mercury.core.relay

/** Android compatibility name; primitives come from the shared provider-backed implementation. */
object AndroidRelayCrypto : RelayCrypto by RelayPlatformCrypto
