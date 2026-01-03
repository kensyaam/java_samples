# StubGenerator

`StubGenerator` is a tool for automatically generating Java stub files for types that are missing from the classpath. This helps improve the precision of static analysis tools like Spoon by providing type definitions for unresolved references.

## Features

- **Automatic Stub Generation**: Generates Java class, interface, and annotation stubs for missing types.
- **Inference**: Infers methods, fields, and type kinds (class/interface/annotation) based on usage.
- **Nested Type Support**: Correctly handles and generates static nested classes.
- **Classpath Expansion**: Supports adding directories (and JARs within them) to the analysis classpath.
- **Configuration**: Allows setting Java compliance level and source encoding.

## Usage

### Building the Tool

To build the standalone JAR file for `StubGenerator`:

```bash
./gradlew stubGeneratorJar
```

This will create `stub-generator-1.0.0.jar` (or similar versioned name) in the root directory.

### Running the Tool

```bash
java -jar stub-generator-1.0.0.jar -s <source_dir> [options]
```

**Options:**

*   `-s`, `--source <paths>`: (Required) Comma-separated list of source directories to analyze.
*   `-c`, `--classpath <paths>`: (Optional) Comma-separated list of classpath entries (JARs or directories). If a directory is specified, all JARs inside it are also added.
*   `-o`, `--output <dir>`: (Optional) Output directory for generated stubs. Defaults to `stubs`.
*   `-cl`, `--complianceLevel <level>`: (Optional) Java compliance level (e.g., 8, 11, 17, 21). Defaults to 21.
*   `-e`, `--encoding <charset>`: (Optional) Source code encoding (e.g., UTF-8, Shift_JIS). Defaults to UTF-8.
*   `-d`, `--debug`: (Optional) Enable debug logging.
*   `-h`, `--help`: Show help message.

### Example

```bash
java -jar stub-generator-1.0.0.jar -s src/main/java -o generated-stubs -cl 17
```

### Compiling and Packaging Stubs

After generating stubs, you can compile them and package them into a JAR using the provided Gradle tasks. This is useful if you want to use the stubs as a library for subsequent analysis steps.

1.  **Generate Stubs**: By default, the Gradle tasks assume stubs are in `java-call-tree-analyzer/app/build/generated/stubs`. You should direct `StubGenerator` output there, or configure the build script accordingly.

    ```bash
    # Example: Run generator outputting to the expected build directory
    java -jar stub-generator-1.0.0.jar -s src/main/java -o java-call-tree-analyzer/app/build/generated/stubs
    ```

2.  **Build Stub JAR**:

    ```bash
    ./gradlew stubJar
    ```

    This will:
    1.  Compile the Java files found in `java-call-tree-analyzer/app/build/generated/stubs`.
    2.  Package the compiled classes into `generated-stubs-1.0.0.jar` in the root directory.

## How it Works

1.  **Analysis**: The tool parses the provided source code using Spoon. It operates in "no classpath" mode initially to tolerate missing dependencies.
2.  **Detection**: It identifies types that are referenced but not defined in the source or the provided classpath.
3.  **Inference**:
    *   **Type Kind**: Checks if the missing type is used as an annotation (`@Missing`) or implemented as an interface (`implements Missing`). Otherwise, defaults to a class.
    *   **Nested Types**: Detects if a missing type is a nested class of another missing type (e.g., `Missing.Inner`) and generates it structure accordingly.
    *   **Methods**: Scans for invocations on the missing type to infer method signatures (name, argument count/types). It tries to infer the return type based on context (e.g., assignment to a variable).
    *   **Fields**: Scans for field accesses to generate public fields with inferred types.
4.  **Generation**: Generates valid Java source files for these inferred types.
