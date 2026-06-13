package com.example;

public class VarArgs {
    public String fmt(String fmt, Object... args) { return String.format(fmt, args); }
    public void use() { fmt("hi %s", "world"); fmt("hi %s %s", "a", "b"); }
}
