package analyzer.impl;

import analyzer.AnalysisContext;
import analyzer.Analyzer;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.io.File;
import java.util.Set;

/**
 * クラス間の依存関係（継承、実装、フィールド保持）を抽出するAnalyzer。
 */
public class ClassDependencyAnalyzer implements Analyzer {

    private static final String CATEGORY = "Class Dependency";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        // クラス依存関係チェックが無効なら何もしない
        if (!context.isCheckClassDependency()) {
            return;
        }

        // 型定義（クラス、インターフェース、列挙型など）を対象とする
        if (element instanceof CtType) {
            CtType<?> type = (CtType<?>) element;
            String fromClassName = type.getQualifiedName();

            // 匿名クラスなどはスキップ
            if (fromClassName == null || fromClassName.isEmpty() || type.isAnonymous()) {
                return;
            }

            // 元クラスが除外対象ならスキップ
            if (context.isExcludedDependency(fromClassName)) {
                return;
            }

            // 役割の判定と登録
            String role = "CLASS";
            if (type.isInterface()) {
                role = "INTERFACE";
            } else if (type.isAbstract()) {
                role = "ABSTRACT";
            }
            context.addClassRole(fromClassName, role);

            File file = type.getPosition().isValidPosition() ? type.getPosition().getFile() : null;
            int lineNumber = type.getPosition().isValidPosition() ? type.getPosition().getLine() : -1;

            // 1. 継承 (extends)
            CtTypeReference<?> superclass = type.getSuperclass();
            if (superclass != null) {
                String toClassName = superclass.getQualifiedName();
                // 依存先のRoleを登録（Spoonのモデルから取得可能な範囲で）
                registerTargetRole(context, superclass);
                addDependencyIfValid(context, file, lineNumber, fromClassName, toClassName, "EXTENDS");
            }

            // 2. 実装 (implements)
            Set<CtTypeReference<?>> superInterfaces = type.getSuperInterfaces();
            if (superInterfaces != null) {
                for (CtTypeReference<?> iface : superInterfaces) {
                    registerTargetRole(context, iface);
                    addDependencyIfValid(context, file, lineNumber, fromClassName, iface.getQualifiedName(), "IMPLEMENTS");
                }
            }

            // 3. フィールド (has-a)
            for (CtField<?> field : type.getFields()) {
                CtTypeReference<?> fieldType = field.getType();
                if (fieldType != null) {
                    // 配列やジェネリクスの場合はベースのクラス名を取得
                    CtTypeReference<?> erasedType = fieldType.getTypeErasure();
                    String toClassName = erasedType.getQualifiedName();
                    registerTargetRole(context, erasedType);
                    
                    int fieldLine = field.getPosition().isValidPosition() ? field.getPosition().getLine() : lineNumber;
                    addDependencyIfValid(context, file, fieldLine, fromClassName, toClassName, "FIELD");
                }
            }
        }
    }

    /**
     * 依存先クラスの役割を登録する。
     */
    private void registerTargetRole(AnalysisContext context, CtTypeReference<?> typeRef) {
        if (typeRef == null) return;
        CtType<?> type = typeRef.getTypeDeclaration();
        if (type != null) {
            String role = "CLASS";
            if (type.isInterface()) {
                role = "INTERFACE";
            } else if (type.isAbstract()) {
                role = "ABSTRACT";
            }
            context.addClassRole(type.getQualifiedName(), role);
        }
    }

    private void addDependencyIfValid(AnalysisContext context, File file, int lineNumber, String fromClass, String toClass, String type) {
        // プリミティブ型、voidなどはスキップ
        if (toClass == null || toClass.isEmpty() || isPrimitiveOrVoid(toClass)) {
            return;
        }

        // 標準ライブラリのクラスは常に除外
        if (isStandardLibrary(toClass)) {
            return;
        }

        // ユーザー指定の除外パターンにマッチする場合もスキップ
        if (context.isExcludedDependency(toClass)) {
            return;
        }

        // グラフ用にエッジを追加
        context.addClassDependency(fromClass, toClass, type);

        // txtやcsv出力用にも結果として登録
        String elementStr = "-> " + toClass + " (" + type + ")";
        String snippet = "class " + fromClass;
        ClassDependencyResult result = new ClassDependencyResult(
                getCategory(),
                context.getRelativePath(file),
                lineNumber,
                fromClass,
                elementStr,
                snippet,
                fromClass,
                toClass,
                type);
        context.addResult(result);
    }

    /**
     * プリミティブ型またはvoidかどうかを判定する。
     */
    private boolean isPrimitiveOrVoid(String className) {
        return className.equals("byte") || className.equals("short") || className.equals("int") ||
               className.equals("long") || className.equals("float") || className.equals("double") ||
               className.equals("char") || className.equals("boolean") || className.equals("void") || className.equals("?");
    }

    /**
     * Java標準ライブラリのクラスかどうかを判定する。
     * java.*, javax.*, jdk.* パッケージに属するクラスを標準クラスとみなす。
     */
    private boolean isStandardLibrary(String className) {
        return className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("jdk.");
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
