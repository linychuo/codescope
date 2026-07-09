package com.codescope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface DependencyResolver {
    List<String> resolve(Path projectRoot) throws IOException;
}
