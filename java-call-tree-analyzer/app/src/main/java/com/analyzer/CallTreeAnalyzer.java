package com.analyzer;

import org.apache.commons.cli.*;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.reflect.code.*;

import java.io.*;
import java.nio.charset.Charset;
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
    private Map<String, MethodMetadata> methodMetadata = new HashMap<>();
    private Map<String, ClassMetadata> classMetadata = new HashMap<>();
    private Map<String, Map<String, String>> fieldInjections = new HashMap<>(); // クラス -> (フィールド名 -> 型)
    private Map<String, FieldInjectionInfo> fieldInjectionDetails = new HashMap<>(); // クラス.フィールド名 -> 詳細情報
    private Map<String, BeanDefinition> beanDefinitions = new HashMap<>(); // beanId -> Bean定義
    private boolean debugMode = false; // デバッグモードフラグ
    private Set<String> searchWords = new HashSet<>(); // 検索ワード
    private Map<String, Set<String>> methodHitWords = new HashMap<>(); // メソッド -> 検出ワード
    private Map<String, Set<String>> methodCreatedInstances = new HashMap<>(); // メソッド -> 生成されたインスタンスのクラス
    private Map<String, List<FieldInitializer>> classFieldInitializers = new HashMap<>(); // クラス -> フィールド初期化情報
    private Map<String, List<HttpCallInfo>> httpCalls = new HashMap<>(); // メソッド -> HTTPリクエスト情報

    /**
     * HTTPリクエスト情報
     */
    static class HttpCallInfo {
        String httpMethod; // GET, POST, PUT, DELETE, PATCH, etc.
        String uri; // URIパス or プロパティキー
        String clientLibrary; // "Apache HttpClient 4.x", "CXF WebClient", "RestTemplate", etc.

        HttpCallInfo(String httpMethod, String uri, String clientLibrary) {
            this.httpMethod = httpMethod;
            this.uri = uri;
            this.clientLibrary = clientLibrary;
        }
    }

    /**
     * フィールド初期化情報
     */
    static class FieldInitializer {
        String fieldName;
        String fieldType;
        String initializedClass;

        FieldInitializer(String fieldName, String fieldType, String initializedClass) {
            this.fieldName = fieldName;
            this.fieldType = fieldType;
            this.initializedClass = initializedClass;
        }
    }

    /**
     * Bean定義情報
     */
    static class BeanDefinition {
        String id;
        String className;
        String source; // "XML", "Component", "Service" など

        BeanDefinition(String id, String className, String source) {
            this.id = id;
            this.className = className;
            this.source = source;
        }
    }

    /**
     * フィールドインジェクション情報
     */
    static class FieldInjectionInfo {
        String fieldName;
        String fieldType;
        String qualifierId; // @Qualifier で指定されたID
        String ownerClass;

        FieldInjectionInfo(String ownerClass, String fieldName, String fieldType, String qualifierId) {
            this.ownerClass = ownerClass;
            this.fieldName = fieldName;
            this.fieldType = fieldType;
            this.qualifierId = qualifierId;
        }
    }

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
        Set<String> annotationRaws = new HashSet<>();
        Set<String> classAnnotations = new HashSet<>();
        String returnType;
        String methodName;
        int parameterCount;
        String declaringClass;
        String superClass;
        Set<String> interfaces = new HashSet<>();
        String javadocSummary;
        // 引数アノテーション情報（例: "@ModelAttribute("myDto") UserForm form"）
        String parameterAnnotations;

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
                String annotationStr = ann.toString();
                annotations.add(simpleName);
                annotationRaws.add(annotationStr);

                // デバッグ: WebMethod等の重要なアノテーションをログ出力
                if (debugMode && (simpleName.equals("WebMethod") ||
                        simpleName.equals("WebService") ||
                        simpleName.equals("RequestMapping") ||
                        simpleName.contains("Mapping"))) {
                    System.out.println("  Found annotation: " + annotationStr +
                            " on method: " + method.getSignature());
                }
            });

            // 親インターフェース・親クラスのオーバーライド元メソッドからもアノテーションを収集
            collectInheritedMethodAnnotations(method, annotations, annotationRaws, new HashSet<>());

            // クラス情報を保存
            if (classMeta != null) {
                this.classAnnotations = classMeta.annotations;
                this.superClass = classMeta.superClass;
                this.interfaces = classMeta.interfaces;
            }

            // Javadocの1行目を抽出（存在すれば）
            this.javadocSummary = "";
            try {
                String doc = null;
                try {
                    doc = method.getDocComment();
                } catch (Throwable t) {
                    // Spoon implementation may not provide doc comment; ignore
                    doc = null;
                }

                // getDocComment()が空の場合、getComments()からJavadocを探す
                // （アノテーションがメソッドとJavadocの間にある場合対策）
                if ((doc == null || doc.isEmpty()) && method.getComments() != null) {
                    for (CtComment comment : method.getComments()) {
                        if (comment.getCommentType() == CtComment.CommentType.JAVADOC) {
                            doc = comment.getContent();
                            break;
                        }
                    }
                }

                if (doc != null) {
                    // コメントマーカーを取り除き、最初の有効な行を取得
                    String cleaned = doc.replaceAll("(?s)/\\*\\*|\\*/", "");
                    String[] lines = cleaned.split("\\r?\\n");
                    for (String l : lines) {
                        String s = l.trim();
                        s = s.replaceFirst("^\\*+\\s?", "").trim();
                        if (!s.isEmpty()) {
                            this.javadocSummary = s;
                            break;
                        }
                    }

                    // @inheritDoc または {@inheritDoc} が含まれている場合、親メソッドからJavadocを継承
                    if (this.javadocSummary.contains("@inheritDoc") ||
                            this.javadocSummary.contains("{@inheritDoc}")) {
                        String inheritedDoc = getInheritedJavadoc(method);
                        if (inheritedDoc != null && !inheritedDoc.isEmpty()) {
                            this.javadocSummary = inheritedDoc;
                        }
                    }
                }

                // Javadocが空で@Overrideアノテーションがある場合も、親メソッドからJavadocを継承
                if ((this.javadocSummary == null || this.javadocSummary.isEmpty()) &&
                        annotations.contains("Override")) {
                    String inheritedDoc = getInheritedJavadoc(method);
                    if (inheritedDoc != null && !inheritedDoc.isEmpty()) {
                        this.javadocSummary = inheritedDoc;
                    }
                }
            } catch (Exception e) {
                this.javadocSummary = "";
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

            // 引数アノテーションを収集
            // 例: "@ModelAttribute("myDto") UserForm form, @RequestParam String id"
            List<String> paramAnnotations = new ArrayList<>();
            for (CtParameter<?> param : method.getParameters()) {
                StringBuilder paramStr = new StringBuilder();

                // 引数に付与されたアノテーションを収集
                List<CtAnnotation<?>> paramAnns = param.getAnnotations();
                for (CtAnnotation<?> ann : paramAnns) {
                    paramStr.append(ann.toString()).append(" ");
                }

                // 型名（シンプル名のみ）
                String typeName = param.getType().getSimpleName();
                paramStr.append(typeName);

                // 変数名
                paramStr.append(" ");
                paramStr.append(param.getSimpleName());

                paramAnnotations.add(paramStr.toString().trim());
            }

            // アノテーション付きの引数のみをフィルタして出力
            // アノテーションが付いている引数（@で始まるもの）のみ出力
            List<String> annotatedParams = new ArrayList<>();
            for (String p : paramAnnotations) {
                if (p.startsWith("@")) {
                    annotatedParams.add(p);
                }
            }

            // アノテーション付き引数がない場合、親クラス・インターフェースから継承
            if (annotatedParams.isEmpty()) {
                List<String> inheritedParams = collectInheritedParameterAnnotations(method, new HashSet<>());
                if (!inheritedParams.isEmpty()) {
                    annotatedParams = inheritedParams;
                }
            }

            this.parameterAnnotations = String.join(", ", annotatedParams);
        }

        public boolean isEntryPointCandidate() {
            // 1. main メソッド
            if (isMain)
                return true;

            // 2. テストメソッド
            if (isTestMethod())
                return true;

            // 3. HTTP エンドポイント
            if (isHttpEndpoint())
                return true;

            // 4. SOAP エンドポイント (Apache CXF / JAX-WS)
            if (isSoapEndpoint())
                return true;

            // 5. スケジュールジョブ
            if (isScheduledJob())
                return true;

            // 6. イベントリスナー
            if (isEventListener())
                return true;

            // 7. ライフサイクルメソッド
            if (isLifecycleMethod())
                return true;

            // 8. Servlet メソッド
            if (isServletMethod())
                return true;

            // 9. Spring Boot起動メソッド
            if (isSpringBootRunner())
                return true;

            // 10. Runnable/Callable
            if (isRunnableOrCallable())
                return true;

            // 11. Bean factory メソッド
            if (annotations.contains("Bean"))
                return true;

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

                // setterは除外
                if (methodName.startsWith("set") && parameterCount == 1) {
                    return false;
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
            if (superClass == null)
                return false;

            boolean isServlet = superClass.contains("HttpServlet") ||
                    superClass.contains("GenericServlet");

            if (!isServlet)
                return false;

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
            if (isMain)
                return "Main";
            if (isTestMethod())
                return "Test";
            if (isHttpEndpoint())
                return "HTTP";
            if (isSoapEndpoint())
                return "SOAP";
            if (isScheduledJob())
                return "Scheduled";
            if (isEventListener())
                return "Event";
            if (isLifecycleMethod())
                return "Lifecycle";
            if (isServletMethod())
                return "Servlet";
            if (isSpringBootRunner())
                return "SpringBoot";
            if (isRunnableOrCallable())
                return "Thread";
            if (annotations.contains("Bean"))
                return "Bean";
            return "";
        }

        /**
         * 親クラスまたはインターフェースからJavadocを継承
         * 親の親クラス、親クラスのインターフェース、インターフェースの親インターフェースも再帰的にチェック
         */
        private String getInheritedJavadoc(CtMethod<?> method) {
            if (method == null || method.getDeclaringType() == null) {
                return null;
            }

            // 無限ループを防ぐため、訪問済みの型を追跡
            Set<String> visitedTypes = new HashSet<>();
            return searchInType(method, method.getDeclaringType(), visitedTypes);
        }

        /**
         * 指定された型とその親型を再帰的に検索してJavadocを取得
         */
        private String searchInType(CtMethod<?> method, CtType<?> type, Set<String> visitedTypes) {
            if (type == null) {
                return null;
            }

            // 型の完全修飾名を取得して訪問済みチェック
            String typeName = type.getQualifiedName();
            if (visitedTypes.contains(typeName)) {
                return null; // 既に訪問済み（循環参照を防ぐ）
            }
            visitedTypes.add(typeName);

            // 1. 親クラスから検索
            if (type instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) type;
                CtTypeReference<?> superClassRef = ctClass.getSuperclass();

                if (superClassRef != null) {
                    try {
                        CtType<?> superClass = superClassRef.getTypeDeclaration();
                        if (superClass != null) {
                            // 親クラスのメソッドを検索
                            CtMethod<?> parentMethod = findParentMethod(method, superClass);
                            if (parentMethod != null) {
                                String doc = extractJavadocSummary(parentMethod);
                                // 親メソッドも@inheritDocを使用している場合は再帰的に解決
                                if (doc != null && (doc.contains("@inheritDoc") || doc.contains("{@inheritDoc}"))) {
                                    String inheritedDoc = searchInType(method, superClass, visitedTypes);
                                    if (inheritedDoc != null && !inheritedDoc.isEmpty()) {
                                        return inheritedDoc;
                                    }
                                } else if (doc != null && !doc.isEmpty()) {
                                    return doc;
                                }
                            }

                            // 親クラスのメソッドが見つからない場合、親クラスのインターフェースも検索
                            String docFromParentInterfaces = searchInInterfaces(method, superClass, visitedTypes);
                            if (docFromParentInterfaces != null && !docFromParentInterfaces.isEmpty()) {
                                return docFromParentInterfaces;
                            }

                            // さらに親クラスの親クラスも検索
                            String docFromGrandParent = searchInType(method, superClass, visitedTypes);
                            if (docFromGrandParent != null && !docFromGrandParent.isEmpty()) {
                                return docFromGrandParent;
                            }
                        }
                    } catch (Exception e) {
                        // 型解決できない場合は無視
                    }
                }
            }

            // 2. 直接実装しているインターフェースから検索
            String docFromInterfaces = searchInInterfaces(method, type, visitedTypes);
            if (docFromInterfaces != null && !docFromInterfaces.isEmpty()) {
                return docFromInterfaces;
            }

            return null;
        }

        /**
         * インターフェースとその親インターフェースを再帰的に検索
         */
        private String searchInInterfaces(CtMethod<?> method, CtType<?> type, Set<String> visitedTypes) {
            if (type == null) {
                return null;
            }

            Set<CtTypeReference<?>> interfaces = type.getSuperInterfaces();
            for (CtTypeReference<?> ifaceRef : interfaces) {
                try {
                    CtType<?> iface = ifaceRef.getTypeDeclaration();
                    if (iface != null) {
                        String ifaceName = iface.getQualifiedName();
                        if (visitedTypes.contains(ifaceName)) {
                            continue; // 既に訪問済み
                        }
                        visitedTypes.add(ifaceName);

                        // インターフェースのメソッドを検索
                        CtMethod<?> parentMethod = findParentMethod(method, iface);
                        if (parentMethod != null) {
                            String doc = extractJavadocSummary(parentMethod);
                            // インターフェースメソッドも@inheritDocを使用している場合は再帰的に解決
                            if (doc != null && (doc.contains("@inheritDoc") || doc.contains("{@inheritDoc}"))) {
                                // インターフェースの親インターフェースを検索
                                String inheritedDoc = searchInInterfaces(method, iface, visitedTypes);
                                if (inheritedDoc != null && !inheritedDoc.isEmpty()) {
                                    return inheritedDoc;
                                }
                            } else if (doc != null && !doc.isEmpty()) {
                                return doc;
                            }
                        }

                        // インターフェースの親インターフェースも検索
                        String docFromParentInterface = searchInInterfaces(method, iface, visitedTypes);
                        if (docFromParentInterface != null && !docFromParentInterface.isEmpty()) {
                            return docFromParentInterface;
                        }
                    }
                } catch (Exception e) {
                    // 型解決できない場合は無視
                }
            }

            return null;
        }

        /**
         * 指定された型から対応するメソッドを検索
         */
        private CtMethod<?> findParentMethod(CtMethod<?> method, CtType<?> parentType) {
            if (method == null || parentType == null) {
                return null;
            }

            String methodName = method.getSimpleName();
            List<CtParameter<?>> params = method.getParameters();

            // 親型のメソッドを検索
            for (CtMethod<?> parentMethod : parentType.getMethods()) {
                if (!parentMethod.getSimpleName().equals(methodName)) {
                    continue;
                }

                // パラメータ数が一致するか確認
                List<CtParameter<?>> parentParams = parentMethod.getParameters();
                if (params.size() != parentParams.size()) {
                    continue;
                }

                // パラメータの型が一致するか確認
                boolean allMatch = true;
                for (int i = 0; i < params.size(); i++) {
                    String paramType = params.get(i).getType().getQualifiedName();
                    String parentParamType = parentParams.get(i).getType().getQualifiedName();
                    if (!paramType.equals(parentParamType)) {
                        allMatch = false;
                        break;
                    }
                }

                if (allMatch) {
                    return parentMethod;
                }
            }

            return null;
        }

        /**
         * メソッドからJavadocの要約を抽出
         */
        private String extractJavadocSummary(CtMethod<?> method) {
            if (method == null) {
                return null;
            }

            try {
                String doc = null;
                try {
                    doc = method.getDocComment();
                } catch (Throwable t) {
                    return null;
                }

                if (doc != null) {
                    String cleaned = doc.replaceAll("(?s)/\\*\\*|\\*/", "");
                    String[] lines = cleaned.split("\\r?\\n");
                    for (String l : lines) {
                        String s = l.trim();
                        s = s.replaceFirst("^\\*+\\s?", "").trim();
                        if (!s.isEmpty()) {
                            return s;
                        }
                    }
                }
            } catch (Exception e) {
                return null;
            }

            return null;
        }

        /**
         * 親クラスおよびインターフェースのオーバーライド元メソッドからアノテーションを再帰的に収集
         */
        private void collectInheritedMethodAnnotations(CtMethod<?> method,
                Set<String> annotations,
                Set<String> annotationRaws,
                Set<String> visitedTypes) {
            if (method == null || method.getDeclaringType() == null) {
                return;
            }

            CtType<?> declaringType = method.getDeclaringType();

            // 親クラスのメソッドからアノテーションを収集
            if (declaringType instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) declaringType;
                CtTypeReference<?> superClassRef = ctClass.getSuperclass();
                if (superClassRef != null) {
                    try {
                        CtType<?> superType = superClassRef.getTypeDeclaration();
                        if (superType != null) {
                            collectAnnotationsFromType(method, superType, annotations, annotationRaws, visitedTypes);
                        }
                    } catch (Exception e) {
                        // 型解決できない場合は無視
                    }
                }
            }

            // インターフェースのメソッドからアノテーションを収集
            try {
                for (CtTypeReference<?> iface : declaringType.getSuperInterfaces()) {
                    try {
                        CtType<?> ifaceType = iface.getTypeDeclaration();
                        if (ifaceType != null) {
                            collectAnnotationsFromType(method, ifaceType, annotations, annotationRaws, visitedTypes);
                        }
                    } catch (Exception e) {
                        // 型解決できない場合は無視
                    }
                }
            } catch (Exception e) {
                // 無視
            }
        }

        /**
         * 指定された型から対応するメソッドを探し、そのアノテーションを収集
         */
        private void collectAnnotationsFromType(CtMethod<?> method,
                CtType<?> targetType,
                Set<String> annotations,
                Set<String> annotationRaws,
                Set<String> visitedTypes) {
            if (targetType == null) {
                return;
            }

            String typeName = targetType.getQualifiedName();
            if (visitedTypes.contains(typeName)) {
                return;
            }
            visitedTypes.add(typeName);

            // 対応するメソッドを検索
            CtMethod<?> parentMethod = findParentMethod(method, targetType);
            if (parentMethod != null) {
                // 親メソッドのアノテーションを追加
                parentMethod.getAnnotations().forEach(ann -> {
                    String simpleName = ann.getAnnotationType().getSimpleName();
                    String annotationStr = ann.toString();
                    annotations.add(simpleName);
                    annotationRaws.add(annotationStr);
                });
            }

            // さらに親を辿る（再帰）
            // 親クラス
            if (targetType instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) targetType;
                CtTypeReference<?> superClassRef = ctClass.getSuperclass();
                if (superClassRef != null) {
                    try {
                        CtType<?> superType = superClassRef.getTypeDeclaration();
                        if (superType != null) {
                            collectAnnotationsFromType(method, superType, annotations, annotationRaws, visitedTypes);
                        }
                    } catch (Exception e) {
                        // 無視
                    }
                }
            }

            // インターフェース
            try {
                for (CtTypeReference<?> iface : targetType.getSuperInterfaces()) {
                    try {
                        CtType<?> ifaceType = iface.getTypeDeclaration();
                        if (ifaceType != null) {
                            collectAnnotationsFromType(method, ifaceType, annotations, annotationRaws, visitedTypes);
                        }
                    } catch (Exception e) {
                        // 無視
                    }
                }
            } catch (Exception e) {
                // 無視
            }
        }

        /**
         * 親クラス・インターフェースから引数アノテーションを継承
         * 
         * @param method       対象のメソッド
         * @param visitedTypes 訪問済み型（循環防止）
         * @return 継承した引数アノテーションのリスト（例: "@RequestParam("id") String id"）
         */
        private List<String> collectInheritedParameterAnnotations(CtMethod<?> method, Set<String> visitedTypes) {
            if (method == null || method.getDeclaringType() == null) {
                return Collections.emptyList();
            }

            CtType<?> declaringType = method.getDeclaringType();

            // 親クラスから継承
            if (declaringType instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) declaringType;
                CtTypeReference<?> superClassRef = ctClass.getSuperclass();
                if (superClassRef != null) {
                    try {
                        CtType<?> superType = superClassRef.getTypeDeclaration();
                        if (superType != null) {
                            List<String> result = collectParamAnnotationsFromType(method, superType, visitedTypes);
                            if (!result.isEmpty()) {
                                return result;
                            }
                        }
                    } catch (Exception e) {
                        // 型解決できない場合は無視
                    }
                }
            }

            // インターフェースから継承
            try {
                for (CtTypeReference<?> iface : declaringType.getSuperInterfaces()) {
                    try {
                        CtType<?> ifaceType = iface.getTypeDeclaration();
                        if (ifaceType != null) {
                            List<String> result = collectParamAnnotationsFromType(method, ifaceType, visitedTypes);
                            if (!result.isEmpty()) {
                                return result;
                            }
                        }
                    } catch (Exception e) {
                        // 型解決できない場合は無視
                    }
                }
            } catch (Exception e) {
                // 無視
            }

            return Collections.emptyList();
        }

        /**
         * 指定された型から対応するメソッドを探し、その引数アノテーションを収集
         */
        private List<String> collectParamAnnotationsFromType(CtMethod<?> method, CtType<?> targetType,
                Set<String> visitedTypes) {
            if (targetType == null) {
                return Collections.emptyList();
            }

            String typeName = targetType.getQualifiedName();
            if (visitedTypes.contains(typeName)) {
                return Collections.emptyList();
            }
            visitedTypes.add(typeName);

            // 対応するメソッドを検索
            CtMethod<?> parentMethod = findParentMethod(method, targetType);
            if (parentMethod != null) {
                // 親メソッドの引数アノテーションを収集
                List<String> annotatedParams = new ArrayList<>();
                for (CtParameter<?> param : parentMethod.getParameters()) {
                    StringBuilder paramStr = new StringBuilder();

                    // 引数に付与されたアノテーションを収集
                    List<CtAnnotation<?>> paramAnns = param.getAnnotations();
                    for (CtAnnotation<?> ann : paramAnns) {
                        paramStr.append(ann.toString()).append(" ");
                    }

                    // アノテーションがある場合のみ追加
                    if (!paramAnns.isEmpty()) {
                        // 型名（シンプル名のみ）
                        String paramTypeName = param.getType().getSimpleName();
                        paramStr.append(paramTypeName);

                        // 変数名
                        paramStr.append(" ");
                        paramStr.append(param.getSimpleName());

                        annotatedParams.add(paramStr.toString().trim());
                    }
                }

                if (!annotatedParams.isEmpty()) {
                    return annotatedParams;
                }
            }

            // さらに親を辿る（再帰）
            // 親クラス
            if (targetType instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) targetType;
                CtTypeReference<?> superClassRef = ctClass.getSuperclass();
                if (superClassRef != null) {
                    try {
                        CtType<?> superType = superClassRef.getTypeDeclaration();
                        if (superType != null) {
                            List<String> result = collectParamAnnotationsFromType(method, superType, visitedTypes);
                            if (!result.isEmpty()) {
                                return result;
                            }
                        }
                    } catch (Exception e) {
                        // 無視
                    }
                }
            }

            // インターフェース
            try {
                for (CtTypeReference<?> iface : targetType.getSuperInterfaces()) {
                    try {
                        CtType<?> ifaceType = iface.getTypeDeclaration();
                        if (ifaceType != null) {
                            List<String> result = collectParamAnnotationsFromType(method, ifaceType, visitedTypes);
                            if (!result.isEmpty()) {
                                return result;
                            }
                        }
                    } catch (Exception e) {
                        // 無視
                    }
                }
            } catch (Exception e) {
                // 無視
            }

            return Collections.emptyList();
        }
    }

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption("s", "source", true, "解析対象のソースディレクトリ（複数指定可、カンマ区切り）");
        options.addOption("cp", "classpath", true, "依存ライブラリのJARファイルまたはディレクトリ（複数指定可、カンマ区切り）");
        options.addOption("xml", "xml-config", true, "Spring設定XMLファイルのディレクトリ（複数指定可、カンマ区切り）");
        options.addOption("o", "output", true, "出力ファイルパス（デフォルト: analyzed_result.json）");
        options.addOption("f", "format", true, "出力フォーマット（json/graphml、デフォルト: json）");
        options.addOption("d", "debug", false, "デバッグモードを有効化");
        options.addOption("cl", "complianceLevel", true, "Javaのコンプライアンスレベル（デフォルト: 21）");
        options.addOption("e", "encoding", true, "ソースコードの文字エンコーディング（デフォルト: UTF-8）");
        options.addOption("w", "words", true, "リテラル文字列の検索ワードファイルのパス（デフォルト: search_words.txt）");
        options.addOption("h", "help", false, "ヘルプを表示");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                formatter.setOptionComparator(null);
                formatter.printHelp("CallTreeAnalyzer", options);
                return;
            }

            String sourceDirs = cmd.getOptionValue("source", "src/main/java");
            String classpath = cmd.getOptionValue("classpath", "");
            String xmlConfig = cmd.getOptionValue("xml-config", "");
            String outputPath = cmd.getOptionValue("output", "analyzed_result.json");
            String format = cmd.getOptionValue("format", "json");
            boolean debug = cmd.hasOption("debug");
            int complianceLevel = Integer.parseInt(cmd.getOptionValue("complianceLevel", "21"));
            String encoding = cmd.getOptionValue("encoding", "UTF-8");
            String wordsFile = cmd.getOptionValue("words", "search_words.txt");

            CallTreeAnalyzer analyzer = new CallTreeAnalyzer();
            analyzer.setDebugMode(debug);
            analyzer.analyze(sourceDirs, classpath, xmlConfig, complianceLevel, encoding, wordsFile);
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
    public void analyze(String sourceDirs, String classpath, String xmlConfig, int complianceLevel, String encoding,
            String wordsFile) {
        System.out.println("解析開始...");

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(false);
        launcher.getEnvironment().setComplianceLevel(complianceLevel);
        launcher.getEnvironment().setEncoding(Charset.forName(encoding));
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

        // 1. 検索ワードの読み込み
        loadSearchWords(wordsFile);

        // 2. XML Bean定義の解析
        if (!xmlConfig.isEmpty()) {
            parseXmlBeanDefinitions(xmlConfig);
        }

        // 3. メソッド情報の収集
        collectMethods();

        // 4. クラス階層の収集
        collectClassHierarchy();

        // 5. アノテーションベースのBean定義を収集
        collectAnnotationBasedBeans();

        // 6. 呼び出し関係の解析
        analyzeCallRelations();

        // 7. フィールドインジェクションの解析
        analyzeFieldInjections();

        // 8. SQL文の検出
        detectSqlStatements();

        // 9. リテラル文字列中の検索ワードを検出
        detectHitWords();

        // 10. メソッド内のインスタンス生成を検出
        detectCreatedInstances();

        // 11. フィールド初期化を検出（宣言時+コンストラクタ内）
        detectFieldInitializers();

        // 12. HTTPクライアント呼び出しを検出
        detectHttpClientCalls();

        System.out.println("解析完了: " + methodMap.size() + "個のメソッドを検出");
        System.out.println("Bean定義: " + beanDefinitions.size() + "個");
        System.out.println("SQL文検出: " + sqlStatements.size() + "個のメソッドでSQL文を検出");
        System.out.println("検索ワード検出: " + methodHitWords.size() + "個のメソッドでワードを検出");
        System.out.println("インスタンス生成: " + methodCreatedInstances.size() + "個のメソッドで生成を検出");
        System.out.println("フィールド初期化: " + classFieldInitializers.size() + "個のクラスで検出");
        System.out.println("HTTPクライアント呼び出し: " + httpCalls.size() + "個のメソッドで検出");
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
                    // ディレクトリ自体を追加
                    result.add(cpPath);
                    System.out.println("ディレクトリ追加: " + cpPath);

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
     * XML Bean定義を解析
     */
    private void parseXmlBeanDefinitions(String xmlConfigDirs) {
        for (String dirPath : xmlConfigDirs.split(",")) {
            dirPath = dirPath.trim();
            Path dir = Paths.get(dirPath);

            try {
                if (Files.isDirectory(dir)) {
                    Files.walk(dir)
                            .filter(p -> p.toString().endsWith(".xml"))
                            .forEach(this::parseXmlFile);
                } else if (Files.isRegularFile(dir) && dir.toString().endsWith(".xml")) {
                    parseXmlFile(dir);
                }
            } catch (IOException e) {
                System.err.println("XML設定ファイル読み込みエラー: " + e.getMessage());
            }
        }
    }

    /**
     * 単一のXMLファイルを解析
     */
    private void parseXmlFile(Path xmlFile) {
        try {
            String content = Files.readString(xmlFile, StandardCharsets.UTF_8);

            // 簡易的なXMLパース（正規表現使用）
            // <bean id="..." class="..."> を抽出
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "<bean\\s+[^>]*id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*class\\s*=\\s*[\"']([^\"']+)[\"']|" +
                            "<bean\\s+[^>]*class\\s*=\\s*[\"']([^\"']+)[\"'][^>]*id\\s*=\\s*[\"']([^\"']+)[\"']");
            java.util.regex.Matcher matcher = pattern.matcher(content);

            while (matcher.find()) {
                String id = matcher.group(1) != null ? matcher.group(1) : matcher.group(4);
                String className = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);

                if (id != null && className != null) {
                    beanDefinitions.put(id, new BeanDefinition(id, className, "XML"));

                    if (debugMode) {
                        System.out.println("  XML Bean: " + id + " -> " + className);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("XMLファイル読み込みエラー (" + xmlFile + "): " + e.getMessage());
        }
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

            // スーパークラス（再帰的に収集）
            if (type instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) type;
                collectSuperClassesRecursive(ctClass, parents);
            }

            // インターフェース（再帰的に収集）
            Set<String> allInterfaces = getAllInterfaces(type);
            for (String ifaceName : allInterfaces) {
                if (!isJavaStandardLibrary(ifaceName)) {
                    parents.add(ifaceName);
                    // インターフェース実装の記録
                    interfaceImplementations.computeIfAbsent(ifaceName, k -> new HashSet<>()).add(typeName);
                }
            }

            if (!parents.isEmpty()) {
                classHierarchy.put(typeName, parents);
            }
        }
    }

    /**
     * 型が実装・継承するすべてのインターフェースを再帰的に収集
     */
    private Set<String> getAllInterfaces(CtType<?> type) {
        Set<String> interfaces = new HashSet<>();
        collectInterfacesRecursive(type, interfaces);
        return interfaces;
    }

    private void collectInterfacesRecursive(CtType<?> type, Set<String> collected) {
        if (type == null)
            return;

        // 直接のインターフェース
        for (CtTypeReference<?> ifaceRef : type.getSuperInterfaces()) {
            String ifaceName = ifaceRef.getQualifiedName();
            if (collected.add(ifaceName)) {
                // インターフェースの親インターフェースを再帰的に収集
                try {
                    CtType<?> ifaceDecl = ifaceRef.getTypeDeclaration();
                    if (ifaceDecl != null) {
                        collectInterfacesRecursive(ifaceDecl, collected);
                    }
                } catch (Exception e) {
                    // 型解決できない場合は無視
                }
            }
        }

        // スーパークラスのインターフェース
        if (type instanceof CtClass) {
            CtTypeReference<?> superRef = ((CtClass<?>) type).getSuperclass();
            if (superRef != null) {
                try {
                    CtType<?> superDecl = superRef.getTypeDeclaration();
                    if (superDecl != null) {
                        collectInterfacesRecursive(superDecl, collected);
                    }
                } catch (Exception e) {
                    // 型解決できない場合は無視
                }
            }
        }
    }

    /**
     * スーパークラスを再帰的に収集
     */
    private void collectSuperClassesRecursive(CtClass<?> ctClass, Set<String> collected) {
        CtTypeReference<?> superRef = ctClass.getSuperclass();
        if (superRef == null)
            return;

        String superName = superRef.getQualifiedName();
        if (isJavaStandardLibrary(superName))
            return;

        if (collected.add(superName)) {
            try {
                CtType<?> superDecl = superRef.getTypeDeclaration();
                if (superDecl instanceof CtClass) {
                    collectSuperClassesRecursive((CtClass<?>) superDecl, collected);
                }
            } catch (Exception e) {
                // 型解決できない場合は無視
            }
        }
    }

    /**
     * アノテーションベースのBean定義を収集
     */
    private void collectAnnotationBasedBeans() {
        for (CtType<?> type : model.getElements(new TypeFilter<>(CtType.class))) {
            String className = type.getQualifiedName();
            String beanId = null;
            String source = null;

            for (var annotation : type.getAnnotations()) {
                String annName = annotation.getAnnotationType().getSimpleName();

                if (annName.equals("Component") || annName.equals("Service") ||
                        annName.equals("Repository") || annName.equals("Controller")) {
                    source = annName;

                    // アノテーションの value 属性を取得（安全にリフレクションで取得）
                    try {
                        java.lang.annotation.Annotation actual = (java.lang.annotation.Annotation) annotation
                                .getActualAnnotation();
                        try {
                            java.lang.reflect.Method m = actual.getClass().getMethod("value");
                            Object value = m.invoke(actual);
                            if (value != null && !value.toString().isEmpty()) {
                                beanId = value.toString();
                            }
                        } catch (NoSuchMethodException nsme) {
                            // value メソッドがない場合は無視
                        }
                    } catch (Exception e) {
                        // value属性がない、または取得できない場合
                    }

                    // ID指定がない場合、クラス名から自動生成
                    if (beanId == null || beanId.isEmpty()) {
                        beanId = generateDefaultBeanId(className);
                    }

                    beanDefinitions.put(beanId, new BeanDefinition(beanId, className, source));

                    if (debugMode) {
                        System.out.println("  Annotation Bean: " + beanId + " -> " + className + " (@" + source + ")");
                    }

                    break;
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
     * フィールドインジェクションを解析
     */
    private void analyzeFieldInjections() {
        for (CtType<?> type : model.getElements(new TypeFilter<>(CtType.class))) {
            String className = type.getQualifiedName();
            Map<String, String> injectedFields = new HashMap<>();

            // フィールドを解析
            for (CtField<?> field : type.getFields()) {
                boolean isInjected = false;
                String qualifierId = null;
                String fieldType = field.getType().getQualifiedName();
                String fieldName = field.getSimpleName();

                // @Autowired, @Inject, @Resource などのアノテーションをチェック
                for (var annotation : field.getAnnotations()) {
                    String annName = annotation.getAnnotationType().getSimpleName();
                    if (annName.equals("Autowired") ||
                            annName.equals("Inject") ||
                            annName.equals("Resource") ||
                            annName.equals("Value")) {
                        isInjected = true;
                    }

                    // @Qualifier のチェック
                    if (annName.equals("Qualifier")) {
                        // アノテーションの value 属性を取得（安全にリフレクションで取得）
                        try {
                            java.lang.annotation.Annotation actual = (java.lang.annotation.Annotation) annotation
                                    .getActualAnnotation();
                            try {
                                java.lang.reflect.Method m = actual.getClass().getMethod("value");
                                Object value = m.invoke(actual);
                                if (value != null && !value.toString().isEmpty()) {
                                    qualifierId = value.toString();
                                }
                            } catch (NoSuchMethodException nsme) {
                                // value メソッドがない場合は無視
                            }
                        } catch (Exception e) {
                            // value属性がない、または取得できない場合
                        }
                    }
                }

                if (isInjected) {
                    injectedFields.put(fieldName, fieldType);

                    // 詳細情報を保存
                    String key = className + "." + fieldName;
                    fieldInjectionDetails.put(key,
                            new FieldInjectionInfo(className, fieldName, fieldType, qualifierId));

                    if (debugMode) {
                        System.out.println("  Found injection: " + className + "." + fieldName +
                                " (type: " + fieldType +
                                (qualifierId != null ? ", qualifier: " + qualifierId : "") + ")");
                    }
                }
            }

            if (!injectedFields.isEmpty()) {
                fieldInjections.put(className, injectedFields);
            }
        }
    }

    /**
     * SQL文を検出
     */
    private void detectSqlStatements() {
        for (CtMethod<?> method : methodMap.values()) {
            String methodSig = getMethodSignature(method);
            Set<String> sqlSet = new LinkedHashSet<>(); // 重複を防ぐためSetを使用

            // 1. リテラル文字列からSQLを検出
            List<CtLiteral<?>> literals = method.getElements(new TypeFilter<>(CtLiteral.class));
            for (CtLiteral<?> literal : literals) {
                if (literal.getValue() instanceof String) {
                    String value = (String) literal.getValue();
                    // テキストブロック内の改行や空白を正規化
                    String normalized = value.replaceAll("\\n", " ").replaceAll("\\s+", " ").trim();
                    if (looksLikeSql(normalized)) {
                        sqlSet.add(normalized);
                    }
                }
            }

            // 2. 文字列連結式からSQLを検出
            List<CtBinaryOperator<?>> binaryOps = method.getElements(new TypeFilter<>(CtBinaryOperator.class));
            for (CtBinaryOperator<?> binOp : binaryOps) {
                if (binOp.getKind() == spoon.reflect.code.BinaryOperatorKind.PLUS) {
                    // 文字列連結の可能性がある
                    String evaluated = evaluateStringExpression(binOp);
                    if (evaluated != null) {
                        String normalized = normalizeAndOptimizeSql(evaluated);
                        if (looksLikeSql(normalized)) {
                            sqlSet.add(normalized);
                        }
                    }
                }
            }

            // 3. 変数代入からSQLを検出
            // ローカル変数宣言
            List<CtLocalVariable<?>> localVars = method.getElements(new TypeFilter<>(CtLocalVariable.class));
            for (CtLocalVariable<?> localVar : localVars) {
                CtExpression<?> assignment = localVar.getAssignment();
                if (assignment != null) {
                    String evaluated = evaluateStringExpression(assignment);
                    if (evaluated != null) {
                        String normalized = normalizeAndOptimizeSql(evaluated);
                        if (looksLikeSql(normalized)) {
                            sqlSet.add(normalized);
                        }
                    }
                }
            }

            // 代入文
            List<CtAssignment<?, ?>> assignments = method.getElements(new TypeFilter<>(CtAssignment.class));
            for (CtAssignment<?, ?> assignment : assignments) {
                String evaluated = evaluateStringExpression(assignment.getAssignment());
                if (evaluated != null) {
                    String normalized = normalizeAndOptimizeSql(evaluated);
                    if (looksLikeSql(normalized)) {
                        sqlSet.add(normalized);
                    }
                }
            }

            if (!sqlSet.isEmpty()) {
                List<String> sqls = new ArrayList<>(sqlSet);

                // 重複除去: あるSQL文が別のSQL文の部分文字列である場合、短い方を除外
                List<String> filtered = new ArrayList<>();
                for (String sql : sqls) {
                    boolean isSubstring = false;
                    for (String other : sqls) {
                        if (!sql.equals(other) && other.contains(sql)) {
                            isSubstring = true;
                            break;
                        }
                    }
                    if (!isSubstring) {
                        filtered.add(sql);
                    }
                }

                if (debugMode) {
                    System.out.println("  Found SQL in method: " + methodSig);
                    System.out.println("    Before deduplication: " + sqls.size() + " SQL(s)");
                    System.out.println("    After deduplication: " + filtered.size() + " SQL(s)");
                    for (String sql : filtered) {
                        System.out.println("      SQL: " + sql);
                    }
                }
                sqlStatements.put(methodSig, filtered);
            }
        }
    }

    /**
     * 文字列式を評価して文字列値を取得
     * 文字列連結式を再帰的に評価する
     * 動的な部分は ${UNRESOLVED} として表現する
     * 
     * @param expr 評価する式
     * @return 評価結果の文字列、評価できない場合はnull
     */
    private String evaluateStringExpression(CtExpression<?> expr) {
        if (expr == null) {
            return null;
        }

        // リテラル文字列
        if (expr instanceof CtLiteral) {
            CtLiteral<?> literal = (CtLiteral<?>) expr;
            if (literal.getValue() instanceof String) {
                return (String) literal.getValue();
            }
            // 文字列以外のリテラル(数値など)は未解決として扱う
            return "${UNRESOLVED}";
        }

        // 文字列連結 (+ 演算子)
        if (expr instanceof CtBinaryOperator) {
            CtBinaryOperator<?> binOp = (CtBinaryOperator<?>) expr;
            if (binOp.getKind() == spoon.reflect.code.BinaryOperatorKind.PLUS) {
                String left = evaluateStringExpression(binOp.getLeftHandOperand());
                String right = evaluateStringExpression(binOp.getRightHandOperand());

                // 少なくとも一方が評価できた場合は連結
                if (left != null && right != null) {
                    return left + right;
                } else if (left != null) {
                    return left + "${UNRESOLVED}";
                } else if (right != null) {
                    return "${UNRESOLVED}" + right;
                }
            }
            // その他の二項演算子は未解決
            return "${UNRESOLVED}";
        }

        // 三項演算子 (condition ? trueValue : falseValue)
        if (expr instanceof spoon.reflect.code.CtConditional) {
            spoon.reflect.code.CtConditional<?> conditional = (spoon.reflect.code.CtConditional<?>) expr;
            String trueValue = evaluateStringExpression(conditional.getThenExpression());
            String falseValue = evaluateStringExpression(conditional.getElseExpression());

            // 両方が同じ値なら採用
            if (trueValue != null && trueValue.equals(falseValue)) {
                return trueValue;
            }

            // 片方が空文字列の場合、条件によって追加される可能性があるため未解決
            if ((trueValue != null && trueValue.isEmpty()) || (falseValue != null && falseValue.isEmpty())) {
                return "${UNRESOLVED}";
            }

            // それ以外は未解決
            return "${UNRESOLVED}";
        }

        // 変数参照、メソッド呼び出し、その他の式は未解決として扱う
        return "${UNRESOLVED}";
    }

    /**
     * SQL文字列を正規化し、${UNRESOLVED}を最適化
     * 
     * @param sql SQL文字列
     * @return 正規化・最適化されたSQL文字列
     */
    private String normalizeAndOptimizeSql(String sql) {
        if (sql == null) {
            return null;
        }

        // 改行や空白を正規化
        String normalized = sql.replaceAll("\\n", " ").replaceAll("\\s+", " ").trim();

        // ${UNRESOLVED}の連続を1つにまとめる
        // 例: ${UNRESOLVED}${UNRESOLVED} -> ${UNRESOLVED}
        while (normalized.contains("${UNRESOLVED}${UNRESOLVED}")) {
            normalized = normalized.replace("${UNRESOLVED}${UNRESOLVED}", "${UNRESOLVED}");
        }

        // ${UNRESOLVED}の前後に適切なスペースを確保
        // まず、余分なスペースを削除してから、必要なスペースを追加
        normalized = normalized.replace(" ${UNRESOLVED} ", "${UNRESOLVED}");
        normalized = normalized.replace(" ${UNRESOLVED}", "${UNRESOLVED}");
        normalized = normalized.replace("${UNRESOLVED} ", "${UNRESOLVED}");

        // 前後にスペースを追加（文字列の先頭・末尾を除く）
        normalized = normalized.replace("${UNRESOLVED}", " ${UNRESOLVED} ");

        // 文字列の先頭・末尾の余分なスペースを削除
        normalized = normalized.trim();

        // 連続したスペースを1つにまとめる
        normalized = normalized.replaceAll("\\s+", " ");

        return normalized;
    }

    /**
     * 文字列がSQLらしいかを判定
     * ${UNRESOLVED}を含む文字列も判定対象とする
     */
    private boolean looksLikeSql(String str) {
        if (str == null || str.length() < 3)
            return false;

        // ${UNRESOLVED}を一時的に除去してSQL判定
        String testStr = str.replace("${UNRESOLVED}", "").trim();
        if (testStr.isEmpty()) {
            return false;
        }

        String upper = testStr.toUpperCase();
        return upper.startsWith("SELECT ") || upper.startsWith("INSERT ") ||
                upper.startsWith("UPDATE ") || upper.startsWith("DELETE ") ||
                upper.startsWith("MERGE ") || upper.startsWith("LOCK TABLE ") ||
                upper.startsWith("CREATE ") || upper.startsWith("ALTER ") ||
                upper.startsWith("DROP ") ||
                // PL/SQL, PL/pgSQL
                upper.startsWith("CALL ") || upper.startsWith("BEGIN ") ||
                upper.startsWith("DECLARE ") || upper.startsWith("DO ") ||
                upper.startsWith("EXECUTE ") ||
                // ストアドプロシージャ呼び出し (JDBC CallableStatement syntax) - 波括弧と等号の周りの空白を無視
                upper.matches("^\\{\\s*CALL\\s+.*") || // { CALL ...
                upper.matches("^\\{\\s*\\?\\s*=\\s*CALL\\s+.*"); // { ? = CALL ...
    }

    /**
     * 検索ワードファイルを読み込む
     */
    private void loadSearchWords(String wordsFilePath) {
        Path path = Paths.get(wordsFilePath);
        if (!Files.exists(path)) {
            if (debugMode) {
                System.out.println("検索ワードファイルが見つかりません: " + wordsFilePath);
            }
            return;
        }

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                // 空行とコメント行をスキップ
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    searchWords.add(trimmed);
                }
            }
            System.out.println("検索ワード読み込み: " + searchWords.size() + "個");
            if (debugMode) {
                System.out.println("  検索ワード: " + String.join(", ", searchWords));
            }
        } catch (IOException e) {
            System.err.println("検索ワードファイル読み込みエラー: " + e.getMessage());
        }
    }

    /**
     * リテラル文字列中の検索ワードを検出
     */
    private void detectHitWords() {
        if (searchWords.isEmpty()) {
            return;
        }

        // 検索ワードごとに正規表現パターンを作成
        Map<String, java.util.regex.Pattern> patterns = new HashMap<>();
        for (String word : searchWords) {
            // 単語境界: 空白、句読点、文字列の開始/終了のみ
            // (?<=^|[\s,.;:!?()[\]{}\"'])<word>(?=[\s,.;:!?()[\]{}\"']|$)
            String escapedWord = java.util.regex.Pattern.quote(word);
            String regex = "(?<=^|[\\s,.;:!?()\\[\\]{}\"'])" + escapedWord + "(?=[\\s,.;:!?()\\[\\]{}\"']|$)";
            patterns.put(word, java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE));
        }

        for (CtMethod<?> method : methodMap.values()) {
            String methodSig = getMethodSignature(method);
            Set<String> hitWords = new HashSet<>();

            // リテラル文字列からワードを検出
            List<CtLiteral<?>> literals = method.getElements(new TypeFilter<>(CtLiteral.class));
            for (CtLiteral<?> literal : literals) {
                if (literal.getValue() instanceof String) {
                    String value = (String) literal.getValue();

                    // 各検索ワードについてマッチング
                    for (Map.Entry<String, java.util.regex.Pattern> entry : patterns.entrySet()) {
                        java.util.regex.Matcher matcher = entry.getValue().matcher(value);
                        if (matcher.find()) {
                            hitWords.add(entry.getKey());
                        }
                    }
                }
            }

            if (!hitWords.isEmpty()) {
                methodHitWords.put(methodSig, hitWords);
                if (debugMode) {
                    System.out.println("  検出ワード in " + methodSig + ": " + String.join(", ", hitWords));
                }
            }
        }
    }

    /**
     * メソッド内で生成されるインスタンスを検出
     */
    private void detectCreatedInstances() {
        for (CtMethod<?> method : methodMap.values()) {
            String methodSig = getMethodSignature(method);
            Set<String> createdInstances = new HashSet<>();

            // newキーワードによるコンストラクタ呼び出しを検出
            List<CtConstructorCall<?>> constructorCalls = method.getElements(new TypeFilter<>(CtConstructorCall.class));
            for (CtConstructorCall<?> constructorCall : constructorCalls) {
                CtTypeReference<?> type = constructorCall.getType();
                if (type != null) {
                    String createdClass = type.getQualifiedName();
                    if (!isJavaStandardLibrary(createdClass)) {
                        createdInstances.add(createdClass);
                    }
                }
            }

            if (!createdInstances.isEmpty()) {
                methodCreatedInstances.put(methodSig, createdInstances);
                if (debugMode) {
                    System.out.println("  生成インスタンス in " + methodSig + ": " + String.join(", ", createdInstances));
                }
            }
        }
    }

    /**
     * フィールド初期化を検出（宣言時+コンストラクタ内）
     */
    private void detectFieldInitializers() {
        List<CtType<?>> types = model.getElements(new TypeFilter<>(CtType.class));

        for (CtType<?> type : types) {
            // インターフェースはフィールドを持たないので除外
            if (type.isInterface()) {
                continue;
            }

            String className = type.getQualifiedName();
            List<FieldInitializer> initializers = new ArrayList<>();

            // 1. フィールド宣言時の初期化を検出
            for (CtField<?> field : type.getFields()) {
                CtExpression<?> defaultExpr = field.getDefaultExpression();
                if (defaultExpr instanceof CtConstructorCall) {
                    CtConstructorCall<?> constructorCall = (CtConstructorCall<?>) defaultExpr;
                    CtTypeReference<?> initType = constructorCall.getType();
                    if (initType != null) {
                        String initializedClass = initType.getQualifiedName();
                        if (!isJavaStandardLibrary(initializedClass)) {
                            String fieldName = field.getSimpleName();
                            String fieldType = field.getType().getQualifiedName();
                            initializers.add(new FieldInitializer(fieldName, fieldType, initializedClass));
                            if (debugMode) {
                                System.out.println("  フィールド宣言時初期化: " + className + "." + fieldName +
                                        " = new " + initializedClass);
                            }
                        }
                    }
                }
            }

            // 2. コンストラクタ内でのフィールド初期化を検出
            if (type instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) type;
                for (CtConstructor<?> constructor : ctClass.getConstructors()) {
                    // コンストラクタ内のすべての代入文を検査
                    List<CtAssignment<?, ?>> assignments = constructor
                            .getElements(new TypeFilter<>(CtAssignment.class));
                    for (CtAssignment<?, ?> assignment : assignments) {
                        // フィールドへの代入かつnewによる初期化かを確認
                        if (assignment.getAssigned() instanceof CtFieldWrite &&
                                assignment.getAssignment() instanceof CtConstructorCall) {
                            CtFieldWrite<?> fieldWrite = (CtFieldWrite<?>) assignment.getAssigned();
                            CtConstructorCall<?> constructorCall = (CtConstructorCall<?>) assignment.getAssignment();

                            CtTypeReference<?> initType = constructorCall.getType();
                            if (initType != null) {
                                String initializedClass = initType.getQualifiedName();
                                if (!isJavaStandardLibrary(initializedClass)) {
                                    String fieldName = fieldWrite.getVariable().getSimpleName();
                                    String fieldType = fieldWrite.getType() != null
                                            ? fieldWrite.getType().getQualifiedName()
                                            : "";

                                    // 重複チェック（宣言時初期化と重複しないように）
                                    boolean isDuplicate = initializers.stream()
                                            .anyMatch(fi -> fi.fieldName.equals(fieldName));
                                    if (!isDuplicate) {
                                        initializers.add(new FieldInitializer(fieldName, fieldType, initializedClass));
                                        if (debugMode) {
                                            System.out.println("  コンストラクタ内初期化: " + className + "." + fieldName +
                                                    " = new " + initializedClass);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!initializers.isEmpty()) {
                classFieldInitializers.put(className, initializers);
            }
        }
    }

    /**
     * HTTPクライアント呼び出しを検出
     */
    private void detectHttpClientCalls() {
        // Apache HttpClient 4.x のリクエストクラスとHTTPメソッドのマッピング
        Map<String, String> apacheHttp4RequestClasses = new HashMap<>();
        apacheHttp4RequestClasses.put("org.apache.http.client.methods.HttpGet", "GET");
        apacheHttp4RequestClasses.put("org.apache.http.client.methods.HttpPost", "POST");
        apacheHttp4RequestClasses.put("org.apache.http.client.methods.HttpPut", "PUT");
        apacheHttp4RequestClasses.put("org.apache.http.client.methods.HttpDelete", "DELETE");
        apacheHttp4RequestClasses.put("org.apache.http.client.methods.HttpPatch", "PATCH");
        apacheHttp4RequestClasses.put("org.apache.http.client.methods.HttpHead", "HEAD");
        apacheHttp4RequestClasses.put("org.apache.http.client.methods.HttpOptions", "OPTIONS");

        // CXF WebClient のHTTPメソッド名マッピング
        Set<String> cxfWebClientMethods = new HashSet<>(Arrays.asList(
                "get", "post", "put", "delete", "head", "options", "invoke"));

        // RestTemplate のメソッド名とHTTPメソッドのマッピング
        Map<String, String> restTemplateMethods = new HashMap<>();
        restTemplateMethods.put("getForObject", "GET");
        restTemplateMethods.put("getForEntity", "GET");
        restTemplateMethods.put("postForObject", "POST");
        restTemplateMethods.put("postForEntity", "POST");
        restTemplateMethods.put("postForLocation", "POST");
        restTemplateMethods.put("put", "PUT");
        restTemplateMethods.put("delete", "DELETE");
        restTemplateMethods.put("patchForObject", "PATCH");
        restTemplateMethods.put("exchange", "EXCHANGE"); // HTTPメソッドは引数から判定

        for (CtMethod<?> method : methodMap.values()) {
            String methodSig = getMethodSignature(method);
            List<HttpCallInfo> callInfos = new ArrayList<>();

            // メソッド内のすべてのメソッド呼び出しを取得
            List<CtInvocation<?>> invocations = method.getElements(new TypeFilter<>(CtInvocation.class));

            for (CtInvocation<?> invocation : invocations) {
                CtExecutableReference<?> executable = invocation.getExecutable();
                if (executable == null || executable.getDeclaringType() == null) {
                    continue;
                }

                String declaringClass = executable.getDeclaringType().getQualifiedName();
                String methodName = executable.getSimpleName();

                // 1. Apache HttpClient 4.x の検出 - execute()はスキップ（コンストラクタで検出するため）
                // execute()を検出すると重複するため、コンストラクタ検出のみに統一

                // 2. Apache CXF WebClient の検出
                if (declaringClass.equals("org.apache.cxf.jaxrs.client.WebClient")) {
                    String lowerMethodName = methodName.toLowerCase();
                    if (cxfWebClientMethods.contains(lowerMethodName)) {
                        String httpMethod = lowerMethodName.toUpperCase();
                        if (httpMethod.equals("INVOKE")) {
                            // invoke("GET", payload) の場合、第1引数からHTTPメソッドを取得
                            List<CtExpression<?>> args = invocation.getArguments();
                            if (!args.isEmpty()) {
                                String firstArgValue = evaluateStringExpression(args.get(0));
                                if (firstArgValue != null && !firstArgValue.contains("${")) {
                                    httpMethod = firstArgValue.toUpperCase();
                                }
                            }
                        }

                        // WebClient.create() 呼び出しからURIを抽出
                        String uri = extractUriFromWebClientChain(invocation);

                        callInfos.add(new HttpCallInfo(httpMethod, uri, "CXF WebClient"));
                    }
                }

                // 3. Spring RestTemplate の検出
                if (declaringClass.equals("org.springframework.web.client.RestTemplate")) {
                    String httpMethod = restTemplateMethods.get(methodName);
                    if (httpMethod != null) {
                        List<CtExpression<?>> args = invocation.getArguments();
                        String uri = "${UNRESOLVED}";

                        // 第1引数がURIの場合が多い
                        if (!args.isEmpty()) {
                            uri = extractUriFromExpression(args.get(0));
                        }

                        // exchange の場合、HTTPメソッドを引数から判定
                        if (httpMethod.equals("EXCHANGE") && args.size() >= 2) {
                            for (CtExpression<?> arg : args) {
                                if (arg.getType() != null &&
                                        arg.getType().getQualifiedName()
                                                .equals("org.springframework.http.HttpMethod")) {
                                    String argStr = arg.toString();
                                    if (argStr.contains("GET"))
                                        httpMethod = "GET";
                                    else if (argStr.contains("POST"))
                                        httpMethod = "POST";
                                    else if (argStr.contains("PUT"))
                                        httpMethod = "PUT";
                                    else if (argStr.contains("DELETE"))
                                        httpMethod = "DELETE";
                                    else if (argStr.contains("PATCH"))
                                        httpMethod = "PATCH";
                                    break;
                                }
                            }
                        }

                        callInfos.add(new HttpCallInfo(httpMethod, uri, "RestTemplate"));
                    }
                }

                // 4. Java 11+ HttpClient の検出
                if (declaringClass.equals("java.net.http.HttpClient") &&
                        (methodName.equals("send") || methodName.equals("sendAsync"))) {

                    List<CtExpression<?>> args = invocation.getArguments();
                    String httpMethod = "UNKNOWN";
                    String uri = "${UNRESOLVED}";

                    if (!args.isEmpty()) {
                        // HttpRequest からURIとメソッドを抽出（複雑なため簡易実装）
                        uri = extractUriFromExpression(args.get(0));
                    }

                    callInfos.add(new HttpCallInfo(httpMethod, uri, "Java HttpClient"));
                }

                // 5. Apache HttpClient 3.x の検出 - executeMethod()はスキップ（コンストラクタで検出するため）
                // executeMethodを検出すると重複するため、コンストラクタ検出のみに統一

                // 6. Apache HttpClient 5.x の検出
                if ((declaringClass.equals("org.apache.hc.client5.http.classic.HttpClient") ||
                        declaringClass.equals("org.apache.hc.client5.http.impl.classic.CloseableHttpClient") ||
                        declaringClass.contains("hc.client5")) &&
                        methodName.equals("execute")) {

                    List<CtExpression<?>> args = invocation.getArguments();
                    String httpMethod = "UNKNOWN";
                    String uri = "${UNRESOLVED}";

                    if (!args.isEmpty()) {
                        CtExpression<?> firstArg = args.get(0);
                        if (firstArg.getType() != null) {
                            String argType = firstArg.getType().getQualifiedName();
                            if (argType.contains("HttpGet"))
                                httpMethod = "GET";
                            else if (argType.contains("HttpPost"))
                                httpMethod = "POST";
                            else if (argType.contains("HttpPut"))
                                httpMethod = "PUT";
                            else if (argType.contains("HttpDelete"))
                                httpMethod = "DELETE";
                            else if (argType.contains("HttpPatch"))
                                httpMethod = "PATCH";
                        }
                        uri = extractUriFromExpression(firstArg);
                    }

                    callInfos.add(new HttpCallInfo(httpMethod, uri, "Apache HttpClient 5.x"));
                }

                // 7. JAX-RS Client の検出 (Invocation.Builder)
                if ((declaringClass.contains("javax.ws.rs.client") ||
                        declaringClass.contains("jakarta.ws.rs.client")) &&
                        (methodName.equals("get") || methodName.equals("post") ||
                                methodName.equals("put") || methodName.equals("delete") ||
                                methodName.equals("head") || methodName.equals("options"))) {

                    String httpMethod = methodName.toUpperCase();
                    String uri = extractUriFromJaxRsClientChain(invocation);

                    callInfos.add(new HttpCallInfo(httpMethod, uri, "JAX-RS Client"));
                }

                // 8. OkHttp の検出
                if ((declaringClass.equals("okhttp3.Call") ||
                        declaringClass.contains("okhttp3")) &&
                        (methodName.equals("execute") || methodName.equals("enqueue"))) {

                    String httpMethod = "UNKNOWN";
                    String uri = "${UNRESOLVED}";

                    // OkHttpClient.newCall(request) からURIを抽出
                    uri = extractUriFromOkHttpChain(invocation);

                    callInfos.add(new HttpCallInfo(httpMethod, uri, "OkHttp"));
                }

                // 9. Spring WebClient の検出 (reactive)
                if (declaringClass.equals("org.springframework.web.reactive.function.client.WebClient") ||
                        declaringClass.contains("WebClient$RequestHeadersUriSpec") ||
                        declaringClass.contains("WebClient$RequestBodyUriSpec")) {

                    if (methodName.equals("get") || methodName.equals("post") ||
                            methodName.equals("put") || methodName.equals("delete") ||
                            methodName.equals("patch") || methodName.equals("head") ||
                            methodName.equals("options")) {

                        String httpMethod = methodName.toUpperCase();
                        String uri = extractUriFromWebClientChain(invocation);

                        callInfos.add(new HttpCallInfo(httpMethod, uri, "Spring WebClient"));
                    }
                }

                // 10. HttpURLConnection の検出 - connect()のみ検出（重複防止）
                if ((declaringClass.equals("java.net.HttpURLConnection") ||
                        declaringClass.equals("java.net.URLConnection")) &&
                        methodName.equals("connect")) {

                    String httpMethod = "UNKNOWN";
                    String uri = "${UNRESOLVED}";

                    // 同じメソッド内のsetRequestMethod()呼び出しからHTTPメソッドを抽出
                    for (CtInvocation<?> otherInv : invocations) {
                        if (otherInv.getExecutable() != null &&
                                otherInv.getExecutable().getSimpleName().equals("setRequestMethod")) {
                            List<CtExpression<?>> setMethodArgs = otherInv.getArguments();
                            if (!setMethodArgs.isEmpty()) {
                                String methodArg = extractUriFromExpression(setMethodArgs.get(0));
                                if (methodArg != null && !methodArg.contains("${")) {
                                    httpMethod = methodArg;
                                }
                            }
                        }
                    }

                    // invocationのターゲットから変数を追跡してURLを抽出
                    CtExpression<?> target = invocation.getTarget();
                    uri = extractUriFromHttpUrlConnection(target, method);

                    callInfos.add(new HttpCallInfo(httpMethod, uri, "HttpURLConnection"));
                }

                // 11. SSLSocketFactory の検出 (SSL/TLS通信)
                if ((declaringClass.equals("javax.net.ssl.SSLSocketFactory") ||
                        declaringClass.equals("javax.net.SocketFactory") ||
                        declaringClass.contains("SSLSocketFactory")) &&
                        (methodName.equals("createSocket") || methodName.equals("getDefault"))) {

                    String httpMethod = "UNKNOWN";
                    String uri = "${UNRESOLVED}";

                    // createSocket(host, port) の引数からホスト情報を抽出
                    List<CtExpression<?>> args = invocation.getArguments();
                    if (!args.isEmpty()) {
                        String host = extractUriFromExpression(args.get(0));
                        if (args.size() > 1) {
                            String port = extractUriFromExpression(args.get(1));
                            if (!host.contains("${UNRESOLVED}") && !port.contains("${UNRESOLVED}")) {
                                uri = host + ":" + port;
                            } else if (!host.contains("${UNRESOLVED}")) {
                                uri = host;
                            }
                        } else if (!host.contains("${UNRESOLVED}")) {
                            uri = host;
                        }
                    }

                    callInfos.add(new HttpCallInfo(httpMethod, uri, "SSLSocketFactory"));
                }

                // 12. Apache Axis の検出 (SOAP Webサービス)
                if ((declaringClass.equals("org.apache.axis.client.Call") ||
                        declaringClass.equals("org.apache.axis.client.Service") ||
                        declaringClass.equals("org.apache.axis2.client.ServiceClient") ||
                        declaringClass.contains("axis.client") ||
                        declaringClass.contains("axis2.client")) &&
                        (methodName.equals("invoke") || methodName.equals("invokeBlocking") ||
                                methodName.equals("sendReceive") || methodName.equals("setTargetEndpointAddress"))) {

                    String httpMethod = "UNKNOWN";
                    String uri = "${UNRESOLVED}";

                    // setTargetEndpointAddress(url) や invoke の引数からエンドポイントを抽出
                    if (methodName.equals("setTargetEndpointAddress")) {
                        List<CtExpression<?>> args = invocation.getArguments();
                        if (!args.isEmpty()) {
                            uri = extractUriFromExpression(args.get(0));
                        }
                    }

                    callInfos.add(new HttpCallInfo(httpMethod, uri, "Apache Axis"));
                }

                // 13. JAX-WS Service の検出 (SOAP Webサービス)
                if ((declaringClass.equals("javax.xml.ws.Service") ||
                        declaringClass.equals("jakarta.xml.ws.Service") ||
                        declaringClass.contains("xml.ws.Service")) &&
                        (methodName.equals("getPort") || methodName.equals("create"))) {

                    String httpMethod = "UNKNOWN";
                    String uri = "${UNRESOLVED}";

                    // Service.create(url, qname) の引数からWSDL URLを抽出
                    List<CtExpression<?>> args = invocation.getArguments();
                    if (!args.isEmpty()) {
                        uri = extractUriFromExpression(args.get(0));
                    }

                    callInfos.add(new HttpCallInfo(httpMethod, uri, "JAX-WS"));
                }
            }

            // コンストラクタ呼び出しからApache HttpClient リクエストオブジェクト生成を検出
            List<CtConstructorCall<?>> constructorCalls = method.getElements(new TypeFilter<>(CtConstructorCall.class));
            for (CtConstructorCall<?> ctorCall : constructorCalls) {
                if (ctorCall.getType() != null) {
                    String ctorType = ctorCall.getType().getQualifiedName();
                    String httpMethod = apacheHttp4RequestClasses.get(ctorType);
                    if (httpMethod != null) {
                        List<CtExpression<?>> args = ctorCall.getArguments();
                        String extractedUri = "${UNRESOLVED}";
                        if (!args.isEmpty()) {
                            extractedUri = extractUriFromExpression(args.get(0));
                        }
                        // この情報は execute() 呼び出しで既に取得している可能性があるので
                        // 重複チェックを行う
                        final String finalHttpMethod = httpMethod;
                        final String finalUri = extractedUri;
                        boolean alreadyExists = callInfos.stream()
                                .anyMatch(info -> info.httpMethod.equals(finalHttpMethod) && info.uri.equals(finalUri));
                        if (!alreadyExists) {
                            callInfos.add(new HttpCallInfo(httpMethod, extractedUri, "Apache HttpClient 4.x"));
                        }
                    }

                    // Apache HttpClient 3.x のメソッドクラス（GetMethod, PostMethod等）
                    Map<String, String> apache3MethodClasses = Map.of(
                            "org.apache.commons.httpclient.methods.GetMethod", "GET",
                            "org.apache.commons.httpclient.methods.PostMethod", "POST",
                            "org.apache.commons.httpclient.methods.PutMethod", "PUT",
                            "org.apache.commons.httpclient.methods.DeleteMethod", "DELETE");
                    String apache3HttpMethod = apache3MethodClasses.get(ctorType);
                    if (apache3HttpMethod != null) {
                        List<CtExpression<?>> args3 = ctorCall.getArguments();
                        String extractedUri = "${UNRESOLVED}";
                        if (!args3.isEmpty()) {
                            extractedUri = extractUriFromExpression(args3.get(0));
                        }
                        // 重複チェック
                        final String finalUri = extractedUri;
                        final String finalMethod = apache3HttpMethod;
                        boolean alreadyExists = callInfos.stream()
                                .anyMatch(info -> info.httpMethod.equals(finalMethod) && info.uri.equals(finalUri));
                        if (!alreadyExists) {
                            callInfos.add(new HttpCallInfo(apache3HttpMethod, extractedUri, "Apache HttpClient 3.x"));
                        }
                    }
                }
            }
            if (!callInfos.isEmpty()) {
                httpCalls.put(methodSig, callInfos);
                if (debugMode) {
                    System.out.println("  HTTPクライアント呼び出し検出 in " + methodSig + ": " + callInfos.size() + "件");
                    for (HttpCallInfo info : callInfos) {
                        System.out.println("    " + info.httpMethod + " " + info.uri + " (" + info.clientLibrary + ")");
                    }
                }
            }
        }
    }

    /**
     * 式からURIを抽出（簡易実装）
     */
    private String extractUriFromExpression(CtExpression<?> expr) {
        if (expr == null) {
            return "${UNRESOLVED}";
        }

        // 文字列リテラルの場合
        String evaluated = evaluateStringExpression(expr);
        if (evaluated != null && !evaluated.isEmpty()) {
            return evaluated;
        }

        // 変数参照の場合、定義まで遡る
        if (expr instanceof CtVariableRead) {
            CtVariableRead<?> varRead = (CtVariableRead<?>) expr;
            CtVariableReference<?> varRef = varRead.getVariable();
            if (varRef != null) {
                CtVariable<?> varDecl = varRef.getDeclaration();
                if (varDecl != null && varDecl.getDefaultExpression() != null) {
                    // 変数の初期値を再帰的に抽出
                    return extractUriFromExpression(varDecl.getDefaultExpression());
                }
            }
        }

        // フィールドアクセスの場合（定数フィールドなど）
        if (expr instanceof CtFieldRead) {
            CtFieldRead<?> fieldRead = (CtFieldRead<?>) expr;
            CtFieldReference<?> fieldRef = fieldRead.getVariable();
            if (fieldRef != null) {
                CtField<?> field = fieldRef.getFieldDeclaration();
                if (field != null && field.getDefaultExpression() != null) {
                    // フィールドの初期値を再帰的に抽出
                    return extractUriFromExpression(field.getDefaultExpression());
                }
            }
        }

        // コンストラクタ呼び出しの場合（new HttpGet(url)）
        if (expr instanceof CtConstructorCall) {
            CtConstructorCall<?> ctorCall = (CtConstructorCall<?>) expr;
            List<CtExpression<?>> args = ctorCall.getArguments();
            if (!args.isEmpty()) {
                return extractUriFromExpression(args.get(0));
            }
        }

        // メソッド呼び出しの場合（URI.create(url)等）
        if (expr instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) expr;
            List<CtExpression<?>> args = inv.getArguments();
            if (!args.isEmpty()) {
                return extractUriFromExpression(args.get(0));
            }
        }

        // 二項演算子（文字列連結）の場合
        if (expr instanceof CtBinaryOperator) {
            CtBinaryOperator<?> binOp = (CtBinaryOperator<?>) expr;
            String left = extractUriFromExpression(binOp.getLeftHandOperand());
            String right = extractUriFromExpression(binOp.getRightHandOperand());
            // 両方が解決できた場合は連結
            if (!left.contains("${UNRESOLVED}") && !right.contains("${UNRESOLVED}")) {
                return left + right;
            } else if (!left.contains("${UNRESOLVED}")) {
                return left + "${UNRESOLVED}";
            } else if (!right.contains("${UNRESOLVED}")) {
                return "${UNRESOLVED}" + right;
            }
        }

        return "${UNRESOLVED}";
    }

    /**
     * CXF WebClientチェーンからURIを抽出
     */
    private String extractUriFromWebClientChain(CtInvocation<?> invocation) {
        // メソッドチェーンを遡ってベースURLとパスを収集
        List<String> pathParts = new ArrayList<>();
        String baseUrl = "${UNRESOLVED}";

        CtExpression<?> target = invocation.getTarget();

        while (target != null) {
            if (target instanceof CtInvocation) {
                CtInvocation<?> targetInv = (CtInvocation<?>) target;
                CtExecutableReference<?> exec = targetInv.getExecutable();
                if (exec != null) {
                    String methodName = exec.getSimpleName();
                    if (methodName.equals("create")) {
                        // WebClient.create(url) の引数を取得
                        List<CtExpression<?>> args = targetInv.getArguments();
                        if (!args.isEmpty()) {
                            baseUrl = extractUriFromExpression(args.get(0));
                        }
                        break; // create()が見つかったらループ終了
                    } else if (methodName.equals("path")) {
                        // path() の引数を収集
                        List<CtExpression<?>> args = targetInv.getArguments();
                        if (!args.isEmpty()) {
                            String pathPart = extractUriFromExpression(args.get(0));
                            pathParts.add(0, pathPart); // リストの先頭に追加（逆順で遡っているため）
                        }
                    }
                }
                target = targetInv.getTarget();
            } else if (target instanceof CtVariableRead) {
                // 変数参照の場合（client.path(...).get() のパターン）
                CtVariableRead<?> varRead = (CtVariableRead<?>) target;
                CtVariableReference<?> varRef = varRead.getVariable();
                if (varRef != null) {
                    CtVariable<?> varDecl = varRef.getDeclaration();
                    if (varDecl != null && varDecl.getDefaultExpression() != null) {
                        // 変数の初期値からWebClient.create()を探す
                        baseUrl = extractBaseUrlFromWebClientCreate(varDecl.getDefaultExpression());
                    }
                }
                break;
            } else {
                break;
            }
        }

        // ベースURLとパスを結合
        if (!pathParts.isEmpty()) {
            StringBuilder fullUrl = new StringBuilder(baseUrl);
            for (String part : pathParts) {
                if (!fullUrl.toString().endsWith("/") && !part.startsWith("/")) {
                    fullUrl.append("/");
                }
                fullUrl.append(part);
            }
            return fullUrl.toString();
        }

        return baseUrl;
    }

    /**
     * WebClient.create()からベースURLを抽出
     */
    private String extractBaseUrlFromWebClientCreate(CtExpression<?> expr) {
        if (expr instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) expr;
            CtExecutableReference<?> exec = inv.getExecutable();
            if (exec != null && exec.getSimpleName().equals("create")) {
                List<CtExpression<?>> args = inv.getArguments();
                if (!args.isEmpty()) {
                    return extractUriFromExpression(args.get(0));
                }
            }
        }
        return "${UNRESOLVED}";
    }

    /**
     * JAX-RS ClientチェーンからURIを抽出
     */
    private String extractUriFromJaxRsClientChain(CtInvocation<?> invocation) {
        // メソッドチェーンを遡ってtarget()を探す
        CtExpression<?> target = invocation.getTarget();

        while (target != null) {
            if (target instanceof CtInvocation) {
                CtInvocation<?> targetInv = (CtInvocation<?>) target;
                CtExecutableReference<?> exec = targetInv.getExecutable();
                if (exec != null) {
                    String methodName = exec.getSimpleName();
                    if (methodName.equals("target")) {
                        // Client.target(url) の引数を取得
                        List<CtExpression<?>> args = targetInv.getArguments();
                        if (!args.isEmpty()) {
                            return extractUriFromExpression(args.get(0));
                        }
                    } else if (methodName.equals("path")) {
                        // path() の引数も考慮
                        List<CtExpression<?>> args = targetInv.getArguments();
                        if (!args.isEmpty()) {
                            String pathPart = extractUriFromExpression(args.get(0));
                            String basePart = extractUriFromJaxRsClientChain(targetInv);
                            if (!basePart.equals("${UNRESOLVED}")) {
                                return basePart + "/" + pathPart;
                            }
                        }
                    }
                }
                target = targetInv.getTarget();
            } else {
                break;
            }
        }

        return "${UNRESOLVED}";
    }

    /**
     * OkHttpチェーンからURIを抽出
     */
    private String extractUriFromOkHttpChain(CtInvocation<?> invocation) {
        // メソッドチェーンを遡ってnewCall()を探す
        CtExpression<?> target = invocation.getTarget();

        while (target != null) {
            if (target instanceof CtInvocation) {
                CtInvocation<?> targetInv = (CtInvocation<?>) target;
                CtExecutableReference<?> exec = targetInv.getExecutable();
                if (exec != null) {
                    String methodName = exec.getSimpleName();
                    if (methodName.equals("newCall")) {
                        // OkHttpClient.newCall(request) の引数からRequestを取得
                        List<CtExpression<?>> args = targetInv.getArguments();
                        if (!args.isEmpty()) {
                            // Request オブジェクトからURLを抽出
                            return extractUriFromOkHttpRequest(args.get(0));
                        }
                    }
                }
                target = targetInv.getTarget();
            } else {
                break;
            }
        }

        return "${UNRESOLVED}";
    }

    /**
     * OkHttp RequestオブジェクトからURLを抽出
     */
    private String extractUriFromOkHttpRequest(CtExpression<?> requestExpr) {
        // 変数参照の場合、変数宣言を追跡
        if (requestExpr instanceof CtVariableRead) {
            CtVariableRead<?> varRead = (CtVariableRead<?>) requestExpr;
            CtVariableReference<?> varRef = varRead.getVariable();
            if (varRef != null) {
                CtVariable<?> varDecl = varRef.getDeclaration();
                if (varDecl != null && varDecl.getDefaultExpression() != null) {
                    return extractUriFromOkHttpRequest(varDecl.getDefaultExpression());
                }
            }
        }

        // Request.Builder().url("...").build() のパターンを追跡
        if (requestExpr instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) requestExpr;
            CtExecutableReference<?> exec = inv.getExecutable();
            if (exec != null) {
                String methodName = exec.getSimpleName();
                if (methodName.equals("build")) {
                    // build()の前のチェーンを追跡してurl()を探す
                    return extractUrlFromOkHttpBuilderChain(inv);
                } else if (methodName.equals("url")) {
                    List<CtExpression<?>> args = inv.getArguments();
                    if (!args.isEmpty()) {
                        return extractUriFromExpression(args.get(0));
                    }
                }
            }
        }

        return "${UNRESOLVED}";
    }

    /**
     * OkHttp Request.Builderチェーンからurl()を探す
     */
    private String extractUrlFromOkHttpBuilderChain(CtInvocation<?> invocation) {
        CtExpression<?> target = invocation.getTarget();

        while (target != null) {
            if (target instanceof CtInvocation) {
                CtInvocation<?> targetInv = (CtInvocation<?>) target;
                CtExecutableReference<?> exec = targetInv.getExecutable();
                if (exec != null && exec.getSimpleName().equals("url")) {
                    List<CtExpression<?>> args = targetInv.getArguments();
                    if (!args.isEmpty()) {
                        return extractUriFromExpression(args.get(0));
                    }
                }
                target = targetInv.getTarget();
            } else {
                break;
            }
        }

        return "${UNRESOLVED}";
    }

    /**
     * HttpURLConnectionからURLを抽出
     */
    private String extractUriFromHttpUrlConnection(CtExpression<?> connExpr, CtMethod<?> method) {
        // conn変数の宣言を追跡
        if (connExpr instanceof CtVariableRead) {
            CtVariableRead<?> varRead = (CtVariableRead<?>) connExpr;
            CtVariableReference<?> varRef = varRead.getVariable();
            if (varRef != null) {
                CtVariable<?> varDecl = varRef.getDeclaration();
                if (varDecl != null && varDecl.getDefaultExpression() != null) {
                    // (HttpURLConnection) url.openConnection() のパターン
                    CtExpression<?> defaultExpr = varDecl.getDefaultExpression();
                    return extractUriFromUrlOpenConnection(defaultExpr, method);
                }
            }
        }

        return "${UNRESOLVED}";
    }

    /**
     * url.openConnection()からURLを抽出
     */
    private String extractUriFromUrlOpenConnection(CtExpression<?> expr, CtMethod<?> method) {
        // キャストを除去
        if (expr instanceof CtTargetedExpression) {
            CtExpression<?> target = ((CtTargetedExpression<?, ?>) expr).getTarget();
            if (target != null) {
                return extractUriFromUrlOpenConnection(target, method);
            }
        }

        if (expr instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) expr;
            CtExecutableReference<?> exec = inv.getExecutable();
            if (exec != null && exec.getSimpleName().equals("openConnection")) {
                // openConnection()のターゲット(URL変数)を追跡
                CtExpression<?> urlTarget = inv.getTarget();
                if (urlTarget instanceof CtVariableRead) {
                    CtVariableRead<?> urlVarRead = (CtVariableRead<?>) urlTarget;
                    CtVariableReference<?> urlVarRef = urlVarRead.getVariable();
                    if (urlVarRef != null) {
                        CtVariable<?> urlVarDecl = urlVarRef.getDeclaration();
                        if (urlVarDecl != null && urlVarDecl.getDefaultExpression() != null) {
                            // new URL("...") からURLを抽出
                            return extractUriFromExpression(urlVarDecl.getDefaultExpression());
                        }
                    }
                }
            }
        }

        return "${UNRESOLVED}";
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
        CtExecutable<?> declaration = executable.getExecutableDeclaration();

        // 宣言が取得でき、かつCtMethodの場合
        if (declaration != null && declaration instanceof CtMethod<?>) {
            return getMethodSignature((CtMethod<?>) declaration);
        }

        // CtMethodでない場合(コンストラクタなど)や取得できない場合のフォールバック
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
        if (typeName == null)
            return "";

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
        return className != null && (className.startsWith("java.") ||
                className.startsWith("javax.") ||
                className.startsWith("sun.") ||
                className.startsWith("com.sun.") ||
                className.startsWith("jdk."));
    }

    /**
     * 結果をエクスポート
     */
    public void export(String outputPath, String format) throws IOException {
        switch (format.toLowerCase()) {
            case "graphml":
                exportGraphML(outputPath);
                break;
            case "json":
            default:
                exportJson(outputPath);
                break;
        }
    }

    /**
     * JSON形式でエクスポート（methods, classes, interfacesを統合）
     */
    private void exportJson(String outputPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputPath), StandardCharsets.UTF_8)) {
            writer.write("{\n");

            // 1. methods セクション
            writer.write("  \"methods\": [\n");
            boolean first = true;
            for (String method : methodMap.keySet()) {
                if (!first)
                    writer.write(",\n");
                first = false;
                writer.write(createJsonMethodEntry(method));
            }
            writer.write("\n  ],\n");

            // 2. classes セクション
            writer.write("  \"classes\": [\n");
            writeClassesJson(writer);
            writer.write("  ],\n");

            // 3. interfaces セクション
            writer.write("  \"interfaces\": [\n");
            writeInterfacesJson(writer);
            writer.write("  ]\n");

            writer.write("}\n");
        }
    }

    /**
     * クラス情報をJSON形式で出力
     */
    private void writeClassesJson(BufferedWriter writer) throws IOException {
        List<CtType<?>> types = model.getElements(new TypeFilter<>(CtType.class));
        boolean first = true;

        for (CtType<?> type : types) {
            if (shouldExcludeType(type) || type.isInterface()) {
                continue;
            }

            String className = type.getQualifiedName();

            if (!first) {
                writer.write(",\n");
            }
            first = false;

            writer.write("    {\n");
            writer.write("      \"className\": \"" + escapeJson(className) + "\",\n");

            // Javadoc
            String javadocRaw = type.getDocComment();
            String javadoc = extractJavadocSummary(javadocRaw);
            writer.write("      \"javadoc\": \"" + escapeJson(javadoc) + "\",\n");

            // アノテーション（クラス名のみ）
            List<String> annotations = type.getAnnotations().stream()
                    .map(a -> a.getAnnotationType().getQualifiedName())
                    .collect(Collectors.toList());
            writer.write("      \"annotations\": [");
            boolean firstAnn = true;
            for (String ann : annotations) {
                if (!firstAnn)
                    writer.write(", ");
                writer.write("\"" + escapeJson(ann) + "\"");
                firstAnn = false;
            }
            writer.write("],\n");

            // アノテーション（フル形式、パス情報等を含む）
            List<String> annotationRaws = type.getAnnotations().stream()
                    .map(a -> a.toString())
                    .collect(Collectors.toList());
            writer.write("      \"annotationRaws\": [");
            boolean firstAnnRaw = true;
            for (String ann : annotationRaws) {
                if (!firstAnnRaw)
                    writer.write(", ");
                writer.write("\"" + escapeJson(ann) + "\"");
                firstAnnRaw = false;
            }
            writer.write("],\n");

            // スーパークラス
            String superClass = "";
            if (type instanceof CtClass) {
                CtClass<?> ctClass = (CtClass<?>) type;
                CtTypeReference<?> superClassRef = ctClass.getSuperclass();
                if (superClassRef != null && !isJavaStandardLibrary(superClassRef.getQualifiedName())) {
                    superClass = superClassRef.getQualifiedName();
                }
            }
            writer.write("      \"superClass\": \"" + escapeJson(superClass) + "\",\n");

            // 直接実装インターフェース
            Set<String> directInterfaces = new HashSet<>();
            for (CtTypeReference<?> ifaceRef : type.getSuperInterfaces()) {
                String ifaceName = ifaceRef.getQualifiedName();
                if (!isJavaStandardLibrary(ifaceName)) {
                    directInterfaces.add(ifaceName);
                }
            }
            writer.write("      \"directInterfaces\": [");
            boolean firstInterface = true;
            for (String iface : directInterfaces) {
                if (!firstInterface) {
                    writer.write(", ");
                }
                writer.write("\"" + escapeJson(iface) + "\"");
                firstInterface = false;
            }
            writer.write("],\n");

            // 全実装インターフェース
            Set<String> allInterfaces = getAllInterfaces(type);
            Set<String> filteredAllInterfaces = new HashSet<>();
            for (String iface : allInterfaces) {
                if (!isJavaStandardLibrary(iface)) {
                    filteredAllInterfaces.add(iface);
                }
            }
            writer.write("      \"allInterfaces\": [");
            firstInterface = true;
            for (String iface : filteredAllInterfaces) {
                if (!firstInterface) {
                    writer.write(", ");
                }
                writer.write("\"" + escapeJson(iface) + "\"");
                firstInterface = false;
            }
            writer.write("],\n");

            // hitWords（クラス本体のリテラル文字列から検出）
            Set<String> classHitWords = detectHitWordsInType(type);
            writer.write("      \"hitWords\": [");
            boolean firstWord = true;
            for (String word : classHitWords) {
                if (!firstWord)
                    writer.write(", ");
                writer.write("\"" + escapeJson(word) + "\"");
                firstWord = false;
            }
            writer.write("],\n");

            // フィールド初期化情報
            List<FieldInitializer> fieldInits = classFieldInitializers.getOrDefault(className, Collections.emptyList());
            writer.write("      \"fieldInitializers\": [");
            boolean firstFieldInit = true;
            for (FieldInitializer fi : fieldInits) {
                if (!firstFieldInit)
                    writer.write(", ");
                writer.write("{");
                writer.write("\"fieldName\": \"" + escapeJson(fi.fieldName) + "\", ");
                writer.write("\"fieldType\": \"" + escapeJson(fi.fieldType) + "\", ");
                writer.write("\"initializedClass\": \"" + escapeJson(fi.initializedClass) + "\"");
                writer.write("}");
                firstFieldInit = false;
            }
            writer.write("]\n");

            writer.write("    }");
        }
        writer.write("\n");
    }

    /**
     * インターフェース情報をJSON形式で出力
     */
    private void writeInterfacesJson(BufferedWriter writer) throws IOException {
        // インターフェースごとに実装クラスをまとめる
        Map<String, List<Map<String, Object>>> interfaceMap = new HashMap<>();
        Map<String, String> interfaceJavadocs = new HashMap<>();
        Map<String, List<String>> interfaceAnnotations = new HashMap<>();
        Map<String, List<String>> interfaceAnnotationRaws = new HashMap<>();
        Map<String, Set<String>> interfaceSuperInterfaces = new HashMap<>();
        Map<String, Set<String>> interfaceHitWordsMap = new HashMap<>();

        List<CtType<?>> types = model.getElements(new TypeFilter<>(CtType.class));

        // まず、インターフェース自体の情報を収集
        for (CtType<?> type : types) {
            if (shouldExcludeType(type)) {
                continue;
            }
            if (type.isInterface()) {
                String ifaceName = type.getQualifiedName();
                String javadocRaw = type.getDocComment();
                String javadoc = extractJavadocSummary(javadocRaw);
                interfaceJavadocs.put(ifaceName, javadoc);
                // System.out.println("Interface: " + ifaceName);

                List<String> anns = type.getAnnotations().stream()
                        .map(a -> a.getAnnotationType().getQualifiedName())
                        .collect(Collectors.toList());
                interfaceAnnotations.put(ifaceName, anns);

                // フル形式のアノテーションを収集
                List<String> annRaws = type.getAnnotations().stream()
                        .map(a -> a.toString())
                        .collect(Collectors.toList());
                interfaceAnnotationRaws.put(ifaceName, annRaws);

                // 親インターフェースを収集
                Set<String> superIfaces = new HashSet<>();
                for (CtTypeReference<?> superIfaceRef : type.getSuperInterfaces()) {
                    String superIfaceName = superIfaceRef.getQualifiedName();
                    if (!isJavaStandardLibrary(superIfaceName)) {
                        superIfaces.add(superIfaceName);
                    }
                }
                interfaceSuperInterfaces.put(ifaceName, superIfaces);

                // hitWords検出
                Set<String> ifaceHitWords = detectHitWordsInType(type);
                interfaceHitWordsMap.put(ifaceName, ifaceHitWords);
            }
        }

        // 実装クラスを収集
        for (CtType<?> type : types) {
            if (shouldExcludeType(type) || type.isInterface()) {
                continue;
            }

            String className = type.getQualifiedName();
            Set<String> directInterfaces = new HashSet<>();
            for (CtTypeReference<?> ifaceRef : type.getSuperInterfaces()) {
                String ifaceName = ifaceRef.getQualifiedName();
                if (!isJavaStandardLibrary(ifaceName)) {
                    directInterfaces.add(ifaceName);
                }
            }

            Set<String> allInterfaces = getAllInterfaces(type);
            String javadocRaw = type.getDocComment();
            String javadoc = extractJavadocSummary(javadocRaw);
            List<String> annotations = type.getAnnotations().stream()
                    .map(a -> a.getAnnotationType().getQualifiedName())
                    .collect(Collectors.toList());

            for (String iface : allInterfaces) {
                if (isJavaStandardLibrary(iface)) {
                    continue;
                }

                String implType = directInterfaces.contains(iface) ? "direct" : "indirect";

                interfaceMap.computeIfAbsent(iface, k -> new ArrayList<>());
                Map<String, Object> impl = new HashMap<>();
                impl.put("className", className);
                impl.put("type", implType);
                impl.put("javadoc", javadoc);
                impl.put("annotations", annotations);
                interfaceMap.get(iface).add(impl);
            }
        }

        // JSON出力（すべてのインターフェースを出力）
        boolean firstInterface = true;
        for (String ifaceName : interfaceJavadocs.keySet()) {
            if (!firstInterface) {
                writer.write(",\n");
            }
            firstInterface = false;

            String ifaceJavadoc = interfaceJavadocs.getOrDefault(ifaceName, "");

            writer.write("    {\n");
            writer.write("      \"interfaceName\": \"" + escapeJson(ifaceName) + "\",\n");
            writer.write("      \"javadoc\": \"" + escapeJson(ifaceJavadoc) + "\",\n");

            // アノテーション
            List<String> ifaceAnns = interfaceAnnotations.getOrDefault(ifaceName, Collections.emptyList());
            writer.write("      \"annotations\": [");
            boolean firstIfaceAnn = true;
            for (String ann : ifaceAnns) {
                if (!firstIfaceAnn)
                    writer.write(", ");
                writer.write("\"" + escapeJson(ann) + "\"");
                firstIfaceAnn = false;
            }
            writer.write("],\n");

            // アノテーション（フル形式）
            List<String> ifaceAnnRaws = interfaceAnnotationRaws.getOrDefault(ifaceName, Collections.emptyList());
            writer.write("      \"annotationRaws\": [");
            boolean firstIfaceAnnRaw = true;
            for (String ann : ifaceAnnRaws) {
                if (!firstIfaceAnnRaw)
                    writer.write(", ");
                writer.write("\"" + escapeJson(ann) + "\"");
                firstIfaceAnnRaw = false;
            }
            writer.write("],\n");

            // 親インターフェース
            Set<String> superIfaces = interfaceSuperInterfaces.getOrDefault(ifaceName, Collections.emptySet());
            writer.write("      \"superInterfaces\": [");
            boolean firstSuperIface = true;
            for (String superIface : superIfaces) {
                if (!firstSuperIface)
                    writer.write(", ");
                writer.write("\"" + escapeJson(superIface) + "\"");
                firstSuperIface = false;
            }
            writer.write("],\n");

            // hitWords
            Set<String> ifaceHitWords = interfaceHitWordsMap.getOrDefault(ifaceName, Collections.emptySet());
            writer.write("      \"hitWords\": [");
            boolean firstWord = true;
            for (String word : ifaceHitWords) {
                if (!firstWord)
                    writer.write(", ");
                writer.write("\"" + escapeJson(word) + "\"");
                firstWord = false;
            }
            writer.write("],\n");

            // 実装クラス
            List<Map<String, Object>> implementations = interfaceMap.getOrDefault(ifaceName, Collections.emptyList());
            writer.write("      \"implementations\": [\n");
            boolean firstImpl = true;
            for (Map<String, Object> impl : implementations) {
                if (!firstImpl) {
                    writer.write(",\n");
                }
                firstImpl = false;

                writer.write("        {\n");
                writer.write("          \"className\": \"" + escapeJson((String) impl.get("className")) + "\",\n");
                writer.write("          \"type\": \"" + escapeJson((String) impl.get("type")) + "\",\n");
                writer.write("          \"javadoc\": \"" + escapeJson((String) impl.get("javadoc")) + "\",\n");

                writer.write("          \"annotations\": [");
                @SuppressWarnings("unchecked")
                List<String> anns = (List<String>) impl.get("annotations");
                boolean firstAnn = true;
                for (String ann : anns) {
                    if (!firstAnn)
                        writer.write(", ");
                    writer.write("\"" + escapeJson(ann) + "\"");
                    firstAnn = false;
                }
                writer.write("]\n");

                writer.write("        }");
            }

            writer.write("\n      ]\n");
            writer.write("    }");
        }
        writer.write("\n");
    }

    /**
     * 型に含まれるリテラル文字列から検索ワードを検出
     */
    private Set<String> detectHitWordsInType(CtType<?> type) {
        Set<String> hitWords = new HashSet<>();
        if (searchWords.isEmpty()) {
            return hitWords;
        }

        List<CtLiteral<?>> literals = type.getElements(new TypeFilter<>(CtLiteral.class));
        for (CtLiteral<?> literal : literals) {
            Object value = literal.getValue();
            if (value instanceof String) {
                String strValue = (String) value;
                for (String word : searchWords) {
                    if (matchesWordBoundary(strValue, word)) {
                        hitWords.add(word);
                    }
                }
            }
        }
        return hitWords;
    }

    /**
     * 文字列が検索ワードを単語境界で含むかチェック
     */
    private boolean matchesWordBoundary(String text, String word) {
        if (text == null || word == null || word.isEmpty()) {
            return false;
        }
        String regex = "(?i)(?<=[\\s\\p{Punct}]|^)" + java.util.regex.Pattern.quote(word) + "(?=[\\s\\p{Punct}]|$)";
        return java.util.regex.Pattern.compile(regex).matcher(text).find();
    }

    /**
     * JSON形式のメソッドエントリを作成
     */
    private String createJsonMethodEntry(String method) {
        String className = methodToClassMap.getOrDefault(method, "");
        Set<String> calls = callGraph.getOrDefault(method, Collections.emptySet());
        Set<String> calledBy = reverseCallGraph.getOrDefault(method, Collections.emptySet());
        MethodMetadata meta = methodMetadata.get(method);

        StringBuilder json = new StringBuilder();
        json.append("    {\n");
        json.append("      \"method\": \"").append(escapeJson(method)).append("\",\n");
        json.append("      \"class\": \"").append(escapeJson(className)).append("\",\n");

        // 呼び出し元の親クラス情報（すべての親クラス・インターフェース）
        String parentClasses = getParentClasses(className);
        json.append("      \"parentClasses\": \"").append(escapeJson(parentClasses)).append("\",\n");

        if (meta != null) {
            json.append("      \"visibility\": \"")
                    .append(meta.isPublic ? "public" : (meta.isProtected ? "protected" : "private")).append("\",\n");
            json.append("      \"isAbstract\": ").append(meta.isAbstract).append(",\n");
            json.append("      \"isStatic\": ").append(meta.isStatic).append(",\n");
            json.append("      \"isEntryPoint\": ").append(meta.isEntryPointCandidate()).append(",\n");
            json.append("      \"entryType\": \"").append(escapeJson(meta.getEntryPointType())).append("\",\n");
            json.append("      \"annotations\": [");
            boolean firstAnn = true;
            for (String ann : meta.annotationRaws) {
                if (!firstAnn)
                    json.append(", ");
                json.append("\"").append(escapeJson(ann)).append("\"");
                firstAnn = false;
            }
            json.append("],\n");
            json.append("      \"javadoc\": \"")
                    .append(escapeJson(meta.javadocSummary != null ? meta.javadocSummary : ""))
                    .append("\",\n");
            json.append("      \"parameterAnnotations\": \"")
                    .append(escapeJson(meta.parameterAnnotations != null ? meta.parameterAnnotations : ""))
                    .append("\",\n");
        }

        // 呼び出し先詳細情報（キー名: calls）
        json.append("      \"calls\": [\n");
        boolean firstCall = true;
        for (String callee : calls) {
            if (!firstCall)
                json.append(",\n");
            firstCall = false;

            String calleeClass = methodToClassMap.getOrDefault(callee, "");
            boolean isParentMethod = isParentClassMethod(method, callee);
            String implementations = getImplementations(method, callee);

            json.append("        {\n");
            json.append("          \"method\": \"").append(escapeJson(callee)).append("\",\n");
            json.append("          \"class\": \"").append(escapeJson(calleeClass)).append("\",\n");
            json.append("          \"isParentMethod\": ").append(isParentMethod);
            if (implementations != null && !implementations.isEmpty()) {
                json.append(",\n          \"implementations\": \"").append(escapeJson(implementations)).append("\"");
            }
            json.append("\n        }");
        }
        json.append("\n      ],\n");

        // 呼び出し元一覧
        json.append("      \"calledBy\": [");
        boolean firstCaller = true;
        for (String caller : calledBy) {
            if (!firstCaller)
                json.append(", ");
            json.append("\"").append(escapeJson(caller)).append("\"");
            firstCaller = false;
        }
        json.append("]");

        if (sqlStatements.containsKey(method)) {
            json.append(",\n      \"sqlStatements\": [");
            boolean firstSql = true;
            for (String sql : sqlStatements.get(method)) {
                if (!firstSql)
                    json.append(", ");
                json.append("\"").append(escapeJson(sql)).append("\"");
                firstSql = false;
            }
            json.append("]");
        }

        // 検出ワードを追加
        Set<String> hitWords = methodHitWords.get(method);
        if (hitWords != null && !hitWords.isEmpty()) {
            json.append(",\n      \"hitWords\": [");
            boolean firstWord = true;
            for (String word : hitWords) {
                if (!firstWord)
                    json.append(", ");
                json.append("\"").append(escapeJson(word)).append("\"");
                firstWord = false;
            }
            json.append("]");
        }

        // 生成インスタンスを追加
        Set<String> createdInstances = methodCreatedInstances.get(method);
        if (createdInstances != null && !createdInstances.isEmpty()) {
            json.append(",\n      \"createdInstances\": [");
            boolean firstInstance = true;
            for (String inst : createdInstances) {
                if (!firstInstance)
                    json.append(", ");
                json.append("\"").append(escapeJson(inst)).append("\"");
                firstInstance = false;
            }
            json.append("]");
        }

        // HTTPクライアント呼び出しを追加
        List<HttpCallInfo> httpCallList = httpCalls.get(method);
        if (httpCallList != null && !httpCallList.isEmpty()) {
            json.append(",\n      \"httpCalls\": [\n");
            boolean firstHttp = true;
            for (HttpCallInfo info : httpCallList) {
                if (!firstHttp)
                    json.append(",\n");
                firstHttp = false;
                json.append("        {\n");
                json.append("          \"httpMethod\": \"").append(escapeJson(info.httpMethod)).append("\",\n");
                json.append("          \"uri\": \"").append(escapeJson(info.uri)).append("\",\n");
                json.append("          \"clientLibrary\": \"").append(escapeJson(info.clientLibrary)).append("\"\n");
                json.append("        }");
            }
            json.append("\n      ]");
        }

        json.append("\n    }");
        return json.toString();
    }

    /**
     * JSON用のエスケープ
     */
    private String escapeJson(String str) {
        if (str == null)
            return "";
        return str.replace("\\", "\\\\") // バックスラッシュ
                .replace("\"", "\\\"") // ダブルクォート
                .replace("\n", "\\n") // 改行
                .replace("\r", "\\r") // キャリッジリターン
                .replace("\t", "\\t"); // タブ
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
     * インターフェース/抽象クラスの実装候補を取得（優先度順）
     */
    private String getImplementations(String callerMethodSignature, String calleeMethodSignature) {
        String calleeClass = methodToClassMap.getOrDefault(calleeMethodSignature, "");
        String callerClass = extractCallerClass(callerMethodSignature);

        // 優先度0: callerClassとcalleeClassが同じ場合は候補なし
        if (callerClass != null && callerClass.equals(calleeClass)) {
            return "";
        }

        List<String> candidates = new ArrayList<>();

        // 優先度1: XML定義されたbeanのidとフィールドの@Qualifier/フィールド名が一致
        if (callerClass != null && fieldInjectionDetails != null) {
            for (FieldInjectionInfo fieldInfo : fieldInjectionDetails.values()) {
                if (fieldInfo.ownerClass.equals(callerClass)) {
                    String matchId = null;

                    // @Qualifierで指定されたIDがある場合、それを優先
                    if (fieldInfo.qualifierId != null) {
                        matchId = fieldInfo.qualifierId;
                    } else {
                        // @Qualifierがない場合、フィールド名を使用
                        matchId = fieldInfo.fieldName;
                    }

                    // XMLで定義されたBeanを検索
                    BeanDefinition xmlBean = beanDefinitions.get(matchId);
                    if (xmlBean != null && "XML".equals(xmlBean.source) &&
                            isAssignableFrom(calleeClass, xmlBean.className)) {
                        candidates.add(xmlBean.className + " [XML:" + xmlBean.id + "]");
                        return String.join(", ", candidates); // 該当したら以降を探さない
                    }
                }
            }
        }

        // 優先度2: Beanアノテーションで明示されたidとフィールドの@Qualifier/フィールド名が一致
        if (callerClass != null && fieldInjectionDetails != null) {
            for (FieldInjectionInfo fieldInfo : fieldInjectionDetails.values()) {
                if (fieldInfo.ownerClass.equals(callerClass)) {
                    String matchId = null;

                    // @Qualifierで指定されたIDがある場合、それを優先
                    if (fieldInfo.qualifierId != null) {
                        matchId = fieldInfo.qualifierId;
                    } else {
                        // @Qualifierがない場合、フィールド名を使用
                        matchId = fieldInfo.fieldName;
                    }

                    // Beanアノテーションで明示されたidを検索
                    BeanDefinition annotatedBean = beanDefinitions.get(matchId);
                    if (annotatedBean != null && !annotatedBean.source.equals("XML") &&
                            !annotatedBean.id.equals(generateDefaultBeanId(annotatedBean.className)) &&
                            isAssignableFrom(calleeClass, annotatedBean.className)) {
                        candidates.add(
                                annotatedBean.className + " [@" + annotatedBean.source + ":" + annotatedBean.id + "]");
                        return String.join(", ", candidates); // 該当したら以降を探さない
                    }
                }
            }
        }

        // 優先度3: Beanクラス名から自動生成されたidとフィールドの@Qualifier/フィールド名が一致
        if (callerClass != null && fieldInjectionDetails != null) {
            for (FieldInjectionInfo fieldInfo : fieldInjectionDetails.values()) {
                if (fieldInfo.ownerClass.equals(callerClass)) {
                    String matchId = null;

                    // @Qualifierで指定されたIDがある場合、それを優先
                    if (fieldInfo.qualifierId != null) {
                        matchId = fieldInfo.qualifierId;
                    } else {
                        // @Qualifierがない場合、フィールド名を使用
                        matchId = fieldInfo.fieldName;
                    }

                    // Beanクラス名から自動生成されたidを検索
                    BeanDefinition autoBean = beanDefinitions.get(matchId);
                    if (autoBean != null && !autoBean.source.equals("XML") &&
                            autoBean.id.equals(generateDefaultBeanId(autoBean.className)) &&
                            isAssignableFrom(calleeClass, autoBean.className)) {
                        candidates.add(autoBean.className + " [@" + autoBean.source + ":auto]");
                        return String.join(", ", candidates); // 該当したら以降を探さない
                    }
                }
            }
        }

        // 優先度4: フィールドの型から推測
        if (callerClass != null && fieldInjections.containsKey(callerClass)) {
            for (FieldInjectionInfo fieldInfo : fieldInjectionDetails.values()) {
                if (fieldInfo.ownerClass.equals(callerClass) &&
                        isAssignableFrom(calleeClass, fieldInfo.fieldType)) {
                    candidates.add(fieldInfo.fieldType + " [field-type]");
                }
            }
            return String.join(", ", candidates);
        }

        // どの条件にも該当しない場合、インターフェース実装から推測
        Set<String> impls = interfaceImplementations.getOrDefault(calleeClass, Collections.emptySet());
        for (String impl : impls) {
            candidates.add(impl + " [interface-impl]");
        }

        return String.join(", ", candidates);
    }

    /**
     * クラスの代入可能性をチェック（継承・実装関係）
     */
    private boolean isAssignableFrom(String baseClass, String derivedClass) {
        if (baseClass.equals(derivedClass)) {
            return true;
        }

        // インターフェース実装のチェック
        Set<String> impls = interfaceImplementations.getOrDefault(baseClass, Collections.emptySet());
        if (impls.contains(derivedClass)) {
            return true;
        }

        // 継承関係のチェック（簡易版）
        Set<String> parents = classHierarchy.getOrDefault(derivedClass, Collections.emptySet());
        return parents.contains(baseClass);
    }

    /**
     * メソッドシグネチャから呼び出し元クラスを抽出
     */
    private String extractCallerClass(String methodSignature) {
        if (methodSignature.contains("#")) {
            return methodSignature.split("#")[0];
        }
        return null;
    }

    /**
     * クラス名からデフォルトのBean IDを生成
     */
    private String generateDefaultBeanId(String className) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    /**
     * TSV/CSV用のエスケープ
     */
    private String escape(String str) {
        if (str == null)
            return "";
        return str.replace("\t", " ").replace("\n", " ").replace("\r", "");
    }

    /**
     * Javadoc文字列から要約（最初の文）を抽出
     */
    private String extractJavadocSummary(String doc) {
        if (doc == null || doc.isEmpty()) {
            return "";
        }

        // Javadocの開始・終了タグを除去
        String cleaned = doc.replaceAll("(?s)/\\*\\*|\\*/", "");
        String[] lines = cleaned.split("\\r?\\n");

        for (String l : lines) {
            String s = l.trim();
            // 行頭のアスタリスクを除去
            s = s.replaceFirst("^\\*+\\s?", "").trim();

            if (!s.isEmpty()) {
                // @タグの行はスキップ（説明文を探す）
                if (s.startsWith("@")) {
                    continue;
                }
                return s;
            }
        }
        return "";
    }

    /**
     * 解析対象から除外すべき型かどうかを判定
     */
    private boolean shouldExcludeType(CtType<?> type) {
        String className = type.getQualifiedName();

        // Java標準ライブラリは除外
        if (isJavaStandardLibrary(className)) {
            return true;
        }

        // 無名クラス
        if (type.isAnonymous()) {
            return true;
        }

        // Lambda (SpoonではCtLambdaだが、念のためクラス名チェック)
        // クラス名が数字のみ、あるいは空の場合は除外
        String simpleName = type.getSimpleName();
        if (simpleName.isEmpty() || simpleName.matches("\\d+")) {
            return true;
        }

        // 難読化クラス（1文字のクラス名）
        // パッケージ名を除いた単純名で判定
        if (simpleName.length() == 1) {
            return true;
        }

        return false;
    }
}
