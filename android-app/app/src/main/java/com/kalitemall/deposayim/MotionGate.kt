package com.kalitemall.deposayim

/** Skip only strongly blurred/rapid-motion frames; do not penalize minor hand tremor. */
class MotionGate(private val pauseMs: Long = 150L) {
    @Volatile private var movingUntil = -1L
    private var accelerationMagnitude = -1f
    fun onGyro(x: Float, y: Float, z: Float, nowMs: Long) {
        val rateSquared=x*x+y*y+z*z
        if(rateSquared > 2.8f*2.8f) movingUntil=nowMs+pauseMs
    }
    fun onAccelerometer(x: Float, y: Float, z: Float, nowMs: Long) {
        val magnitude=kotlin.math.sqrt(x*x+y*y+z*z)
        val prev=accelerationMagnitude
        accelerationMagnitude= if(prev<0f) magnitude else prev*0.5f + magnitude*0.5f
        if(prev>=0f && kotlin.math.abs(magnitude-prev)>5f)
            movingUntil=nowMs+pauseMs
    }
    fun skip(nowMs: Long):Boolean = nowMs < movingUntil
    fun reset() { movingUntil=-1;accelerationMagnitude=-1f }
}
