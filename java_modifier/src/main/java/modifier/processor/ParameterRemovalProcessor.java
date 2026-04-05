package modifier.processor;

import modifier.ModifierContext;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.code.CtJavaDoc;
import spoon.reflect.code.CtJavaDocTag;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * 指定されたFQCNの引数をメソッドやコンストラクタから削除し、
 * 付随するJavadocの @param も削除します。
 */
public class ParameterRemovalProcessor {
    private final ModifierContext context;

    public ParameterRemovalProcessor(ModifierContext context) {
        this.context = context;
    }

    public void process(CtModel model) {
        String removeFqcn = context.getRemoveParamFqcn();
        if (removeFqcn == null) {
            return;
        }

        System.out.println("ParameterRemoval: 型 '" + removeFqcn + "' の引数を検索して削除します。");

        List<CtParameter<?>> params = model.getElements(new TypeFilter<>(CtParameter.class));
        List<CtParameter<?>> toRemove = new ArrayList<>();

        for (CtParameter<?> param : params) {
            if (param.getType() != null && removeFqcn.equals(param.getType().getQualifiedName())) {
                toRemove.add(param);
            }
        }

        for (CtParameter<?> param : toRemove) {
            CtExecutable<?> executable = param.getParent(CtExecutable.class);
            if (executable != null) {
                // Javadocの対応する @param タグを削除
                if (executable.getComments() != null) {
                    for (spoon.reflect.code.CtComment comment : executable.getComments()) {
                        if (comment instanceof CtJavaDoc) {
                            CtJavaDoc javaDoc = (CtJavaDoc) comment;
                            List<CtJavaDocTag> tagsToRemove = new ArrayList<>();
                            for (CtJavaDocTag tag : javaDoc.getTags()) {
                                if (tag.getType() == CtJavaDocTag.TagType.PARAM && 
                                    param.getSimpleName().equals(tag.getParam())) {
                                    tagsToRemove.add(tag);
                                }
                            }
                            for (CtJavaDocTag tag : tagsToRemove) {
                                javaDoc.removeTag(tag);
                            }
                        }
                    }
                }
                
                // コール先のシグネチャ変更等、参照箇所の修正機能は今回は対象外として、
                // 定義元の引数のみ削除する。
                executable.removeParameter(param);
            }
        }
    }
}
