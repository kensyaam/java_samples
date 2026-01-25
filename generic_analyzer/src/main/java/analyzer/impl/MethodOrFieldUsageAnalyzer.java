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
        if (context.isTargetName(methodName)) {
            // 呼び出し先のクラス名も取得
            String declaringType = "";
            if (invocation.getExecutable().getDeclaringType() != null) {
                declaringType = invocation.getExecutable().getDeclaringType().getQualifiedName() + ".";
            }

            AnalysisResult result = AnalysisResult.fromElement(
                    invocation,
                    CATEGORY_METHOD_CALL,
                    declaringType + methodName + "()");
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
        // コンストラクタ名は型名と同じ
        if (context.isTargetName(typeName)) {
            AnalysisResult result = AnalysisResult.fromElement(
                    constructorCall,
                    CATEGORY_CONSTRUCTOR_CALL,
                    "new " + constructorCall.getType().getQualifiedName() + "()");
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
        if (context.isTargetName(fieldName)) {
            // フィールドの宣言クラスも取得
            String declaringType = "";
            if (fieldRead.getVariable().getDeclaringType() != null) {
                declaringType = fieldRead.getVariable().getDeclaringType().getQualifiedName() + ".";
            }

            AnalysisResult result = AnalysisResult.fromElement(
                    fieldRead,
                    CATEGORY_FIELD_ACCESS,
                    declaringType + fieldName + " (read)");
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
        if (context.isTargetName(fieldName)) {
            // フィールドの宣言クラスも取得
            String declaringType = "";
            if (fieldWrite.getVariable().getDeclaringType() != null) {
                declaringType = fieldWrite.getVariable().getDeclaringType().getQualifiedName() + ".";
            }

            AnalysisResult result = AnalysisResult.fromElement(
                    fieldWrite,
                    CATEGORY_FIELD_ACCESS,
                    declaringType + fieldName + " (write)");
            context.addResult(result);
        }
    }

    @Override
    public String getCategory() {
        return "Method/Field Usage";
    }
}
