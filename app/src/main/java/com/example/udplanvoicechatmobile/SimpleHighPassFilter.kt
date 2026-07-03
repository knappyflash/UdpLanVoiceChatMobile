package com.example.udplanvoicechatmobile

class SimpleHighPassFilter(
    sampleRate: Float,
    cutoffHz: Float
) {
    private val rc = 1.0f / (2.0f * Math.PI.toFloat() * cutoffHz)
    private val dt = 1.0f / sampleRate
    private val alpha = rc / (rc + dt)

    private var previousInput = 0f
    private var previousOutput = 0f

    fun process(input: Float): Float {
        val output = alpha * (previousOutput + input - previousInput)

        previousInput = input
        previousOutput = output

        return output
    }
}