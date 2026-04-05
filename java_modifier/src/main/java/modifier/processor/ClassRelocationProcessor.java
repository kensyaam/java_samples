package modifier.processor;

import modifier.ModifierContext;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 正規表現にマッチするクラスを別のパッケージに移動させます。
 * 参照先（CtTypeReference）のパッケージも更新し、Sniperに検知させます。
 */
public class ClassRelocationProcessor {
    private final ModifierContext context;

    public ClassRelocationProcessor(ModifierContext context) {
        this.context = context;
    }

    public void process(CtModel model) {
        String regex = context.getRelocateClassRegex();
        String newPkgName = context.getRelocateClassNewPackage();

        if (regex == null || newPkgName == null) {
            return;
        }

        Pattern pattern = Pattern.compile(regex);
        List<CtType<?>> typesToMove = new ArrayList<>();

        for (CtType<?> type : model.getAllTypes()) {
            if (pattern.matcher(type.getQualifiedName()).matches()) {
                typesToMove.add(type);
            }
        }

        if (typesToMove.isEmpty()) {
            return;
        }

        CtPackage targetPkg = model.getRootPackage().getFactory().Package().getOrCreate(newPkgName);

        for (CtType<?> type : typesToMove) {
            System.out.println("ClassRelocation: " + type.getQualifiedName() + " を " + newPkgName + " パッケージに移動します。");
            
            // 参照の更新 (Sniperに印をつけるため)
            List<CtTypeReference<?>> refs = model.getElements(new TypeFilter<>(CtTypeReference.class));
            for (CtTypeReference<?> ref : refs) {
                if (type.getQualifiedName().equals(ref.getQualifiedName())) {
                    if (ref.getPackage() != null) {
                        ref.getPackage().setSimpleName(newPkgName);
                    }
                }
            }

            // オブジェクトの移動
            CtPackage oldPkg = type.getPackage();
            if (oldPkg != null) {
                oldPkg.removeType(type);
            }
            targetPkg.addType(type);
        }
    }
}
