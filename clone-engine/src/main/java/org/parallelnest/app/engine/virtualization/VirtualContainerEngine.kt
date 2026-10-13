package org.parallelnest.app.engine.virtualization

/** No guest-hosting engine has passed the legal, hardware and isolation gates. */
object VirtualContainerEngine {
    const val verified=false
    const val reason="Unavailable: no virtual engine has passed licensing, Android 13–16 compatibility, hardware and isolation verification. Work profiles support one additional copy per app."
}
