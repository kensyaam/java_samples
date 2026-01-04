package com.analyzer;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;

public class PlantUmlDriver {
    public static void main(String[] args) {
        // Spoonランチャーの設定
        Launcher launcher = new Launcher();
        // テストクラスのあるディレクトリを指定
        launcher.addInputResource("java-call-tree-analyzer/app/src/main/java/com/analyzer/test");
        // ソースコードのビルド（モデル構築）
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setNoClasspath(true); // クラスパス設定なしで動作させる（簡易的）

        CtModel model = launcher.buildModel();

        // TargetClassのmainProcessメソッドを探す
        CtMethod<?> targetMethod = null;
        for (CtType<?> type : model.getAllTypes()) {
            if (type.getSimpleName().equals("TargetClass")) {
                List<CtMethod<?>> methods = type.getMethodsByName("mainProcess");
                if (!methods.isEmpty()) {
                    targetMethod = methods.get(0);
                    break;
                }
            }
        }

        if (targetMethod != null) {
            System.out.println("Generating PlantUML for: " + targetMethod.getSignature());
            PlantUmlGenerator generator = new PlantUmlGenerator();
            String plantUml = generator.generate(targetMethod);

            System.out.println("--- Generated PlantUML ---");
            System.out.println(plantUml);
            System.out.println("--------------------------");
        } else {
            System.err.println("Target method 'mainProcess' not found in TargetClass.");
        }
    }
}
