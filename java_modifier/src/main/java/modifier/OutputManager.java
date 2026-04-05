package modifier;

import com.google.googlejavaformat.java.Formatter;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.reflect.CtModel;
import spoon.reflect.cu.CompilationUnit;
import spoon.reflect.declaration.CtType;
import spoon.support.sniper.SniperJavaPrettyPrinter;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * 変換後のASTを出力し、フォーマット崩れの検知とリカバリを行うクラス。
 */
public class OutputManager {

    private final Launcher launcher;
    private final ModifierContext context;

    public OutputManager(Launcher launcher, ModifierContext context) {
        this.launcher = launcher;
        this.context = context;
    }

    public void processAndOutput() {
        CtModel model = launcher.getModel();
        Environment env = launcher.getEnvironment();

        File outputDir = new File(context.getDestinationDir());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // コメント喪失検知 (ビルド完了時点で問題があるかチェック)
        checkLostComments(model);

        System.out.println("コードの出力を開始します。出力先: " + outputDir.getAbsolutePath());

        // 各トップレベルクラスごとに出力処理
        for (CtType<?> type : model.getAllTypes()) {
            if (!type.isTopLevel()) {
                continue;
            }

            CompilationUnit cu = type.getPosition().getCompilationUnit();
            if (cu == null) {
                CompilationUnit newCu = launcher.getFactory().Core().createCompilationUnit();
                newCu.addDeclaredType(type);
                cu = newCu;
            }

            File targetFile = new File(outputDir, type.getQualifiedName().replace('.', File.separatorChar) + ".java");
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            boolean success = trySniperPrint(cu, type, targetFile);

            if (!success) {
                // リカバリフローへ
                System.err.println(
                        "WARN: [" + type.getQualifiedName() + "] の出力中にフォーマットの破損を検知しました。フォーマットのリカバリを適用して出力します。");
                recoverAndPrint(cu, type, targetFile);
            }
        }
    }

    private void checkLostComments(CtModel model) {
        for (CtType<?> type : model.getAllTypes()) {
            if (!type.isTopLevel())
                continue;

            CompilationUnit cu = type.getPosition().getCompilationUnit();
            if (cu != null && cu.getFile() != null) {
                try {
                    String originalSource = java.nio.file.Files.readString(cu.getFile().toPath(),
                            context.getEncoding());
                    // 簡易判定: 元ソースに '//' や '/*' が含まれるか
                    boolean hasOriginalComment = originalSource.contains("//") || originalSource.contains("/*");
                    boolean hasParsedComment = !cu.getComments().isEmpty() || !type.getComments().isEmpty()
                            || hasDescendantComments(type);

                    if (hasOriginalComment && !hasParsedComment) {
                        System.err.println("WARN: [" + type.getQualifiedName()
                                + "] 読み込み時にエンコード等の問題によりコメントが失われた可能性があります。 (-e オプション等を見直してください)");
                    }
                } catch (Exception e) {
                    // 読み込みエラーは無視
                }
            }
        }
    }

    private boolean hasDescendantComments(spoon.reflect.declaration.CtElement element) {
        if (!element.getComments().isEmpty())
            return true;
        for (spoon.reflect.declaration.CtElement child : element.getDirectChildren()) {
            if (hasDescendantComments(child))
                return true;
        }
        return false;
    }

    private boolean trySniperPrint(CompilationUnit cu, CtType<?> type, File targetFile) {
        try {
            // SniperJavaPrettyPrinter を用いて文字列生成
            SniperJavaPrettyPrinter sniperPrinter = new SniperJavaPrettyPrinter(launcher.getEnvironment());
            sniperPrinter.calculate(cu, java.util.Collections.singletonList(type));
            String printedCode = sniperPrinter.getResult();

            // 生成コードの構文チェック
            if (!isValidSyntax(printedCode)) {
                return false;
            }

            // 出力
            writeCode(printedCode, targetFile);
            return true;
        } catch (Exception e) {
            // 例外発生時は破損とみなす
            return false;
        }
    }

    private void recoverAndPrint(CompilationUnit cu, CtType<?> type, File targetFile) {
        try {
            // DefaultJavaPrettyPrinter を用いてASTから安全にコード生成
            DefaultJavaPrettyPrinter defaultPrinter = new DefaultJavaPrettyPrinter(launcher.getEnvironment());
            defaultPrinter.calculate(cu, java.util.Collections.singletonList(type));
            String printedCode = defaultPrinter.getResult();

            // Google Java Format で整形
            Formatter formatter = new Formatter();
            String formattedCode;
            try {
                formattedCode = formatter.formatSource(printedCode);
            } catch (Throwable t) {
                // Formatting失敗時(またはJava16+等のモジュール制約によるエラー時)はそのまま出力
                formattedCode = printedCode;
            }

            // 出力
            writeCode(formattedCode, targetFile);
        } catch (Exception e) {
            System.err.println("ERROR: リカバリ出力にも失敗しました: " + type.getQualifiedName());
            e.printStackTrace();
        }
    }

    private boolean isValidSyntax(String sourceCode) {
        try {
            Launcher spoonParser = new Launcher();
            spoonParser.getEnvironment().setNoClasspath(true);
            spoonParser.getEnvironment().setComplianceLevel(context.getComplianceLevel());
            spoonParser.addInputResource(new spoon.support.compiler.VirtualFile(sourceCode, "Test.java"));
            spoonParser.buildModel();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeCode(String code, File targetFile) throws Exception {
        // 改行コードの統一
        String normalizedCode = code.replaceAll("\\r\\n|\\r|\\n", context.getNewline());

        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(targetFile), context.getEncoding()))) {
            writer.print(normalizedCode);
        }
    }
}
