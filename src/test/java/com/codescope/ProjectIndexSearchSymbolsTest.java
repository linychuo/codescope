package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Truncation contract of {@link ProjectIndex#searchSymbols}: when the
 * match count exceeds {@code limit}, the returned prefix must be the
 * first {@code limit} entries in the documented sort order
 * ({@code fqn}, then {@code signature}) — not an arbitrary slice of the
 * map's iteration order.
 */
class ProjectIndexSearchSymbolsTest {

    @Test
    void truncationKeepsSortedFirstMatches() {
        ProjectIndex idx = new ProjectIndex();
        List<String> allFqns = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String fqn = "com.example.C" + i + "#process" + i + "/1";
            allFqns.add(fqn);
            idx.recordSymbol(new ProjectIndex.Symbol("process" + i, "method",
                    fqn, "com.example.C" + i, "C" + i + ".java", 1, "java.lang.String"));
        }
        Collections.sort(allFqns);

        ProjectIndex.SymbolSearchResult res = idx.searchSymbols("process", null, 100);
        assertEquals(200, res.totalCount(), "total must count every match");
        assertEquals(100, res.matches().size(), "matches must be capped at limit");

        List<String> returned = res.matches().stream()
                .map(ProjectIndex.Symbol::fqn)
                .toList();
        assertEquals(allFqns.subList(0, 100), returned,
                "truncation must keep the first `limit` matches in sort order");
    }

    @Test
    void negativeLimitReturnsEmptyMatchesRatherThanThrowing() {
        // Defensive: a negative limit is a caller bug, but the old contract
        // returned an empty match list (with the full total) rather than
        // throwing. Keep that behavior.
        ProjectIndex idx = new ProjectIndex();
        idx.recordSymbol(new ProjectIndex.Symbol("process", "method",
                "com.example.C0#process/1", "com.example.C0", "C0.java", 1, "java.lang.String"));
        ProjectIndex.SymbolSearchResult res = idx.searchSymbols("process", null, -1);
        assertTrue(res.matches().isEmpty(),
                "negative limit should yield no matches, got: " + res.matches());
        assertEquals(1, res.totalCount(), "total must still count every match");
    }

    @Test
    void symbolSearchIsLocaleIndependent() {
        // String.toLowerCase() without a locale uses the default one; under
        // Turkish the dotted/dotless I makes "TITLE" and "title" fold to
        // different strings, so a case-insensitive search would miss.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            ProjectIndex idx = new ProjectIndex();
            idx.recordSymbol(new ProjectIndex.Symbol("TITLE", "class",
                    "com.x.TITLE", null, "TITLE.java", 1, null));
            ProjectIndex.SymbolSearchResult res = idx.searchSymbols("title", null, 10);
            assertEquals(1, res.totalCount(),
                    "search must be locale-independent (Turkish-I)");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void noTruncationWhenMatchesFitWithinLimit() {
        ProjectIndex idx = new ProjectIndex();
        for (int i = 0; i < 5; i++) {
            idx.recordSymbol(new ProjectIndex.Symbol("process" + i, "method",
                    "com.example.C" + i + "#process" + i + "/1",
                    "com.example.C" + i, "C" + i + ".java", 1, "java.lang.String"));
        }
        ProjectIndex.SymbolSearchResult res = idx.searchSymbols("process", null, 100);
        assertEquals(5, res.totalCount());
        assertEquals(5, res.matches().size());
    }
}
