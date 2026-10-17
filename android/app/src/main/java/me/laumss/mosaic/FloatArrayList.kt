package me.laumss.mosaic

/** Growable float buffer for pen samples, without boxing. */
class FloatArrayList(capacity: Int = 256) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Float = data[i]
    fun toArray(): FloatArray = data.copyOf(size)
}
