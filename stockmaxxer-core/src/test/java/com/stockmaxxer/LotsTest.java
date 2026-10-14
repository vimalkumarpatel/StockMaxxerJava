package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stockmaxxer.Model.Lot;

/** How a holding's lots change when it is resized: buy more, trim (FIFO), or sell out. */
class LotsTest {

    static final LocalDate D1 = LocalDate.of(2026, 2, 16);
    static final LocalDate D2 = D1.plusDays(1);
    static final LocalDate D3 = D1.plusDays(2);

    /** 10 shares bought at $100 on D1, then 5 more at $120 on D2. */
    static final List<Lot> TWO_LOTS = List.of(lot(D1, "10", "100"), lot(D2, "5", "120"));

    @Test
    void buyingMoreAtANewPriceAddsALotAndLeavesTheOldOneAsItWas() {
        assertThat(Portfolios.resize("NVDA", List.of(lot(D1, "10", "100")), d("15"), D2, d("120")))
                .containsExactly(lot(D1, "10", "100"), lot(D2, "5", "120"));
    }

    @Test
    void trimmingSellsFromTheOldestLotFirstAndNeverChangesALotsPrice() {
        // Sell 4 of 15: they all come out of the D1 lot.
        assertThat(Portfolios.resize("NVDA", TWO_LOTS, d("11"), D3, d("90")))
                .containsExactly(lot(D1, "6", "100"), lot(D2, "5", "120"));
        // Sell 12 of 15: the whole D1 lot, then 2 from the D2 lot.
        assertThat(Portfolios.resize("NVDA", TWO_LOTS, d("3"), D3, d("90")))
                .containsExactly(lot(D2, "3", "120"));
    }

    @Test
    void sellingOutRemovesEveryLot() {
        assertThat(Portfolios.resize("NVDA", TWO_LOTS, BigDecimal.ZERO, D3, d("90"))).isEmpty();
    }

    @Test
    void holdingTheSameSizeChangesNothing() {
        assertThat(Portfolios.resize("NVDA", TWO_LOTS, d("15"), D3, d("90"))).isEqualTo(TWO_LOTS);
    }

    private static Lot lot(LocalDate day, String shares, String price) {
        return new Lot("NVDA", day, d(shares), d(price));
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }
}
