package com.example;

/**
 * Interface that inherits {@link AbstractService#inheritedDoAbstract}
 * from an abstract-class supertype without redeclaring it. Java
 * permits {@code interface IFoo extends AbsBase}, but JDT's
 * binding-side {@code ITypeBinding.getInterfaces()} for an interface
 * does not expose the abstract supertype (it's only visible via the
 * AST node as a superInterfaceType). The forward
 * recordMethodHierarchy walk therefore misses the
 * AbstractService#inheritedDoAbstract ↔ IfaceExtendsAbstractImpl#
 * inheritedDoAbstract edge, which the reverse-type-hierarchy repair
 * pass rebuilds from typeHierarchy.
 */
public interface IfaceExtendsAbstract extends AbstractService {
}