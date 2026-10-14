package com.stockmaxxer;

import java.time.LocalDate;
import java.util.List;

/**
 * The prompt that turns a filing's new risk-factor sentences into typed events. It is a pure function of its
 * arguments: no clock, no run id and no as-of date, so the same filing always gives the same replay-cache key.
 */
public final class Prompts {

    /** Change this whenever the prompt text changes: it is part of every cache key. */
    public static final String VERSION = "v1";

    /** A closed list, so the same kind of risk always gets the same name. */
    static final List<String> EVENT_TYPES = List.of(
            "going_concern", "material_weakness", "restatement", "auditor_change", "litigation",
            "customer_concentration", "supplier_dependency", "contract_non_renewal", "guidance_change",
            "regulatory_action", "tariffs_trade", "export_controls", "china_exposure", "cybersecurity_incident",
            "labor_disruption", "supply_chain_disruption", "liquidity_covenant", "macro_demand", "competition", "other");

    /** The severity scale. It is a stated convention, not a fitted parameter. */
    static final String RUBRIC = """
            0 = no change, or industry-wide boilerplate only
            1 = firm-specific rewording, no new substantive risk
            2 = new risk added, generic, no named exposure
            3 = new firm-specific risk with a NAMED exposure (a customer, supplier, country or regulator named for the first time)
            4 = new firm-specific risk that is QUANTIFIED or MATERIAL (customer concentration disclosed, contract not renewed, guidance softened)
            5 = existential: going-concern doubt, material weakness, restatement, auditor departure, litigation that threatens operations""";

    private Prompts() {}

    public static String extraction(String ticker, String form, LocalDate filedOn, List<String> newSentences) {
        return """
                You read the Risk Factors section (Item 1A) of a company's SEC filing.
                Below are ONLY the sentences that are new since the company's previous %s.

                Company: %s
                Form: %s, filed %s

                New sentences:
                %s

                List the distinct new risks these sentences disclose, at most 10. For each risk give:
                - type: one of %s
                - severity: an integer 0-5 on this scale:
                %s
                - scope: "firm" if the risk is specific to this company, "industry" if it applies to most companies in its industry or the whole economy
                - quote: ONE sentence copied EXACTLY, character for character, from the new sentences above. Do not shorten, paraphrase or join sentences.

                If the sentences only reword existing risks, return an empty list.
                """.formatted(form, ticker, form, filedOn, String.join("\n", newSentences),
                String.join(", ", EVENT_TYPES), RUBRIC);
    }
}
