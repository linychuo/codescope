package com.example;

public class Top {
    private final Mid mid = new Mid();

    public void entryPoint() {
        mid.callsLeaf();
    }
}
