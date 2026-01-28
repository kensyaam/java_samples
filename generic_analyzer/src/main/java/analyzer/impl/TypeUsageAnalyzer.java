package analyzer.impl;

import analyzer.Analyzer;
import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.code.CtComment;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 型使用の調査Analyzer。
 * 正規表現で指定されたパッケージやクラスを使用している箇所を特定する。
 * 
 * 検出対象:
 * - 変数宣言、メソッドの戻り値型・パラメータ型
 * - 継承・実装 (extends, implements)
 * - キャスト、instanceof
 * - ジェネリクス (List<String>)
 * - throws句、catch句
 * - import文
 * - Javadoc (@exception, @throws, @see, @link 等)
 * - コンストラクタ呼び出し (new Foo())
 * - メソッド呼び出しのターゲット型 (connection.prepareStatement())
 */
public class TypeUsageAnalyzer implements Analyzer {

    private static final String CATEGORY_TYPE_USAGE = "Type Usage";
    private static final String CATEGORY_IMPORT = "Import";
    private static final String CATEGORY_JAVADOC = "Javadoc Reference";
    private static final String CATEGORY_CONSTRUCTOR_CALL = "Constructor Call";
    private static final String CATEGORY_METHOD_CALL = "Method Call (on type)";

    // Javadocの型参照パターン（@exception, @throws, @see, @link, @linkplain）
    private static final Pattern JAVADOC_TYPE_PATTERN = Pattern.compile(
            "@(?:exception|throws|see|link|linkplain)\\s+([\\w.]+)");

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        // 型パターンが設定されていない場合はスキップ
        if (context.getTypePattern() == null) {
            return;
        }

        // 1. 通常の型参照（CtTypeReference）
        if (element instanceof CtTypeReference<?>) {
            analyzeTypeReference((CtTypeReference<?>) element, context);
            return;
        }

        // 2. import文（CtImport）
        if (element instanceof CtImport) {
            analyzeImport((CtImport) element, context);
            return;
        }

        // 3. Javadocコメント
        if (element instanceof CtComment) {
            CtComment comment = (CtComment) element;
            if (comment.getCommentType() == CtComment.CommentType.JAVADOC) {
                analyzeJavadoc(comment, context);
            }
            return;
        }

        // 4. コンストラクタ呼び出し（new Foo()）
        if (element instanceof CtConstructorCall<?>) {
            analyzeConstructorCall((CtConstructorCall<?>) element, context);
            return;
        }

        // 5. メソッド呼び出し（foo.bar()）
        if (element instanceof CtInvocation<?>) {
            analyzeMethodInvocation((CtInvocation<?>) element, context);
            return;
        }

        // 6. コンパイルユニットからimport文を取得（型解析時にimportが漏れる場合の対策）
        if (element instanceof CtType<?>) {
            CtType<?> type = (CtType<?>) element;
            analyzeImportsFromType(type, context);
        }
    }

    /**
     * 通常の型参照を解析する。
     */
    private void analyzeTypeReference(CtTypeReference<?> typeRef, AnalysisContext context) {
        String qualifiedName = typeRef.getQualifiedName();
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return;
        }

        if (context.matchesTypePattern(qualifiedName)) {
            AnalysisResult result = AnalysisResult.fromElement(
                    typeRef,
                    CATEGORY_TYPE_USAGE,
                    qualifiedName);
            context.addResult(result);
        }
    }

    /**
     * import文を解析する。
     */
    private void analyzeImport(CtImport ctImport, AnalysisContext context) {
        if (ctImport.getReference() == null) {
            return;
        }

        String importName = ctImport.getReference().toString();
        if (importName == null || importName.isEmpty()) {
            return;
        }

        // ワイルドカードインポートの場合はパッケージ名として扱う
        // 例: java.sql.* -> java.sql
        String checkName = importName.endsWith(".*")
                ? importName.substring(0, importName.length() - 2)
                : importName;

        if (context.matchesTypePattern(checkName) || context.matchesTypePattern(importName)) {
            AnalysisResult result = AnalysisResult.fromElement(
                    ctImport,
                    CATEGORY_IMPORT,
                    "import " + importName);
            context.addResult(result);
        }
    }

    /**
     * 型からimport文を取得して解析する（CtImportが直接スキャンされない場合の対策）。
     */
    private void analyzeImportsFromType(CtType<?> type, AnalysisContext context) {
        if (type.getPosition() == null || !type.getPosition().isValidPosition()) {
            return;
        }

        CtCompilationUnit cu = type.getPosition().getCompilationUnit();
        if (cu == null) {
            return;
        }

        for (CtImport ctImport : cu.getImports()) {
            // 暗黙的なimport（java.lang.*など）はスキップしない
            if (ctImport.getReference() == null) {
                continue;
            }

            String importName = ctImport.getReference().toString();
            if (importName == null || importName.isEmpty()) {
                continue;
            }

            String checkName = importName.endsWith(".*")
                    ? importName.substring(0, importName.length() - 2)
                    : importName;

            if (context.matchesTypePattern(checkName) || context.matchesTypePattern(importName)) {
                // import文はファイルごとに1回だけ検出（重複チェック）
                String importKey = type.getPosition().getFile().getName() + ":" + importName;
                if (!context.isAlreadyDetected(importKey)) {
                    context.markAsDetected(importKey);
                    AnalysisResult result = AnalysisResult.fromElement(
                            ctImport.isImplicit() ? type : ctImport,
                            CATEGORY_IMPORT,
                            "import " + importName);
                    context.addResult(result);
                }
            }
        }
    }

    /**
     * Javadocコメントから型参照を解析する。
     */
    private void analyzeJavadoc(CtComment comment, AnalysisContext context) {
        String content = comment.getContent();
        if (content == null || content.isEmpty()) {
            return;
        }

        Matcher matcher = JAVADOC_TYPE_PATTERN.matcher(content);
        while (matcher.find()) {
            String typeName = matcher.group(1);
            if (context.matchesTypePattern(typeName)) {
                AnalysisResult result = AnalysisResult.fromElement(
                        comment,
                        CATEGORY_JAVADOC,
                        typeName + " (in Javadoc)");
                context.addResult(result);
            }
        }
    }

    /**
     * コンストラクタ呼び出しを解析する。
     * new Foo() 形式の呼び出しを検出。
     */
    private void analyzeConstructorCall(CtConstructorCall<?> constructorCall, AnalysisContext context) {
        CtTypeReference<?> typeRef = constructorCall.getType();
        if (typeRef == null) {
            return;
        }

        String qualifiedName = typeRef.getQualifiedName();
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return;
        }

        if (context.matchesTypePattern(qualifiedName)) {
            AnalysisResult result = AnalysisResult.fromElement(
                    constructorCall,
                    CATEGORY_CONSTRUCTOR_CALL,
                    "new " + qualifiedName + "()");
            context.addResult(result);
        }
    }

    /**
     * メソッド呼び出しを解析する。
     * ターゲットオブジェクトの型がパターンに一致する場合に検出。
     */
    private void analyzeMethodInvocation(CtInvocation<?> invocation, AnalysisContext context) {
        CtExecutableReference<?> executable = invocation.getExecutable();
        if (executable == null) {
            return;
        }

        // メソッドが宣言されている型を取得
        CtTypeReference<?> declaringType = executable.getDeclaringType();
        if (declaringType == null) {
            return;
        }

        String qualifiedName = declaringType.getQualifiedName();
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return;
        }

        if (context.matchesTypePattern(qualifiedName)) {
            String methodName = executable.getSimpleName();
            AnalysisResult result = AnalysisResult.fromElement(
                    invocation,
                    CATEGORY_METHOD_CALL,
                    qualifiedName + "." + methodName + "()");
            context.addResult(result);
        }
    }

    @Override
    public String getCategory() {
        return CATEGORY_TYPE_USAGE;
    }
}
