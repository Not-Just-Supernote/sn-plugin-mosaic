package me.laumss.mosaic

import java.io.FileDescriptor


internal object EvdevClock {
    
    private const val EVIOCSCLOCKID = 0x400445a0
    private const val CLOCK_MONOTONIC = 1

    
    
    private val intRefClass by lazy { Class.forName("android.system.Int32Ref") }
    private val ioctlInt by lazy {
        Class.forName("android.system.Os").getMethod(
            "ioctlInt", FileDescriptor::class.java, Int::class.javaPrimitiveType, intRefClass,
        )
    }

    fun useMonotonic(fd: FileDescriptor) {
        val clock = intRefClass.getConstructor(Int::class.javaPrimitiveType).newInstance(CLOCK_MONOTONIC)
        check(ioctlInt.invoke(null, fd, EVIOCSCLOCKID, clock) == 0) {
            "EVIOCSCLOCKID(CLOCK_MONOTONIC) failed"
        }
    }
}
