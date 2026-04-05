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
        for (CtAnnotation<?> ann : element.getAnnotations()) {
            if (ann.getAnnotationType().getQualifiedName().equals(annFqcn)) {
                return; // すでに存在する場合は追加しない
            }
        }
        CtTypeReference<?> ref = factory.Type().createReference(annFqcn);
        CtAnnotation<?> newAnn = factory.Core().createAnnotation();
        newAnn.setAnnotationType((CtTypeReference) ref);
        element.addAnnotation(newAnn);
    }
}
