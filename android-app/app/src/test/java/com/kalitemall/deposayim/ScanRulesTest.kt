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
    @Test fun lowConfidenceMayCountAfterTwoMatchingReads() {
        val c=AutoCounter()
        val candidate=ScanRules.parse(lines(score=.70f))
        assertNull(c.observe(candidate,0))
        assertNotNull(c.observe(candidate,100))
        assertNull(c.observe(candidate,200))
    }
    @Test fun manualLockPreventsImmediateDoubleCount() {
        val c=AutoCounter()
        val candidate=ScanRules.parse(lines())!!
        c.lockCurrent(candidate.sku,candidate.size)
        assertNull(c.observe(candidate,10))
        assertNull(c.observe(null,20,true))
        assertNull(c.observe(null,800,true))
        assertNull(c.observe(candidate,810))
        assertNotNull(c.observe(candidate,820))
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

    @Test fun dualRegionsAcceptProductAndSizeWithoutColor() {
        val code=listOf(OcrLine("317-2053-R56",.72f,.5f,.5f,0))
        val size=listOf(OcrLine("40",.68f,.5f,.5f,1))
        val result=ScanRules.parseDual(code,size)!!
        assertEquals("317-2053-R56",result.sku)
        assertEquals("40",result.size)
        assertEquals(.68f,result.score,0.001f)
    }
    @Test fun productNameAndColorCannotSupplySizeFromCodeZone() {
        val code=listOf(OcrLine("317-2053-R56 40 Bej",.99f,.5f,.5f,0))
        assertNull(ScanRules.parseDual(code, emptyList()))
    }
    @Test fun mismatchedOrMissingRegionIsRejected() {
        assertNull(ScanRules.parseDual(
            listOf(OcrLine("SM-98035",.99f,.5f,.5f,0)),
            listOf(OcrLine("SM",.99f,.5f,.5f,1))))
        assertNull(ScanRules.parseDual(
            listOf(OcrLine("317-2053-R56",.99f,.5f,.5f,0), OcrLine("197-1251-R01",.99f,.5f,.5f,0)),
            listOf(OcrLine("40",.99f,.5f,.5f,1))))
    }
    @Test fun dualRegionRequiresTwoIdenticalFramesBeforeAutoCount() {
        val code=listOf(OcrLine("317-2053-R56",.73f,.5f,.5f,0))
        val size=listOf(OcrLine("40",.73f,.5f,.5f,1))
        val candidate=ScanRules.parseDual(code,size)!!
        val counter=AutoCounter()
        assertNull(counter.observe(candidate,0))
        assertNotNull(counter.observe(candidate,80))
        assertNull(counter.observe(candidate,150))
    }

}
