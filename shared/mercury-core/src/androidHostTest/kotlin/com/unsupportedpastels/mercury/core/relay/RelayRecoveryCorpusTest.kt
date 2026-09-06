package com.unsupportedpastels.mercury.core.relay

import kotlin.test.Test

class RelayRecoveryCorpusTest {
    @Test fun canonicalCorpus() {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("adapter-parity/relay-recovery.json"))
        RelayRecoveryCorpus.verify(stream.bufferedReader().use { it.readText() })
    }
}
