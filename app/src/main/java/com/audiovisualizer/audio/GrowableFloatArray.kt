package com.audiovisualizer.audio

/** Minimal append-only float buffer that doubles capacity, avoiding boxed Float allocations. */
class GrowableFloatArray(initialCapacity: Int = 1 shl 16) {

    private var array = FloatArray(initialCapacity)
    var size: Int = 0
        private set

    fun append(value: Float) {
        ensureCapacity(size + 1)
        array[size++] = value
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= array.size) return
        var newCapacity = array.size shl 1
        while (newCapacity < minCapacity) newCapacity = newCapacity shl 1
        array = array.copyOf(newCapacity)
    }

    fun toFloatArray(): FloatArray = array.copyOf(size)
}
