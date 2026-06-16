package com.example;

/**
 * Caller that holds a field typed as {@link IfaceDaoSub} (a
 * sub-interface of {@link IfaceDao} that inherits {@code findById}
 * without redeclaring it) and invokes the method through that field.
 * JDT binds the call to {@code IfaceDao#findById/1}; the call edge
 * lives under the IfaceDao key, not the IfaceDaoSub key. A
 * {@code trace_callers(com.example.IfaceDaoSub, findById)} query must
 * still find this caller (issue #3 sub-interface inheritance).
 */
public class SubInterfaceCaller {
    private final IfaceDaoSub repo = new IfaceDao() {
        @Override
        public String findById(long id) {
            return "x";
        }
    };

    public String lookup(long id) {
        return repo.findById(id);
    }
}