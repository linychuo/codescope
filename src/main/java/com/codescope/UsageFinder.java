package com.codescope;

import java.nio.file.Path;
import java.util.List;

/**
 * Indexes all references to a symbol (method, field, or class) across a project
 * and supports reverse lookup: given a symbol, returns every location where it is used.
 *
 * Modeled on IntelliJ IDEA's FileBasedIndex + PsiReference.resolve() two-phase approach:
 *   Phase 1 (build):  walk every CompilationUnit, emit a UsageLocation per reference.
 *   Phase 2 (query):  look up the symbol's key in the reverse index.
 */
public interface UsageFinder {

    /** What kind of declaration the target symbol is. */
    enum SymbolKind { METHOD, FIELD, CLASS }

    /** Identifies a declaration site to search references for. */
    class Symbol {
        public final SymbolKind kind;
        public final String className;   // simple or fully qualified
        public final String memberName;  // null for CLASS

        public Symbol(SymbolKind kind, String className, String memberName) {
            this.kind = kind;
            this.className = className;
            this.memberName = memberName;
        }

        public static Symbol ofClass(String className) {
            return new Symbol(SymbolKind.CLASS, className, null);
        }

        public static Symbol ofMethod(String className, String methodName) {
            return new Symbol(SymbolKind.METHOD, className, methodName);
        }

        public static Symbol ofField(String className, String fieldName) {
            return new Symbol(SymbolKind.FIELD, className, fieldName);
        }

        public String key() {
            return memberName == null ? className : className + "#" + memberName;
        }

        @Override
        public String toString() {
            return memberName == null ? className : className + "." + memberName;
        }
    }

    /** Categorizes the kind of reference site. */
    enum RefKind {
        CALL,                  // method invocation (incl. super.method)
        NEW,                   // class instance creation / constructor call
        FIELD_READ, FIELD_WRITE,
        EXTENDS, IMPLEMENTS,  // type in class/interface/record header
        IMPORT,                // import statement
        CAST, INSTANCEOF,      // type in cast / instanceof
        THROW, CATCH,          // type in throw / catch clause
        TYPE_REF               // plain type annotation (var decl, return type, etc.)
    }

    /** A single reference site for a symbol. */
    class UsageLocation implements Comparable<UsageLocation> {
        public final Path file;
        public final int line;
        public final int column;
        public final RefKind kind;
        public final String snippet;       // source line, trimmed
        public final String containerName; // enclosing method/class, e.g. "Caller.invoke"

        public UsageLocation(Path file, int line, int column, RefKind kind,
                             String snippet, String containerName) {
            this.file = file;
            this.line = line;
            this.column = column;
            this.kind = kind;
            this.snippet = snippet;
            this.containerName = containerName;
        }

        @Override
        public int compareTo(UsageLocation o) {
            int c = file.toString().compareTo(o.file.toString());
            if (c != 0) return c;
            c = Integer.compare(line, o.line);
            if (c != 0) return c;
            return Integer.compare(column, o.column);
        }

        /** "path:line:col  KIND  snippet" — grep-friendly. */
        public String toLine() {
            return file + ":" + line + ":" + column + "  "
                + String.format("%-12s", kind) + " " + snippet;
        }
    }

    /**
     * Returns all reference sites for the given symbol, sorted by (file, line, column).
     * Returns an empty list if the symbol has no usages or was never indexed.
     */
    List<UsageLocation> findUsages(Symbol symbol);
}
