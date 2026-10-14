package com.stockmaxxer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Daily bars from Yahoo's chart API (no key needed). Simplification: the prices are Yahoo's split-adjusted
 * values, frozen when downloaded. A split after the download would show up as a price jump. Dividends are
 * ignored, so every return is a price return, for the stocks and the benchmarks alike.
 */
@Component
public class Yahoo {

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    public record DailyBar(String symbol, LocalDate date, BigDecimal open, BigDecimal close) {}

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();

    /** The bars from {@code from} up to, but not including, {@code before}. */
    public List<DailyBar> daily(String symbol, LocalDate from, LocalDate before) {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                + "?interval=1d&period1=" + from.atStartOfDay(NEW_YORK).toEpochSecond()
                + "&period2=" + before.atStartOfDay(NEW_YORK).toEpochSecond();
        JsonNode result = get(url).path("chart").path("result").path(0);
        if (result.isMissingNode()) {
            throw new IllegalStateException("Yahoo has no data for " + symbol);
        }
        JsonNode times = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").path(0);
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            JsonNode open = quote.path("open").path(i);
            JsonNode close = quote.path("close").path(i);
            LocalDate date = Instant.ofEpochSecond(times.get(i).asLong()).atZone(NEW_YORK).toLocalDate();
            // Skip gaps, and anything on or after `before` (today's session may still be running).
            if (open.isNull() || close.isNull() || !date.isBefore(before)) {
                continue;
            }
            bars.add(new DailyBar(symbol, date, price(open.asDouble()), price(close.asDouble())));
        }
        return bars;
    }

    private JsonNode get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Mozilla/5.0").build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Yahoo returned " + response.statusCode() + " for " + url);
            }
            return json.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Yahoo request failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static BigDecimal price(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_EVEN);
    }
}
