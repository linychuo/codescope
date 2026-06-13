package com.example;
public class GenericUser {
    public void run() { String s = new GenericHost<String>().process("hi"); System.out.println(s); }
}
