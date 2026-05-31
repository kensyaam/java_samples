package modifier.processor;

import modifier.ModifierContext;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * アノテーションの追加、置換、削除を行うプロセッサ。
 */
public class AnnotationProcessor {
    private final ModifierContext context;
    private final Factory factory;

    public AnnotationProcessor(ModifierContext context, Factory factory) {
        this.context = context;
        this.factory = factory;
    }

    public void process(CtModel model) {
        addAnnotationField(model);
        addAnnotationType(model);
        addAnnotationTypeFile(model);
        replaceAnnotation(model);
        removeAnnotation(model);
        addAnnotationByAnnotation(model);
    }

    private void addAnnotationField(CtModel model) {
        String regex = context.getAddAnnotationFieldRegex();
        String annFqcn = context.getAddAnnotationFieldFqcn();
        if (regex == null || annFqcn == null) return;

        System.out.println("Annotation: 型が '" + regex + "' に一致するフィールドに @" + annFqcn + " を追加します。");
        Pattern pattern = Pattern.compile(regex);

        for (CtField<?> field : model.getElements(new TypeFilter<>(CtField.class))) {
            if (field.getType() != null && pattern.matcher(field.getType().getQualifiedName()).matches()) {
                addAnnotationSafely(field, annFqcn);
            }
        }
    }

    private void addAnnotationType(CtModel model) {
        String regex = context.getAddAnnotationTypeRegex();
        String annFqcn = context.getAddAnnotationTypeFqcn();
        if (regex == null || annFqcn == null) return;

        System.out.println("Annotation: 名前が '" + regex + "' に一致するクラス・メソッドに @" + annFqcn + " を追加します。");
        Pattern pattern = Pattern.compile(regex);

        for (CtType<?> type : model.getAllTypes()) {
            if (pattern.matcher(type.getSimpleName()).matches() || pattern.matcher(type.getQualifiedName()).matches()) {
                addAnnotationSafely(type, annFqcn);
            }
            for (CtMethod<?> method : type.getMethods()) {
                if (pattern.matcher(method.getSimpleName()).matches()) {
                    addAnnotationSafely(method, annFqcn);
                }
            }
        }
    }

    private void addAnnotationTypeFile(CtModel model) {
        List<ModifierContext.AnnotationRule> rules = context.getAddAnnotationTypeFileRules();
        if (rules.isEmpty()) return;

        for (ModifierContext.AnnotationRule rule : rules) {
            String regex = rule.getRegex();
            String annFqcn = rule.getAnnFqcn();
            System.out.println("Annotation (File): 名前が '" + regex + "' に一致するクラス・メソッドに @" + annFqcn + " を追加します。");
            Pattern pattern = Pattern.compile(regex);

            for (CtType<?> type : model.getAllTypes()) {
                if (pattern.matcher(type.getSimpleName()).matches() || pattern.matcher(type.getQualifiedName()).matches()) {
                    addAnnotationSafely(type, annFqcn);
                }
                for (CtMethod<?> method : type.getMethods()) {
                    if (pattern.matcher(method.getSimpleName()).matches()) {
                        addAnnotationSafely(method, annFqcn);
                    }
                }
            }
        }
    }

    private void replaceAnnotation(CtModel model) {
        String oldRegex = context.getReplaceAnnotationOldRegex();
        String newFqcn = context.getReplaceAnnotationNewFqcn();
        if (oldRegex == null || newFqcn == null) return;

        System.out.println("Annotation: 正規表現 '" + oldRegex + "' に一致するアノテーションを @" + newFqcn + " に置換します。");
        Pattern pattern = Pattern.compile(oldRegex);

        List<CtAnnotation<?>> annotations = model.getElements(new TypeFilter<>(CtAnnotation.class));
        for (CtAnnotation<?> annotation : annotations) {
            String fqcn = annotation.getAnnotationType().getQualifiedName();
            if (pattern.matcher(fqcn).matches()) {
                CtTypeReference<?> newTypeRef = factory.Type().createReference(newFqcn);
                annotation.setAnnotationType((CtTypeReference) newTypeRef);
            }
        }
    }

    private void removeAnnotation(CtModel model) {
        String regex = context.getRemoveAnnotationRegex();
        if (regex == null) return;

        System.out.println("Annotation: 正規表現 '" + regex + "' に一致するアノテーションを削除します。");
        Pattern pattern = Pattern.compile(regex);

        List<CtAnnotation<?>> annotations = model.getElements(new TypeFilter<>(CtAnnotation.class));
        List<CtAnnotation<?>> toRemove = new ArrayList<>();

        for (CtAnnotation<?> annotation : annotations) {
            String fqcn = annotation.getAnnotationType().getQualifiedName();
            if (pattern.matcher(fqcn).matches()) {
                toRemove.add(annotation);
            }
        }

        for (CtAnnotation<?> annotation : toRemove) {
            CtElement parent = annotation.getParent();
            if (parent != null) {
                parent.removeAnnotation(annotation);
            }
        }
    }

    private void addAnnotationByAnnotation(CtModel model) {
        String targetRegex = context.getAddAnnotationByAnnotationTargetRegex();
        String annFqcn = context.getAddAnnotationByAnnotationFqcn();
        if (targetRegex == null || annFqcn == null) return;

        System.out.println("Annotation: アノテーション正規表現 '" + targetRegex + "' が付与された要素に @" + annFqcn + " を追加します。");
        Pattern pattern = Pattern.compile(targetRegex);

        List<CtElement> elementsToCheck = new ArrayList<>();
        elementsToCheck.addAll(model.getElements(new TypeFilter<>(CtType.class)));
        elementsToCheck.addAll(model.getElements(new TypeFilter<>(CtMethod.class)));
        elementsToCheck.addAll(model.getElements(new TypeFilter<>(CtField.class)));
        elementsToCheck.addAll(model.getElements(new TypeFilter<>(CtParameter.class)));

        for (CtElement element : elementsToCheck) {
            boolean hasTarget = false;
            for (CtAnnotation<?> ann : element.getAnnotations()) {
                if (pattern.matcher(ann.getAnnotationType().getQualifiedName()).matches()) {
                    hasTarget = true;
                    break;
                }
            }
            if (hasTarget) {
                addAnnotationSafely(element, annFqcn);
            }
        }
    }

    /**
     * すでに同名のアノテーションが無ければ追加する。
     */
    private void addAnnotationSafely(CtElement element, String annFqcn) {
        String baseFqcn = annFqcn;
        String paramsStr = "";
        if (annFqcn.contains("(") && annFqcn.endsWith(")")) {
            int firstParen = annFqcn.indexOf('(');
            baseFqcn = annFqcn.substring(0, firstParen);
            paramsStr = annFqcn.substring(firstParen + 1, annFqcn.length() - 1);
        }

        String simpleName = baseFqcn.substring(baseFqcn.lastIndexOf('.') + 1);
        for (CtAnnotation<?> ann : element.getAnnotations()) {
            String existingFqcn = ann.getAnnotationType().getQualifiedName();
            String existingSimple = ann.getAnnotationType().getSimpleName();
            if (existingFqcn.equals(baseFqcn) || existingSimple.equals(simpleName)) {
                return; // すでに存在する場合は追加しない
            }
        }

        // インポート用に完全修飾名の参照を作成
        CtTypeReference<?> importRef = factory.Type().createReference(baseFqcn);

        // CompilationUnitにインポートを追加 (java.langの直下以外の場合)
        boolean isJavaLangDirect = baseFqcn.startsWith("java.lang.") && baseFqcn.substring("java.lang.".length()).indexOf('.') == -1;
        if (baseFqcn.contains(".") && !isJavaLangDirect) {
            spoon.reflect.declaration.CtType<?> parentType = element.getParent(spoon.reflect.declaration.CtType.class);
            if (parentType == null && element instanceof spoon.reflect.declaration.CtType<?>) {
                parentType = (spoon.reflect.declaration.CtType<?>) element;
            }
            if (parentType != null) {
                spoon.reflect.declaration.CtType<?> topLevelType = parentType.getTopLevelType();
                if (topLevelType != null && topLevelType.getPosition() != null) {
                    spoon.reflect.cu.CompilationUnit cu = topLevelType.getPosition().getCompilationUnit();
                    if (cu != null) {
                        spoon.reflect.declaration.CtImport ctImport = factory.Type().createImport(importRef);
                        // 重複チェック
                        boolean alreadyImported = false;
                        for (spoon.reflect.declaration.CtImport imp : cu.getImports()) {
                            if (imp.toString().contains(baseFqcn)) {
                                alreadyImported = true;
                                break;
                            }
                        }
                        if (!alreadyImported) {
                            cu.getImports().add(ctImport);
                        }
                    }
                }
            }
        }

        // アノテーション用には、パッケージ修飾を避けるため簡易名だけの参照を使用する
        CtTypeReference<?> annotationRef;
        if (baseFqcn.contains(".")) {
            annotationRef = factory.Core().createTypeReference();
            annotationRef.setSimpleName(simpleName);
        } else {
            annotationRef = factory.Type().createReference(baseFqcn);
        }

        CtAnnotation<?> newAnn = factory.Core().createAnnotation();
        newAnn.setAnnotationType((CtTypeReference) annotationRef);

        // パラメータの解析と追加
        if (!paramsStr.isEmpty()) {
            String[] pairs = paramsStr.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    spoon.reflect.code.CtExpression<?> expr = factory.Code().createCodeSnippetExpression(kv[1].trim());
                    newAnn.addValue(kv[0].trim(), expr);
                } else if (kv.length == 1) {
                    spoon.reflect.code.CtExpression<?> expr = factory.Code().createCodeSnippetExpression(kv[0].trim());
                    newAnn.addValue("value", expr);
                }
            }
        }

        element.addAnnotation(newAnn);
    }
}
