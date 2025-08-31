package com.example;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

public class DependencyAnalyzer {

    // 呼び元 → 呼び先
    private static final Map<String, Set<String>> callGraph = new HashMap<>();
    // 呼び先 → 呼び元
    private static final Map<String, Set<String>> reverseGraph = new HashMap<>();
    // すべてのメソッド
    private static final Set<String> allMethods = new HashSet<>();

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java -jar dependency-analyzer.jar <src_dir> [classpath1:classpath2:...] [--mode=forward|reverse]");
            System.exit(1);
        }

        String srcDir = args[0];
        String classpathArg = args.length >= 2 && !args[1].startsWith("--") ? args[1] : "";
        String mode = Arrays.stream(args)
                .filter(a -> a.startsWith("--mode="))
                .map(a -> a.substring("--mode=".length()))
                .findFirst()
                .orElse("forward");

        // 型解決のための設定
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());

        // クラスパス設定
        if (!classpathArg.isEmpty()) {
            String[] classpaths = classpathArg.split(File.pathSeparator);
            for (String cp : classpaths) {
                File cpFile = new File(cp);
                if (!cpFile.exists()) {
                    System.err.println("Warning: classpath not found: " + cp);
                    continue;
                }
                if (cpFile.isDirectory()) {
                    typeSolver.add(new JavaParserTypeSolver(cpFile));
                } else if (cpFile.isFile() && cp.endsWith(".jar")) {
                    typeSolver.add(new JarTypeSolver(cpFile));
                }
            }
        }

        // ソースコードも解析対象に追加
        typeSolver.add(new JavaParserTypeSolver(new File(srcDir)));

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);

        // ソースコードを解析
        parseDirectory(new File(srcDir));

        // 出力処理
        if (mode.equals("reverse")) {
            System.out.println("=== 呼び先 → 呼び元 ===");
            for (String callee : reverseGraph.keySet()) {
                System.out.println(callee);
                printReverseTree(callee, 1, new HashSet<>());
            }
        } else {
            System.out.println("=== 呼び元 → 呼び先 ===");
            // 入次数ゼロ（呼ばれていないメソッド）を起点に設定
            Set<String> calledMethods = new HashSet<>();
            callGraph.values().forEach(calledMethods::addAll);

            Set<String> rootMethods = new HashSet<>(allMethods);
            rootMethods.removeAll(calledMethods);

            for (String root : rootMethods) {
                System.out.println(root);
                printForwardTree(root, 1, new HashSet<>());
            }
        }
    }

    /** ディレクトリを再帰的に解析 */
    private static void parseDirectory(File dir) throws FileNotFoundException {
        for (File file : Objects.requireNonNull(dir.listFiles())) {
            if (file.isDirectory()) {
                parseDirectory(file);
            } else if (file.getName().endsWith(".java")) {
                CompilationUnit cu = StaticJavaParser.parse(file);

                String packageName = cu.getPackageDeclaration()
                        .map(pd -> pd.getName().toString())
                        .orElse("");

                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                    String className = clazz.getNameAsString();

                    clazz.findAll(MethodDeclaration.class).forEach(method -> {
                        String caller = buildFullyQualifiedMethodName(packageName, className, method);

                        // すべてのメソッドを収集
                        allMethods.add(caller);

                        Set<String> callees = new HashSet<>();
                        method.findAll(MethodCallExpr.class).forEach(call -> {
                            try {
                                ResolvedMethodDeclaration resolved = call.resolve();
                                // Java標準ライブラリは除外
                                if (resolved.getPackageName().startsWith("java.")) {
                                    return;
                                }
                                String fqmn = buildFullyQualifiedMethodName(resolved);
                                callees.add(fqmn);

                                // 逆グラフ構築
                                reverseGraph.computeIfAbsent(fqmn, k -> new HashSet<>()).add(caller);

                            } catch (Exception e) {
                                // 解決できない場合はメソッド名だけ表示
                                callees.add(call.getNameAsString() + "(?)");
                            }
                        });

                        // 呼び先がゼロでも必ず登録
                        callGraph.put(caller, callees);
                    });
                });
            }
        }
    }

    /** 呼び元用のFQMN生成 */
    private static String buildFullyQualifiedMethodName(String packageName, String className, MethodDeclaration method) {
        String args = method.getParameters().isEmpty()
                ? ""
                : method.getParameters().stream()
                .map(p -> p.getType().asString())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return packageName + "." + className + "." + method.getNameAsString() + "(" + args + ")";
    }

    /** 呼び先用のFQMN生成 */
    private static String buildFullyQualifiedMethodName(ResolvedMethodDeclaration resolved) {
        String args = "";
        try {
            int paramCount = resolved.getNumberOfParams();
            if (paramCount > 0) {
                List<String> argTypes = new ArrayList<>();
                for (int i = 0; i < paramCount; i++) {
                    argTypes.add(resolved.getParam(i).getType().describe());
                }
                args = String.join(", ", argTypes);
            }
        } catch (Exception e) {
            args = "?";
        }
        return resolved.getPackageName() + "." + resolved.getClassName() + "." + resolved.getName() + "(" + args + ")";
    }

    /** 再帰的に呼び先を表示（呼び元 → 呼び先） */
    private static void printForwardTree(String method, int depth, Set<String> visited) {
        if (visited.contains(method)) return;
        visited.add(method);

        Set<String> callees = callGraph.getOrDefault(method, Collections.emptySet());
        for (String callee : callees) {
            System.out.println("  ".repeat(depth) + "└─ " + callee);
            printForwardTree(callee, depth + 1, visited);
        }
    }

    /** 再帰的に呼び元を表示（呼び先 → 呼び元） */
    private static void printReverseTree(String method, int depth, Set<String> visited) {
        if (visited.contains(method)) return;
        visited.add(method);

        Set<String> callers = reverseGraph.getOrDefault(method, Collections.emptySet());
        for (String caller : callers) {
            System.out.println("  ".repeat(depth) + "└─ " + caller);
            printReverseTree(caller, depth + 1, visited);
        }
    }
}
