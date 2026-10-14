package com.stockmaxxer.jobs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockmaxxer.Audit;
import com.stockmaxxer.Engines;
import com.stockmaxxer.Market;
import com.stockmaxxer.Model.Decision;
import com.stockmaxxer.Model.EquityPoint;
import com.stockmaxxer.Model.ForecastRange;
import com.stockmaxxer.Model.Line;
import com.stockmaxxer.Model.Portfolio;
import com.stockmaxxer.Model.PortfolioDetail;
import com.stockmaxxer.Portfolios;
import com.stockmaxxer.Signals;

/**
 * The report job, for demos: what happened today in every active portfolio, as plain text. It only reads,
 * through the same methods the API uses: the fills from the audit trail, the NAV against the benchmark, today's
 * recommendation with each trim's filing quote, and the forecast range.
 */
@Component
public class Report {

    private final Market market;
    private final Portfolios portfolios;
    private final Signals signals;

    public Report(Market market, Portfolios portfolios, Signals signals) {
        this.market = market;
        this.portfolios = portfolios;
        this.signals = signals;
    }

    public String today() {
        LocalDate today = market.today();
        StringBuilder out = new StringBuilder("\n======== " + today + " ========\n");
        // The filings that count from today: filed since the previous trading day.
        List<LocalDate> recent = market.tradingDays(today.minusDays(10), today);
        LocalDate previous = recent.size() > 1 ? recent.get(recent.size() - 2) : today;
        for (String filing : signals.arrived(previous, today)) {
            out.append("new filing: ").append(filing).append("\n");
        }
        for (Portfolio p : portfolios.list(false)) {
            out.append("\n").append(p.name()).append(" (").append(p.engine()).append(", ").append(p.benchmark()).append(")\n");
            List<Decision> decisions = portfolios.decisions(p.id());
            // Trades filled at today's open, from the FILLED audit row.
            for (Decision d : decisions) {
                if (today.equals(d.fillDate())) {
                    for (Audit.Entry e : portfolios.audit(p.id(), d.id())) {
                        if (e.kind().equals("FILLED")) {
                            out.append("  filled at today's open:");
                            for (JsonNode t : e.detail().path("trades")) {
                                out.append(" ").append(t.path("action").asText()).append(" ").append(t.path("shares").asText())
                                        .append(" ").append(t.path("symbol").asText()).append(" @ ").append(t.path("price").asText()).append(";");
                            }
                            out.append("\n");
                        }
                    }
                }
            }
            PortfolioDetail detail = portfolios.detail(p.id());
            List<EquityPoint> curve = portfolios.equity(p.id());
            BigDecimal benchmark = curve.isEmpty() ? detail.nav() : curve.getLast().benchmark();
            out.append("  NAV ").append(money(detail.nav())).append(", cash ").append(pct(detail.cashWeight()))
                    .append(" | ").append(p.benchmark()).append(" from the same start: ").append(money(benchmark)).append("\n");
            // Today's recommendation, if there is one.
            for (Decision d : decisions) {
                if (d.asOf().equals(today)) {
                    out.append("  today's recommendation (").append(d.status()).append("):\n");
                    for (Line l : d.lines()) {
                        if (l.symbol().equals(Engines.CASH) || l.targetWeight().signum() > 0 || l.priorWeight().signum() > 0) {
                            out.append("    %-6s %-4s %7s -> %7s".formatted(l.symbol(), l.action(), pct(l.priorWeight()), pct(l.targetWeight())));
                            if (l.quote() != null) {
                                out.append("  severity ").append(l.severity()).append(": \"").append(l.quote()).append("\"");
                            }
                            out.append("\n");
                        }
                    }
                }
            }
            ForecastRange f = portfolios.forecast(p.id());
            out.append("  past ").append(f.horizonDays()).append("-day range at today's holdings: ").append(money(f.p10()))
                    .append(" to ").append(money(f.p90())).append(" (median ").append(money(f.p50())).append("), ")
                    .append(f.note()).append("\n");
        }
        return out.toString();
    }

    private static String money(BigDecimal value) {
        return "$" + value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String pct(BigDecimal weight) {
        return weight.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }
}
