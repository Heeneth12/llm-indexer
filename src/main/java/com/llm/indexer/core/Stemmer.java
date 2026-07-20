package com.llm.indexer.core;

/**
 * Classic Porter (1980) stemming algorithm, reimplemented from the published
 * rule tables -- no external dependency. Collapses inflected forms to a
 * common root (services/servicing/serviced -> servic) so tier-2 full-text
 * matching isn't limited to the exact suffixes someone thought to add to
 * Synonyms.java. Applied symmetrically: GraphStore stems tokens going into
 * the FTS index, QueryService stems query tokens the same way.
 */
public class Stemmer {

    public static String stem(String input) {
        String w = input == null ? "" : input.toLowerCase();
        if (w.length() <= 2) return w;

        w = step1a(w);
        w = step1b(w);
        w = step1c(w);
        w = step2(w);
        w = step3(w);
        w = step4(w);
        w = step5a(w);
        w = step5b(w);
        return w;
    }

    private static boolean isConsonant(String w, int i) {
        char c = w.charAt(i);
        switch (c) {
            case 'a': case 'e': case 'i': case 'o': case 'u':
                return false;
            case 'y':
                return i == 0 || !isConsonant(w, i - 1);
            default:
                return true;
        }
    }

    /** m in Porter's [C](VC){m}[V] pattern: number of VC groups in the stem. */
    private static int measure(String stem) {
        int n = stem.length();
        int i = 0;
        while (i < n && isConsonant(stem, i)) i++;
        int m = 0;
        while (i < n) {
            while (i < n && !isConsonant(stem, i)) i++;
            if (i >= n) break;
            while (i < n && isConsonant(stem, i)) i++;
            m++;
        }
        return m;
    }

    private static boolean containsVowel(String stem) {
        for (int i = 0; i < stem.length(); i++) if (!isConsonant(stem, i)) return true;
        return false;
    }

    private static boolean endsWithDoubleConsonant(String stem) {
        int n = stem.length();
        if (n < 2) return false;
        return stem.charAt(n - 1) == stem.charAt(n - 2) && isConsonant(stem, n - 1);
    }

    /** *o: ends consonant-vowel-consonant, last consonant not W, X or Y. */
    private static boolean endsWithCvc(String stem) {
        int n = stem.length();
        if (n < 3) return false;
        if (!isConsonant(stem, n - 3) || isConsonant(stem, n - 2) || !isConsonant(stem, n - 1)) return false;
        char last = stem.charAt(n - 1);
        return last != 'w' && last != 'x' && last != 'y';
    }

    private static String step1a(String w) {
        if (w.endsWith("sses")) return w.substring(0, w.length() - 2);
        if (w.endsWith("ies")) return w.substring(0, w.length() - 2);
        if (w.endsWith("ss")) return w;
        if (w.endsWith("s")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static String step1b(String w) {
        if (w.endsWith("eed")) {
            String stem = w.substring(0, w.length() - 3);
            return measure(stem) > 0 ? stem + "ee" : w;
        }

        String stem;
        if (w.endsWith("ed") && containsVowel(stem = w.substring(0, w.length() - 2))) {
            // fallthrough with stem set
        } else if (w.endsWith("ing") && containsVowel(stem = w.substring(0, w.length() - 3))) {
            // fallthrough with stem set
        } else {
            return w;
        }

        if (stem.endsWith("at") || stem.endsWith("bl") || stem.endsWith("iz")) return stem + "e";
        if (endsWithDoubleConsonant(stem) && !stem.endsWith("l") && !stem.endsWith("s") && !stem.endsWith("z"))
            return stem.substring(0, stem.length() - 1);
        if (measure(stem) == 1 && endsWithCvc(stem)) return stem + "e";
        return stem;
    }

    private static String step1c(String w) {
        if (w.endsWith("y")) {
            String stem = w.substring(0, w.length() - 1);
            if (containsVowel(stem)) return stem + "i";
        }
        return w;
    }

    private static final String[][] STEP2_RULES = {
        {"ational", "ate"}, {"tional", "tion"}, {"enci", "ence"}, {"anci", "ance"},
        {"izer", "ize"}, {"abli", "able"}, {"alli", "al"}, {"entli", "ent"},
        {"eli", "e"}, {"ousli", "ous"}, {"ization", "ize"}, {"ation", "ate"},
        {"ator", "ate"}, {"alism", "al"}, {"iveness", "ive"}, {"fulness", "ful"},
        {"ousness", "ous"}, {"aliti", "al"}, {"iviti", "ive"}, {"biliti", "ble"}
    };

    private static String step2(String w) {
        for (String[] rule : STEP2_RULES) {
            if (w.endsWith(rule[0])) {
                String stem = w.substring(0, w.length() - rule[0].length());
                return measure(stem) > 0 ? stem + rule[1] : w;
            }
        }
        return w;
    }

    private static final String[][] STEP3_RULES = {
        {"icate", "ic"}, {"ative", ""}, {"alize", "al"}, {"iciti", "ic"},
        {"ical", "ic"}, {"ful", ""}, {"ness", ""}
    };

    private static String step3(String w) {
        for (String[] rule : STEP3_RULES) {
            if (w.endsWith(rule[0])) {
                String stem = w.substring(0, w.length() - rule[0].length());
                return measure(stem) > 0 ? stem + rule[1] : w;
            }
        }
        return w;
    }

    private static final String[] STEP4_SUFFIXES = {
        "ement", "ance", "ence", "able", "ible", "ment",
        "ant", "ism", "ate", "iti", "ous", "ive", "ize", "ent",
        "al", "er", "ic", "ou"
    };

    private static String step4(String w) {
        for (String suf : STEP4_SUFFIXES) {
            if (w.endsWith(suf)) {
                String stem = w.substring(0, w.length() - suf.length());
                return measure(stem) > 1 ? stem : w;
            }
        }
        if (w.endsWith("ion")) {
            String stem = w.substring(0, w.length() - 3);
            if (measure(stem) > 1 && !stem.isEmpty() && (stem.endsWith("s") || stem.endsWith("t"))) return stem;
        }
        return w;
    }

    private static String step5a(String w) {
        if (w.endsWith("e")) {
            String stem = w.substring(0, w.length() - 1);
            int m = measure(stem);
            if (m > 1 || (m == 1 && !endsWithCvc(stem))) return stem;
        }
        return w;
    }

    private static String step5b(String w) {
        if (w.endsWith("ll") && measure(w) > 1) return w.substring(0, w.length() - 1);
        return w;
    }
}
