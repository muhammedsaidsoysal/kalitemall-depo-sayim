package com.kalitemall.deposayim

import java.util.Locale
import kotlin.math.abs

/** Pure Kotlin: independently testable without camera/Android SDK. */
data class OcrLine(val text: String, val confidence: Float, val centerX: Float, val centerY: Float, val group: Int)
data class ScanCandidate(val sku: String, val size: String, val score: Float, val rawText: String)

object ScanRules {
    const val MIN_SCORE = 0f // No OCR confidence cutoff; rely on consecutive matching reads.
    // Distinguish the KaliteMall SKU from PodyumPlus raw barcode (SM-98035).
    // Mirrors Apps Script SKU rules: 2–3 digits, one or more alphanumeric
    // middle segments (possibly with dashes), then R/K/B and exactly 2 digits.
    private val middlePart = "([A-Z0-9]+(?:\\s*[-–—]\\s*[A-Z0-9]+)*)"
    private val skuRe = Regex("(?<![A-Z0-9])([0-9]{2,3})\\s*[-–—]\\s*" + middlePart + "\\s*[-–—]\\s*([RKB])\\s*([0-9]{2})(?![A-Z0-9–—-])")
    private fun formattedSku(m: MatchResult): String {
        val middle = m.groupValues[2].replace(Regex("\\s*[-–—]\\s*"), "-")
        return "${m.groupValues[1]}-$middle-${m.groupValues[3]}${m.groupValues[4]}"
    }
    private val sizeRe = Regex("(?<![A-Z0-9])(STANDART|XXXL|XXL|LXL|4XL|3XL|STD|SM|ML|XS|XL|S|M|L|(?:2[4-9]|[3-5][0-9]|60))(?![A-Z0-9])")

    fun normalize(raw: String) = raw.uppercase(Locale.ROOT).replace('İ','I').replace('ı','I')

    /** Each region is isolated by bitmap cropping; product name is unnecessary. */
    fun codeFromRegion(lines: List<OcrLine>): String? {
        fun collect(text: String): List<String> = skuRe.findAll(normalize(text)).map { m ->
            formattedSku(m)
        }.toList()
        val direct = lines.flatMap { collect(it.text) }.distinct()
        if(direct.size > 1) return null // Never guess between two product codes.
        if(direct.size == 1) return direct.single()
        // ML Kit occasionally divides a printed code into adjacent fragments,
        // e.g. '317-2053-' and 'R56'. Combine ONLY fragments from a single
        // spatial text line; not separate rows or other labels.
        val spatialGroups = lines.groupBy { it.group }
        val stitched = spatialGroups.values.flatMap { groupLines ->
            groupLines.groupBy { (it.centerY * 18f).toInt() }.values.flatMap { row ->
                if(row.size !in 2..4) emptyList()
                else {
                    val sorted=row.sortedBy { it.centerX }
                    val horizontal=sorted.last().centerX-sorted.first().centerX
                    if(horizontal>0.60f) emptyList()
                    else collect(sorted.joinToString("") { it.text.replace(" ", "") })
                }
            }
        }.distinct()
        return stitched.singleOrNull()
    }

    /**
     * Display-only recovery for a common OCR edge artifact: R43 is read as R431.
     * This is NEVER accepted as an automatic count, because R431 might actually
     * be a different supplier code. The operator must verify the printed label.
     */
    fun provisionalCodeFromRegion(lines: List<OcrLine>): String? {
        val withOneTrailingDigit = Regex(
            "(?<![A-Z0-9])([0-9]{2,3})\\s*[-–—]\\s*" + middlePart + "\\s*[-–—]\\s*([RKB])\\s*([0-9]{2})[0-9](?![A-Z0-9–—-])"
        )
        val possibilities = lines.flatMap { line ->
            withOneTrailingDigit.findAll(normalize(line.text)).map { m ->
                formattedSku(m)
            }.toList()
        }.distinct()
        return possibilities.singleOrNull()
    }

    fun sizeFromRegion(lines: List<OcrLine>): String? {
        val found = lines.flatMap { line ->
            sizeRe.findAll(normalize(line.text)).map { m ->
                when(m.value) { "STANDART" -> "STD"; else -> m.value }
            }.toList()
        }.distinct()
        return found.singleOrNull()
    }

    fun parseDual(codeLines: List<OcrLine>, sizeLines: List<OcrLine>): ScanCandidate? {
        val sku = codeFromRegion(codeLines) ?: return null
        val size = sizeFromRegion(sizeLines) ?: return null
        val confidence = (codeLines + sizeLines).map { it.confidence }
            .filter { it in 0f..1f }.minOrNull() ?: 0f
        return ScanCandidate(sku, size, confidence,
            ("KOD: " + codeLines.joinToString(" ") { it.text } + " | BEDEN: " +
             sizeLines.joinToString(" ") { it.text }).take(800))
    }

    fun parse(lines: List<OcrLine>): ScanCandidate? {
        val inFrame = lines.filter { it.centerX in 0.08f..0.92f && it.centerY in 0.12f..0.88f }
        val codes = inFrame.flatMap { line ->
            skuRe.findAll(normalize(line.text)).map { match ->
                Triple(formattedSku(match), line, match.value)
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

/**
 * Keep successfully decoded text visible during short focus/motion interruptions.
 * Display ONLY: Never combine cached partial OCR into an automatic scan candidate.
 */
class ReadingHold(private val holdMs: Long = 1300L) {
    private var code: String? = null
    private var size: String? = null
    private var codeAt = -1L
    private var sizeAt = -1L

    fun update(now: Long, newCode: String?, newSize: String?): Pair<String?, String?> {
        if(newCode != null) {
            if(newCode != code) {
                code = newCode
                codeAt = now
                // A different SKU must never inherit an old size as a "confirmed" pairing.
                size = null
                sizeAt = -1L
            } else codeAt = now
        }
        if(newSize != null) { size = newSize; sizeAt = now }
        if(now - codeAt > holdMs) code = null
        if(now - sizeAt > holdMs) size = null
        return code to size
    }
    fun reset() { code = null; size = null; codeAt = -1L; sizeAt = -1L }
}

/**
 * Two independent correct OCR observations within a short window (not necessarily
 * consecutive frames). Brief unreadable frames caused by movement are tolerated.
 * Different decoded keys abort the proposal. A counted label stays locked until
 * removed from view for the configured blank interval or another label appears.
 */
class AutoCounter(
    private val threshold: Float = ScanRules.MIN_SCORE,
    private val clearAfterMs: Long = 600L,
    private val matchWindowMs: Long = 1400L
) {
    private var proposed: String? = null
    private var matches = 0
    private var lastValidAt = -1L
    private var locked: String? = null
    private var blankSince = -1L
    private var blankObservations = 0
    private var differentKey: String? = null
    private var differentMatches = 0

    fun observe(candidate: ScanCandidate?, now: Long, isBlankScene: Boolean = false): ScanCandidate? {
        val key = candidate?.let { "${it.sku}|${it.size}" }
        if(locked != null && key == locked) {
            // Seeing the same physical label again never re-arms the counter.
            blankSince = -1L; blankObservations = 0
            differentKey = null; differentMatches = 0
            return null
        }
        if(locked != null) {
            if(key != null && key != locked) {
                // One random OCR hallucination must not unlock an already counted label.
                if(differentKey == key) differentMatches++
                else { differentKey = key; differentMatches = 1 }
                blankSince = -1L; blankObservations = 0
                if(differentMatches >= 2) {
                    // Two verified frames of a new SKU/size: count NOW,
                    // rather than unnecessarily waiting for a third OCR cycle.
                    locked = key
                    proposed = null; matches = 0; lastValidAt = -1L
                    differentKey = null; differentMatches = 0
                    return candidate
                }
            } else if(key == null && isBlankScene) {
                differentKey = null; differentMatches = 0
                if(blankSince < 0L) { blankSince = now; blankObservations = 1 }
                else blankObservations++
                if(blankObservations >= 2 && now - blankSince >= clearAfterMs) {
                    locked = null; blankSince = -1L; blankObservations = 0
                }
            } else {
                blankSince = -1L; blankObservations = 0
                differentKey = null; differentMatches = 0
            }
            if(locked != null) return null
        }
        if(proposed != null && lastValidAt >= 0L && now - lastValidAt > matchWindowMs) {
            proposed = null; matches = 0; lastValidAt = -1L
        }
        if(candidate == null) {
            // Do NOT discard the earlier valid match for a single blurred frame.
            return null
        }
        if(candidate.score < threshold) {
            proposed = null; matches = 0; lastValidAt = -1L
            return null
        }
        if(proposed == key) matches++ else { proposed = key; matches = 1 }
        lastValidAt = now
        if(matches < 2) return null
        proposed = null; matches = 0; lastValidAt = -1L
        locked = key; blankSince = -1L; blankObservations = 0
        differentKey = null; differentMatches = 0
        return candidate
    }
    fun lockCurrent(sku: String, size: String) {
        proposed = null; matches = 0; lastValidAt = -1L
        locked = "${sku}|${size}"; blankSince = -1L; blankObservations = 0
        differentKey = null; differentMatches = 0
    }
    fun reset() {
        proposed = null; matches = 0; lastValidAt = -1L
        locked = null; blankSince = -1L; blankObservations = 0
        differentKey = null; differentMatches = 0
    }
}
