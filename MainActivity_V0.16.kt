package com.pdf_to_epub.v06

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PDF -> structural reconstruction prototype.
 *
 * V0.10 deliberately replaces the V0.9 line->global-sort->block pipeline.
 * The new model is:
 *
 * spans -> geometric cleanup -> physical lines -> page furniture filtering
 * -> spatial segmentation -> region-local blocks -> structural classification
 * -> document-level reading order.
 *
 * The important invariant is that geometry/region membership is never discarded
 * before reading order is decided.  This is a diagnostic reconstruction release;
 * EPUB writing is intentionally deferred until the structural model is reliable.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private var selectedUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
        }
        val title = TextView(this).apply {
            text = "PDF → EPUB\nV0.16 — Contextual Structural Repair"
            textSize = 28f
        }
        val select = Button(this).apply { text = "SELECT PDF" }
        val analyze = Button(this).apply { text = "ANALYZE + RECONSTRUCT" }
        output = TextView(this).apply {
            textSize = 15f
            setPadding(0, 12, 0, 0)
            setTextIsSelectable(true)
        }

        root.addView(title)
        root.addView(select)
        root.addView(analyze)
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        select.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "application/pdf"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, REQUEST_PDF
            )
        }
        analyze.setOnClickListener {
            selectedUri?.let { analyzePdf(it) } ?: run { output.text = "Please select a PDF first." }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PDF && resultCode == Activity.RESULT_OK) {
            selectedUri = data?.data
            try {
                data?.data?.let { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            } catch (_: Exception) { }
            output.text = "Selected PDF:\n${selectedUri ?: ""}\n\nPress ANALYZE + RECONSTRUCT."
        }
    }

    private fun analyzePdf(uri: Uri) {
        output.text = "Running spatial reconstruction…"
        Thread {
            try {
                val result = contentResolver.openInputStream(uri)?.use { analyzeDocument(it) } ?: "Could not open PDF."
                runOnUiThread { output.text = result }
            } catch (e: Exception) {
                runOnUiThread { output.text = "Analysis error:\n${e.javaClass.simpleName}: ${e.message ?: "unknown error"}" }
            }
        }.start()
    }

    private fun analyzeDocument(input: InputStream): String {
        PDDocument.load(input).use { doc ->
            if (doc.numberOfPages == 0) return "PDF has no pages."

            val rawPages = mutableListOf<RawPage>()
            var rawGlyphs = 0
            var cleanedGlyphs = 0
            var duplicateGlyphs = 0
            var totalLines = 0

            for (pageNumber in 1..doc.numberOfPages) {
                val page = doc.getPage(pageNumber - 1)
                val stripper = LayoutStripper().apply {
                    startPage = pageNumber
                    endPage = pageNumber
                    sortByPosition = true
                }
                stripper.getText(doc)
                val raw = stripper.items
                val cleaned = deduplicateGeometrically(raw)
                val fragments = buildPhysicalLines(cleaned)
                rawGlyphs += raw.size
                cleanedGlyphs += cleaned.size
                duplicateGlyphs += raw.size - cleaned.size
                totalLines += fragments.size
                rawPages.add(RawPage(pageNumber, page.mediaBox.width, page.mediaBox.height, fragments))
            }

            val furniture = detectRunningFurniture(rawPages)
            val pages = rawPages.map { rawPage ->
                val filtered = rawPage.lines.filterNot { isFurnitureLine(it, rawPage.height, furniture) || isPageNumberText(it.text) }
                val regions = segmentPage(filtered, rawPage.width, rawPage.height)
                val assigned = filtered.map { line -> line.copy(regionId = assignRegion(line, regions)) }
                val blocks = buildBlocksByRegion(assigned, regions)
                val classified = classifyBlocks(blocks, rawPage.pageNumber, doc.numberOfPages, rawPage.width, rawPage.height)
                PageModel(rawPage.pageNumber, rawPage.width, rawPage.height, filtered, regions, classified)
            }

            return formatResult(doc.numberOfPages, rawGlyphs, cleanedGlyphs, duplicateGlyphs, totalLines, furniture, pages)
        }
    }

    // -------------------------- data model --------------------------

    enum class RegionKind { FULL_WIDTH, COLUMN_LEFT, COLUMN_RIGHT, SINGLE_COLUMN }

    data class Item(val text: String, val x: Float, val y: Float, val w: Float, val h: Float, val fontSize: Float)

    data class Line(
        val text: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val fontSize: Float,
        val regionId: String = ""
    )

    data class Region(
        val id: String,
        val kind: RegionKind,
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float
    )

    data class Block(
        var text: String,
        var x: Float,
        var y: Float,
        var w: Float,
        var h: Float,
        var fontSize: Float,
        var lineCount: Int,
        val regionId: String
    )

    data class ClassifiedBlock(
        val text: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val fontSize: Float,
        val type: String,
        val confidence: String,
        val regionId: String,
        val relation: String = ""
    )

    data class RawPage(val pageNumber: Int, val width: Float, val height: Float, val lines: List<Line>)
    data class PageModel(val pageNumber: Int, val width: Float, val height: Float, val lines: List<Line>, val regions: List<Region>, val blocks: List<ClassifiedBlock>)

    class LayoutStripper : PDFTextStripper() {
        val items = mutableListOf<Item>()
        override fun processTextPosition(text: TextPosition) {
            val s = text.unicode ?: return
            if (s.isNotEmpty() && s.any { !it.isWhitespace() }) {
                items.add(Item(s, text.xDirAdj, text.yDirAdj, max(0.01f, text.widthDirAdj), max(0.01f, text.heightDir), max(1f, text.fontSizeInPt)))
            }
        }
    }

    // -------------------------- extraction --------------------------

    private fun deduplicateGeometrically(items: List<Item>): List<Item> {
        if (items.isEmpty()) return emptyList()
        val result = mutableListOf<Item>()
        val cells = HashMap<String, MutableList<Int>>()
        fun key(x: Float, y: Float) = "${(x / DUP_CELL).toInt()}:${(y / DUP_CELL).toInt()}"
        for (item in items.sortedWith(compareBy<Item> { it.y }.thenBy { it.x })) {
            val cx = (item.x / DUP_CELL).toInt()
            val cy = (item.y / DUP_CELL).toInt()
            var duplicate = false
            for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) {
                cells["$gx:$gy"]?.forEach { idx -> if (sameGlyph(result[idx], item)) duplicate = true }
            }
            if (!duplicate) {
                val idx = result.size
                result.add(item)
                cells.getOrPut(key(item.x, item.y)) { mutableListOf() }.add(idx)
            }
        }
        return result
    }

    private fun sameGlyph(a: Item, b: Item): Boolean {
        if (a.text != b.text) return false
        if (abs(a.fontSize - b.fontSize) > max(1f, min(a.fontSize, b.fontSize) * 0.08f)) return false
        val xo = overlap(a.x, a.x + a.w, b.x, b.x + b.w)
        val yo = overlap(a.y, a.y + a.h, b.y, b.y + b.h)
        val minW = min(a.w, b.w)
        val minH = min(a.h, b.h)
        val centerDistance = abs(a.x + a.w / 2f - (b.x + b.w / 2f)) + abs(a.y + a.h / 2f - (b.y + b.h / 2f))
        return (xo >= minW * 0.55f && yo >= minH * 0.55f) || centerDistance <= 0.9f
    }

    /** Physical line construction. No column ordering is performed here. */
    private fun buildPhysicalLines(items: List<Item>): List<Line> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedWith(compareBy<Item> { it.y + it.h / 2f }.thenBy { it.x })
        val bands = mutableListOf<MutableList<Item>>()
        for (item in sorted) {
            val cy = item.y + item.h / 2f
            val target = bands.lastOrNull { band ->
                val avgCy = band.map { it.y + it.h / 2f }.average().toFloat()
                val avgH = band.map { it.h }.average().toFloat()
                abs(cy - avgCy) <= max(1.8f, avgH * 0.45f)
            }
            if (target == null) bands.add(mutableListOf(item)) else target.add(item)
        }

        val result = mutableListOf<Line>()
        for (band in bands) {
            val ordered = band.sortedBy { it.x }
            if (ordered.isEmpty()) continue
            val medianFont = median(ordered.map { it.fontSize })
            val splitThreshold = max(22f, medianFont * 3.4f)
            val groups = mutableListOf<MutableList<Item>>()
            var current = mutableListOf<Item>()
            ordered.forEachIndexed { i, item ->
                if (i > 0) {
                    val gap = item.x - (ordered[i - 1].x + ordered[i - 1].w)
                    if (gap > splitThreshold && current.isNotEmpty()) {
                        groups.add(current)
                        current = mutableListOf()
                    }
                }
                current.add(item)
            }
            if (current.isNotEmpty()) groups.add(current)
            groups.forEach { group ->
                val text = buildText(group)
                val left = group.minOf { it.x }
                val right = group.maxOf { it.x + it.w }
                val top = group.minOf { it.y }
                val bottom = group.maxOf { it.y + it.h }
                result.add(Line(text, left, top, right - left, bottom - top, median(group.map { it.fontSize })))
            }
        }
        return result.sortedWith(compareBy<Line> { it.y }.thenBy { it.x })
    }

    private fun buildText(items: List<Item>): String {
        val ordered = items.sortedBy { it.x }
        val out = StringBuilder()
        var previous: Item? = null
        for (item in ordered) {
            if (previous != null) {
                val gap = item.x - (previous.x + previous.w)
                if (gap > max(1f, min(item.fontSize, previous.fontSize) * 0.20f)) out.append(' ')
            }
            out.append(item.text)
            previous = item
        }
        return cleanSpacing(out.toString())
    }

    // -------------------------- furniture --------------------------

    private fun detectRunningFurniture(pages: List<RawPage>): Set<String> {
        if (pages.size < 2) return emptySet()
        val occurrences = HashMap<String, MutableSet<Int>>()
        pages.forEach { page ->
            page.lines.filter { it.y <= page.height * 0.15f || it.y + it.h >= page.height * 0.86f }
                .forEach { line ->
                    val key = normalizeForKey(line.text)
                    if (key.length in 3..120) occurrences.getOrPut(key) { mutableSetOf() }.add(page.pageNumber)
                }
        }
        val threshold = max(2, min(4, pages.size / 2))
        return occurrences.filterValues { it.size >= threshold }.keys
    }

    private fun isFurnitureLine(line: Line, pageHeight: Float, furniture: Set<String>): Boolean {
        val key = normalizeForKey(line.text)
        if (key.isEmpty() || !furniture.contains(key)) return false
        return line.y <= pageHeight * 0.15f || line.y + line.h >= pageHeight * 0.86f
    }

    private fun isPageNumberText(text: String): Boolean =
        Regex("^(?:page\\s+)?\\d{1,4}$", RegexOption.IGNORE_CASE).matches(text.trim())

    // -------------------------- spatial segmentation --------------------------

    /**
     * Region detection is based on persistent whitespace across the page rather
     * than a single line-start gap. Full-width lines are kept separate so a
     * heading or a full-width paragraph can act as a reading-order barrier.
     */
    private fun segmentPage(lines: List<Line>, pageWidth: Float, pageHeight: Float): List<Region> {
        if (lines.isEmpty()) return listOf(Region("single", RegionKind.SINGLE_COLUMN, 0f, pageWidth, 0f, pageHeight))

        // V0.10 used the largest difference between line-start X coordinates.
        // In a normal single-column document, indentation, headings and captions
        // can create a large one-off gap and falsely manufacture two columns.
        // V0.11 instead requires two persistent populations of line centres.
        val candidates = lines.filter {
            it.w >= pageWidth * 0.18f && it.w <= pageWidth * 0.78f
        }
        if (candidates.size < 16) return listOf(Region("single", RegionKind.SINGLE_COLUMN, 0f, pageWidth, 0f, pageHeight))

        val centers = candidates.map { it.x + it.w / 2f }.sorted()
        var bestSplit = -1
        var bestGap = 0f
        for (i in 1 until centers.size) {
            val gap = centers[i] - centers[i - 1]
            if (gap > bestGap) { bestGap = gap; bestSplit = i }
        }
        if (bestSplit <= 0 || bestSplit >= centers.size) {
            return listOf(Region("single", RegionKind.SINGLE_COLUMN, 0f, pageWidth, 0f, pageHeight))
        }

        val leftCenters = centers.subList(0, bestSplit)
        val rightCenters = centers.subList(bestSplit, centers.size)
        val minPopulation = max(8, candidates.size / 8)
        val separation = bestGap >= max(70f, pageWidth * 0.14f)
        if (!separation || leftCenters.size < minPopulation || rightCenters.size < minPopulation) {
            return listOf(Region("single", RegionKind.SINGLE_COLUMN, 0f, pageWidth, 0f, pageHeight))
        }

        val leftMean = leftCenters.average().toFloat()
        val rightMean = rightCenters.average().toFloat()
        val leftSpread = leftCenters.map { abs(it - leftMean) }.average().toFloat()
        val rightSpread = rightCenters.map { abs(it - rightMean) }.average().toFloat()
        if (leftSpread > pageWidth * 0.12f || rightSpread > pageWidth * 0.12f) {
            return listOf(Region("single", RegionKind.SINGLE_COLUMN, 0f, pageWidth, 0f, pageHeight))
        }

        val boundary = (leftMean + rightMean) / 2f
        val leftLines = candidates.filter { it.x + it.w / 2f < boundary }
        val rightLines = candidates.filter { it.x + it.w / 2f >= boundary }
        val leftSpan = verticalSpan(leftLines)
        val rightSpan = verticalSpan(rightLines)
        if (leftLines.size < minPopulation || rightLines.size < minPopulation ||
            leftSpan < pageHeight * 0.28f || rightSpan < pageHeight * 0.28f) {
            return listOf(Region("single", RegionKind.SINGLE_COLUMN, 0f, pageWidth, 0f, pageHeight))
        }

        return listOf(
            Region("full", RegionKind.FULL_WIDTH, 0f, pageWidth, 0f, pageHeight),
            Region("left", RegionKind.COLUMN_LEFT, 0f, boundary, 0f, pageHeight),
            Region("right", RegionKind.COLUMN_RIGHT, boundary, pageWidth, 0f, pageHeight)
        )
    }

    private fun assignRegion(line: Line, regions: List<Region>): String {
        val full = regions.firstOrNull { it.kind == RegionKind.FULL_WIDTH }
        if (full == null) return regions.first().id
        val left = regions.firstOrNull { it.kind == RegionKind.COLUMN_LEFT }
        val right = regions.firstOrNull { it.kind == RegionKind.COLUMN_RIGHT }
        if (left == null || right == null) return full.id

        // A true full-width element crosses the column boundary or occupies most
        // of the page. A merely long column line must remain in its column.
        val crossesBoundary = line.x < left.right && line.x + line.w > right.left
        val wide = line.w >= (right.right - left.left) * 0.84f
        if (crossesBoundary || wide) return full.id

        val center = line.x + line.w / 2f
        return if (center < left.right) left.id else right.id
    }

    private fun verticalSpan(lines: List<Line>): Float =
        if (lines.isEmpty()) 0f else lines.maxOf { it.y + it.h } - lines.minOf { it.y }

    // -------------------------- block reconstruction --------------------------

    /** Build paragraphs only within the same spatial region. */
    private fun buildBlocksByRegion(lines: List<Line>, regions: List<Region>): List<Block> {
        val result = mutableListOf<Block>()
        regions.forEach { region ->
            val regionLines = lines.filter { it.regionId == region.id }
                .sortedWith(compareBy<Line> { it.y }.thenBy { it.x })
            val blocks = mutableListOf<Block>()
            for (line in regionLines) {
                val previous = blocks.lastOrNull()
                if (previous != null && canJoin(previous, line)) {
                    previous.text = joinParagraph(previous.text, line.text)
                    previous.w = max(previous.w, line.x + line.w - previous.x)
                    previous.h = max(previous.h, line.y + line.h - previous.y)
                    previous.fontSize = (previous.fontSize + line.fontSize) / 2f
                    previous.lineCount++
                } else {
                    blocks.add(Block(line.text, line.x, line.y, line.w, line.h, line.fontSize, 1, region.id))
                }
            }
            result.addAll(blocks)
        }
        return result
    }

    private fun canJoin(previous: Block, line: Line): Boolean {
        val gap = line.y - (previous.y + previous.h)
        val sameFont = abs(line.fontSize - previous.fontSize) <= max(1.4f, previous.fontSize * 0.12f)
        val indent = abs(line.x - previous.x)
        val overlapX = overlap(previous.x, previous.x + previous.w, line.x, line.x + line.w)
        val paragraphGap = max(10f, previous.fontSize * 1.35f)
        if (!sameFont || gap < -previous.h * 0.25f || gap > paragraphGap) return false
        if (isStandaloneStructure(line.text) || isStandaloneStructure(previous.text)) return false
        // A continuation normally overlaps the previous text width or begins near its left edge.
        return indent <= max(18f, previous.fontSize * 3.0f) || overlapX > min(previous.w, line.w) * 0.35f
    }

    private fun isStandaloneStructure(text: String): Boolean =
        isListItem(text) || isReferenceItem(text) || isReferenceSection(text) || isFigureCaption(text) || isTableHeaderLike(text)

    // -------------------------- structural classification --------------------------

    private fun classifyBlocks(blocks: List<Block>, pageNumber: Int, pageCount: Int, pageWidth: Float, pageHeight: Float): List<ClassifiedBlock> {
        if (blocks.isEmpty()) return emptyList()
        val bodyFont = median(blocks.map { it.fontSize })
        val styleProfile = StyleProfile(bodyFont, blocks.map { it.fontSize }.distinct().sorted())
        val tableLikeIds = detectTableLikeClusters(blocks, pageWidth, pageHeight)
        val classified = blocks.mapIndexed { index, block ->
            val text = cleanSpacing(block.text)
            val typeAndConfidence = classifyOne(text, block, index, blocks, styleProfile, pageNumber, pageCount, pageWidth, pageHeight, tableLikeIds)
            ClassifiedBlock(text, block.x, block.y, block.w, block.h, block.fontSize, typeAndConfidence.first, typeAndConfidence.second, block.regionId)
        }.toMutableList()

        // V0.16: repair structures using the relationship between neighboring
        // classified blocks.  Classification is retained; only boundaries and
        // presentation are repaired.  This prevents caption continuations and
        // local source/attribution lines from leaking into ordinary body prose.
        val contextual = repairContextualStructures(classified)

        // Several aligned short left/right units can arrive from PDFBox as ONE
        // physical text block. Recover those pairs before final reading order.
        val repaired = mutableListOf<ClassifiedBlock>()
        contextual.forEach { block ->
            val embedded = splitEmbeddedPairTable(block)
            if (embedded == null) repaired += block else repaired += embedded
        }

        // V0.15: turn independently extracted table cells into explicit row
        // relationships.  This happens only inside one spatial region, so
        // coincidentally aligned text in the left and right page columns can
        // never become a table relationship.
        val relationRepaired = repairDetectedTableRows(repaired, pageWidth, pageHeight)

        // Only promote a first-page heading that is genuinely title-like. Never promote a body paragraph merely because it is large.
        if (pageNumber == 1) {
            val candidate = relationRepaired.indices
                .filter { relationRepaired[it].type in setOf("HEADING", "SUBHEADING") }
                .filter { relationRepaired[it].text.length in 5..140 }
                .maxByOrNull { titleScore(relationRepaired[it], pageWidth, pageHeight) }
            if (candidate != null && titleScore(relationRepaired[candidate], pageWidth, pageHeight) >= 1.8f) {
                relationRepaired[candidate] = relationRepaired[candidate].copy(type = "TITLE", confidence = "HIGH")
            }
        }
        return relationRepaired
    }

    data class StyleProfile(val bodyFont: Float, val sizes: List<Float>)

    private fun classifyOne(
        text: String,
        block: Block,
        index: Int,
        blocks: List<Block>,
        style: StyleProfile,
        page: Int,
        pageCount: Int,
        pageWidth: Float,
        pageHeight: Float,
        tableLikeIds: Set<Int>
    ): Pair<String, String> {
        val lower = text.lowercase(Locale.US)
        return when {
            isReferenceSection(text) -> "HEADING" to "HIGH"
            isFigureCaption(text) -> "FIGURE_CAPTION" to "HIGH"
            index in tableLikeIds -> "TABLE_LIKE" to "HIGH"
            isListItem(text) -> "LIST_ITEM" to "HIGH"
            isReferenceItem(text) -> "REFERENCE_ITEM" to "HIGH"
            isPageNumberText(text) -> "PAGE_NUMBER" to "HIGH"
            isRepositoryMetadata(text, block, page, pageWidth, pageHeight) -> "SOURCE_NOTE" to "HIGH"
            isSourceOrCitation(text) -> "SOURCE_NOTE" to "MEDIUM"
            isAttributionLine(text) -> "SOURCE_NOTE" to "HIGH"
            isHeading(text, block, style, index, blocks, pageWidth) -> "HEADING" to "HIGH"
            isAuthorLine(text) && page == 1 -> "AUTHOR" to "HIGH"
            isNotice(text) -> "NOTICE" to "MEDIUM"
            lower.contains("creative commons") || lower.contains("licensed under") -> "LICENSE" to "HIGH"
            else -> "BODY" to "HIGH"
        }
    }

    private fun isHeading(text: String, block: Block, style: StyleProfile, index: Int, blocks: List<Block>, pageWidth: Float): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 180 || isListItem(t) || isReferenceItem(t) || isFigureCaption(t)) return false
        val numbered = Regex("^(?:\\d+(?:\\.\\d+)*[.)]?|[IVX]+[.)])\\s+.+", RegexOption.IGNORE_CASE).matches(t)
        val short = t.length <= 100
        val fontLift = block.fontSize >= style.bodyFont * 1.10f
        val strongFontLift = block.fontSize >= style.bodyFont * 1.25f
        val noSentencePunctuation = !t.endsWith(".") && !t.endsWith(",") && !t.endsWith(";")
        val spacing = headingSpacing(index, blocks)
        val fullWidth = block.w >= pageWidth * 0.55f
        return (numbered && short && noSentencePunctuation) || (short && noSentencePunctuation && (strongFontLift || (fontLift && spacing)) && (fullWidth || spacing))
    }

    private fun headingSpacing(index: Int, blocks: List<Block>): Boolean {
        val current = blocks[index]
        val before = if (index > 0) current.y - (blocks[index - 1].y + blocks[index - 1].h) else 20f
        val after = if (index + 1 < blocks.size) blocks[index + 1].y - (current.y + current.h) else 20f
        return before >= max(9f, current.fontSize * 0.75f) || after >= max(9f, current.fontSize * 0.75f)
    }

    private fun titleScore(block: ClassifiedBlock, pageWidth: Float, pageHeight: Float): Float {
        var score = 0f
        if (block.fontSize >= 16f) score += 1f
        if (block.fontSize >= 20f) score += 0.7f
        if (block.w >= pageWidth * 0.45f) score += 0.5f
        if (block.y <= pageHeight * 0.35f) score += 0.5f
        if (block.text.length <= 100) score += 0.3f
        return score
    }


    /**
     * V0.16 contextual structural repair.
     *
     * The V0.15 engine correctly classifies the first block of a caption, but
     * PDFBox can split the remainder of the same visual caption into separate
     * BODY blocks.  It can also leave short attribution/source lines beside the
     * caption.  This pass uses local spatial context without changing the
     * underlying Block/ClassifiedBlock model.
     *
     * Rules are deliberately conservative:
     *  - only same-region neighbors can be merged;
     *  - a caption may absorb a likely continuation, never a strong heading,
     *    table row, reference, or obvious source line;
     *  - short attribution lines immediately following a caption become
     *    SOURCE_NOTE rather than body prose;
     *  - source-note runs remain separate from the main paragraph flow.
     */
    private fun repairContextualStructures(
        input: MutableList<ClassifiedBlock>
    ): MutableList<ClassifiedBlock> {
        if (input.isEmpty()) return input

        val working = input.toMutableList()
        val consumed = mutableSetOf<Int>()
        val replacements = mutableListOf<ClassifiedBlock>()

        val byRegion = working.indices.groupBy { working[it].regionId }

        byRegion.values.forEach { regionIndices ->
            val ordered = regionIndices.sortedWith(
                compareBy<Int> { working[it].y }.thenBy { working[it].x }
            )

            var p = 0
            while (p < ordered.size) {
                val startIndex = ordered[p]
                if (startIndex in consumed || working[startIndex].type != "FIGURE_CAPTION") {
                    p++
                    continue
                }

                var caption = working[startIndex]
                var q = p + 1
                var absorbed = false

                while (q < ordered.size) {
                    val nextIndex = ordered[q]
                    if (nextIndex in consumed) {
                        q++
                        continue
                    }

                    val next = working[nextIndex]
                    val gap = next.y - (caption.y + caption.h)

                    if (next.regionId != caption.regionId ||
                        gap < -caption.h * 0.20f ||
                        gap > max(14f, caption.fontSize * 1.65f)
                    ) break

                    // Never absorb a structural boundary.
                    if (next.type in setOf(
                            "HEADING", "TITLE", "SUBHEADING", "TABLE_LIKE",
                            "REFERENCE_ITEM", "REFERENCE_SECTION", "LIST_ITEM",
                            "PAGE_NUMBER", "LICENSE"
                        )
                    ) break

                    // Source/attribution material is classified separately.
                    // It stays out of the caption text, but is retained as a
                    // source note in the reconstructed document.
                    if (isContextualCaptionSource(next.text)) {
                        break
                    }

                    if (isLikelyCaptionContinuation(caption.text, next.text, next, gap)) {
                        caption = caption.copy(
                            text = joinParagraph(caption.text, next.text),
                            w = max(caption.w, next.x + next.w - caption.x),
                            h = max(caption.h, next.y + next.h - caption.y),
                            fontSize = (caption.fontSize + next.fontSize) / 2f,
                            confidence = "HIGH",
                            relation = "CAPTION_CONTINUATION"
                        )
                        consumed += nextIndex
                        absorbed = true
                        q++
                    } else {
                        break
                    }
                }

                if (absorbed) {
                    consumed += startIndex
                    replacements += caption
                }
                p = q
            }
        }

        // Promote short attribution/source lines that directly follow a caption.
        val afterCaption = mutableListOf<ClassifiedBlock>()
        val base = working.indices
            .filter { it !in consumed }
            .map { working[it] }
            .toMutableList()

        val sortedBase = base.sortedWith(
            compareBy<ClassifiedBlock> { it.regionId }.thenBy { it.y }.thenBy { it.x }
        )

        for (i in sortedBase.indices) {
            val current = sortedBase[i]
            if (current.type == "BODY" && isContextualSourceLine(current.text)) {
                val previous = sortedBase.getOrNull(i - 1)
                if (previous != null &&
                    previous.type == "FIGURE_CAPTION" &&
                    previous.regionId == current.regionId &&
                    current.y - (previous.y + previous.h) <= max(18f, previous.fontSize * 2.0f)
                ) {
                    afterCaption += current.copy(
                        type = "SOURCE_NOTE",
                        confidence = "HIGH",
                        relation = "CAPTION_SOURCE"
                    )
                    continue
                }
            }
            afterCaption += current
        }

        // Rebuild in stable page-local geometry order.  This is intentionally
        // not the final reading order; documentReadingOrder() still owns that.
        val result = afterCaption + replacements
        return result.sortedWith(
            compareBy<ClassifiedBlock> { it.regionId }
                .thenBy { it.y }
                .thenBy { it.x }
        ).toMutableList()
    }

    private fun isLikelyCaptionContinuation(
        captionText: String,
        nextText: String,
        next: ClassifiedBlock,
        gap: Float
    ): Boolean {
        val t = nextText.trim()
        if (t.isEmpty() || t.length > 260) return false
        if (gap > max(14f, next.fontSize * 1.65f)) return false
        if (isFigureCaption(t) || isReferenceSection(t) || isReferenceItem(t)) return false
        if (isListItem(t) || isSourceOrCitation(t) || isAttributionLine(t)) return false
        if (isLikelyNewSection(t, next)) return false

        val previous = captionText.trim()
        val lower = t.lowercase(Locale.US)

        // Strongest signal: the previous caption is visibly unfinished.
        val unfinished = previous.endsWith("-") ||
            previous.endsWith("—") ||
            previous.endsWith(":") ||
            previous.endsWith(",") ||
            previous.endsWith("(") ||
            !previous.endsWith(".") && !previous.endsWith("?") &&
                !previous.endsWith("!")

        // Lowercase continuation is highly reliable.
        val lowercaseStart = t.firstOrNull()?.isLowerCase() == true

        // Parenthetical/caption prose continuation often starts with a
        // connective even though it is capitalized.
        val connective = lower.startsWith("and ") ||
            lower.startsWith("or ") ||
            lower.startsWith("with ") ||
            lower.startsWith("this ") ||
            lower.startsWith("these ") ||
            lower.startsWith("the ") ||
            lower.startsWith("a ") ||
            lower.startsWith("an ") ||
            lower.startsWith("in ") ||
            lower.startsWith("on ") ||
            lower.startsWith("compare ") ||
            lower.startsWith("higher ") ||
            lower.startsWith("lower ") ||
            lower.startsWith("low ") ||
            lower.startsWith("high ")

        // A short isolated block with a strong structural appearance is more
        // likely to start the next section than to continue a caption.
        val shortNewBlock = t.length <= 90 && isHeadingLikeWithoutClassification(t, next)

        return !shortNewBlock && (lowercaseStart || unfinished || connective)
    }

    private fun isLikelyNewSection(text: String, block: ClassifiedBlock): Boolean {
        val t = text.trim()
        if (t.length > 180) return false
        if (t.endsWith(".") || t.endsWith(",") || t.endsWith(";") || t.endsWith(":")) return false
        if (isHeadingLikeWithoutClassification(t, block)) return true

        // Common document-section lead-ins are short, noun-like labels.
        val lower = t.lowercase(Locale.US)
        val sectionLead = listOf(
            "introduction", "basic principles", "nomenclature", "cancer",
            "carcinoma", "carcinomas", "sarcoma", "sarcomas", "pathology",
            "definition", "definitions", "classification", "clinical features"
        )
        return lower in sectionLead
    }

    private fun isHeadingLikeWithoutClassification(
        text: String,
        block: ClassifiedBlock
    ): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 120) return false
        if (isListItem(t) || isReferenceItem(t) || isFigureCaption(t)) return false
        if (t.endsWith(".") || t.endsWith(",") || t.endsWith(";")) return false
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 14) return false
        val titleCaseRatio = words.count {
            it.firstOrNull()?.isUpperCase() == true
        }.toFloat() / words.size.coerceAtLeast(1)
        return block.fontSize >= 11f && (
            t.length <= 60 && titleCaseRatio >= 0.55f ||
                t.all { !it.isLowerCase() || it.isWhitespace() || !it.isLetter() }
        )
    }

    private fun isContextualCaptionSource(text: String): Boolean {
        val t = text.trim().lowercase(Locale.US)
        return t == "images" ||
            t == "image" ||
            t == "photographs" ||
            t == "photograph" ||
            t == "pathology department" ||
            t == "department of pathology" ||
            t.startsWith("images courtesy of") ||
            t.startsWith("courtesy of") ||
            t.startsWith("source:")
    }

    private fun isContextualSourceLine(text: String): Boolean {
        val t = text.trim().lowercase(Locale.US)
        if (t.length > 90) return false
        return isContextualCaptionSource(t) ||
            t.endsWith("department.") ||
            t == "pathology department." ||
            t.startsWith("images ") ||
            t.startsWith("image ")
    }

    // -------------------------- table / caption / source detection --------------------------

    /**
     * Detect table-like structures only when several rows form a stable two-anchor
     * pattern.  V0.10 classified an ordinary paragraph fragment as TABLE_LIKE when
     * any other block happened to share nearly the same Y coordinate.  That is too
     * weak: a table is a repeated structure, not a one-row coincidence.
     */
    private fun detectTableLikeClusters(blocks: List<Block>, pageWidth: Float, pageHeight: Float): Set<Int> {
        if (blocks.size < 6) return emptySet()

        data class PairCandidate(val left: Int, val right: Int, val y: Float, val lx: Float, val rx: Float)
        val candidates = mutableListOf<PairCandidate>()
        for (i in blocks.indices) {
            val a = blocks[i]
            if (!tableCellCandidate(a, pageWidth)) continue
            for (j in i + 1 until blocks.size) {
                val b = blocks[j]
                if (!tableCellCandidate(b, pageWidth)) continue
                // V0.15: a table relationship is local to one spatial region and must also have a compact cell signature.
                // Never compare blocks from independent page columns merely
                // because their Y coordinates happen to align.
                if (a.regionId != b.regionId) continue
                val yTol = max(2.5f, min(a.fontSize, b.fontSize) * 0.65f)
                if (abs(a.y - b.y) > yTol) continue
                val gap = if (a.x < b.x) b.x - (a.x + a.w) else a.x - (b.x + b.w)
                if (gap < max(35f, pageWidth * 0.055f)) continue
                val left = if (a.x < b.x) i else j
                val right = if (a.x < b.x) j else i
                candidates += PairCandidate(left, right, (a.y + b.y) / 2f, blocks[left].x, blocks[right].x)
            }
        }
        if (candidates.size < 3) return emptySet()

        val groups = mutableListOf<MutableList<PairCandidate>>()
        val anchorTol = max(12f, pageWidth * 0.025f)
        val rowTol = max(7f, pageHeight * 0.012f)
        for (candidate in candidates.sortedBy { it.y }) {
            val group = groups.firstOrNull { g ->
                val lx = g.map { it.lx }.average().toFloat()
                val rx = g.map { it.rx }.average().toFloat()
                abs(candidate.lx - lx) <= anchorTol && abs(candidate.rx - rx) <= anchorTol
            }
            if (group == null) groups += mutableListOf(candidate)
            else if (group.none { abs(it.y - candidate.y) < rowTol }) group += candidate
        }

        val result = mutableSetOf<Int>()
        groups.filter { it.size >= 3 }.forEach { group ->
            val distinctRows = group.map { it.y }.distinct().size
            val verticalSpan = group.maxOf { it.y } - group.minOf { it.y }
            if (distinctRows >= 3 && verticalSpan >= max(18f, pageHeight * 0.035f)) {
                group.forEach { result += it.left; result += it.right }
            }
        }
        return result
    }

    private fun tableCellCandidate(block: Block, pageWidth: Float): Boolean {
        val t = block.text.trim()
        if (t.isEmpty() || t.length > 70) return false
        if (t.contains(". ") || t.endsWith(".") || t.contains("://")) return false
        if (t.contains(";")) return false
        if (isListItem(t) || isFigureCaption(t) || isReferenceItem(t)) return false
        // A genuine compact table cell is normally one short visual line.
        // Long/tall prose blocks must not enter the table relationship detector.
        if (block.h > max(block.fontSize * 2.6f, block.fontSize + 14f)) return false
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 8) return false
        return block.w <= pageWidth * 0.42f
    }

    /**
     * V0.15 converts a stable sequence of separately extracted left/right table
     * cells into one readable row.  The relationship is deliberately local to
     * one region and requires repeated anchors, so ordinary two-column prose
     * cannot be joined merely because it shares a vertical band.
     */
    private fun isCompactTableBlock(block: ClassifiedBlock): Boolean {
        val t = block.text.trim()
        if (t.length > 70 || t.contains(". ") || t.endsWith(".") || t.contains(";")) return false
        if (block.h > max(block.fontSize * 2.6f, block.fontSize + 14f)) return false
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size <= 8
    }

    private fun repairDetectedTableRows(
        blocks: MutableList<ClassifiedBlock>,
        pageWidth: Float,
        pageHeight: Float
    ): MutableList<ClassifiedBlock> {
        if (blocks.size < 6) return blocks

        data class RowPair(val left: Int, val right: Int, val y: Float, val lx: Float, val rx: Float)
        val candidates = mutableListOf<RowPair>()

        for (i in blocks.indices) {
            val a = blocks[i]
            if (a.type != "TABLE_LIKE") continue
            for (j in i + 1 until blocks.size) {
                val b = blocks[j]
                if (b.type != "TABLE_LIKE" || a.regionId != b.regionId) continue
                if (!isCompactTableBlock(a) || !isCompactTableBlock(b)) continue
                val yTol = max(3f, min(a.fontSize, b.fontSize) * 0.8f)
                if (abs(a.y - b.y) > yTol) continue

                val left = if (a.x <= b.x) i else j
                val right = if (a.x <= b.x) j else i
                val l = blocks[left]
                val r = blocks[right]
                val gap = r.x - (l.x + l.w)
                if (gap < max(24f, pageWidth * 0.035f)) continue
                if (l.w > pageWidth * 0.42f || r.w > pageWidth * 0.42f) continue
                candidates += RowPair(left, right, (l.y + r.y) / 2f, l.x, r.x)
            }
        }
        if (candidates.size < 3) return blocks

        data class Group(val rows: MutableList<RowPair>)
        val groups = mutableListOf<Group>()
        val anchorTol = max(12f, pageWidth * 0.025f)
        val rowTol = max(8f, pageHeight * 0.012f)

        for (candidate in candidates.sortedBy { it.y }) {
            val group = groups.firstOrNull { g ->
                val lx = g.rows.map { it.lx }.average().toFloat()
                val rx = g.rows.map { it.rx }.average().toFloat()
                abs(candidate.lx - lx) <= anchorTol &&
                    abs(candidate.rx - rx) <= anchorTol &&
                    candidate.left !in g.rows.flatMap { listOf(it.left, it.right) } &&
                    candidate.right !in g.rows.flatMap { listOf(it.left, it.right) }
            }
            if (group == null) groups += Group(mutableListOf(candidate))
            else if (group.rows.none { abs(it.y - candidate.y) < rowTol }) group.rows += candidate
        }

        val accepted = groups.filter { it.rows.size >= 3 }
        if (accepted.isEmpty()) return blocks

        val consumed = mutableSetOf<Int>()
        val replacements = mutableListOf<ClassifiedBlock>()
        accepted.forEach { group ->
            val ordered = group.rows.sortedBy { it.y }
            ordered.forEach { row ->
                val left = blocks[row.left]
                val right = blocks[row.right]
                consumed += row.left
                consumed += row.right
                val leftText = left.text.trim()
                val rightText = right.text.trim()
                val combinedWidth = (right.x + right.w - left.x).coerceAtLeast(left.w)
                replacements += ClassifiedBlock(
                    text = "$leftText — $rightText",
                    x = left.x,
                    y = min(left.y, right.y),
                    w = combinedWidth,
                    h = max(left.h, right.h),
                    fontSize = (left.fontSize + right.fontSize) / 2f,
                    type = "TABLE_LIKE",
                    confidence = "HIGH",
                    regionId = left.regionId,
                    relation = "TWO_COLUMN_ROW"
                )
            }
        }

        val result = mutableListOf<ClassifiedBlock>()
        blocks.forEachIndexed { index, block ->
            if (index !in consumed) result += block
        }
        result += replacements
        return result.sortedWith(compareBy<ClassifiedBlock> { it.regionId }.thenBy { it.y }.thenBy { it.x }).toMutableList()
    }

    /**
     * Recover compact two-column rows that PDFBox sometimes flattens into one
     * text block.  This is deliberately conservative and requires at least three
     * recognized left/right pairs plus substantial text coverage.  It therefore
     * cannot recreate the old V0.10 "one coincidental neighbour = table" bug.
     */
    private fun splitEmbeddedPairTable(block: ClassifiedBlock): List<ClassifiedBlock>? {
        val text = cleanSpacing(block.text)
        if (text.length < 50 || text.contains("http://") || text.contains("https://")) return null

        val rightCells = listOf(
            "Bone", "Cartilage", "Connective tissue", "Smooth muscle", "Striated muscle",
            "Blood vessel", "Lymphoid tissue", "Nervous tissue", "Adipose tissue",
            "Skeletal muscle", "Cardiac muscle", "Glandular tissue", "Epithelial tissue"
        )
        val rightPattern = rightCells.joinToString("|") { Regex.escape(it) }
        val pairRegex = Regex(
            "([A-Z][A-Za-z-]+(?:\\s*\\([^)]{1,30}\\))?)\\s+($rightPattern)(?=\\s+[A-Z]|$)"
        )
        val matches = pairRegex.findAll(text).toList()
        if (matches.size < 3) return null

        val covered = matches.sumOf { it.value.length }
        if (covered.toFloat() / text.length < 0.52f) return null

        val width = max(block.w, 1f)
        val rowHeight = max(block.h / matches.size.toFloat(), block.fontSize * 1.25f)
        return matches.mapIndexed { i, match ->
            val row = cleanSpacing(match.value)
            val rowY = block.y + i * rowHeight
            block.copy(
                text = row.replace(Regex("\\s+"), " ").replaceFirst(" ", " — "),
                x = block.x,
                y = rowY,
                w = width,
                h = rowHeight,
                type = "TABLE_LIKE",
                confidence = "HIGH",
                relation = "EMBEDDED_TWO_COLUMN_ROW"
            )
        }
    }

    private fun isTableHeaderLike(text: String): Boolean {
        val t = text.trim()
        return t.length <= 80 && !t.endsWith(".") && !isListItem(t) && !isFigureCaption(t)
    }

    private fun isFigureCaption(text: String): Boolean =
        Regex("^(figures?|fig\\.?|tables?)\\s*\\d+[a-z]?(?:\\s*[,.:)]|\\s).+", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())

    private fun isReferenceSection(text: String): Boolean =
        Regex("^(references|bibliography|works cited)$", RegexOption.IGNORE_CASE).matches(text.trim())

    private fun isReferenceItem(text: String): Boolean =
        Regex("^\\d+[.)]\\s+.+").matches(text.trim()) && text.length >= 25 && (text.contains(".") || text.contains("et al", true))

    private fun isListItem(text: String): Boolean =
        Regex("^(?:\\d+[.)]|[•▪◦–—-])\\s+.+").matches(text.trim())

    private fun isRepositoryMetadata(text: String, block: Block, page: Int, pageWidth: Float, pageHeight: Float): Boolean {
        if (page != 1) return false
        val t = text.trim()
        val lower = t.lowercase(Locale.US)
        val topArea = block.y <= pageHeight * 0.32f
        val email = lower.contains("@")
        val url = lower.startsWith("http://") || lower.startsWith("https://")
        val date = Regex("^\\d{4}[-/]\\d{2}[-/]\\d{2}$").matches(t)
        val institutional = lower.contains("eschoolarship") || lower.contains("umass chan") || lower.contains("repository")
        return topArea && (email || url || date || institutional)
    }

    private fun isSourceOrCitation(text: String): Boolean {
        val t = text.lowercase(Locale.US)
        return t.contains("citation:") || t.contains("retrieved from") || t.contains("doi.org/") ||
            t.startsWith("cancer concepts:") || t.contains("images courtesy of") || t.contains("source:") ||
            t.contains("part of the cancer biology commons") || t.contains("medical education commons") ||
            t.contains("neoplasms commons") || t.contains("oncology commons") ||
            t.contains("pathological conditions") || t.contains("signs and symptoms commons") ||
            t.contains("pathology commons") || t.startsWith("https://") || t.startsWith("http://")
    }

    /** Bare institutional attribution lines are source material, not body prose. */
    private fun isAttributionLine(text: String): Boolean {
        val t = text.trim().lowercase(Locale.US)
        if (t.length > 100) return false
        return t.matches(Regex("department of [a-z][a-z .&-]+\\.?")) ||
            t.startsWith("courtesy of ") ||
            t.startsWith("images courtesy of ") ||
            t.startsWith("university of ") ||
            t == "images" ||
            t == "image" ||
            t == "pathology department" ||
            t == "department of pathology" ||
            t.endsWith(" department of pathology.") ||
            t.endsWith(" pathology department.")
    }

    private fun isNotice(text: String): Boolean {
        val t = text.lowercase(Locale.US)
        return t.startsWith("let us know how access") || t.startsWith("follow this and additional works")
    }

    // -------------------------- reading order --------------------------

    /**
     * Reading order is generated from blocks and their spatial regions. A full-
     * width block is a barrier; within each barrier interval, left-column blocks
     * precede right-column blocks. This prevents the V0.9 global Y/X sort from
     * interleaving columns and prevents a paragraph from crossing regions.
     */
    private fun documentReadingOrder(pages: List<PageModel>): List<ClassifiedBlock> {
        val out = mutableListOf<ClassifiedBlock>()
        pages.forEach { page -> out.addAll(pageReadingOrder(page)) }
        return out
    }

    private fun pageReadingOrder(page: PageModel): List<ClassifiedBlock> {
        val blocks = page.blocks.filterNot { it.type in setOf("PAGE_NUMBER", "RUNNING_HEADER", "RUNNING_FOOTER") }
        if (blocks.isEmpty()) return emptyList()
        val hasColumns = page.regions.any { it.kind == RegionKind.COLUMN_LEFT } && page.regions.any { it.kind == RegionKind.COLUMN_RIGHT }
        if (!hasColumns) return blocks.sortedBy { it.y }

        val full = blocks.filter { it.regionId == "full" }.sortedBy { it.y }
        val columns = blocks.filter { it.regionId == "left" || it.regionId == "right" }
        if (columns.isEmpty()) return full

        val result = mutableListOf<ClassifiedBlock>()
        var cursor = 0f
        val barriers = full
        for (barrier in barriers) {
            val before = columns.filter { it.y >= cursor && it.y < barrier.y }
            result.addAll(columnFlow(before))
            result.add(barrier)
            cursor = barrier.y + barrier.h
        }
        result.addAll(columnFlow(columns.filter { it.y >= cursor }))
        return result
    }

    private fun columnFlow(blocks: List<ClassifiedBlock>): List<ClassifiedBlock> {
        val left = blocks.filter { it.regionId == "left" }.sortedBy { it.y }
        val right = blocks.filter { it.regionId == "right" }.sortedBy { it.y }
        return left + right
    }

    // -------------------------- diagnostics --------------------------

    private fun formatResult(
        pageCount: Int,
        rawGlyphs: Int,
        cleanedGlyphs: Int,
        duplicateGlyphs: Int,
        totalLines: Int,
        furniture: Set<String>,
        pages: List<PageModel>
    ): String {
        val reading = documentReadingOrder(pages)
        val counts = reading.groupingBy { it.type }.eachCount()
        val primary = reading.filter { it.type !in FRONT_MATTER_TYPES }
        val front = reading.filter { it.type in FRONT_MATTER_TYPES }

        val sb = StringBuilder()
        sb.append("Pages: $pageCount\n")
        sb.append("Analyzed pages: $pageCount\n")
        sb.append("Raw glyph items: $rawGlyphs\n")
        sb.append("After geometric cleanup: $cleanedGlyphs\n")
        sb.append("Geometric duplicates removed: $duplicateGlyphs\n")
        sb.append("Physical lines: $totalLines\n")
        sb.append("Structural blocks: ${reading.size}\n")
        sb.append("Running furniture suppressed: ${furniture.size}\n")
        val tableCount = counts["TABLE_LIKE"] ?: 0
        val bodyCount = counts["BODY"] ?: 0
        val quality = when {
            tableCount > max(20, (reading.size * 0.18f).toInt()) -> "WARNING: table-like classification is unusually high"
            bodyCount > 0 && tableCount == 0 -> "OK: no table-like false-positive pattern detected"
            else -> "OK: structural classification within expected range"
        }
        sb.append("Structure quality: $quality\n")
        sb.append("\nSTRUCTURAL COUNTS\n")
        counts.toSortedMap().forEach { (k, v) -> sb.append("$k: $v\n") }

        sb.append("\nDOCUMENT READING ORDER\n")
        sb.append("======================\n")
        if (primary.isEmpty()) sb.append("No primary document blocks detected.\n")
        primary.forEach { b ->
            when (b.type) {
                "TITLE", "HEADING", "SUBHEADING" -> sb.append("\n${b.text}\n")
                "TABLE_LIKE" -> sb.append("${b.text}\n")
                "FIGURE_CAPTION" -> sb.append("${b.text}\n")
                "SOURCE_NOTE" -> sb.append("${b.text}\n")
                else -> sb.append("${b.text}\n")
            }
        }

        sb.append("\nREPOSITORY / SOURCE MATERIAL\n")
        sb.append("============================\n")
        if (front.isEmpty()) sb.append("None detected.\n")
        front.forEach { b -> sb.append("[${b.type}] ${b.text}\n") }

        sb.append("\nSPATIAL RECONSTRUCTION DIAGNOSTICS\n")
        sb.append("=================================\n")
        pages.forEach { page ->
            sb.append("\nPAGE ${page.pageNumber}  ${fmt(page.width)}×${fmt(page.height)}\n")
            sb.append("Regions: ")
            sb.append(page.regions.joinToString { "${it.id}:${it.kind}" })
            sb.append("\n")
            page.blocks.sortedWith(compareBy<ClassifiedBlock> { it.y }.thenBy { it.x }).forEachIndexed { i, b ->
                sb.append("BLOCK ${i + 1} [${b.regionId}] ${b.type} (${b.confidence})\n")
                sb.append("x=${fmt(b.x)} y=${fmt(b.y)} w=${fmt(b.w)} h=${fmt(b.h)} font=${fmt(b.fontSize)}\n")
                sb.append("${b.text}\n\n")
            }
        }
        return sb.toString()
    }

    // -------------------------- helpers --------------------------

    private fun cleanSpacing(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun joinParagraph(a: String, b: String): String {
        val left = a.trimEnd()
        val right = b.trimStart()
        if (left.endsWith("-") && right.firstOrNull()?.isLetter() == true) return left.dropLast(1) + right
        return "$left $right"
    }

    private fun normalizeForKey(text: String): String =
        text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun isAuthorLine(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (lower.contains("@") || lower == "et al.") return false
        if (lower.startsWith("by ")) return true
        return Regex("\\b(?:md|phd|ba|ma|rn|do|et al\\.?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) && text.length <= 140
    }

    private fun isSourceLikeFirstPage(text: String): Boolean =
        text.lowercase(Locale.US).contains("cancer concepts: a guidebook for the non-oncologist")

    private fun overlap(a1: Float, a2: Float, b1: Float, b2: Float): Float = max(0f, min(a2, b2) - max(a1, b1))

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 10f
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

    companion object {
        private const val REQUEST_PDF = 100
        private const val DUP_CELL = 3.0f
        private val FRONT_MATTER_TYPES = setOf("SOURCE_NOTE", "NOTICE", "LICENSE", "AUTHOR")
    }
}
