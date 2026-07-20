package com.llm.indexer.query;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small curated seed of domain-synonym clusters, expanded into extra search
 * terms before querying the full-text index. Not exhaustive, not ML-based --
 * this is the cheap fix for concept mismatches like "email" not being a
 * substring of "GmailService" (tokens: gmail, service). Extend the list below
 * as real misses show up; a hybrid embedding search is the eventual fuller fix.
 */
public class Synonyms {

    private static final List<Set<String>> CLUSTERS = List.of(
            Set.of("email", "mail", "gmail", "smtp", "notification", "notify"),
            Set.of("user", "customer", "account", "member"),
            Set.of("delete", "remove", "destroy", "purge"),
            Set.of("create", "add", "new", "insert", "save"),
            Set.of("update", "edit", "modify", "patch", "change"),
            Set.of("get", "fetch", "retrieve", "find", "read", "load"),
            Set.of("config", "configuration", "settings", "properties", "options"),
            Set.of("auth", "authentication", "login", "security", "credential"),
            Set.of("db", "database", "repository", "dao", "store", "persistence"),
            Set.of("error", "exception", "failure", "fault")
    );

    private static final Map<String, Set<String>> INDEX = new HashMap<>();

    static {
        for (Set<String> cluster : CLUSTERS)
            for (String word : cluster)
                INDEX.put(word, cluster);
    }

    /** Returns the word plus any known synonyms for it, all lowercase. */
    public static Set<String> expand(String word) {
        String w = word.toLowerCase();
        Set<String> known = INDEX.get(w);
        if (known == null) return Set.of(w);
        Set<String> result = new HashSet<>(known);
        result.add(w);
        return result;
    }
}
