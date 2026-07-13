package com.codescope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #5: trace_callers `line` pointed at the Javadoc/annotation
 * preamble instead of the method signature, so the user couldn't
 * jump-to-definition reliably. After the fix, line is the source
 * position of the method name token.
 */
class MethodLineFixTest {

    @Test
    void methodLinePointsAtSignatureNotJavadoc(@TempDir Path tmp) throws IOException {
        // Source has:
        //   line 1: package
        //   line 2: public class ...
        //   line 3: blank
        //   line 4: /**
        //   line 5:  * Multi-line Javadoc
        //   line 6:  * spanning several lines.
        //   line 7:  */
        //   line 8: @SuppressWarnings("all")
        //   line 9: public int compute() { return 1; }   <-- name token
        //   line 10: }
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Foo.java"),
                "package com.example;\n"
              + "public class Foo {\n"
              + "\n"
              + "/**\n"
              + " * Multi-line Javadoc\n"
              + " * spanning several lines.\n"
              + " */\n"
              + "@SuppressWarnings(\"all\")\n"
              + "    public int compute() { return 1; }\n"
              + "}\n");
        ProjectIndex index = new JdtIndexer().build(
                List.of(srcDir.resolve("Foo.java")),
                List.of(),
                List.of(srcDir.getParent().getParent().toString()),
                tmp);

        MethodKey compute = new MethodKey("com.example.Foo", "compute", 0, List.of());
        ProjectIndex.SourceLoc loc = index.declarationOf(compute);
        assertEquals(9, loc.line(),
                "declaration line should be the method signature line (the line containing 'compute'), "
              + "not the Javadoc start. Got: " + loc);
    }
}
