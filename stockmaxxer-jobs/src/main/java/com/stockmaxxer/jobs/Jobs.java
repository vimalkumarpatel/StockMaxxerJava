package com.stockmaxxer.jobs;

import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.stockmaxxer.Backtest;
import com.stockmaxxer.Edgar;
import com.stockmaxxer.Evals;
import com.stockmaxxer.Market;
import com.stockmaxxer.Portfolios;
import com.stockmaxxer.Signals;

/**
 * Every subsystem of the nightly pipeline as an independently runnable job:
 * {@code java -jar stockmaxxer-jobs.jar --job=<name>}. Jobs share nothing in memory, only the database,
 * and each one is safe to re-run. A failure throws, so the JVM exits non-zero and the script stops.
 * Simplification: re-runs are safe because of the SQL itself (INSERT OR IGNORE, status filters, one
 * decision per portfolio per day) rather than watermark tables recording what each job has done.
 */
@SpringBootApplication(scanBasePackages = "com.stockmaxxer")
public class Jobs implements ApplicationRunner {

    static final String JOBS = "seed|fetch|filings|signals|advance|prices|expire|settle|mark|propose|backtest|evals";

    private final Market market;
    private final Portfolios portfolios;
    private final Edgar edgar;
    private final Signals signals;
    private final Backtest backtest;
    private final Evals evals;

    public Jobs(Market market, Portfolios portfolios, Edgar edgar, Signals signals, Backtest backtest, Evals evals) {
        this.market = market;
        this.portfolios = portfolios;
        this.edgar = edgar;
        this.signals = signals;
        this.backtest = backtest;
        this.evals = evals;
    }

    public static void main(String[] args) {
        // sqlite-jdbc won't create a missing folder, so make sure the database file's folder exists.
        new File(System.getenv().getOrDefault("SM_DB_PATH", "data/stockmaxxer.sqlite"))
                .getAbsoluteFile().getParentFile().mkdirs();
        SpringApplication app = new SpringApplication(Jobs.class);
        app.setDefaultProperties(Map.of("logging.level.root", "WARN"));   // a job prints one result line
        System.exit(SpringApplication.exit(app.run(args)));
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> job = args.getOptionValues("job");
        if (job == null || job.size() != 1) {
            throw new IllegalArgumentException("usage: --job=" + JOBS);
        }
        String result = switch (job.getFirst()) {
            case "seed" -> {
                market.seed();
                yield "seeded; the simulated day is " + market.today();
            }
            case "fetch" -> "downloaded " + market.fetch() + " new bar(s)";
            case "filings" -> "stored " + edgar.download() + " new filing(s)";
            case "signals" -> "scored " + signals.scoreAll() + " filing(s); quotes so far: " + signals.quoteCounts();
            case "advance" -> "the simulated day is now " + market.advance();
            case "prices" -> "revealed " + market.reveal(market.today()) + " bar(s) for " + market.today();
            case "expire" -> "expired " + portfolios.expireAll() + " unaccepted recommendation(s)";
            case "settle" -> "filled " + portfolios.settleAll() + " accepted recommendation(s) at the "
                    + market.today() + " open";
            case "mark" -> "marked " + portfolios.markAll() + " portfolio(s) at the " + market.today() + " close";
            case "propose" -> "proposed " + portfolios.proposeAll() + " recommendation(s) for " + market.today();
            case "backtest" -> backtest(option(args, "benchmark", "SPY"), LocalDate.parse(option(args, "from", "2026-01-02")));
            case "evals" -> "\n" + String.join("\n", evals.report());
            default -> throw new IllegalArgumentException("unknown job '" + job.getFirst() + "'; expected " + JOBS);
        };
        System.out.println("[" + job.getFirst() + "] " + result);
    }

    /** The backtest as a table: one line per leg. */
    private String backtest(String benchmark, LocalDate from) {
        StringBuilder out = new StringBuilder(benchmark + " universe, " + from + " to " + market.today() + "\n");
        out.append("%-24s %10s %9s %13s %9s%n".formatted("", "end NAV", "return", "max drawdown", "avg cash"));
        for (Backtest.Result r : backtest.run(benchmark, from)) {
            out.append("%-24s %10s %+8.2f%% %12.2f%% %8.2f%%%n".formatted(r.name(), r.endNav(),
                    r.totalReturn().movePointRight(2), r.maxDrawdown().movePointRight(2), r.averageCash().movePointRight(2)));
        }
        return out.toString();
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.getFirst();
    }
}
