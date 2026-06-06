package com.example;

public class Cycle {
    private final Cycle self = this;

    public void a() {
        b();
    }

    public void b() {
        self.a();
    }
}
