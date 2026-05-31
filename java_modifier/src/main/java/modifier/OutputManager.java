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

            // 生成コードの構文チェックおよびアノテーション配置チェック
            if (!isValidSyntax(printedCode) || hasBadAnnotationPlacement(printedCode)) {
                return false;
            }

            // 出力
            writeCode(printedCode, targetFile);
            return true;
        } catch (Exception e) {
            // 例外発生時は破損とみなす
            System.err.println("Sniper print failed with exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean hasBadAnnotationPlacement(String code) {
        // アノテーションの直後に Javadocコメント (/**) が来ている不正な配置を検出
        // 例: @java.lang.Deprecated/**
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("@[a-zA-Z0-9_.]+(?:\\([^)]*\\))?\\s*/\\*\\*");
        return p.matcher(code).find();
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

            // 完全修飾名（FQCN）を展開された箇所を、インポート情報に基づき簡易名へ事後置換する
            formattedCode = simplifyFullyQualifiedNames(formattedCode);

            // 出力
            writeCode(formattedCode, targetFile);
        } catch (Exception e) {
            System.err.println("ERROR: リカバリ出力にも失敗しました: " + type.getQualifiedName());
            e.printStackTrace();
        }
    }

    private String simplifyFullyQualifiedNames(String code) {
        // 1. インポート文を解析して、インポートされているFQCNのリストを取得
        java.util.List<String> imports = new java.util.ArrayList<>();
        java.util.regex.Pattern importPattern = java.util.regex.Pattern.compile("^\\s*import\\s+([a-zA-Z0-9_.]+)\\s*;");
        String[] lines = code.split("\\r?\\n");
        for (String line : lines) {
            java.util.regex.Matcher m = importPattern.matcher(line);
            if (m.find()) {
                imports.add(m.group(1));
            }
        }

        // java.lang の基本クラスも暗黙的インポートとして追加
        imports.add("java.lang.Deprecated");
        imports.add("java.lang.String");
        imports.add("java.lang.Object");
        imports.add("java.lang.System");
        imports.add("java.lang.SuppressWarnings");
        imports.add("java.lang.Override");

        // 2. 行ごとに置換処理 (import行やpackage行はスキップ)
        java.util.List<String> resultLines = new java.util.ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                resultLines.add(line);
                continue;
            }

            String processedLine = line;
            for (String fqcn : imports) {
                String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                String escapedFqcn = java.util.regex.Pattern.quote(fqcn);
                processedLine = processedLine.replaceAll("\\b" + escapedFqcn + "\\b", simpleName);
            }
            resultLines.add(processedLine);
        }

        return String.join(context.getNewline(), resultLines);
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
