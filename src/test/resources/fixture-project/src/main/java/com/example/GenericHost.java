package com.example;
public class GenericHost<T> {
    private T doIt(T input) { return input; }
    public T process(T input) { return doIt(input); }
}
