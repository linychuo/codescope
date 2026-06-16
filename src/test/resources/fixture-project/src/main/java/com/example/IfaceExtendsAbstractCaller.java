package com.example;

/**
 * Caller that exercises the interface-extends-abstract-class path
 * end-to-end. Holds an {@link IfaceExtendsAbstract}-typed field and
 * invokes {@code inheritedDoAbstract} through it. JDT binds the call
 * to {@code IfaceExtendsAbstractImpl#inheritedDoAbstract/1}; the
 * call edge lives under that key. A trace_callers or find_call_sites
 * query against any of {AbstractService, IfaceExtendsAbstract,
 * IfaceExtendsAbstractImpl} must surface this caller — issue #3
 * interface-extends-abstract-class regression.
 */
public class IfaceExtendsAbstractCaller {
    private final IfaceExtendsAbstract svc;

    public IfaceExtendsAbstractCaller(IfaceExtendsAbstract svc) {
        this.svc = svc;
    }

    public void doWork(String s) {
        svc.inheritedDoAbstract(s);
    }
}