package com.example;

/** The leaf method that everything in the test fixture points at. */
public class Target {
    public void leaf() {
        System.out.println("leaf");
    }

    public void leafWithArg(int x) {
        System.out.println(x);
    }
}
