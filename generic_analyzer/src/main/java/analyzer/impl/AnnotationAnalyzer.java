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

import java.lang.annotation.Annotation;

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

        // CtAnnotationのみを対象とする
        if (!(element instanceof CtAnnotation<?>)) {
            return;
        }

        CtAnnotation<? extends Annotation> annotation = (CtAnnotation<? extends Annotation>) element;

        // アノテーション名を取得
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
