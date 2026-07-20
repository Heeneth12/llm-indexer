package com.llm.indexer.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits an identifier (camelCase, PascalCase, snake_case, dotted method names
 * like "OrderService.createOrder") -- or a free-text query phrase like "email
 * notification service" -- into lowercase word tokens, e.g. "GmailService" ->
 * ["gmail", "service"]. Backs the full-text index in GraphStore so a search
 * for "gmail" or "service" matches even though neither is a contiguous
 * substring of arbitrary identifiers, and lets QueryService split a multi-word
 * search term into separate matchable tokens instead of one long phrase.
 */
public class Tokenizer {

    private static final Pattern BOUNDARY = Pattern.compile(
            "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_\\-.\\s]+");

    public static List<String> tokens(String identifier) {
        List<String> out = new ArrayList<>();
        if (identifier == null || identifier.isBlank()) return out;
        for (String part : BOUNDARY.split(identifier)) {
            String t = part.trim().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Space-joined tokens, suitable for an FTS5 indexed column. */
    public static String tokenize(String identifier) {
        return String.join(" ", tokens(identifier));
    }
}
