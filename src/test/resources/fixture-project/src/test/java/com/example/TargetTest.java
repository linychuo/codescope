package com.example;

import org.junit.jupiter.api.Test;

public class TargetTest {
    @Test
    public void shouldCallLeaf() {
        new Target().leaf();
    }
}
