package analyzer.impl;

import analyzer.Analyzer;
import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;

import java.lang.annotation.Annotation;

/**
 * @Deprecated(forRemoval=true) が付与されたメソッド、クラス等を使用している箇所を抽出するAnalyzer。
 */
public class DeprecatedForRemovalAnalyzer implements Analyzer {

    private static final String CATEGORY = "Deprecated for Removal";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        if (!context.isCheckDeprecatedForRemoval()) {
            return;
        }

        // 1. メソッド・コンストラクタ呼び出し
        if (element instanceof CtInvocation<?>) {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> ref = invocation.getExecutable();
            if (checkExecutable(ref)) {
                String declaringTypeName = "unknown";
                if (ref.getDeclaringType() != null) {
                    declaringTypeName = ref.getDeclaringType().getQualifiedName();
                }
                String matched = "Method Call: " + declaringTypeName + "." + ref.getSimpleName() + "()";
                context.addResult(AnalysisResult.fromElement(invocation, CATEGORY, matched, context));
            }
        } else if (element instanceof CtConstructorCall<?>) {
            CtConstructorCall<?> consCall = (CtConstructorCall<?>) element;
            CtExecutableReference<?> ref = consCall.getExecutable();
            if (checkExecutable(ref)) {
                String typeName = "unknown";
                if (consCall.getType() != null) {
                    typeName = consCall.getType().getQualifiedName();
                }
                String matched = "Constructor Call: new " + typeName + "()";
                context.addResult(AnalysisResult.fromElement(consCall, CATEGORY, matched, context));
            }
        }
        // 2. フィールド参照
        else if (element instanceof CtFieldAccess<?>) {
            CtFieldAccess<?> fieldAccess = (CtFieldAccess<?>) element;
            CtFieldReference<?> ref = fieldAccess.getVariable();
            if (checkField(ref)) {
                String declaringTypeName = "unknown";
                if (ref.getDeclaringType() != null) {
                    declaringTypeName = ref.getDeclaringType().getQualifiedName();
                }
                String matched = "Field Access: " + declaringTypeName + "." + ref.getSimpleName();
                context.addResult(AnalysisResult.fromElement(fieldAccess, CATEGORY, matched, context));
            }
        }
        // 3. 型参照
        else if (element instanceof CtTypeReference<?>) {
            CtTypeReference<?> typeRef = (CtTypeReference<?>) element;
            if (checkType(typeRef)) {
                String matched = "Type Usage: " + typeRef.getQualifiedName();
                context.addResult(AnalysisResult.fromElement(typeRef, CATEGORY, matched, context));
            }
        }
    }

    /**
     * メソッドやコンストラクタが @Deprecated(forRemoval=true) かチェックする。
     */
    private boolean checkExecutable(CtExecutableReference<?> ref) {
        if (ref == null) {
            return false;
        }
        // 1. AST内の宣言からチェック
        try {
            CtExecutable<?> decl = ref.getDeclaration();
            if (decl != null && isDeprecatedForRemoval(decl)) {
                return true;
            }
        } catch (Exception e) {
            // Spoon解析エラー対策
        }
        // 2. リフレクション（バイナリ参照）からチェック
        try {
            java.lang.reflect.Method method = ref.getActualMethod();
            if (method != null) {
                Deprecated dep = method.getAnnotation(Deprecated.class);
                if (dep != null && dep.forRemoval()) {
                    return true;
                }
            }
        } catch (Exception e) {
            // 無視
        }
        try {
            java.lang.reflect.Constructor<?> cons = ref.getActualConstructor();
            if (cons != null) {
                Deprecated dep = cons.getAnnotation(Deprecated.class);
                if (dep != null && dep.forRemoval()) {
                    return true;
                }
            }
        } catch (Exception e) {
            // 無視
        }
        return false;
    }

    /**
     * フィールドが @Deprecated(forRemoval=true) かチェックする。
     */
    private boolean checkField(CtFieldReference<?> ref) {
        if (ref == null) {
            return false;
        }
        // 1. AST内の宣言からチェック
        try {
            CtField<?> decl = ref.getDeclaration();
            if (decl != null && isDeprecatedForRemoval(decl)) {
                return true;
            }
        } catch (Exception e) {
            // 無視
        }
        // 2. リフレクション（バイナリ参照）からチェック
        try {
            java.lang.reflect.Member member = ref.getActualField();
            if (member instanceof java.lang.reflect.Field) {
                java.lang.reflect.Field field = (java.lang.reflect.Field) member;
                Deprecated dep = field.getAnnotation(Deprecated.class);
                if (dep != null && dep.forRemoval()) {
                    return true;
                }
            }
        } catch (Exception e) {
            // 無視
        }
        return false;
    }

    /**
     * 型参照が @Deprecated(forRemoval=true) かチェックする。
     */
    private boolean checkType(CtTypeReference<?> ref) {
        if (ref == null) {
            return false;
        }
        // プリミティブやvoidは対象外
        if (ref.isPrimitive() || "void".equals(ref.getQualifiedName())) {
            return false;
        }
        // 1. AST内の宣言からチェック
        try {
            CtType<?> decl = ref.getTypeDeclaration();
            if (decl != null && isDeprecatedForRemoval(decl)) {
                return true;
            }
        } catch (Exception e) {
            // 無視
        }
        // 2. リフレクション（バイナリ参照）からチェック
        try {
            Class<?> clazz = ref.getActualClass();
            if (clazz != null) {
                Deprecated dep = clazz.getAnnotation(Deprecated.class);
                if (dep != null && dep.forRemoval()) {
                    return true;
                }
            }
        } catch (Exception e) {
            // 無視
        }
        return false;
    }

    /**
     * 要素に @Deprecated(forRemoval=true) が付与されているか判定する。
     */
    private boolean isDeprecatedForRemoval(CtElement element) {
        if (element == null) {
            return false;
        }
        // 1. getAnnotation APIによるアノテーション取得
        try {
            Deprecated deprecated = element.getAnnotation(Deprecated.class);
            if (deprecated != null) {
                return deprecated.forRemoval();
            }
        } catch (Exception e) {
            // 無視
        }
        // 2. CtAnnotation 表現としての探索 (フォールバック)
        try {
            for (spoon.reflect.declaration.CtAnnotation<? extends Annotation> ann : element.getAnnotations()) {
                if (ann.getAnnotationType() != null) {
                    String qName = ann.getAnnotationType().getQualifiedName();
                    if ("java.lang.Deprecated".equals(qName)) {
                        Object val = ann.getValue("forRemoval");
                        if (val instanceof spoon.reflect.code.CtLiteral) {
                            Object litVal = ((spoon.reflect.code.CtLiteral<?>) val).getValue();
                            if (litVal instanceof Boolean) {
                                return (Boolean) litVal;
                            }
                        } else if (val != null) {
                            String strVal = val.toString();
                            if ("true".equalsIgnoreCase(strVal)) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 無視
        }
        return false;
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
