package analyzer.impl;

import analyzer.Analyzer;
import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtArrayTypeReference;

public class FullyQualifiedNameUsageAnalyzer implements Analyzer {

    private static final String CATEGORY_FQCN = "Fully Qualified Name";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        // 設定が無効な場合はスキップ
        if (!context.isCheckFullyQualifiedName()) {
            return;
        }

        CtTypeReference<?> typeRef = null;
        CtElement reportElement = element;

        if (element instanceof CtTypeAccess<?>) {
            typeRef = ((CtTypeAccess<?>) element).getAccessedType();
            // CtTypeAccess自体が暗黙的ならスキップ
            if (element.isImplicit()) return;
        } else if (element instanceof CtTypeReference<?>) {
            typeRef = (CtTypeReference<?>) element;
        }

        if (typeRef != null) {
            // 暗黙的な要素（ソースコードに現れない等）はスキップ
            if (typeRef.isImplicit() && !(element instanceof CtTypeAccess)) {
                return;
            }

            // 元コードの位置情報チェック（Spoonが自動生成した要素など）
            if (reportElement.getPosition() == null || !reportElement.getPosition().isValidPosition()) {
                return;
            }

            // import文内の型参照はスキップ
            if (typeRef.isParentInitialized() && typeRef.getParent(CtImport.class) != null) {
                return;
            }

            // プリミティブ型や無名クラス等はスキップ
            if (typeRef.isPrimitive() || typeRef.isAnonymous()) {
                return;
            }

            // 配列型の場合はコンポーネント型（要素の型）をチェックするので、ここでの重複チェックはスキップ
            if (typeRef instanceof CtArrayTypeReference) {
                return;
            }

            // ジェネリクスの型引数などは、別途個別のCtTypeReferenceとしてVisitorから回ってくるため、
            // 本要素のパッケージ指定や外部クラス指定が明示的かどうかのみ判定する

            boolean isExplicitlyQualified = false;

            // パッケージが明示的に記載されているか
            if (typeRef.getPackage() != null && !typeRef.getPackage().isImplicit()) {
                // 名前なしパッケージ（デフォルトパッケージ）の場合は明示的とはみなさない
                if (!typeRef.getPackage().getSimpleName().isEmpty() &&
                    !typeRef.getPackage().getSimpleName().equals(CtPackage.TOP_LEVEL_PACKAGE_NAME)) {
                    isExplicitlyQualified = true;
                }
            }

            // 外部クラス（エンクロージング型）が明示的に記載されているか（例: Outer.Inner）
            if (typeRef.getDeclaringType() != null && !typeRef.getDeclaringType().isImplicit()) {
                isExplicitlyQualified = true;
            }

            if (isExplicitlyQualified) {
                String fqcn = typeRef.getQualifiedName();
                AnalysisResult result = AnalysisResult.fromElement(
                        reportElement,
                        CATEGORY_FQCN,
                        fqcn,
                        context);
                context.addResult(result);
            }
        }
    }

    @Override
    public String getCategory() {
        return CATEGORY_FQCN;
    }
}
