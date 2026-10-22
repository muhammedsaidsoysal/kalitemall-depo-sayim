package com.kalitemall.deposayim

import java.util.Locale
import kotlin.math.abs

/** Pure Kotlin: independently testable without camera/Android SDK. */
data class OcrLine(val text: String, val confidence: Float, val centerX: Float, val centerY: Float, val group: Int)
data class ScanCandidate(val sku: String, val size: String, val score: Float, val rawText: String)

object ScanRules {
    const val MIN_SCORE = 0.85f
    // Distinguish the KaliteMall SKU from PodyumPlus raw barcode (SM-98035).
    private val skuRe = Regex("(?<![A-Z0-9])([0-9]{3})\\s*[-–—]\\s*([0-9]{4})\\s*[-–—]\\s*([RKB])\\s*([0-9]{2})(?![A-Z0-9])")
    private val sizeRe = Regex("(?<![A-Z0-9])(STANDART|XXXL|XXL|LXL|STD|SM|4XL|3XL|XS|XL|XXL|S|M|L|(?:3[4-9]|4[0-9]|5[0-9]|60))(?![A-Z0-9])")

    fun normalize(raw: String) = raw.uppercase(Locale.ROOT).replace('İ','I').replace('ı','I')

    fun parse(lines: List<OcrLine>): ScanCandidate? {
        val inFrame = lines.filter { it.centerX in 0.08f..0.92f && it.centerY in 0.12f..0.88f }
        val codes = inFrame.flatMap { line ->
            skuRe.findAll(normalize(line.text)).map { match ->
                Triple("${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}${match.groupValues[4]}", line, match.value)
            }.toList()
        }
        // More than one identifiable SKU in frame: do not guess.
        if (codes.map { it.first }.distinct().size != 1 || codes.isEmpty()) return null
        val chosen = codes.maxByOrNull { it.second.confidence }!!
        val skuLine = chosen.second
        val sizes = inFrame.flatMap { line ->
            sizeRe.findAll(normalize(line.text)).map { m ->
                val size = if (m.value == "STANDART") "STD" else m.value
                Triple(size, line, m.value)
            }.toList()
        }.filter { (size, line, _) ->
            // Prefer the same label block. If the OCR split the label into blocks,
            // require spatial proximity and do not combine distant labels.
            (line.group == skuLine.group || abs(line.centerY - skuLine.centerY) < 0.30f) &&
                !(size == "S" && line.text.trim().length > 2)
        }
        val distinctSizes = sizes.map { it.first }.distinct()
        if (distinctSizes.size != 1) return null
        val sizeLine = sizes.maxByOrNull { it.second.confidence }!!.second
        val score = minOf(skuLine.confidence, sizeLine.confidence)
        if (score !in 0f..1f) return null
        return ScanCandidate(chosen.first, distinctSizes.single(), score, inFrame.joinToString(" | ") { it.text }.take(800))
    }
}

/** Two matching frames before save. Keeps same visible physical label locked. */
class AutoCounter(private val threshold: Float = ScanRules.MIN_SCORE, private val clearAfterMs: Long = 600L) {
    private var proposed: String? = null
    private var matches = 0
    private var locked: String? = null
    private var blankSince = -1L

    /** isBlankScene means NO text in the central guide, not merely failed code parsing. */
    fun observe(candidate: ScanCandidate?, now: Long, isBlankScene: Boolean = false): ScanCandidate? {
        val key = candidate?.let { "${it.sku}|${it.size}" }
        if (locked != null && key == locked) {
            blankSince = -1L
            return null
        }
        if (locked != null) {
            if (key != null && key != locked) { // visibly different SKU/size
                locked = null
                blankSince = -1L
            } else if (key == null && isBlankScene) {
                if (blankSince < 0L) blankSince = now
                if (now - blankSince >= clearAfterMs) { locked = null; blankSince = -1L }
            } else blankSince = -1L // still other text in view; don't rearm by accident
        }
        if (locked != null) return null
        if (candidate == null || candidate.score < threshold) {
            proposed = null; matches = 0
            return null
        }
        if (proposed == key) matches++ else { proposed = key; matches = 1 }
        if (matches < 2) return null
        proposed = null; matches = 0
        locked = key; blankSince = -1L
        return candidate
    }
    fun reset() { proposed = null; matches = 0; locked = null; blankSince = -1L }
}
