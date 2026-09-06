package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia

/** Main-thread presentation owner, including results arriving after disposal. */
internal class ManagedVideoPresentationLease : AutoCloseable {
    private var current: ManagedVideoMedia? = null
    private var disposed = false

    fun adopt(media: ManagedVideoMedia): Boolean {
        if (disposed) {
            media.close()
            return false
        }
        if (current !== media) current?.close()
        current = media
        return true
    }

    override fun close() {
        disposed = true
        current?.close()
        current = null
    }
}
