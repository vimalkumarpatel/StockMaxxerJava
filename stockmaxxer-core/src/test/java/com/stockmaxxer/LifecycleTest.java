package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.stockmaxxer.Model.Decision;
import com.stockmaxxer.Model.EquityPoint;
import com.stockmaxxer.Model.Line;
import com.stockmaxxer.Model.Portfolio;
import com.stockmaxxer.Model.PortfolioDetail;
import com.stockmaxxer.Model.Position;
import com.stockmaxxer.Model.Status;

class LifecycleTest {

    @TempDir
    Path dir;

    TestDb.World world;
    Portfolios portfolios;
    Market market;

    @BeforeEach
    void setUp() {
        world = TestDb.seeded(dir);
        portfolios = world.portfolios();
        market = world.market();
    }

    @Test
    void createUsesTheDefaultsAndValidatesInput() {
        Portfolio p = portfolios.create("Default", null, null, null);
        assertThat(p.initialDeposit()).isEqualByComparingTo("1000.00");
        assertThat(p.cash()).isEqualByComparingTo("1000.00");
        assertThat(p.benchmark()).isEqualTo("SPY");
        assertThat(p.engine()).isEqualTo(Engines.QUANT_LLM);

        assertThatThrownBy(() -> portfolios.create(" ", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> portfolios.create("x", BigDecimal.ZERO, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> portfolios.create("x", new BigDecimal("10.001"), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> portfolios.create("x", null, "XYZ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> portfolios.create("x", null, null, "magic")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAcceptedRecommendationFillsAtTheNextOpen() {
        Portfolio p = portfolios.create("Accept", new BigDecimal("2500.00"), "QQQ", Engines.QUANT_ONLY);
        Decision d = portfolios.propose(p.id());
        assertThat(d.status()).isEqualTo(Status.PROPOSED);
        assertThat(d.lines()).filteredOn(l -> !l.symbol().equals(Engines.CASH)).extracting(Line::action).containsOnly("BUY");
        assertThat(Engines.sum(d.lines().stream().map(Line::targetWeight).toList())).isEqualByComparingTo("1");

        portfolios.accept(p.id(), d.id());
        assertThat(portfolios.detail(p.id()).holdings()).as("accepting does not trade by itself").isEmpty();

        TestDb.nextDay(world);
        assertThat(portfolios.settleAll()).isEqualTo(1);
        LocalDate fillDay = market.today();
        Decision filled = portfolios.decision(d.id());
        assertThat(filled.status()).isEqualTo(Status.FILLED);
        assertThat(filled.fillDate()).isEqualTo(fillDay);

        PortfolioDetail after = portfolios.detail(p.id());
        assertThat(after.holdings()).hasSize(Engines.TOP_N);
        assertThat(after.portfolio().cash()).isNotNegative();
        BigDecimal valueAtOpen = after.portfolio().cash();
        for (Position h : after.holdings()) {
            valueAtOpen = valueAtOpen.add(h.shares().multiply(market.bar(h.symbol(), fillDay).open()));
        }
        assertThat(valueAtOpen).as("no costs: the value at the fill equals the deposit").isEqualByComparingTo("2500.00");
    }

    @Test
    void holdingsShowMarketValueAverageCostBasisAndShareOfNav() {
        Portfolio p = portfolios.create("Basis", null, null, Engines.QUANT_ONLY);
        portfolios.accept(p.id(), portfolios.propose(p.id()).id());
        TestDb.nextDay(world);
        portfolios.settleAll();
        LocalDate firstFill = market.today();

        PortfolioDetail afterFirst = portfolios.detail(p.id());
        Map<String, Position> first = afterFirst.holdings().stream()
                .collect(Collectors.toMap(Position::symbol, Function.identity()));
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalWeight = afterFirst.cashWeight();
        for (Position h : first.values()) {
            assertThat(h.marketValue()).isEqualByComparingTo(h.shares().multiply(market.bar(h.symbol(), firstFill).close()));
            assertThat(h.costBasis()).as("first fill: basis is what was paid at the open")
                    .isEqualByComparingTo(h.shares().multiply(market.bar(h.symbol(), firstFill).open()));
            totalCost = totalCost.add(h.costBasis());
            totalWeight = totalWeight.add(h.weight());
        }
        assertThat(totalCost.add(afterFirst.portfolio().cash())).isEqualByComparingTo("1000.00");
        assertThat(totalWeight).isCloseTo(BigDecimal.ONE, org.assertj.core.api.Assertions.within(new BigDecimal("0.00001")));

        // A second accepted recommendation rebalances. Every name held one lot from the first fill.
        portfolios.accept(p.id(), portfolios.propose(p.id()).id());
        TestDb.nextDay(world);
        portfolios.settleAll();
        LocalDate secondFill = market.today();
        for (Position h : portfolios.detail(p.id()).holdings()) {
            Position before = first.get(h.symbol());
            BigDecimal open = market.bar(h.symbol(), secondFill).open();
            BigDecimal expected = before == null
                    ? h.shares().multiply(open)                                                   // a new name: one new lot
                    : h.shares().compareTo(before.shares()) >= 0
                            ? before.costBasis().add(h.shares().subtract(before.shares()).multiply(open))   // bought more: a second lot
                            : h.shares().multiply(market.bar(h.symbol(), firstFill).open());          // trimmed: what's left of the first lot
            assertThat(h.costBasis()).as("cost basis of %s", h.symbol()).isEqualByComparingTo(expected);
        }
    }

    @Test
    void everyLineHasAShortPlainLanguageExplanation() {
        Portfolio p = portfolios.create("Explain", null, null, null);   // quant_llm
        portfolios.accept(p.id(), portfolios.propose(p.id()).id());
        TestDb.nightly(world);                                          // fills, then proposes a rebalance
        TestDb.nightly(world);
        for (Decision d : portfolios.decisions(p.id())) {
            for (Line line : d.lines()) {
                String[] sentences = line.explanation().split("(?<=\\.)\\s+");
                assertThat(sentences.length).as(line.explanation()).isBetween(2, 3);
                for (String sentence : sentences) {
                    // ASD-STE100: at most 25 words in a descriptive sentence.
                    assertThat(sentence.split("\\s+").length).as(sentence).isLessThanOrEqualTo(25);
                }
                assertThat(line.explanation()).as("no contractions").doesNotContain("'");
            }
            assertThat(d.lines()).filteredOn(l -> l.symbol().equals(Engines.CASH))
                    .singleElement().extracting(Line::explanation).asString().startsWith("Cash is the money");
        }
    }

    @Test
    void anIgnoredRecommendationLeavesThePortfolioUnchanged() {
        Portfolio p = portfolios.create("Ignore", null, null, null);
        Decision d = portfolios.propose(p.id());
        portfolios.ignore(p.id(), d.id());
        TestDb.nextDay(world);
        portfolios.settleAll();

        assertThat(portfolios.decision(d.id()).status()).isEqualTo(Status.IGNORED);
        assertThat(portfolios.detail(p.id()).holdings()).isEmpty();
        assertThat(portfolios.get(p.id()).cash()).isEqualByComparingTo("1000.00");
    }

    @Test
    void anUnacceptedRecommendationExpiresAndCanNoLongerBeAccepted() {
        Portfolio p = portfolios.create("Expire", null, null, null);
        Decision d = portfolios.propose(p.id());
        TestDb.nextDay(world);

        assertThatThrownBy(() -> portfolios.accept(p.id(), d.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
        assertThat(portfolios.expireAll()).isEqualTo(1);
        assertThat(portfolios.decision(d.id()).status()).isEqualTo(Status.EXPIRED);
        assertThat(portfolios.detail(p.id()).holdings()).isEmpty();
    }

    @Test
    void oneRecommendationPerDayAndOneDecisionPerRecommendation() {
        Portfolio p = portfolios.create("Once", null, null, null);
        Decision d = portfolios.propose(p.id());
        assertThatThrownBy(() -> portfolios.propose(p.id())).isInstanceOf(IllegalStateException.class);
        portfolios.accept(p.id(), d.id());
        assertThatThrownBy(() -> portfolios.ignore(p.id(), d.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void archivedPortfoliosAreSkippedUntilRestored() {
        Portfolio kept = portfolios.create("Kept", null, null, null);
        Portfolio archived = portfolios.create("Archived", null, null, null);
        portfolios.archive(archived.id());

        assertThat(portfolios.list(false)).extracting(Portfolio::id).containsExactly(kept.id());
        assertThat(portfolios.proposeAll()).isEqualTo(1);
        assertThatThrownBy(() -> portfolios.propose(archived.id())).isInstanceOf(IllegalStateException.class);

        portfolios.restore(archived.id());
        assertThat(portfolios.proposeAll()).isEqualTo(1);
        assertThat(portfolios.decisions(archived.id())).hasSize(1);
    }

    @Test
    void theDemoLoopFillsEachDaysRecommendationTheNextDay() {
        Portfolio llm = portfolios.create("Demo LLM", null, "SPY", Engines.QUANT_LLM);
        Portfolio only = portfolios.create("Demo quant", null, "SPY", Engines.QUANT_ONLY);
        portfolios.proposeAll();
        assertThat(portfolios.acceptAll()).isEqualTo(2);
        for (int day = 0; day < 3; day++) {
            TestDb.nightly(world);   // fills yesterday's accepted recommendation, proposes today's
            assertThat(portfolios.acceptAll()).isEqualTo(2);
        }
        for (Portfolio p : List.of(llm, only)) {
            assertThat(portfolios.decisions(p.id())).extracting(Decision::status)
                    .containsExactly(Status.ACCEPTED, Status.FILLED, Status.FILLED, Status.FILLED);
        }
    }

    @Test
    void theNightlyRunMarksAnEquityCurveAgainstTheChosenBenchmark() {
        Portfolio p = portfolios.create("Curve", null, "DIA", null);
        portfolios.accept(p.id(), portfolios.propose(p.id()).id());
        TestDb.nightly(world);   // fills at the open, marks at the close, proposes again
        TestDb.nightly(world);   // that proposal was never accepted, so it expires

        List<EquityPoint> curve = portfolios.equity(p.id());
        assertThat(curve).hasSize(3);
        assertThat(curve.getFirst().nav()).isEqualByComparingTo("1000.00");
        assertThat(curve.getFirst().benchmark()).isEqualByComparingTo("1000.00");
        assertThat(portfolios.decisions(p.id())).extracting(Decision::status)
                .containsExactly(Status.PROPOSED, Status.EXPIRED, Status.FILLED);
    }
}
