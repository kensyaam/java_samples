package modifier;

import spoon.Launcher;
import spoon.support.sniper.SniperJavaPrettyPrinter;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spoonのランチャーおよび環境設定を構築するファクトリクラス。
 */
public class EnvironmentFactory {

    public static Launcher createLauncher(ModifierContext context) {
        Launcher launcher = new Launcher();

        // ソースディレクトリの追加
        for (String sourceDir : context.getSourceDirs()) {
            if (Files.exists(Path.of(sourceDir))) {
                launcher.addInputResource(sourceDir);
                System.out.println("ソースディレクトリ追加: " + sourceDir);
            } else {
                System.err.println("警告: ディレクトリが存在しません: " + sourceDir);
            }
        }

        // クラスパスの設定
        if (!context.getClasspathEntries().isEmpty()) {
            String[] cpArray = context.getClasspathEntries().toArray(new String[0]);
            launcher.getEnvironment().setSourceClasspath(cpArray);
            System.out.println("クラスパス追加: " + String.join(", ", cpArray));
        }

        // 環境設定
        launcher.getEnvironment().setComplianceLevel(context.getComplianceLevel());
        launcher.getEnvironment().setEncoding(context.getEncoding());
        // リフレクションに依存せずソース内の型情報を最大限活用するため
        launcher.getEnvironment().setNoClasspath(true); 
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setCommentEnabled(true);

        // SniperJavaPrettyPrinterの設定
        launcher.getEnvironment().setPrettyPrinterCreator(() -> {
            return new SniperJavaPrettyPrinter(launcher.getEnvironment());
        });

        return launcher;
    }
}
