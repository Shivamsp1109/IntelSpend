package com.spendwise.data.ingestion.harness

import com.google.gson.Gson
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.data.ingestion.ocr.OcrWord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

data class WordFixture(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val page: Int = 1
)

data class ExpectedTxn(
    val date: String? = null,
    val amount: Double = 0.0,
    val type: String? = null,
    val merchantContains: String? = null
)

data class StatementFixture(
    val note: String = "",
    val fullText: String = "",
    val words: List<WordFixture> = emptyList(),
    val expected: List<ExpectedTxn> = emptyList()
)

data class TabularExpectation(
    val note: String = "",
    val expected: List<ExpectedTxn> = emptyList()
)

object Fixtures {
    private val gson = Gson()

    val root: File by lazy {
        // Unit tests run with the module directory as CWD.
        val candidates = listOf(File("test-data"), File("../test-data"), File("../../test-data"))
        candidates.firstOrNull { it.isDirectory }?.canonicalFile
            ?: error("test-data directory not found from ${File(".").canonicalPath}")
    }

    fun dir(name: String): File = File(root, name)

    fun statements(): List<Pair<String, StatementFixture>> =
        (dir("statements").listFiles { _, n -> n.endsWith(".fixture.json") } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.name.removeSuffix(".fixture.json") to gson.fromJson(it.readText(), StatementFixture::class.java) }

    fun tabular(folder: String, extension: String): List<Triple<String, File, TabularExpectation>> =
        (dir(folder).listFiles { _, n -> n.endsWith(".expected.json") } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { expectedFile ->
                val dataName = expectedFile.name.removeSuffix(".expected.json")
                val dataFile = File(dir(folder), dataName)
                if (!dataFile.exists() || !dataName.endsWith(extension)) return@mapNotNull null
                Triple(
                    dataName,
                    dataFile,
                    gson.fromJson(expectedFile.readText(), TabularExpectation::class.java)
                )
            }

    fun toOcrResult(fixture: StatementFixture): OcrResult = OcrResult(
        fullText = fixture.fullText,
        lines = emptyList(),
        words = fixture.words.map {
            OcrWord(it.text, it.left, it.top, it.right, it.bottom, it.page)
        }
    )
}

/** Per-field tallies for one or more fixtures. */
data class Score(
    var expected: Int = 0,
    var extracted: Int = 0,
    var matched: Int = 0,
    var dateCorrect: Int = 0,
    var typeCorrect: Int = 0,
    var merchantCorrect: Int = 0,
    var fullyCorrect: Int = 0
) {
    /** Amount recall: the share of real transactions we found at the right value. */
    val recall: Double get() = if (expected == 0) 0.0 else matched.toDouble() / expected

    /** Share of what we emitted that corresponds to a real transaction. */
    val precision: Double get() = if (extracted == 0) 0.0 else matched.toDouble() / extracted

    /** Rows where amount, date and direction are all right — the only ones safe to import. */
    val strict: Double get() = if (expected == 0) 0.0 else fullyCorrect.toDouble() / expected

    fun plus(other: Score) = Score(
        expected + other.expected,
        extracted + other.extracted,
        matched + other.matched,
        dateCorrect + other.dateCorrect,
        typeCorrect + other.typeCorrect,
        merchantCorrect + other.merchantCorrect,
        fullyCorrect + other.fullyCorrect
    )
}

object Scorer {
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Matches on amount first, then breaks ties by date and direction, so two rows sharing an
     * amount can't be scored against each other by accident. Order-independent by design —
     * extraction order is not part of what we're measuring.
     */
    fun score(
        expected: List<ExpectedTxn>,
        actual: List<RawTransaction>,
        mismatches: MutableList<String>? = null
    ): Score {
        val score = Score(expected = expected.size, extracted = actual.size)
        val used = BooleanArray(actual.size)

        for (want in expected) {
            var bestIndex = -1
            var bestRank = -1

            actual.forEachIndexed { index, got ->
                if (used[index]) return@forEachIndexed
                if (abs(got.amount - want.amount) > 0.01) return@forEachIndexed
                val dateOk = want.date == null || iso.format(got.date) == want.date
                val typeOk = want.type == null || got.type.name == want.type
                val rank = (if (dateOk) 2 else 0) + (if (typeOk) 1 else 0)
                if (rank > bestRank) {
                    bestRank = rank
                    bestIndex = index
                }
            }

            if (bestIndex < 0) continue
            used[bestIndex] = true
            val got = actual[bestIndex]
            val dateOk = want.date == null || iso.format(got.date) == want.date
            val typeOk = want.type == null || got.type.name == want.type
            score.matched++
            if (dateOk) score.dateCorrect++
            if (typeOk) score.typeCorrect++
            if (dateOk && typeOk) score.fullyCorrect++
            if (want.merchantContains == null ||
                got.merchant?.contains(want.merchantContains, ignoreCase = true) == true
            ) {
                score.merchantCorrect++
            } else {
                mismatches?.add("wanted '${want.merchantContains}' got '${got.merchant}'")
            }
        }
        return score
    }

    fun header(): String =
        "%-44s %5s %5s %7s %7s %7s %7s %7s".format(
            "fixture", "want", "got", "amount", "date", "dir", "name", "strict"
        )

    fun row(name: String, s: Score): String =
        "%-44s %5d %5d %6.0f%% %6.0f%% %6.0f%% %6.0f%% %6.0f%%".format(
            name.take(44), s.expected, s.extracted,
            s.recall * 100,
            pct(s.dateCorrect, s.matched),
            pct(s.typeCorrect, s.matched),
            pct(s.merchantCorrect, s.matched),
            s.strict * 100
        )

    private fun pct(part: Int, whole: Int): Double =
        if (whole == 0) 0.0 else part.toDouble() / whole * 100
}
