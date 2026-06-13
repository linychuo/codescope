package com.example;

public class CtorChild extends CtorParent {
    public CtorChild() { super(); System.out.println("CtorChild()"); }
    public CtorChild(int x) { super(x); System.out.println("CtorChild(int)"); }
}
