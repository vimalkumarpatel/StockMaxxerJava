package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filing text: normalizing, finding Item 1A, and the new-sentence diff. */
class FilingsTest {

    @TempDir
    Path dir;

    static final String HTML_10K = """
            <html><body>
            <p>Table of Contents</p><p>Item&#160;1A. Risk Factors 12</p><p>Item 1B. Unresolved Staff Comments 30</p>
            <p>Item 1. Business. We design chips. For more, see Item 1A below.</p>
            <h2>ITEM 1A.  RISK&nbsp;FACTORS</h2>
            <p>Our business depends on a small number of customers.</p>
            <p>We rely on “third-party” foundries.</p>
            <h2>Item 1B. Unresolved Staff Comments</h2><p>None.</p>
            </body></html>""";

    @Test
    void normalizeMakesOneLineOfPlainText() {
        assertThat(Filings.normalize("<p>A  b</p>\n<p>“C’s”</p>")).isEqualTo("A b \"C's\"");
    }

    @Test
    void item1aIsTheRealSectionNotTheContentsOrAReference() {
        String section = Filings.item1a(Filings.normalize(HTML_10K), "10-K");
        assertThat(section).startsWith("ITEM 1A. RISK FACTORS")
                .contains("small number of customers", "\"third-party\" foundries")
                .doesNotContain("Unresolved");
    }

    @Test
    void newSentencesAreTheOnesMostlyMissingFromTheOldText() {
        String old = "We rely on third-party foundries to make our chips. Demand for our products can change quickly.";
        String now = "We rely on third-party foundries to make all of our chips. "
                + "The U.S. government restricted exports of our products to China in 2025. Short one.";
        assertThat(Filings.newSentences(old, now))
                .containsExactly("The U.S. government restricted exports of our products to China in 2025.");
    }

    @Test
    void storeKeepsTheWholeTextAndTheSection() {
        TestDb.World w = TestDb.seeded(dir);
        Edgar edgar = new Edgar(w.db(), "", dir.toString());
        edgar.store("NVDA", 1045810, "10-K", LocalDate.of(2025, 2, 26), "0001045810-25-000023", "https://x", HTML_10K);
        edgar.store("NVDA", 1045810, "10-K", LocalDate.of(2025, 2, 26), "0001045810-25-000023", "https://x", HTML_10K);

        assertThat(w.db().sql("SELECT COUNT(*) FROM filings").query(Integer.class).single()).isEqualTo(1);
        String text = w.db().sql("SELECT text FROM filings").query(String.class).single();
        String item1a = w.db().sql("SELECT item1a FROM filings").query(String.class).single();
        assertThat(text).contains("We design chips", "Unresolved Staff Comments");
        assertThat(text).contains(item1a);
    }
}
