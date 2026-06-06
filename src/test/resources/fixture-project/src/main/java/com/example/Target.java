package com.example;

/** The leaf method that everything in the test fixture points at. */
public class Target {
    public void leaf() {
        System.out.println("leaf");
    }

    public void leafWithArg(int x) {
        System.out.println(x);
    }

    /** Two overloads with the same arity (1) but different parameter types, to test ambiguity. */
    public void process(int x) {
        System.out.println("int " + x);
    }

    public void process(String s) {
        System.out.println("str " + s);
    }
}

