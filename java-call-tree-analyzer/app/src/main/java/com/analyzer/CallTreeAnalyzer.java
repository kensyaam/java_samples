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
    private Map<String, MethodMetadata> methodMetadata = new HashMap<>();
    private Map<String, ClassMetadata> classMetadata = new HashMap<>();
    private boolean debugMode = false; // デバッグモードフラグ
    
    // クラスのメタデータを保持するクラス
    static class ClassMetadata {
        Set<String> annotations = new HashSet<>();
        Set<String> interfaces = new HashSet<>();
        String superClass;
        boolean isWebService;
        boolean isWebServiceProvider;
        
        public ClassMetadata(CtType<?> type) {
            // クラスアノテーション
            type.getAnnotations().forEach(ann -> {
                String annName = ann.getAnnotationType().getSimpleName();
                annotations.add(annName);
                
                // SOAP Webサービスの検出
                if (annName.equals("WebService") || annName.equals("WebServiceProvider")) {
                    isWebService = true;
                }
                if (annName.equals("WebServiceProvider")) {
                    isWebServiceProvider = true;
                }
            });
            
            // インターフェース
            type.getSuperInterfaces().forEach(iface -> {
                String ifaceName = iface.getQualifiedName();
                interfaces.add(ifaceName);
                
                // JAX-WS Provider interface
                if (ifaceName.equals("javax.xml.ws.Provider") || 
                    ifaceName.equals("jakarta.xml.ws.Provider")) {
                    isWebServiceProvider = true;
                }
            });
            
            // スーパークラス
            if (type instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) type;
                CtTypeReference<?> superClassRef = ctClass.getSuperclass();
                if (superClassRef != null) {
                    superClass = superClassRef.getQualifiedName();
                }
            }
        }
        
        public boolean isFrameworkManagedClass() {
            // Spring管理クラス
            if (annotations.contains("Controller") ||
                annotations.contains("RestController") ||
                annotations.contains("Service") ||
                annotations.contains("Repository") ||
                annotations.contains("Component") ||
                annotations.contains("Configuration")) {
                return true;
            }
            
            // SOAP Webサービス
            if (isWebService || isWebServiceProvider) {
                return true;
            }
            
            // Servlet
            if (superClass != null && 
                (superClass.contains("HttpServlet") || 
                 superClass.contains("GenericServlet"))) {
                return true;
            }
            
            // Spring Boot起動クラス
            if (interfaces.contains("org.springframework.boot.CommandLineRunner") ||
                interfaces.contains("org.springframework.boot.ApplicationRunner")) {
                return true;
            }
            
            // Runnable/Callable
            if (interfaces.contains("java.lang.Runnable") ||
                interfaces.contains("java.util.concurrent.Callable")) {
                return true;
            }
            
            return false;
        }
    }
    
    // メソッドのメタデータを保持するクラス
    static class MethodMetadata {
        boolean isPublic;
        boolean isProtected;
        boolean isStatic;
        boolean isMain;
        boolean isAbstract;
        Set<String> annotations = new HashSet<>();
        Set<String> classAnnotations = new HashSet<>();
        String returnType;
        String methodName;
        int parameterCount;
        String declaringClass;
        String superClass;
        Set<String> interfaces = new HashSet<>();
        
        public MethodMetadata(CtMethod<?> method, ClassMetadata classMeta, boolean debugMode) {
            this.isPublic = method.isPublic();
            this.isProtected = method.hasModifier(spoon.reflect.declaration.ModifierKind.PROTECTED);
            this.isStatic = method.isStatic();
            this.isAbstract = method.isAbstract();
            this.methodName = method.getSimpleName();
            this.parameterCount = method.getParameters().size();
            
            // main メソッド判定
            this.isMain = method.getSimpleName().equals("main") && 
                         method.isStatic() && 
                         method.isPublic() &&
                         method.getParameters().size() == 1;
            
            // アノテーションを収集（完全修飾名と単純名の両方をチェック）
            method.getAnnotations().forEach(ann -> {
                String simpleName = ann.getAnnotationType().getSimpleName();
                String qualifiedName = ann.getAnnotationType().getQualifiedName();
                annotations.add(simpleName);
                
                // デバッグ: WebMethod等の重要なアノテーションをログ出力
                if (debugMode && (simpleName.equals("WebMethod") || 
                    simpleName.equals("WebService") ||
                    simpleName.equals("RequestMapping") ||
                    simpleName.contains("Mapping"))) {
                    System.out.println("  Found annotation: " + qualifiedName + 
                                     " on method: " + method.getSignature());
                }
            });
            
            // クラス情報を保存
            if (classMeta != null) {
                this.classAnnotations = classMeta.annotations;
                this.superClass = classMeta.superClass;
                this.interfaces = classMeta.interfaces;
            }
            
            // 戻り値の型
            if (method.getType() != null) {
                this.returnType = method.getType().getSimpleName();
            }
            
            // 宣言クラス
            CtType<?> declaringType = method.getDeclaringType();
            if (declaringType != null) {
                this.declaringClass = declaringType.getQualifiedName();
            }
        }
        
        public boolean isEntryPointCandidate() {
            // 1. main メソッド
            if (isMain) return true;
            
            // 2. テストメソッド
            if (isTestMethod()) return true;
            
            // 3. HTTP エンドポイント
            if (isHttpEndpoint()) return true;
            
            // 4. SOAP エンドポイント (Apache CXF / JAX-WS)
            if (isSoapEndpoint()) return true;
            
            // 5. スケジュールジョブ
            if (isScheduledJob()) return true;
            
            // 6. イベントリスナー
            if (isEventListener()) return true;
            
            // 7. ライフサイクルメソッド
            if (isLifecycleMethod()) return true;
            
            // 8. Servlet メソッド
            if (isServletMethod()) return true;
            
            // 9. Spring Boot起動メソッド
            if (isSpringBootRunner()) return true;
            
            // 10. Runnable/Callable
            if (isRunnableOrCallable()) return true;
            
            // 11. Bean factory メソッド
            if (annotations.contains("Bean")) return true;
            
            return false;
        }
        
        private boolean isTestMethod() {
            return annotations.contains("Test") || 
                   annotations.contains("TestTemplate") ||
                   annotations.contains("ParameterizedTest") ||
                   annotations.contains("RepeatedTest") ||
                   annotations.contains("TestFactory");
        }
        
        private boolean isHttpEndpoint() {
            // Spring
            if (annotations.contains("RequestMapping") ||
                annotations.contains("GetMapping") ||
                annotations.contains("PostMapping") ||
                annotations.contains("PutMapping") ||
                annotations.contains("DeleteMapping") ||
                annotations.contains("PatchMapping")) {
                return true;
            }
            
            // クラスレベルのController + publicメソッド
            if ((classAnnotations.contains("Controller") || 
                 classAnnotations.contains("RestController")) && 
                isPublic) {
                return true;
            }
            
            // JAX-RS
            if (annotations.contains("Path") ||
                annotations.contains("GET") ||
                annotations.contains("POST") ||
                annotations.contains("PUT") ||
                annotations.contains("DELETE") ||
                annotations.contains("PATCH")) {
                return true;
            }
            
            return false;
        }
        
        private boolean isSoapEndpoint() {
            // JAX-WS / Apache CXF SOAP エンドポイント
            
            // 1. メソッドレベルのWebMethodアノテーション（最優先）
            if (annotations.contains("WebMethod")) {
                return true;
            }
            
            // 2. クラスレベルでWebServiceアノテーションがある場合のpublicメソッド
            if (classAnnotations.contains("WebService") && isPublic && !isStatic) {
                // @WebMethod(exclude=true) は除外すべきだが、
                // Spoonでアノテーションの属性値を取得するのは複雑なので、
                // ここではpublicメソッドを対象とする
                
                // 除外すべきメソッド（Object クラスのメソッドなど）
                if (methodName.equals("equals") || 
                    methodName.equals("hashCode") || 
                    methodName.equals("toString") ||
                    methodName.equals("getClass") ||
                    methodName.equals("notify") ||
                    methodName.equals("notifyAll") ||
                    methodName.equals("wait") ||
                    methodName.equals("finalize") ||
                    methodName.equals("clone")) {
                    return false;
                }
                
                // getter/setterっぽいメソッドも除外（厳密にはWSDLに含まれる可能性あり）
                // ただし、明示的に@WebMethodがついている場合は上で既にtrueを返している
                if ((methodName.startsWith("get") || methodName.startsWith("set") || 
                     methodName.startsWith("is")) && parameterCount <= 1) {
                    // getter/setterでもWebサービスメソッドの可能性があるため
                    // ここでは除外しない（必要に応じて調整可能）
                }
                
                return true;
            }
            
            // 3. WebServiceProviderの場合のinvokeメソッド
            if (classAnnotations.contains("WebServiceProvider") && 
                methodName.equals("invoke") &&
                isPublic) {
                return true;
            }
            
            // 4. Provider<T> インターフェースのinvokeメソッド
            if ((interfaces.contains("javax.xml.ws.Provider") || 
                 interfaces.contains("jakarta.xml.ws.Provider")) &&
                methodName.equals("invoke") &&
                isPublic) {
                return true;
            }
            
            // 5. Apache CXF特有のパターン
            // @WebService(endpointInterface = "...") で実装クラス側にアノテーションがない場合でも
            // インターフェースに@WebServiceがあればエンドポイントとなる
            // ただし、インターフェース情報からの判定は複雑なため、
            // 最低限クラスレベルの@WebServiceで判定
            
            return false;
        }
        
        private boolean isScheduledJob() {
            return annotations.contains("Scheduled") ||
                   annotations.contains("Schedules") ||
                   annotations.contains("Async");
        }
        
        private boolean isEventListener() {
            return annotations.contains("EventListener") ||
                   annotations.contains("TransactionalEventListener") ||
                   annotations.contains("JmsListener") ||
                   annotations.contains("RabbitListener") ||
                   annotations.contains("KafkaListener") ||
                   annotations.contains("StreamListener") ||
                   annotations.contains("MessageMapping") ||
                   annotations.contains("SubscribeMapping");
        }
        
        private boolean isLifecycleMethod() {
            // Spring lifecycle
            if (annotations.contains("PostConstruct") ||
                annotations.contains("PreDestroy")) {
                return true;
            }
            
            // JUnit lifecycle
            if (annotations.contains("BeforeAll") ||
                annotations.contains("AfterAll") ||
                annotations.contains("BeforeEach") ||
                annotations.contains("AfterEach") ||
                annotations.contains("Before") ||
                annotations.contains("After") ||
                annotations.contains("BeforeClass") ||
                annotations.contains("AfterClass")) {
                return true;
            }
            
            return false;
        }
        
        private boolean isServletMethod() {
            if (superClass == null) return false;
            
            boolean isServlet = superClass.contains("HttpServlet") || 
                               superClass.contains("GenericServlet");
            
            if (!isServlet) return false;
            
            // Servletの典型的なメソッド
            if (methodName.equals("doGet") || 
                methodName.equals("doPost") ||
                methodName.equals("doPut") ||
                methodName.equals("doDelete") ||
                methodName.equals("service") ||
                methodName.equals("init") ||
                methodName.equals("destroy")) {
                return true;
            }
            
            return false;
        }
        
        private boolean isSpringBootRunner() {
            if (interfaces.contains("org.springframework.boot.CommandLineRunner") &&
                methodName.equals("run")) {
                return true;
            }
            
            if (interfaces.contains("org.springframework.boot.ApplicationRunner") &&
                methodName.equals("run")) {
                return true;
            }
            
            return false;
        }
        
        private boolean isRunnableOrCallable() {
            if (interfaces.contains("java.lang.Runnable") && 
                methodName.equals("run") && 
                parameterCount == 0) {
                return true;
            }
            
            if (interfaces.contains("java.util.concurrent.Callable") && 
                methodName.equals("call") && 
                parameterCount == 0) {
                return true;
            }
            
            return false;
        }
        
        public String getEntryPointType() {
            if (isMain) return "Main";
            if (isTestMethod()) return "Test";
            if (isHttpEndpoint()) return "HTTP";
            if (isSoapEndpoint()) return "SOAP";
            if (isScheduledJob()) return "Scheduled";
            if (isEventListener()) return "Event";
            if (isLifecycleMethod()) return "Lifecycle";
            if (isServletMethod()) return "Servlet";
            if (isSpringBootRunner()) return "SpringBoot";
            if (isRunnableOrCallable()) return "Thread";
            if (annotations.contains("Bean")) return "Bean";
            return "";
        }
    }
    
    public static void main(String[] args) {
        Options options = new Options();
        
        options.addOption("s", "source", true, "解析対象のソースディレクトリ（複数指定可、カンマ区切り）");
        options.addOption("cp", "classpath", true, "依存ライブラリのJARファイルまたはディレクトリ（複数指定可、カンマ区切り）");
        options.addOption("o", "output", true, "出力ファイルパス（デフォルト: call-tree.tsv）");
        options.addOption("f", "format", true, "出力フォーマット（tsv/json/graphml、デフォルト: tsv）");
        options.addOption("d", "debug", false, "デバッグモードを有効化");
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
            boolean debug = cmd.hasOption("debug");
            
            CallTreeAnalyzer analyzer = new CallTreeAnalyzer();
            analyzer.setDebugMode(debug);
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
     * デバッグモードを設定
     */
    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
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
     * メソッド情報を収集
     */
    private void collectMethods() {
        // まずクラス情報を収集
        List<CtType<?>> types = model.getElements(new TypeFilter<>(CtType.class));
        for (CtType<?> type : types) {
            classMetadata.put(type.getQualifiedName(), new ClassMetadata(type));
        }
        
        // メソッド情報を収集
        List<CtMethod<?>> methods = model.getElements(new TypeFilter<>(CtMethod.class));
        
        for (CtMethod<?> method : methods) {
            String signature = getMethodSignature(method);
            methodMap.put(signature, method);
            methodSignatureMap.put(signature, method);
            
            CtType<?> declaringType = method.getDeclaringType();
            String className = declaringType != null ? declaringType.getQualifiedName() : null;
            
            // メタデータを収集（クラス情報を含む）
            ClassMetadata classMeta = className != null ? classMetadata.get(className) : null;
            methodMetadata.put(signature, new MethodMetadata(method, classMeta, debugMode));
            
            if (declaringType != null) {
                methodToClassMap.put(signature, className);
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
                        "呼び出し先の実装クラス候補\tSQL文\t方向\t" +
                        "可視性\tStatic\tエントリーポイント候補\tエントリータイプ\tアノテーション\tクラスアノテーション\n");
            
            // 呼び出し元からのツリー
            for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
                String caller = entry.getKey();
                String callerClass = methodToClassMap.getOrDefault(caller, "");
                String callerParents = getParentClasses(callerClass);
                MethodMetadata callerMeta = methodMetadata.get(caller);
                
                for (String callee : entry.getValue()) {
                    String calleeClass = methodToClassMap.getOrDefault(callee, "");
                    boolean isParentMethod = isParentClassMethod(caller, callee);
                    String implementations = getImplementations(callee);
                    String sql = sqlStatements.containsKey(caller) ? 
                                String.join("; ", sqlStatements.get(caller)) : "";
                    
                    String visibility = callerMeta != null && callerMeta.isPublic ? "public" : 
                                       (callerMeta != null && callerMeta.isProtected ? "protected" : "private");
                    String isStatic = callerMeta != null && callerMeta.isStatic ? "Yes" : "No";
                    String isEntry = callerMeta != null && callerMeta.isEntryPointCandidate() ? "Yes" : "No";
                    String entryType = callerMeta != null ? callerMeta.getEntryPointType() : "";
                    String annotations = callerMeta != null ? String.join(",", callerMeta.annotations) : "";
                    String classAnnotations = callerMeta != null ? String.join(",", callerMeta.classAnnotations) : "";
                    
                    writer.write(String.format("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
                        escape(caller), escape(callerClass), escape(callerParents),
                        escape(callee), escape(calleeClass), isParentMethod ? "Yes" : "No",
                        escape(implementations), escape(sql), "Forward",
                        visibility, isStatic, isEntry, entryType, escape(annotations), escape(classAnnotations)));
                }
            }
            
            // 呼び出し先からのツリー（逆方向）
            for (Map.Entry<String, Set<String>> entry : reverseCallGraph.entrySet()) {
                String callee = entry.getKey();
                String calleeClass = methodToClassMap.getOrDefault(callee, "");
                
                for (String caller : entry.getValue()) {
                    String callerClass = methodToClassMap.getOrDefault(caller, "");
                    String callerParents = getParentClasses(callerClass);
                    MethodMetadata callerMeta = methodMetadata.get(caller);
                    
                    String visibility = callerMeta != null && callerMeta.isPublic ? "public" : 
                                       (callerMeta != null && callerMeta.isProtected ? "protected" : "private");
                    String isStatic = callerMeta != null && callerMeta.isStatic ? "Yes" : "No";
                    String isEntry = callerMeta != null && callerMeta.isEntryPointCandidate() ? "Yes" : "No";
                    String entryType = callerMeta != null ? callerMeta.getEntryPointType() : "";
                    String annotations = callerMeta != null ? String.join(",", callerMeta.annotations) : "";
                    String classAnnotations = callerMeta != null ? String.join(",", callerMeta.classAnnotations) : "";
                    
                    writer.write(String.format("%s\t%s\t%s\t%s\t%s\t\t\t\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
                        escape(callee), escape(calleeClass), "",
                        escape(caller), escape(callerClass), "Reverse",
                        visibility, isStatic, isEntry, entryType, escape(annotations), escape(classAnnotations)));
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
