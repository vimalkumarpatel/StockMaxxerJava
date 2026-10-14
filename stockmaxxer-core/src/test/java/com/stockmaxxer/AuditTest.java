package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockmaxxer.Model.Decision;
import com.stockmaxxer.Model.Portfolio;

class AuditTest {

    @TempDir
    Path dir;

    @Test
    void everyStepOfARecommendationIsRecordedInOrder() {
        TestDb.World w = TestDb.seeded(dir);
        Portfolio p = w.portfolios().create("Audited", null, "QQQ", null);
        Decision d = w.portfolios().propose(p.id());
        w.portfolios().accept(p.id(), d.id());
        TestDb.nightly(w);   // fills it, then proposes again
        TestDb.nightly(w);   // that one is never accepted, so it expires

        assertThat(w.portfolios().audit(p.id(), d.id())).extracting(Audit.Entry::kind)
                .containsExactly("PROPOSED", "ACCEPTED", "FILLED");
        JsonNode filled = w.portfolios().audit(p.id(), d.id()).get(2).detail();
        assertThat(filled.path("trades")).hasSize(Engines.TOP_N);
        assertThat(filled.path("cashAfter").asText()).isEqualTo(w.portfolios().get(p.id()).cash().toPlainString());

        Decision second = w.portfolios().decisions(p.id()).get(1);
        assertThat(w.portfolios().audit(p.id(), second.id())).extracting(Audit.Entry::kind)
                .containsExactly("PROPOSED", "EXPIRED");
    }

    @Test
    void theTrailIsAppendOnly() {
        TestDb.World w = TestDb.seeded(dir);
        Portfolio p = w.portfolios().create("Locked", null, null, null);
        w.portfolios().propose(p.id());
        assertThatThrownBy(() -> w.db().sql("UPDATE audit SET kind = 'ACCEPTED'").update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> w.db().sql("DELETE FROM audit").update()).hasMessageContaining("append-only");
    }

    @Test
    void replayingAProposalGivesTheWeightsItRecorded() {
        TestDb.World w = TestDb.seeded(dir);
        Portfolio p = w.portfolios().create("Replay", null, "DIA", null);
        Decision d = w.portfolios().propose(p.id());
        TestDb.nextDay(w);   // the world moves on; the proposal's inputs are all point-in-time

        JsonNode recorded = w.portfolios().audit(p.id(), d.id()).getFirst().detail();
        List<String> universe = Market.UNIVERSES.get("DIA");
        Map<String, BigDecimal> again = Engines.target(p.engine(), d.asOf(), w.market(), universe,
                w.signals().flags(universe, d.asOf())).weights();
        assertThat(recorded.path("targetWeights").size()).isEqualTo(again.size());
        again.forEach((symbol, weight) ->
                assertThat(new BigDecimal(recorded.path("targetWeights").path(symbol).asText())).isEqualByComparingTo(weight));
    }
}
