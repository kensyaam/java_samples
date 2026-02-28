package analyzer.impl;

import analyzer.Analyzer;
import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

/**
 * アノテーションの調査Analyzer。
 * 特定のアノテーションが付与されているクラス、メソッド、フィールド、引数を特定する。
 */
public class AnnotationAnalyzer implements Analyzer {

    private static final String CATEGORY = "Annotation";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        // 対象アノテーションリストが空の場合はスキップ
        if (context.getTargetAnnotations().isEmpty()) {
            return;
        }

        // 1. 直接付与されているアノテーションの検出
        if (element instanceof CtAnnotation<?>) {
            CtAnnotation<? extends Annotation> annotation = (CtAnnotation<? extends Annotation>) element;
            String annotationName = annotation.getAnnotationType().getQualifiedName();

            if (context.isTargetAnnotation(annotationName)) {
                // アノテーションが付与されている対象を特定
                CtElement annotatedElement = annotation.getAnnotatedElement();
                String targetDescription = getTargetDescription(annotatedElement);

                AnalysisResult result = AnalysisResult.fromElement(
                        annotatedElement != null ? annotatedElement : element,
                        CATEGORY,
                        "@" + annotation.getAnnotationType().getSimpleName() + " on " + targetDescription,
                        context);
                context.addResult(result);
            }
            return;
        }

        // 2. 継承されているアノテーションの検出
        if (element instanceof CtType<?>) {
            checkInheritedAnnotations((CtType<?>) element, context);
        } else if (element instanceof CtMethod<?>) {
            checkInheritedMethodAnnotations((CtMethod<?>) element, context);
        }
    }

    /**
     * クラス・インターフェースの継承アノテーションを再帰的に確認する。
     */
    private void checkInheritedAnnotations(CtType<?> type, AnalysisContext context) {
        if (type == null)
            return;
        checkInheritedTypeAnnotationsRecursive(type, type, context, new HashSet<>());
    }

    private void checkInheritedTypeAnnotationsRecursive(CtType<?> originalType, CtType<?> currentType,
            AnalysisContext context, Set<String> visited) {
        if (currentType == null)
            return;

        String qualifiedName = currentType.getQualifiedName();
        if (!visited.add(qualifiedName)) {
            return; // 循環・重複防止
        }

        if (originalType != currentType) {
            for (CtAnnotation<? extends Annotation> annotation : currentType.getAnnotations()) {
                if (annotation.getAnnotationType() != null) {
                    String annotationName = annotation.getAnnotationType().getQualifiedName();
                    if (context.isTargetAnnotation(annotationName)) {
                        String targetDescription = getTargetDescription(originalType);
                        String inheritedFromName = currentType.getSimpleName();

                        AnalysisResult result = AnalysisResult.fromElement(
                                originalType,
                                CATEGORY,
                                "@" + annotation.getAnnotationType().getSimpleName() + " on " + targetDescription
                                        + " (inherited from " + inheritedFromName + ")",
                                context);
                        context.addResult(result);
                    }
                }
            }
        }

        // 親クラスを再帰的に確認
        if (currentType.getSuperclass() != null) {
            CtType<?> superType = currentType.getSuperclass().getTypeDeclaration();
            if (superType != null) {
                checkInheritedTypeAnnotationsRecursive(originalType, superType, context, visited);
            }
        }

        // 実装インターフェースを再帰的に確認
        for (CtTypeReference<?> intfRef : currentType.getSuperInterfaces()) {
            CtType<?> intfType = intfRef.getTypeDeclaration();
            if (intfType != null) {
                checkInheritedTypeAnnotationsRecursive(originalType, intfType, context, visited);
            }
        }
    }

    /**
     * メソッドの継承アノテーション（オーバーライド元）を再帰的に確認する。
     */
    private void checkInheritedMethodAnnotations(CtMethod<?> method, AnalysisContext context) {
        if (method == null)
            return;
        CtType<?> declaringType = method.getDeclaringType();
        if (declaringType == null)
            return;

        checkInheritedMethodAnnotationsRecursive(method, declaringType, context, new HashSet<>());
    }

    private void checkInheritedMethodAnnotationsRecursive(CtMethod<?> originalMethod, CtType<?> currentType,
            AnalysisContext context, Set<String> visitedTypeNames) {
        if (currentType == null)
            return;

        String qualifiedName = currentType.getQualifiedName();
        if (!visitedTypeNames.add(qualifiedName)) {
            return;
        }

        if (originalMethod.getDeclaringType() != currentType) {
            for (CtMethod<?> inheritedMethod : currentType.getMethodsByName(originalMethod.getSimpleName())) {
                if (originalMethod.getSignature().equals(inheritedMethod.getSignature())) {
                    for (CtAnnotation<? extends Annotation> annotation : inheritedMethod.getAnnotations()) {
                        if (annotation.getAnnotationType() != null) {
                            String annotationName = annotation.getAnnotationType().getQualifiedName();
                            if (context.isTargetAnnotation(annotationName)) {
                                String targetDescription = getTargetDescription(originalMethod);
                                String inheritedFromName = currentType.getSimpleName() + "."
                                        + inheritedMethod.getSimpleName() + "()";

                                AnalysisResult result = AnalysisResult.fromElement(
                                        originalMethod,
                                        CATEGORY,
                                        "@" + annotation.getAnnotationType().getSimpleName() + " on "
                                                + targetDescription + " (inherited from " + inheritedFromName + ")",
                                        context);
                                context.addResult(result);
                            }
                        }
                    }
                }
            }
        }

        // 親クラスを再帰的に確認
        if (currentType.getSuperclass() != null) {
            CtType<?> superType = currentType.getSuperclass().getTypeDeclaration();
            if (superType != null) {
                checkInheritedMethodAnnotationsRecursive(originalMethod, superType, context, visitedTypeNames);
            }
        }

        // 実装インターフェースを再帰的に確認
        for (CtTypeReference<?> intfRef : currentType.getSuperInterfaces()) {
            CtType<?> intfType = intfRef.getTypeDeclaration();
            if (intfType != null) {
                checkInheritedMethodAnnotationsRecursive(originalMethod, intfType, context, visitedTypeNames);
            }
        }
    }

    /**
     * アノテーションが付与されている対象の説明を取得する。
     */
    private String getTargetDescription(CtElement element) {
        if (element == null) {
            return "unknown";
        }

        if (element instanceof CtType<?>) {
            return "class " + ((CtType<?>) element).getQualifiedName();
        }

        if (element instanceof CtMethod<?>) {
            CtMethod<?> method = (CtMethod<?>) element;
            return "method " + method.getSimpleName() + "()";
        }

        if (element instanceof CtField<?>) {
            CtField<?> field = (CtField<?>) element;
            return "field " + field.getSimpleName();
        }

        if (element instanceof CtParameter<?>) {
            CtParameter<?> param = (CtParameter<?>) element;
            return "parameter " + param.getSimpleName();
        }

        return element.getClass().getSimpleName();
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
