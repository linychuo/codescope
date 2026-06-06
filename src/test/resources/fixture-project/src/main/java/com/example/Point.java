package com.example;

/** Record fixture: verifies the indexer tracks methods declared inside records. */
public record Point(int x, int y) {
    public double distanceFromOrigin() {
        return Math.sqrt((double) x * x + (double) y * y);
    }
}
