package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The signal, offline: two filings, a hand-written cache answer, and the flag it produces. */
class SignalsTest {

    @TempDir
    Path dir;

    static final String OLD = "<p>Item 1A. Risk Factors</p><p>We rely on third-party foundries to make our chips.</p><p>Item 2. Other</p>";
    static final String NEW = "<p>Item 1A. Risk Factors</p><p>We rely on third-party foundries to make our chips.</p>"
            + "<p>The U.S. government restricted exports of our products to China in 2025.</p><p>Item 2. Other</p>";
    static final LocalDate FILED = LocalDate.of(2025, 11, 20);

    @Test
    void aNewRiskInTheLatestFilingFlagsTheStockFromTheNextDay() throws Exception {
        TestDb.World w = TestDb.seeded(dir);
        Edgar edgar = new Edgar(w.db(), "", dir.toString());
        edgar.store("NVDA", 1045810, "10-Q", LocalDate.of(2025, 8, 27), "acc-old", "https://x", OLD);
        edgar.store("NVDA", 1045810, "10-Q", FILED, "acc-new", "https://x", NEW);

        // The answer Claude would give, written into the replay cache by hand.
        String quote = "The U.S. government restricted exports of our products to China in 2025.";
        String prompt = Prompts.extraction("NVDA", "10-Q", FILED, List.of(quote));
        Files.createDirectories(dir.resolve("llm-cache"));
        Files.writeString(dir.resolve("llm-cache").resolve(w.llm().key(prompt) + ".json"), """
                {"model": "test-model", "events": {"events": [
                  {"type": "export_controls", "severity": 4, "scope": "firm", "quote": "%s"},
                  {"type": "macro_demand", "severity": 5, "scope": "industry", "quote": "%s"},
                  {"type": "litigation", "severity": 5, "scope": "firm", "quote": "We face a lawsuit that threatens our business."}]}}"""
                .formatted(quote, quote));

        assertThat(w.signals().scoreAll()).isEqualTo(2);   // the old one is the baseline: no call
        assertThat(w.db().sql("SELECT COUNT(*) FROM events").query(Integer.class).single()).isEqualTo(3);
        assertThat(w.signals().quoteCounts()).isEqualTo("2 verified, 1 dropped");

        assertThat(w.signals().flags(List.of("NVDA"), FILED)).as("not on the day it is filed").isEmpty();
        Signals.Flag flag = w.signals().flags(List.of("NVDA"), FILED.plusDays(1)).get("NVDA");
        assertThat(flag.severity()).as("the industry-wide 5 and the invented quote's 5 are both dropped").isEqualTo(4);
        assertThat(flag.quote()).isEqualTo(quote);
        assertThat(flag.accession()).isEqualTo("acc-new");
    }

    @Test
    void aQuoteMustBeAnExactSubstringOfTheStoredSection() {
        String item1a = "Item 1A. Risk Factors We rely on \"third-party\" foundries to make our chips.";
        assertThat(Signals.verified("We rely on \u201cthird-party\u201d  foundries", item1a)).as("same text after normalizing").isTrue();
        assertThat(Signals.verified("We depend on third-party foundries", item1a)).as("paraphrased").isFalse();
        assertThat(Signals.verified("", item1a)).isFalse();
    }

    @Test
    void replayOnlyRefusesACacheMiss() {
        Llm llm = new Llm(dir.toString(), true, "test-model", "");
        assertThatThrownBy(() -> llm.extract("anything")).hasMessageContaining("replay only");
    }

    @Test
    void anIncompleteAnswerIsNeverCached() {
        Llm.Events none = new Llm.Events(List.of());
        assertThat(Llm.checked("end_turn", none)).isSameAs(none);
        assertThatThrownBy(() -> Llm.checked("max_tokens", none)).hasMessageContaining("not cached");
        assertThatThrownBy(() -> Llm.checked("refusal", none)).hasMessageContaining("not cached");
        assertThatThrownBy(() -> Llm.checked("end_turn", null)).hasMessageContaining("not cached");
    }

    @Test
    void theRequestBuildsWithItsJsonSchema() {
        // Builds the structured-output request without sending it: this runs the SDK's schema derivation.
        Llm llm = new Llm(dir.toString(), true, "claude-sonnet-5-5", "");
        assertThat(llm.params("Find the risks.").rawParams().model().asString()).isEqualTo("claude-sonnet-5-5");
    }
}
