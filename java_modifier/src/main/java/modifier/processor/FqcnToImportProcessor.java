package modifier.processor;

import modifier.ModifierContext;
import spoon.reflect.CtModel;
import spoon.reflect.cu.CompilationUnit;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.regex.Pattern;

/**
 * コード内でFQCNで直接参照されている型を、単純名に変更（import文の利用）します。
 */
public class FqcnToImportProcessor {
    private final ModifierContext context;

    public FqcnToImportProcessor(ModifierContext context) {
        this.context = context;
    }

    public void process(CtModel model) {
        String regex = context.getFqcnToImportRegex();
        if (regex == null) {
            return;
        }

        System.out.println("FqcnToImport: 正規表現 '" + regex + "' に一致するFQCN参照をimport文利用に変更します。");
        Pattern pattern = Pattern.compile(regex);

        List<CtTypeReference<?>> refs = model.getElements(new TypeFilter<>(CtTypeReference.class));
        for (CtTypeReference<?> ref : refs) {
            // パッケージの参照を持たないものは無視（プリミティブ型や型パラメータなど）
            if (ref.getPackage() == null) continue;

            // 暗黙的（FQCNで書かれていない＝すでにimport等で短縮）ならスキップ
            if (ref.isImplicit()) continue;

            if (pattern.matcher(ref.getQualifiedName()).matches()) {
                // 暗黙的に設定（Sniperは単純名を出力するようになる）
                ref.setImplicit(true);

                // CompilationUnitに明示的にインポートを追加
                if (ref.getPosition() != null && ref.getPosition().isValidPosition()) {
                    CompilationUnit cu = ref.getPosition().getCompilationUnit();
                    if (cu != null) {
                        try {
                            CtImport ctImport = ref.getFactory().Core().createImport();
                            ctImport.setReference(ref.clone());
                            List<CtImport> imports = cu.getImports();
                            boolean exists = false;
                            for (CtImport i : imports) {
                                if (i.getReference() != null && i.getReference().equals(ctImport.getReference())) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                cu.getImports().add(ctImport);
                            }
                        } catch (Exception e) {
                            // Unsupported operation or clone failure
                        }
                    }
                }
            }
        }
    }
}
