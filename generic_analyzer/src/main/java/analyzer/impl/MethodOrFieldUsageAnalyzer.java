package analyzer.impl;

import analyzer.Analyzer;
import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtElement;

/**
 * メソッド・フィールド使用の調査Analyzer。
 * 特定の名前のメソッド（コンストラクタを含む）やフィールドが使用されている箇所を特定する。
 * 
 * 名前の指定方法:
 * - SimpleName: "executeQuery" → 全クラスのexecuteQueryメソッドにマッチ
 * - QualifiedName: "java.sql.Connection.prepareStatement" → 特定クラスのメソッドにマッチ
 * - QualifiedName (フィールド): "java.lang.System.out" → 特定クラスのフィールドにマッチ
 */
public class MethodOrFieldUsageAnalyzer implements Analyzer {

    private static final String CATEGORY_METHOD_CALL = "Method Call";
    private static final String CATEGORY_CONSTRUCTOR_CALL = "Constructor Call";
    private static final String CATEGORY_FIELD_ACCESS = "Field Access";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        // 対象名リストが空の場合はスキップ
        if (context.getTargetNames().isEmpty()) {
            return;
        }

        // メソッド呼び出し
        if (element instanceof CtInvocation<?>) {
            analyzeInvocation((CtInvocation<?>) element, context);
            return;
        }

        // コンストラクタ呼び出し
        if (element instanceof CtConstructorCall<?>) {
            analyzeConstructorCall((CtConstructorCall<?>) element, context);
            return;
        }

        // フィールド読み取り
        if (element instanceof CtFieldRead<?>) {
            analyzeFieldRead((CtFieldRead<?>) element, context);
            return;
        }

        // フィールド書き込み
        if (element instanceof CtFieldWrite<?>) {
            analyzeFieldWrite((CtFieldWrite<?>) element, context);
        }
    }

    /**
     * メソッド呼び出しを解析する。
     */
    private void analyzeInvocation(CtInvocation<?> invocation, AnalysisContext context) {
        if (invocation.getExecutable() == null) {
            return;
        }

        String methodName = invocation.getExecutable().getSimpleName();
        String declaringType = "";
        if (invocation.getExecutable().getDeclaringType() != null) {
            declaringType = invocation.getExecutable().getDeclaringType().getQualifiedName();
        }

        // 完全修飾名を構築（例: java.sql.Connection.prepareStatement）
        String qualifiedName = declaringType.isEmpty() ? methodName : declaringType + "." + methodName;

        // SimpleNameまたはQualifiedNameでマッチ
        if (isTargetNameMatch(context, methodName, qualifiedName)) {
            AnalysisResult result = AnalysisResult.fromElement(
                    invocation,
                    CATEGORY_METHOD_CALL,
                    qualifiedName + "()",
                    context);
            context.addResult(result);
        }
    }

    /**
     * コンストラクタ呼び出しを解析する。
     */
    private void analyzeConstructorCall(CtConstructorCall<?> constructorCall, AnalysisContext context) {
        if (constructorCall.getType() == null) {
            return;
        }

        String typeName = constructorCall.getType().getSimpleName();
        String qualifiedName = constructorCall.getType().getQualifiedName();

        // SimpleNameまたはQualifiedNameでマッチ
        if (isTargetNameMatch(context, typeName, qualifiedName)) {
            AnalysisResult result = AnalysisResult.fromElement(
                    constructorCall,
                    CATEGORY_CONSTRUCTOR_CALL,
                    "new " + qualifiedName + "()",
                    context);
            context.addResult(result);
        }
    }

    /**
     * フィールド読み取りを解析する。
     */
    private void analyzeFieldRead(CtFieldRead<?> fieldRead, AnalysisContext context) {
        if (fieldRead.getVariable() == null) {
            return;
        }

        String fieldName = fieldRead.getVariable().getSimpleName();
        String declaringType = "";
        if (fieldRead.getVariable().getDeclaringType() != null) {
            declaringType = fieldRead.getVariable().getDeclaringType().getQualifiedName();
        }

        // 完全修飾名を構築（例: java.lang.System.out）
        String qualifiedName = declaringType.isEmpty() ? fieldName : declaringType + "." + fieldName;

        // SimpleNameまたはQualifiedNameでマッチ
        if (isTargetNameMatch(context, fieldName, qualifiedName)) {
            AnalysisResult result = AnalysisResult.fromElement(
                    fieldRead,
                    CATEGORY_FIELD_ACCESS,
                    qualifiedName + " (read)",
                    context);
            context.addResult(result);
        }
    }

    /**
     * フィールド書き込みを解析する。
     */
    private void analyzeFieldWrite(CtFieldWrite<?> fieldWrite, AnalysisContext context) {
        if (fieldWrite.getVariable() == null) {
            return;
        }

        String fieldName = fieldWrite.getVariable().getSimpleName();
        String declaringType = "";
        if (fieldWrite.getVariable().getDeclaringType() != null) {
            declaringType = fieldWrite.getVariable().getDeclaringType().getQualifiedName();
        }

        // 完全修飾名を構築
        String qualifiedName = declaringType.isEmpty() ? fieldName : declaringType + "." + fieldName;

        // SimpleNameまたはQualifiedNameでマッチ
        if (isTargetNameMatch(context, fieldName, qualifiedName)) {
            AnalysisResult result = AnalysisResult.fromElement(
                    fieldWrite,
                    CATEGORY_FIELD_ACCESS,
                    qualifiedName + " (write)",
                    context);
            context.addResult(result);
        }
    }

    /**
     * 対象名にマッチするかを確認する。
     * SimpleNameまたはQualifiedNameのいずれかでマッチすれば真。
     *
     * @param context       コンテキスト
     * @param simpleName    単純名（例: prepareStatement）
     * @param qualifiedName 完全修飾名（例: java.sql.Connection.prepareStatement）
     * @return マッチした場合true
     */
    private boolean isTargetNameMatch(AnalysisContext context, String simpleName, String qualifiedName) {
        for (String targetName : context.getTargetNames()) {
            // 完全修飾名でのマッチ
            if (targetName.contains(".")) {
                if (qualifiedName.equals(targetName)) {
                    return true;
                }
            } else {
                // 単純名でのマッチ
                if (simpleName.equals(targetName)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String getCategory() {
        return "Method/Field Usage";
    }
}
