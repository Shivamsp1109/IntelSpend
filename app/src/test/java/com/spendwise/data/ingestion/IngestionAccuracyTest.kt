package com.spendwise.data.ingestion

import com.spendwise.data.ingestion.extractor.CsvExtractor
import com.spendwise.data.ingestion.extractor.StatementExtractor
import com.spendwise.data.ingestion.harness.Fixtures
import com.spendwise.data.ingestion.harness.Score
import com.spendwise.data.ingestion.harness.Scorer
import com.spendwise.data.ingestion.legacy.LegacyStatementExtractor
import com.spendwise.data.ingestion.reader.CsvReader
import com.spendwise.data.ingestion.reader.XlsxReadResult
import com.spendwise.data.ingestion.reader.XlsxReader
import com.spendwise.domain.model.ExpenseSource
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileInputStream

/**
 * Scores the whole ingestion corpus and prints a per-fixture breakdown.
 *
 * "strict" is the number that matters: the share of real transactions where amount, date and
 * direction are all correct. Those are the only rows that can be imported without a human
 * checking them, so it is the honest measure of the feature.
 */
class IngestionAccuracyTest {

    @Test
    fun `statement extraction beats the previous pipeline`() {
        val statementExtractor = StatementExtractor()
        val legacy = LegacyStatementExtractor()

        var current = Score()
        var before = Score()

        val merchantMisses = mutableListOf<String>()

        println("\n=== STATEMENTS (current) ===")
        println(Scorer.header())
        for ((name, fixture) in Fixtures.statements()) {
            val actual = statementExtractor.extract(Fixtures.toOcrResult(fixture))
            val misses = mutableListOf<String>()
            val score = Scorer.score(fixture.expected, actual, misses)
            current = current.plus(score)
            misses.forEach { merchantMisses.add("$name: $it") }
            println(Scorer.row(name, score))
        }
        println(Scorer.row("TOTAL", current))

        if (merchantMisses.isNotEmpty()) {
            println("\n--- merchant naming misses (${merchantMisses.size}) ---")
            merchantMisses.forEach { println("  $it") }
        }

        println("\n=== STATEMENTS (previous pipeline, same fixtures) ===")
        println(Scorer.header())
        for ((name, fixture) in Fixtures.statements()) {
            // The old code only ever saw fabricated coordinates for a text-layer PDF, so the
            // comparison feeds it exactly what the old orchestrator would have handed it.
            val actual = legacy.extract(legacy.wrapTextInOcrResult(fixture.fullText))
            val score = Scorer.score(fixture.expected, actual)
            before = before.plus(score)
            println(Scorer.row(name, score))
        }
        println(Scorer.row("TOTAL", before))

        println(
            "\nstrict accuracy: %.0f%% before -> %.0f%% now (amount recall %.0f%% -> %.0f%%)"
                .format(before.strict * 100, current.strict * 100, before.recall * 100, current.recall * 100)
        )

        assertTrue(
            "Statement strict accuracy regressed below the previous pipeline",
            current.strict > before.strict
        )
        assertTrue(
            "Statement strict accuracy is ${"%.0f".format(current.strict * 100)}%, expected at least 90%",
            current.strict >= 0.90
        )
        // Merchant naming is cosmetic next to amount and direction, but it silently regressed
        // once already, so it gets a floor of its own.
        val merchant = current.merchantCorrect.toDouble() / current.matched
        assertTrue(
            "Merchant naming is ${"%.0f".format(merchant * 100)}%, expected at least 95%",
            merchant >= 0.95
        )
    }

    @Test
    fun `csv extraction is accurate across delimiters encodings and header layouts`() {
        val reader = CsvReader()
        val extractor = CsvExtractor()
        var total = Score()

        println("\n=== CSV ===")
        println(Scorer.header())
        for ((name, file, expectation) in Fixtures.tabular("csv", ".csv")) {
            val rows = FileInputStream(file).use { reader.read(it) }
            val actual = extractor.extract(rows, ExpenseSource.CSV)
            val score = Scorer.score(expectation.expected, actual)
            total = total.plus(score)
            println(Scorer.row(name, score))
        }
        println(Scorer.row("TOTAL", total))

        assertTrue("No CSV fixtures were found", total.expected > 0)
        assertTrue(
            "CSV strict accuracy is ${"%.0f".format(total.strict * 100)}%, expected at least 95%",
            total.strict >= 0.95
        )
    }

    @Test
    fun `xlsx extraction handles date serials and sparse cells`() {
        val reader = XlsxReader()
        val extractor = CsvExtractor()
        var total = Score()

        println("\n=== XLSX ===")
        println(Scorer.header())
        for ((name, file, expectation) in Fixtures.tabular("xlsx", ".xlsx")) {
            val result = FileInputStream(file).use { reader.read(it) }
            val rows = (result as? XlsxReadResult.Rows)?.rows
                ?: error("$name did not parse: $result")
            val actual = extractor.extract(rows, ExpenseSource.EXCEL)
            val score = Scorer.score(expectation.expected, actual)
            total = total.plus(score)
            println(Scorer.row(name, score))
        }
        println(Scorer.row("TOTAL", total))

        assertTrue("No XLSX fixtures were found", total.expected > 0)
        assertTrue(
            "XLSX strict accuracy is ${"%.0f".format(total.strict * 100)}%, expected at least 95%",
            total.strict >= 0.95
        )
    }
}
