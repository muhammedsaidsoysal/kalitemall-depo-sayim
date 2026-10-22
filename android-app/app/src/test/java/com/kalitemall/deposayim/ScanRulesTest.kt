package com.kalitemall.deposayim

import org.junit.Assert.*
import org.junit.Test

class ScanRulesTest {
    private fun lines(code:String="346-3054-R18",size:String="SM",score:Float=.94f)=listOf(
        OcrLine(code,score,.5f,.44f,0),OcrLine("Füme",.93f,.5f,.50f,0),OcrLine(size,score,.5f,.56f,0)
    )
    @Test fun readsSkuSizeWithoutColor() {
        assertEquals(ScanCandidate("346-3054-R18","SM",.94f, "346-3054-R18 | Füme | SM"),ScanRules.parse(lines()))
    }
    @Test fun alsoHandlesStd() {
        val r=ScanRules.parse(lines("357-6040-R01","STD"))!!
        assertEquals("357-6040-R01",r.sku)
        assertEquals("STD",r.size)
    }
    @Test fun rawSupplierBarcodeIsNotAProductSku() { assertNull(ScanRules.parse(listOf(OcrLine("SM-98035",.99f,.5f,.5f,0)))) }
    @Test fun mixedLabelsRejected() { assertNull(ScanRules.parse(lines()+OcrLine("197-1251-R01",.99f,.5f,.7f,1))) }
    @Test fun lowConfidenceNeverCounted() {
        val c=AutoCounter()
        assertNull(c.observe(ScanRules.parse(lines(score=.70f)),0))
        assertNull(c.observe(ScanRules.parse(lines(score=.70f)),100))
    }
    @Test fun requiresTwoFramesAndLocksSameLabel() {
        val c=AutoCounter()
        val good=ScanRules.parse(lines())!!
        assertNull(c.observe(good,100))
        assertNotNull(c.observe(good,200))
        assertNull(c.observe(good,300))
        assertNull(c.observe(null,400,true))
        assertNull(c.observe(null,1100,true))
        assertNull(c.observe(good,1200))
        assertNotNull(c.observe(good,1300))
    }
}
