package com.example;

/**
 * Abstract parent that declares {@link #inheritedDoAbstract}
 * without an implementation. The interface
 * {@link IfaceExtendsAbstract} inherits this method without
 * redeclaring it (legal Java: {@code interface extends AbsBase}).
 *
 * <p>Exercises the issue #3 sub-class / interface-extends-class
 * path: JDT's binding-side supertype traversal does NOT expose the
 * abstract parent (IfaceExtendsAbstract.binding.getInterfaces()
 * returns empty even though the AST exposes AbstractService as a
 * super-interface), so the indexer's forward method-hierarchy walk
 * would miss the AbstractService ↔ IfaceExtendsAbstractImpl
 * edge. The reverse-type-hierarchy repair pass added with this
 * fixture closes that gap by walking typeHierarchy (which IS
 * populated from the AST) and linking any same-named same-arity
 * method on a subtype.
 */
public abstract class AbstractService {
    public abstract void inheritedDoAbstract(String s);
}