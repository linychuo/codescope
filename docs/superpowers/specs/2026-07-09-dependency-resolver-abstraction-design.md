# Dependency Resolver Abstraction

## Problem

`ProjectLoader.load()` 硬编码了 `new MavenClasspathResolver().resolve(effective)`，而 `MavenClasspathResolver` 的实现是手动解析 pom.xml 后在 `~/.m2/repository` 里拼 jar 路径：

- 不处理 `${property}` 变量解析，遇到 property 占位的 version 直接返回字面字符串
- 没有网络解析能力，依赖必须已在本地缓存
- 不支持 Gradle 等其他构建工具

根本原因是依赖解析没有抽象层，`ProjectLoader` 直接依赖具体实现。

## Design

### 1. DependencyResolver 接口

```java
package com.codescope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface DependencyResolver {
    /** 解析项目依赖，返回 classpath 条目列表（jar 绝对路径） */
    List<String> resolve(Path projectRoot) throws IOException;
}
```

最小接口，只有一个方法。`resolve` 返回 jar 文件的绝对路径列表，语义与现有 `MavenClasspathResolver.resolve()` 一致。

### 2. MvnCliDependencyResolver

调用 `mvn dependency:build-classpath` 获取真实的依赖树。

```java
package com.codescope;

public final class MvnCliDependencyResolver implements DependencyResolver {
    // 用户通过 CLI / 环境变量传入的额外参数，如 "-gs /path/to/settings.xml"
    private final List<String> extraArgs;

    public MvnCliDependencyResolver(List<String> extraArgs) { ... }
    public MvnCliDependencyResolver() { this(List.of()); }

    @Override
    public List<String> resolve(Path projectRoot) throws IOException { ... }
}
```

**resolve 内部流程：**

1. 确认 `projectRoot/pom.xml` 存在，不存在则抛 `IOException("pom.xml not found")`
2. 写临时文件 `mvn-output-XXXXX.cp`（`Files.createTempFile`）
3. 执行：
   ```
   mvn -f projectRoot/pom.xml dependency:build-classpath \
       -Dmdep.outputFile=/tmp/mvn-output-XXXXX.cp \
       -Dmdep.outputAbsoluteArtifactFilename=true \
       -Dmdep.includeScope=compile \
       [-gs /path/to/settings.xml ...]
   ```
4. 等待进程完成，检查 exit code
5. 读输出文件内容，按 `:` 或 `File.pathSeparator` 拆分得到 jar 路径列表
6. 删除临时文件
7. 返回列表

**额外参数传递：**
- 构造参数 `extraArgs`（从 CLI 传入）
- 环境变量 `CODESCOPE_MVN_ARGS` 作为兜底
- 两者都存在时，`extraArgs` 优先

### 3. DependencyResolverFactory

根据项目文件自动选择合适的 resolver。

```java
package com.codescope;

public final class DependencyResolverFactory {

    // 用户可配置的额外 Maven 参数
    private static List<String> mavenExtraArgs = List.of();

    public static void setMavenExtraArgs(List<String> args) {
        mavenExtraArgs = List.copyOf(args);
    }

    public static DependencyResolver create(Path projectRoot) {
        if (hasFile(projectRoot, "pom.xml")) {
            return new MvnCliDependencyResolver(mavenExtraArgs);
        }
        if (hasFile(projectRoot, "build.gradle")
                || hasFile(projectRoot, "build.gradle.kts")) {
            // GradleDependencyResolver 后续实现，暂时抛异常
            throw new UnsupportedOperationException(
                    "Gradle support is not yet implemented");
        }
        throw new IllegalArgumentException(
                "No recognized build file found under " + projectRoot
                + " (supported: pom.xml, build.gradle)");
    }

    private static boolean hasFile(Path dir, String name) {
        return Files.isRegularFile(dir.resolve(name));
    }
}
```

### 4. ProjectLoader 改动

```java
// 替换第 49 行：
// 旧：List<String> classpath = new MavenClasspathResolver().resolve(effective);
// 新：
List<String> classpath = DependencyResolverFactory.create(effective).resolve(effective);
```

`discoverEffectiveRoot()` 保留不变（它仍然基于 pom.xml 向上找 aggregator）。

**关于 `discoverEffectiveRoot` 的适配：**
当前实现只查找 pom.xml 来确定 effective root。对于 Gradle 项目（后续支持），`discoverEffectiveRoot` 也需要感知 Gradle 的多模块布局（找 `settings.gradle` 文件）。这个改动留到 Gradle 实现时再做。

### 5. MavenClasspathResolver 的去留

`MavenClasspathResolver` **保留不动**，不再被 `ProjectLoader` 引用。它可以用于：
- 离线环境（没有 `mvn` 命令可用时手动调用）
- 测试中对比 `MvnCliDependencyResolver` 的结果
- 后续如果发现有场景需要纯文件系统解析，可以复用

### 6. 错误处理

| 场景 | 行为 |
|---|---|
| `projectRoot` 下没有 pom.xml | `DependencyResolverFactory.create()` 抛 `IllegalArgumentException` |
| `mvn` 命令不存在或执行失败 | `MvnCliDependencyResolver.resolve()` 抛 `IOException` 带 exit code 和 stderr |
| `mvn` 输出文件为空 | 返回空列表（等同于项目无第三方依赖） |
| 临时文件创建失败 | `IOException` 传播给调用方 |

## 后续扩展

### Gradle 支持（后续实现）

```java
public final class GradleDependencyResolver implements DependencyResolver {
    @Override
    public List<String> resolve(Path projectRoot) throws IOException {
        // 调用 gradle dependencies 或 gradle buildEnvironment
        // 从输出中提取 jar 路径
    }
}
```

在 `DependencyResolverFactory.create()` 中添加 `build.gradle`/`build.gradle.kts` 的分支。

### 自定义 Maven 路径

如果后续需要支持自定义 `mvn` 二进制路径（如 `MAVEN_HOME/bin/mvn` 或 wrapper `mvnw`），可以在 `MvnCliDependencyResolver` 中增加一个构造参数，或通过环境变量 `CODESCOPE_MVN_HOME` 配置。

## 文件清单

| 操作 | 文件 |
|---|---|
| **新增** | `src/main/java/com/codescope/DependencyResolver.java` |
| **新增** | `src/main/java/com/codescope/MvnCliDependencyResolver.java` |
| **新增** | `src/main/java/com/codescope/DependencyResolverFactory.java` |
| **修改** | `src/main/java/com/codescope/ProjectLoader.java`（1 行） |
| **新增** | `src/test/java/com/codescope/MvnCliDependencyResolverTest.java` |
| **新增** | `src/test/java/com/codescope/DependencyResolverFactoryTest.java` |
