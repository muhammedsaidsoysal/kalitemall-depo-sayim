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


    @Test fun momentaryBlurBetweenGoodFramesKeepsProposal() {
        val c=AutoCounter()
        val good=ScanRules.parse(lines())!!
        assertNull(c.observe(good,0))
        assertNull(c.observe(null,240,false))
        assertNotNull(c.observe(good,640))
        assertNull(c.observe(good,700)) // Already counted: label stays locked
    }
    @Test fun longBlurCannotJoinStaleReadings() {
        val c=AutoCounter()
        val good=ScanRules.parse(lines())!!
        assertNull(c.observe(good,0))
        assertNull(c.observe(null,1550,false))
        assertNull(c.observe(good,1700))
        assertNotNull(c.observe(good,1790))
    }
    @Test fun contradictoryReadingResetsProposal() {
        val c=AutoCounter()
        val a=ScanRules.parse(lines())!!
        val b=ScanRules.parse(lines("317-2053-R56","40"))!!
        assertNull(c.observe(a,0))
        assertNull(c.observe(b,200))
        assertNull(c.observe(null,300,false))
        assertNotNull(c.observe(b,410))
    }
    @Test fun shakeHoldDisplaysLastCodeButNeverCreatesCount() {
        val h=ReadingHold(1300)
        assertEquals("317-2053-R56" to "40",h.update(0,"317-2053-R56","40"))
        assertEquals("317-2053-R56" to "40",h.update(600,null,null))
        assertEquals(null to null,h.update(1400,null,null))
        assertEquals("346-3054-R18" to null,h.update(1500,"346-3054-R18",null))
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

    @Test fun partialSkuReadDoesNotNeedProductDescriptionOrSize() {
        assertEquals("317-2053-R56", ScanRules.codeFromRegion(listOf(
            OcrLine("317-2053-R56",.72f,.48f,.45f,0))))
        assertNull(ScanRules.parseDual(listOf(OcrLine("317-2053-R56",.72f,.48f,.45f,0)),emptyList()))
    }
    @Test fun splitSkuFragmentsOnSameLine() {
        assertEquals("317-2053-R56",ScanRules.codeFromRegion(listOf(
            OcrLine("317-2053-",.7f,.37f,.42f,0),
            OcrLine("R56",.7f,.56f,.42f,0))))
    }
    @Test fun doesNotGuessFromMultipleDifferentCodes() {
        assertNull(ScanRules.codeFromRegion(listOf(
            OcrLine("317-2053-R56",.70f,.4f,.42f,0),
            OcrLine("346-3054-R18",.75f,.4f,.50f,0))))
    }

    @Test fun fiveDigitMiddleIsAccepted() {
        val code=listOf(OcrLine("111-50660-R43",.74f,.5f,.5f,0))
        assertEquals("111-50660-R43", ScanRules.codeFromRegion(code))
        assertEquals("111-50660-R43", ScanRules.parseDual(code,listOf(OcrLine("36",.8f,.5f,.5f,1)))!!.sku)
    }
    @Test fun trailingOcrDigitIsSuggestionOnly() {
        val noisy=listOf(OcrLine("111-50660-R431",.74f,.5f,.5f,0))
        assertNull(ScanRules.codeFromRegion(noisy))
        assertNull(ScanRules.parseDual(noisy,listOf(OcrLine("36",.9f,.5f,.5f,1))))
        assertEquals("111-50660-R43", ScanRules.provisionalCodeFromRegion(noisy))
    }
    @Test fun sixDigitMiddleAlsoCounts() {
        assertEquals("111-506600-R43",ScanRules.codeFromRegion(listOf(OcrLine("111-506600-R43",.9f,.5f,.5f,0))))
    }
    @Test fun variableLengthMiddleButExactlyTwoSuffixDigits() {
        for (mid in listOf("7","89","123","2053","50660","123456","1234567890")) {
            assertEquals("111-$mid-R43",ScanRules.codeFromRegion(listOf(OcrLine("111-$mid-R43",.9f,.5f,.5f,0))))
        }
        assertNull(ScanRules.codeFromRegion(listOf(OcrLine("111-50660-R431",.9f,.5f,.5f,0))))
        assertNull(ScanRules.codeFromRegion(listOf(OcrLine("111-50660-R4",.9f,.5f,.5f,0))))
    }
    @Test fun transientWrongLabelDoesNotRecountLockedOldLabel() {
        val c=AutoCounter()
        val a=ScanRules.parse(lines())!!
        val b=ScanRules.parse(lines("317-2053-R56","40"))!!
        assertNull(c.observe(a,100))
        assertNotNull(c.observe(a,200))
        assertNull(c.observe(b,300))
        assertNull(c.observe(a,400)) // one bad frame cannot unlock
        assertNull(c.observe(b,500))
        assertNotNull(c.observe(b,600)) // second same new code: count immediately
        assertNull(c.observe(b,700))
    }
    @Test fun rapidSameSkuRequiresBlankFrameToRearm() {
        val c=AutoCounter(clearAfterMs=450L)
        val a=ScanRules.parse(lines())!!
        assertNull(c.observe(a,100))
        assertNotNull(c.observe(a,200))
        for(i in 1..10) assertNull(c.observe(a,200+i*100L))
        assertNull(c.observe(null,1300L,true))
        assertNull(c.observe(null,1800L,true))
        assertNull(c.observe(a,1900L))
        assertNotNull(c.observe(a,2000L))
    }

    @Test fun middleWithLettersAndHyphensAcceptsActualProducts() {
        val examples = listOf("186-033Y-R15", "197-7920A-R01", "302-ES-030-R01", "111-50660-R43", "317-2053-R56")
        for(sku in examples) {
            val lines = listOf(OcrLine(sku, .9f, .5f, .5f, 0))
            assertEquals(sku, ScanRules.codeFromRegion(lines))
            assertEquals(sku, ScanRules.parseDual(lines, listOf(OcrLine("28", .9f, .5f, .5f, 1)))!!.sku)
        }
    }
    @Test fun suffixMustStillBeExactlyTwoDigits() {
        for(sku in listOf("186-033Y-R151", "186-033Y-R1", "186-033Y-R15A", "186-033Y-R15-XX")) {
            assertNull(ScanRules.codeFromRegion(listOf(OcrLine(sku, .9f, .5f, .5f, 0))))
        }
    }
    @Test fun provisionalCodeWithLettersNeverCountsAutomatically() {
        val noisy = listOf(OcrLine("186-033Y-R151", .9f, .5f, .5f, 0))
        assertNull(ScanRules.codeFromRegion(noisy))
        assertEquals("186-033Y-R15", ScanRules.provisionalCodeFromRegion(noisy))
        assertNull(ScanRules.parseDual(noisy, listOf(OcrLine("28", .9f, .5f, .5f, 1))))
    }
    @Test fun jeansWaistSizesAreRecognizedByCameraOcr() {
        val code = listOf(OcrLine("357-6040-R01", .94f, .5f, .5f, 0))
        for (waist in 24..35) {
            val size = listOf(OcrLine(waist.toString(), .92f, .5f, .5f, 1))
            assertEquals(waist.toString(), ScanRules.sizeFromRegion(size))
            assertEquals(waist.toString(), ScanRules.parseDual(code, size)?.size)
        }
    }
    @Test fun numericAndLetterSizesRemainSupported() {
        val allowed = listOf("36", "38", "40", "42", "44", "46", "48", "50", "52", "54", "56", "60", "STD", "SM", "ML", "LXL", "XS", "S", "M", "L", "XL", "XXL", "XXXL", "3XL", "4XL")
        for (size in allowed) {
            assertEquals(size, ScanRules.sizeFromRegion(listOf(OcrLine(size, .95f, .5f, .5f, 0))))
        }
        assertEquals("STD", ScanRules.sizeFromRegion(listOf(OcrLine("Standart", .95f, .5f, .5f, 0))))
    }
    @Test fun sizeNumberCannotBePartOfLongerNumberOrWord() {
        for (raw in listOf("128", "0280", "28A", "28/30", "24B")) {
            assertNull("Should reject $raw", ScanRules.sizeFromRegion(listOf(OcrLine(raw, .95f, .5f, .5f, 0))))
        }
    }

}
