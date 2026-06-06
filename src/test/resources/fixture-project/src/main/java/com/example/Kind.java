package com.example;

/** Enum fixture: verifies the indexer tracks methods declared inside enums. */
public enum Kind {
    ALPHA, BETA, GAMMA;

    public String label() {
        return name().toLowerCase();
    }
}
