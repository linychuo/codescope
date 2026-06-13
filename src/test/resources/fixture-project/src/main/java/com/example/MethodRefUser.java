package com.example;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Verifies the indexer records method references (Foo::bar, Foo::new) as
 * call edges. Uses its OWN methods (aRef, bRef, cRef, dRef) as targets so
 * other tests that trace Target#leaf etc. are unaffected by these
 * deliberately added references.
 */
public class MethodRefUser {
    public void aRef() { System.out.println("aRef"); }
    public void bRef() { System.out.println("bRef"); }
    public void cRef() { System.out.println("cRef"); }
    public void dRef() { System.out.println("dRef"); }
    public void eRef() { System.out.println("eRef"); }

    // instance method reference (field)
    public void useInstanceRef() {
        Runnable r = this::aRef;
        r.run();
    }

    // instance method reference (local variable)
    public void useInstanceRefLocal() {
        MethodRefUser u = new MethodRefUser();
        Runnable r = u::bRef;
        r.run();
    }

    // static method reference
    public void useStaticRef() {
        Comparator<String> c = String::compareTo;
        Runnable r = MethodRefUser::cRef;
        r.run();
    }

    // constructor reference
    public void useCtorRef() {
        Supplier<MethodRefUser> s = MethodRefUser::new;
    }

    // bound instance method reference
    public void useBoundRef() {
        List<String> list = new ArrayList<>();
        list.forEach(System.out::println);  // println is a library method, not indexed
    }
}
