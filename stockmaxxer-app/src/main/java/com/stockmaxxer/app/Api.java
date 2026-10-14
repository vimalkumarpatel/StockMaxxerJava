package com.stockmaxxer.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.stockmaxxer.Engines;
import com.stockmaxxer.Market;
import com.stockmaxxer.Model;
import com.stockmaxxer.Model.Decision;
import com.stockmaxxer.Model.EngineInfo;
import com.stockmaxxer.Model.EquityPoint;
import com.stockmaxxer.Model.Portfolio;
import com.stockmaxxer.Model.PortfolioDetail;
import com.stockmaxxer.Portfolios;

/** The JSON API. No auth: this is a local, personal app. */
@RestController
@RequestMapping("/api/v1")
public class Api {

    private final Portfolios portfolios;
    private final Market market;

    public Api(Portfolios portfolios, Market market) {
        this.portfolios = portfolios;
        this.market = market;
    }

    /** Only the name is required: the deposit defaults to 1000.00, the benchmark to SPY, the engine to quant_llm. */
    public record CreatePortfolio(String name, BigDecimal initialDeposit, String benchmark, String engine) {}

    @PostMapping("/portfolios")
    @ResponseStatus(HttpStatus.CREATED)
    public Portfolio create(@RequestBody CreatePortfolio body) {
        return portfolios.create(body.name(), body.initialDeposit(), body.benchmark(), body.engine());
    }

    @GetMapping("/portfolios")
    public List<Portfolio> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return portfolios.list(includeArchived);
    }

    @GetMapping("/portfolios/{id}")
    public PortfolioDetail get(@PathVariable long id) {
        return portfolios.detail(id);
    }

    /** Archives (a soft delete): the history is kept and the portfolio can be restored. */
    @DeleteMapping("/portfolios/{id}")
    public Portfolio archive(@PathVariable long id) {
        return portfolios.archive(id);
    }

    @PostMapping("/portfolios/{id}/restore")
    public Portfolio restore(@PathVariable long id) {
        return portfolios.restore(id);
    }

    /** Generates today's recommendation now, instead of waiting for the nightly propose job. */
    @PostMapping("/portfolios/{id}/recommendations")
    @ResponseStatus(HttpStatus.CREATED)
    public Decision propose(@PathVariable long id) {
        return portfolios.propose(id);
    }

    @GetMapping("/portfolios/{id}/decisions")
    public List<Decision> decisions(@PathVariable long id) {
        return portfolios.decisions(id);
    }

    @PostMapping("/portfolios/{id}/decisions/{decisionId}/accept")
    public Decision accept(@PathVariable long id, @PathVariable long decisionId) {
        return portfolios.accept(id, decisionId);
    }

    @PostMapping("/portfolios/{id}/decisions/{decisionId}/ignore")
    public Decision ignore(@PathVariable long id, @PathVariable long decisionId) {
        return portfolios.ignore(id, decisionId);
    }

    @GetMapping("/portfolios/{id}/equity")
    public List<EquityPoint> equity(@PathVariable long id) {
        return portfolios.equity(id);
    }

    /** The range the NAV moved over past 63-day windows at today's weights: historical dispersion, not a prediction. */
    @GetMapping("/portfolios/{id}/forecast")
    public Model.ForecastRange forecast(@PathVariable long id) {
        return portfolios.forecast(id);
    }

    @GetMapping("/benchmarks")
    public List<String> benchmarks() {
        return Market.BENCHMARKS;
    }

    @GetMapping("/engines")
    public List<EngineInfo> engines() {
        return Engines.ALL;
    }

    @GetMapping("/clock")
    public Map<String, LocalDate> clock() {
        return Map.of("today", market.today());
    }

    // Simplification: plain Java exceptions carry the HTTP meaning, so the core module needs no web dependency.

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, e);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
