package com.analyzer;

import org.apache.commons.cli.*;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.reflect.code.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Javaソースコードの呼び出しツリーを解析するツール
 */
public class CallTreeAnalyzer {
    
    private CtModel model;
    private Map<String, CtMethod<?>> methodMap = new HashMap<>();
    private Map<String, Set<String>> callGraph = new HashMap<>(); // 呼び出し元 -> 呼び出し先
    private Map<String, Set<String>> reverseCallGraph = new HashMap<>(); // 呼び出し先 -> 呼び出し元
    private Map<String, CtMethod<?>> methodSignatureMap = new HashMap<>();
    private Map<String, String> methodToClassMap = new HashMap<>();
    private Map<String, Set<String>> classHierarchy = new HashMap<>(); // クラス -> 親クラス群
    private Map<String, Set<String>> interfaceImplementations = new HashMap<>();
    private Map<String, List<String>> sqlStatements = new HashMap<>();
    private Set<String> visitedMethods = new HashSet<>();
    
    public static void main(String[] args) {
        Options options = new Options();
        
        options.addOption("s", "source", true, "解析対象のソースディレクトリ（複数指定可、カンマ区切り）");
        options.addOption("cp", "classpath", true, "依存ライブラリのJARファイルまたはディレクトリ（複数指定可、カンマ区切り）");
        options.addOption("o", "output", true, "出力ファイルパス（デフォルト: call-tree.tsv）");
        options.addOption("f", "format", true, "出力フォーマット（tsv/json/graphml、デフォルト: tsv）");
        options.addOption("h", "help", false, "ヘルプを表示");
        
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        
        try {
            CommandLine cmd = parser.parse(options, args);
            
            if (cmd.hasOption("h")) {
                formatter.printHelp("CallTreeAnalyzer", options);
                return;
            }
            
            String sourceDirs = cmd.getOptionValue("source", "src/main/java");
            String classpath = cmd.getOptionValue("classpath", "");
            String outputPath = cmd.getOptionValue("output", "call-tree.tsv");
            String format = cmd.getOptionValue("format", "tsv");
            
            CallTreeAnalyzer analyzer = new CallTreeAnalyzer();
            analyzer.analyze(sourceDirs, classpath);
            analyzer.export(outputPath, format);
            
            System.out.println("解析完了: " + outputPath);
            
        } catch (ParseException e) {
            System.err.println("引数解析エラー: " + e.getMessage());
            formatter.printHelp("CallTreeAnalyzer", options);
        } catch (Exception e) {
            System.err.println("解析エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * ソースコードを解析
     */
    public void analyze(String sourceDirs, String classpath) {
        System.out.println("解析開始...");
        
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(false);
        launcher.getEnvironment().setComplianceLevel(21);
        launcher.getEnvironment().setAutoImports(true);
        
        // ソースディレクトリを追加
        for (String dir : sourceDirs.split(",")) {
            launcher.addInputResource(dir.trim());
            System.out.println("ソースディレクトリ追加: " + dir.trim());
        }
        
        // クラスパスを設定
        if (!classpath.isEmpty()) {
            String[] cpPaths = classpath.split(",");
            List<String> expandedClasspath = expandClasspath(cpPaths);
            launcher.getEnvironment().setSourceClasspath(expandedClasspath.toArray(new String[0]));
            System.out.println("クラスパス設定: " + expandedClasspath.size() + "個");
        }
        
        model = launcher.buildModel();
        System.out.println("モデル構築完了");
        
        // 1. メソッド情報の収集
        collectMethods();
        
        // 2. クラス階層の収集
        collectClassHierarchy();
        
        // 3. 呼び出し関係の解析
        analyzeCallRelations();
        
        // 4. SQL文の検出
        detectSqlStatements();
        
        System.out.println("解析完了: " + methodMap.size() + "個のメソッドを検出");
    }
    
    /**
     * メソッド情報を収集
     */
    private void collectMethods() {
        List<CtMethod<?>> methods = model.getElements(new TypeFilter<>(CtMethod.class));
        
        for (CtMethod<?> method : methods) {
            String signature = getMethodSignature(method);
            methodMap.put(signature, method);
            methodSignatureMap.put(signature, method);
            
            CtType<?> declaringType = method.getDeclaringType();
            if (declaringType != null) {
                methodToClassMap.put(signature, declaringType.getQualifiedName());
            }
        }
    }
    
    /**
     * クラス階層を収集
     */
    private void collectClassHierarchy() {
        List<CtType<?>> types = model.getElements(new TypeFilter<>(CtType.class));
        
        for (CtType<?> type : types) {
            String typeName = type.getQualifiedName();
            Set<String> parents = new HashSet<>();
            
            // スーパークラス
            if (type instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) type;
                CtTypeReference<?> superClass = ctClass.getSuperclass();
                if (superClass != null && !isJavaStandardLibrary(superClass.getQualifiedName())) {
                    parents.add(superClass.getQualifiedName());
                }
            }
            
            // インターフェース
            Set<CtTypeReference<?>> interfaces = type.getSuperInterfaces();
            for (CtTypeReference<?> iface : interfaces) {
                if (!isJavaStandardLibrary(iface.getQualifiedName())) {
                    parents.add(iface.getQualifiedName());
                }
            }
            
            if (!parents.isEmpty()) {
                classHierarchy.put(typeName, parents);
            }
            
            // インターフェース実装の記録
            if (!interfaces.isEmpty()) {
                for (CtTypeReference<?> iface : interfaces) {
                    String ifaceName = iface.getQualifiedName();
                    if (!isJavaStandardLibrary(ifaceName)) {
                        interfaceImplementations.computeIfAbsent(ifaceName, k -> new HashSet<>()).add(typeName);
                    }
                }
            }
        }
    }
    
    /**
     * 呼び出し関係を解析
     */
    private void analyzeCallRelations() {
        for (CtMethod<?> method : methodMap.values()) {
            String callerSignature = getMethodSignature(method);
            
            List<CtInvocation<?>> invocations = method.getElements(new TypeFilter<>(CtInvocation.class));
            
            for (CtInvocation<?> invocation : invocations) {
                CtExecutableReference<?> executable = invocation.getExecutable();
                if (executable != null) {
                    String calleeSignature = getExecutableSignature(executable);
                    
                    // Java標準ライブラリは除外
                    if (isJavaStandardLibrary(executable.getDeclaringType().getQualifiedName())) {
                        continue;
                    }
                    
                    // 呼び出しグラフに追加
                    callGraph.computeIfAbsent(callerSignature, k -> new LinkedHashSet<>()).add(calleeSignature);
                    reverseCallGraph.computeIfAbsent(calleeSignature, k -> new HashSet<>()).add(callerSignature);
                }
            }
        }
    }
    
    /**
     * SQL文を検出
     */
    private void detectSqlStatements() {
        for (CtMethod<?> method : methodMap.values()) {
            String methodSig = getMethodSignature(method);
            List<String> sqls = new ArrayList<>();
            
            // リテラル文字列からSQLを検出
            List<CtLiteral<?>> literals = method.getElements(new TypeFilter<>(CtLiteral.class));
            for (CtLiteral<?> literal : literals) {
                if (literal.getValue() instanceof String) {
                    String value = (String) literal.getValue();
                    if (looksLikeSql(value)) {
                        sqls.add(value);
                    }
                }
            }
            
            if (!sqls.isEmpty()) {
                sqlStatements.put(methodSig, sqls);
            }
        }
    }
    
    /**
     * 文字列がSQLらしいかを判定
     */
    private boolean looksLikeSql(String str) {
        if (str == null || str.length() < 10) return false;
        String upper = str.trim().toUpperCase();
        return upper.startsWith("SELECT ") || upper.startsWith("INSERT ") || 
               upper.startsWith("UPDATE ") || upper.startsWith("DELETE ") ||
               upper.startsWith("CREATE ") || upper.startsWith("ALTER ") ||
               upper.startsWith("DROP ");
    }
    
    /**
     * メソッドシグネチャを取得（完全修飾名）
     */
    private String getMethodSignature(CtMethod<?> method) {
        CtType<?> declaringType = method.getDeclaringType();
        String className = declaringType != null ? declaringType.getQualifiedName() : "Unknown";
        String methodName = method.getSimpleName();
        
        List<String> params = method.getParameters().stream()
            .map(p -> simplifyTypeName(p.getType().getQualifiedName()))
            .collect(Collectors.toList());
        
        return className + "#" + methodName + "(" + String.join(", ", params) + ")";
    }
    
    /**
     * 実行可能参照からシグネチャを取得
     */
    private String getExecutableSignature(CtExecutableReference<?> executable) {
        String className = executable.getDeclaringType().getQualifiedName();
        String methodName = executable.getSimpleName();
        
        List<String> params = executable.getParameters().stream()
            .map(p -> simplifyTypeName(p.getQualifiedName()))
            .collect(Collectors.toList());
        
        return className + "#" + methodName + "(" + String.join(", ", params) + ")";
    }
    
    /**
     * 型名を簡略化（Java標準ライブラリのパッケージ名を省略）
     */
    private String simplifyTypeName(String typeName) {
        if (typeName == null) return "";
        
        // 配列の処理
        int arrayDim = 0;
        while (typeName.endsWith("[]")) {
            arrayDim++;
            typeName = typeName.substring(0, typeName.length() - 2);
        }
        
        // Java標準ライブラリのパッケージ省略
        if (typeName.startsWith("java.lang.")) {
            typeName = typeName.substring("java.lang.".length());
        } else if (typeName.startsWith("java.util.")) {
            typeName = typeName.substring("java.util.".length());
        } else if (typeName.startsWith("java.io.")) {
            typeName = typeName.substring("java.io.".length());
        }
        
        // 配列記号を復元
        for (int i = 0; i < arrayDim; i++) {
            typeName += "[]";
        }
        
        return typeName;
    }
    
    /**
     * Java標準ライブラリかどうかを判定
     */
    private boolean isJavaStandardLibrary(String className) {
        return className != null && (
            className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("sun.") ||
            className.startsWith("com.sun.") ||
            className.startsWith("jdk.")
        );
    }
    
    /**
     * クラスパスを展開（ディレクトリ指定の場合、その中のすべてのJARファイルを追加）
     */
    private List<String> expandClasspath(String[] cpPaths) {
        List<String> result = new ArrayList<>();
        
        for (String cpPath : cpPaths) {
            cpPath = cpPath.trim();
            Path path = Paths.get(cpPath);
            
            try {
                if (Files.isDirectory(path)) {
                    // ディレクトリの場合、その中のすべてのJARファイルを追加
                    try (var stream = Files.list(path)) {
                        stream.filter(p -> p.toString().endsWith(".jar"))
                              .map(Path::toString)
                              .forEach(result::add);
                    }
                    System.out.println("ディレクトリスキャン: " + cpPath);
                } else if (Files.isRegularFile(path)) {
                    // ファイルの場合、そのまま追加
                    result.add(cpPath);
                    System.out.println("ファイル追加: " + cpPath);
                } else if (!Files.exists(path)) {
                    System.out.println("警告: パスが存在しません: " + cpPath);
                }
            } catch (IOException e) {
                System.err.println("クラスパス処理エラー (" + cpPath + "): " + e.getMessage());
            }
        }
        
        return result;
    }
    
    /**
     * 結果をエクスポート
     */
    public void export(String outputPath, String format) throws IOException {
        switch (format.toLowerCase()) {
            case "json":
                exportJson(outputPath);
                break;
            case "graphml":
                exportGraphML(outputPath);
                break;
            case "tsv":
            default:
                exportTsv(outputPath);
                break;
        }
    }
    
    /**
     * TSV形式でエクスポート
     */
    private void exportTsv(String outputPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputPath), StandardCharsets.UTF_8)) {
            // ヘッダー
            writer.write("呼び出し元メソッド\t呼び出し元クラス\t呼び出し元の親クラス\t" +
                        "呼び出し先メソッド\t呼び出し先クラス\t呼び出し先は親クラスのメソッド\t" +
                        "呼び出し先の実装クラス候補\tSQL文\t方向\n");
            
            // 呼び出し元からのツリー
            for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
                String caller = entry.getKey();
                String callerClass = methodToClassMap.getOrDefault(caller, "");
                String callerParents = getParentClasses(callerClass);
                
                for (String callee : entry.getValue()) {
                    String calleeClass = methodToClassMap.getOrDefault(callee, "");
                    boolean isParentMethod = isParentClassMethod(caller, callee);
                    String implementations = getImplementations(callee);
                    String sql = sqlStatements.containsKey(caller) ? 
                                String.join("; ", sqlStatements.get(caller)) : "";
                    
                    writer.write(String.format("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
                        escape(caller), escape(callerClass), escape(callerParents),
                        escape(callee), escape(calleeClass), isParentMethod ? "Yes" : "No",
                        escape(implementations), escape(sql), "Forward"));
                }
            }
            
            // 呼び出し先からのツリー（逆方向）
            for (Map.Entry<String, Set<String>> entry : reverseCallGraph.entrySet()) {
                String callee = entry.getKey();
                String calleeClass = methodToClassMap.getOrDefault(callee, "");
                
                for (String caller : entry.getValue()) {
                    String callerClass = methodToClassMap.getOrDefault(caller, "");
                    String callerParents = getParentClasses(callerClass);
                    
                    writer.write(String.format("%s\t%s\t%s\t%s\t%s\t\t\t\t%s\n",
                        escape(callee), escape(calleeClass), "",
                        escape(caller), escape(callerClass), "Reverse"));
                }
            }
        }
    }
    
    /**
     * JSON形式でエクスポート
     */
    private void exportJson(String outputPath) throws IOException {
        // 簡易的なJSON出力（Gsonを使うとより良い）
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputPath), StandardCharsets.UTF_8)) {
            writer.write("{\n");
            writer.write("  \"methods\": [\n");
            
            boolean first = true;
            for (String method : methodMap.keySet()) {
                if (!first) writer.write(",\n");
                first = false;
                
                String className = methodToClassMap.getOrDefault(method, "");
                Set<String> calls = callGraph.getOrDefault(method, Collections.emptySet());
                Set<String> calledBy = reverseCallGraph.getOrDefault(method, Collections.emptySet());
                
                writer.write(String.format("    {\"method\": \"%s\", \"class\": \"%s\", " +
                    "\"calls\": [%s], \"calledBy\": [%s]}",
                    escape(method), escape(className),
                    calls.stream().map(s -> "\"" + escape(s) + "\"").collect(Collectors.joining(", ")),
                    calledBy.stream().map(s -> "\"" + escape(s) + "\"").collect(Collectors.joining(", "))));
            }
            
            writer.write("\n  ]\n");
            writer.write("}\n");
        }
    }
    
    /**
     * GraphML形式でエクスポート（Gephi等で可視化可能）
     */
    private void exportGraphML(String outputPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputPath), StandardCharsets.UTF_8)) {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
            writer.write("  <graph id=\"G\" edgedefault=\"directed\">\n");
            
            // ノード
            for (String method : methodMap.keySet()) {
                writer.write(String.format("    <node id=\"%s\"/>\n", escape(method)));
            }
            
            // エッジ
            int edgeId = 0;
            for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
                for (String callee : entry.getValue()) {
                    writer.write(String.format("    <edge id=\"e%d\" source=\"%s\" target=\"%s\"/>\n",
                        edgeId++, escape(entry.getKey()), escape(callee)));
                }
            }
            
            writer.write("  </graph>\n");
            writer.write("</graphml>\n");
        }
    }
    
    /**
     * 親クラスを取得
     */
    private String getParentClasses(String className) {
        Set<String> parents = classHierarchy.getOrDefault(className, Collections.emptySet());
        return String.join(", ", parents);
    }
    
    /**
     * 呼び出し先が親クラスのメソッドかどうか
     */
    private boolean isParentClassMethod(String callerMethod, String calleeMethod) {
        String callerClass = methodToClassMap.getOrDefault(callerMethod, "");
        String calleeClass = methodToClassMap.getOrDefault(calleeMethod, "");
        
        Set<String> parents = classHierarchy.getOrDefault(callerClass, Collections.emptySet());
        return parents.contains(calleeClass);
    }
    
    /**
     * インターフェース/抽象クラスの実装候補を取得
     */
    private String getImplementations(String methodSignature) {
        String className = methodToClassMap.getOrDefault(methodSignature, "");
        Set<String> impls = interfaceImplementations.getOrDefault(className, Collections.emptySet());
        return String.join(", ", impls);
    }
    
    /**
     * TSV/CSV用のエスケープ
     */
    private String escape(String str) {
        if (str == null) return "";
        return str.replace("\t", " ").replace("\n", " ").replace("\r", "");
    }
}