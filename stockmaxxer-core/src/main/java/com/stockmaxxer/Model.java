package com.stockmaxxer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The domain records. Money, weights and shares are exact BigDecimals throughout. */
public final class Model {

    private Model() {}

    /** PROPOSED → ACCEPTED → FILLED, or PROPOSED → IGNORED / EXPIRED. Only FILLED changes holdings. */
    public enum Status { PROPOSED, ACCEPTED, FILLED, IGNORED, EXPIRED }

    public record Portfolio(long id, String name, BigDecimal initialDeposit, BigDecimal cash,
                            String benchmark, String engine, LocalDate createdOn, boolean archived) {}

    /** One purchase: shares still held from it, and the price paid per share (it never changes). */
    public record Lot(String symbol, LocalDate acquiredOn, BigDecimal shares, BigDecimal price) {
        public BigDecimal cost() {
            return shares.multiply(price);
        }
    }

    /** A holding: the sum of its lots. costBasis is what was paid for the shares still held. */
    public record Holding(String symbol, BigDecimal shares, BigDecimal costBasis) {}

    /** A holding valued at the simulated day's close: market value, average cost basis, and its share of NAV. */
    public record Position(String symbol, BigDecimal shares, BigDecimal marketValue, BigDecimal costBasis,
                           BigDecimal weight) {}

    /** A portfolio with its holdings, valued at the simulated day's close. */
    public record PortfolioDetail(Portfolio portfolio, List<Position> holdings, BigDecimal nav, BigDecimal cashWeight,
                                  LocalDate navDate) {}

    public record Line(String symbol, BigDecimal priorWeight, BigDecimal targetWeight, int severity, String action) {}

    public record Decision(long id, long portfolioId, LocalDate asOf, String engine, Status status,
                           BigDecimal nav, LocalDate fillDate, List<Line> lines) {}

    /** Portfolio NAV next to the chosen benchmark, rebased so both start at the deposit. */
    public record EquityPoint(LocalDate date, BigDecimal nav, BigDecimal benchmark) {}

    public record EngineInfo(String id, String description) {}
}
