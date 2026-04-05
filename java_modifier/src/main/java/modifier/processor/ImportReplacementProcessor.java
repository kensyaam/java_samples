package modifier.processor;

import modifier.ModifierContext;
import spoon.reflect.CtModel;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;

/**
 * 指定されたパッケージプレフィックスを新しいプレフィックスに置換する。
 * 例: javax を jakarta に置換する等。
 */
public class ImportReplacementProcessor {
    private final ModifierContext context;

    public ImportReplacementProcessor(ModifierContext context) {
        this.context = context;
    }

    public void process(CtModel model) {
        String oldPrefix = context.getReplaceImportOldPrefix();
        String newPrefix = context.getReplaceImportNewPrefix();

        if (oldPrefix == null || newPrefix == null) {
            return;
        }

        System.out.println("ImportReplacementProcessor: " + oldPrefix + " を " + newPrefix + " に置換します。");

        // 型参照内のパッケージ参照をすべて更新
        List<CtTypeReference<?>> typeRefs = model.getElements(new TypeFilter<>(CtTypeReference.class));
        for (CtTypeReference<?> typeRef : typeRefs) {
            CtPackageReference pkgRef = typeRef.getPackage();
            if (pkgRef != null) {
                String pkgName = pkgRef.getQualifiedName();
                if (pkgName.equals(oldPrefix) || pkgName.startsWith(oldPrefix + ".")) {
                    String newPkgName = newPrefix + pkgName.substring(oldPrefix.length());
                    
                    // SpoonではsetSimpleName()に完全修飾パッケージ名を設定することが許容されています
                    pkgRef.setSimpleName(newPkgName);
                }
            }
        }
    }
}
