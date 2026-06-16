package com.example;

/**
 * Concrete implementation of {@link IfaceExtendsAbstract}. Provides
 * the only concrete implementation of {@code inheritedDoAbstract}
 * in the project. The IfaceExtendsAbstractCaller (separate fixture)
 * invokes {@code inheritedDoAbstract} through an
 * {@link IfaceExtendsAbstract}-typed field; JDT binds the call to
 * this concrete implementation's key.
 */
public class IfaceExtendsAbstractImpl implements IfaceExtendsAbstract {
    @Override
    public void inheritedDoAbstract(String s) {
        System.out.println("impl " + s);
    }
}