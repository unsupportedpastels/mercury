package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.mercury.core.voice.SpeechTextPolicy

/** Speech sanitisation is decided in the shared core; this keeps the Android call sites. */
fun sanitizeTextForSpeech(text: String): String = SpeechTextPolicy.sanitize(text)
