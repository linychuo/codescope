package com.example;

public class Mid {
    private final Target target = new Target();

    public void callsLeaf() {
        target.leaf();
    }

    public void callsLeafWithArg() {
        target.leafWithArg(42);
    }

    public void unrelated() {
        System.out.println("unrelated");
    }
}
