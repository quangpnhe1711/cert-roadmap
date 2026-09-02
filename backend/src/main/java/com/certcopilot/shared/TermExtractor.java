package com.certcopilot.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pulls technical terms out of course text.
 *
 * <p>Deterministic and shared, because two places need exactly the same notion
 * of "term": the glossary shown to the learner and the inverted index used for
 * cross-referencing. If they disagreed, a term could be explained but not
 * findable, or vice versa.
 *
 * <p>The stopword list exists because a naive capitalised-word regex happily
 * treats "This", "You" and "The" as technical vocabulary - every sentence start
 * looks like a proper noun.
 */
public final class TermExtractor {

    private static final Pattern CANDIDATE = Pattern.compile(
            "\b([A-Z][a-zA-Z]{2,}(?:\s+[A-Z][a-zA-Z]{2,}){0,2}|[A-Z]{2,6})\b");

    /** Words that begin sentences far more often than they name concepts. */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "this", "that", "these", "those", "there", "then", "they", "them",
            "you", "your", "yours", "our", "ours", "his", "her", "its",
            "and", "but", "for", "not", "with", "from", "into", "onto", "upon",
            "when", "where", "which", "while", "what", "who", "how", "why",
            "can", "could", "should", "would", "will", "shall", "may", "might", "must",
            "has", "have", "had", "was", "were", "are", "been", "being",
            "each", "every", "some", "any", "all", "both", "few", "more", "most",
            "such", "only", "own", "same", "than", "too", "very", "just",
            "also", "here", "now", "one", "two", "three", "first", "second", "third",
            "note", "example", "important", "summary", "overview", "chapter", "section",
            "page", "slide", "course", "exam", "question", "answer", "topic", "lesson");

    private TermExtractor() {
    }

    /** @param limit maximum number of distinct terms to return, or 0 for unlimited */
    public static List<String> extract(String text, int limit) {
        List<String> found = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return found;
        }
        var matcher = CANDIDATE.matcher(text);
        while (matcher.find()) {
            String term = matcher.group(1).strip();
            if (!isUseful(term) || found.contains(term)) {
                continue;
            }
            found.add(term);
            if (limit > 0 && found.size() >= limit) {
                break;
            }
        }
        return found;
    }

    static boolean isUseful(String term) {
        if (term.length() < 3) {
            return false;
        }
        String[] words = term.split("\s+");
        // A phrase is kept when at least one of its words is not a stopword;
        // a single word must not be a stopword at all.
        int meaningful = 0;
        for (String word : words) {
            if (!STOPWORDS.contains(word.toLowerCase(Locale.ROOT))) {
                meaningful++;
            }
        }
        return meaningful > 0 && !(words.length == 1
                && STOPWORDS.contains(term.toLowerCase(Locale.ROOT)));
    }
}
