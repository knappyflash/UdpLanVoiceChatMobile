package com.example.udplanvoicechatmobile

class SimpleLowPassFilter(
    sampleRate: Float,
    cutoffHz: Float
) {
    private val rc = 1.0f / (2.0f * Math.PI.toFloat() * cutoffHz)
    private val dt = 1.0f / sampleRate
    private val alpha = dt / (rc + dt)

    private var previous = 0f

    fun process(input: Float): Float {
        previous += alpha * (input - previous)
        return previous
    }
}