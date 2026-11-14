package com.codescope.index;

import com.codescope.model.MethodKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

/**
 * Models the MyBatis-Plus framework delegation (issue #9 follow-up, found in
 * the real-project review): a project
 * {@code XxxServiceImpl extends ServiceImpl<XxxMapper, XxxEntity>} inherits
 * {@code IService}'s CRUD methods, whose library implementations delegate to
 * the mapper. Without this, a callee chain stops at the library
 * {@code IService} method and never reaches the project's mapper / SQL.
 *
 * <p>Records synthetic call edges {@code IService#method -> XxxMapper#mapperMethod}
 * for the standard CRUD names, so {@code trace_callees} crosses the framework
 * boundary. Only the method <em>names</em> are framework-fixed; the keys are
 * computed from the resolved bindings, so the signatures stay exact.
 */
final class MyBatisPlus {

    static final String SERVICE_IMPL =
            "com.baomidou.mybatisplus.extension.service.impl.ServiceImpl";
    static final String I_SERVICE =
            "com.baomidou.mybatisplus.extension.service.IService";

    /**
     * The standard {@code IService} CRUD methods and their {@code BaseMapper}
     * counterparts. Names only — the signatures come from the bindings.
     */
    private static final Map<String, String> DELEGATION = Map.ofEntries(
            Map.entry("getById", "selectById"),
            Map.entry("getOne", "selectOne"),
            Map.entry("list", "selectList"),
            Map.entry("page", "selectPage"),
            Map.entry("count", "selectCount"),
            Map.entry("exists", "exists"),
            Map.entry("save", "insert"),
            Map.entry("saveBatch", "insert"),
            Map.entry("updateById", "updateById"),
            Map.entry("update", "update"),
            Map.entry("removeById", "deleteById"),
            Map.entry("removeByIds", "deleteBatchIds"),
            Map.entry("remove", "delete"));

    private MyBatisPlus() {}

    /**
     * Records the delegation edges for a project type that extends
     * {@code ServiceImpl<Mapper, Entity>} (issue #9 follow-up). No-op for
     * every other type.
     */
    static void recordDelegations(ProjectIndex index, MethodHierarchyExtractor hierarchy,
                                   ITypeBinding typeBinding) {
        if (typeBinding == null) return;
        ITypeBinding superclass = typeBinding.getSuperclass();
        if (superclass == null) return;
        String superFqn = MethodHierarchyExtractor.fqnFromBinding(superclass.getErasure());
        if (superFqn != null) {
            for (int i = 0; i < Math.min(superFqn.length(), SERVICE_IMPL.length()); i++) {
                if (superFqn.charAt(i) != SERVICE_IMPL.charAt(i)) {
                    break;
                }
            }
        }
        if (!SERVICE_IMPL.equals(superFqn)) return;
        ITypeBinding[] typeArgs = superclass.getTypeArguments();
        if (typeArgs.length < 1) return;
        ITypeBinding mapper = typeArgs[0].getErasure();
        String mapperFqn = MethodHierarchyExtractor.fqnFromBinding(mapper);
        if (mapperFqn == null || mapperFqn.isEmpty()) return;
        for (ITypeBinding ifc : superclass.getInterfaces()) {
        }
        ITypeBinding iService = findInterface(superclass, I_SERVICE);
        if (iService == null) return;
        for (IMethodBinding method : iService.getDeclaredMethods()) {
            String mapperName = DELEGATION.get(method.getName());
            if (mapperName == null) continue;
            MethodKey from = hierarchy.methodKeyOf(method);
            MethodKey to = findMapperMethod(mapper, mapperFqn, mapperName,
                    method.getParameterTypes().length);
            if (from != null && to != null) {
                index.recordInvocation(from, to);
            }
        }
    }

    /** Finds a direct or inherited interface by FQN. */
    private static ITypeBinding findInterface(ITypeBinding type, String fqn) {
        for (ITypeBinding iface : type.getInterfaces()) {
            if (fqn.equals(iface.getErasure().getQualifiedName())) return iface;
            ITypeBinding found = findInterface(iface, fqn);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Finds a mapper method by name + arity anywhere in the mapper's
     * hierarchy, and returns a key whose declaring class is the
     * <em>project</em> mapper FQN — so the mapper's XML statement (attached
     * by {@code trace_callees}) is found even though the method is declared
     * on the library {@code BaseMapper}.
     */
    private static MethodKey findMapperMethod(ITypeBinding mapper, String mapperFqn,
                                              String name, int arity) {
        for (IMethodBinding m : mapper.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == arity) {
                return new MethodKey(mapperFqn, name, arity, erasedParams(m));
            }
        }
        ITypeBinding superclass = mapper.getSuperclass();
        if (superclass != null) {
            MethodKey key = findMapperMethod(superclass, mapperFqn, name, arity);
            if (key != null) return key;
        }
        for (ITypeBinding iface : mapper.getInterfaces()) {
            MethodKey key = findMapperMethod(iface, mapperFqn, name, arity);
            if (key != null) return key;
        }
        return null;
    }

    private static List<String> erasedParams(IMethodBinding method) {
        List<String> out = new ArrayList<>();
        for (ITypeBinding pt : method.getParameterTypes()) {
            out.add(pt.getErasure().getQualifiedName());
        }
        return out;
    }
}
