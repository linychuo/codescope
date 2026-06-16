package com.example;

/**
 * Sub-interface that inherits {@code findById} from {@link IfaceDao}
 * without redeclaring it. Exercises the issue #3 sub-interface
 * scenario: a caller holds a field of this type, JDT binds the call
 * to {@code IfaceDao#findById/1}, and a {@code trace_callers} /
 * {@code find_call_sites} query against this interface must walk up
 * the type hierarchy to find callers.
 */
public interface IfaceDaoSub extends IfaceDao {
}