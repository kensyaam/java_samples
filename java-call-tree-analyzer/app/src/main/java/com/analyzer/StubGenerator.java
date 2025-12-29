package com.analyzer;

import org.apache.commons.cli.*;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.reflect.code.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Spoonによる解析の精度を高めるために、
 * 解決できない型やメソッドなどのスタブを自動生成するツール
 */
public class StubGenerator {

    private boolean debugMode = false;
    private Factory stubFactory;
    private Set<String> processedTypes = new HashSet<>();

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption("s", "source", true, "解析対象のソースディレクトリ（複数指定可、カンマ区切り）");
        options.addOption("c", "classpath", true, "（オプション）既存のクラスパス");
        options.addOption("o", "output", true, "スタブ出力ディレクトリ（デフォルト: stubs）");
        options.addOption("d", "debug", false, "デバッグモード");
        options.addOption("cl", "complianceLevel", true, "Javaのコンプライアンスレベル（デフォルト: 21）");
        options.addOption("e", "encoding", true, "ソースコードの文字エンコーディング（デフォルト: UTF-8）");
        options.addOption("h", "help", false, "ヘルプを表示");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                formatter.printHelp("StubGenerator", options);
                return;
            }

            String sourceDirs = cmd.getOptionValue("source");
            if (sourceDirs == null || sourceDirs.isEmpty()) {
                System.err.println("エラー: ソースディレクトリ(-s)は必須です。");
                formatter.printHelp("StubGenerator", options);
                return;
            }

            String classpath = cmd.getOptionValue("classpath", "");
            String outputDir = cmd.getOptionValue("output", "stubs");
            boolean debug = cmd.hasOption("debug");
            int complianceLevel = Integer.parseInt(cmd.getOptionValue("complianceLevel", "21"));
            String encoding = cmd.getOptionValue("encoding", "UTF-8");

            StubGenerator generator = new StubGenerator();
            generator.setDebugMode(debug);
            generator.generate(sourceDirs, classpath, outputDir, complianceLevel, encoding);

        } catch (ParseException e) {
            System.err.println("引数解析エラー: " + e.getMessage());
            formatter.printHelp("StubGenerator", options);
        } catch (Exception e) {
            System.err.println("生成エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
    }

    public void generate(String sourceDirs, String classpath, String outputDir, int complianceLevel, String encoding) {
        System.out.println("解析開始...");
        System.out.println("ソース: " + sourceDirs);
        System.out.println("出力先: " + outputDir);

        // 1. 解析用Launcherのセットアップ
        Launcher analyzerLauncher = new Launcher();
        analyzerLauncher.getEnvironment().setNoClasspath(true); // クラスパスが不完全でも動作させる
        analyzerLauncher.getEnvironment().setAutoImports(true);
        analyzerLauncher.getEnvironment().setComplianceLevel(complianceLevel);
        analyzerLauncher.getEnvironment().setEncoding(Charset.forName(encoding));

        for (String dir : sourceDirs.split(",")) {
            analyzerLauncher.addInputResource(dir.trim());
        }

        if (!classpath.isEmpty()) {
            // クラスパスがある場合は設定（解決できるものは解決させる）
            String[] cpPaths = classpath.split(",");
            List<String> expandedClasspath = expandClasspath(cpPaths);
            analyzerLauncher.getEnvironment().setSourceClasspath(expandedClasspath.toArray(new String[0]));
            System.out.println("クラスパス設定: " + expandedClasspath.size() + "個");
        }

        // 2. モデル構築
        CtModel model = analyzerLauncher.buildModel();
        System.out.println("モデル構築完了。未解決型の探索を開始します...");

        // 3. スタブ生成用Launcherのセットアップ
        Launcher stubLauncher = new Launcher();
        stubLauncher.getEnvironment().setNoClasspath(true);
        stubFactory = stubLauncher.getFactory();

        // 4. 未解決の型参照を収集
        // CtTypeReferenceを探す
        List<CtTypeReference<?>> references = model.getElements(new TypeFilter<>(CtTypeReference.class));

        // 処理すべき未解決型を特定
        // プリミティブやjava.*などは除外
        Set<String> unresolvedTypeNames = new HashSet<>();
        List<CtTypeReference<?>> unresolvedRefs = new ArrayList<>();

        for (CtTypeReference<?> ref : references) {
            if (ref.isPrimitive()) continue;
            if (ref.getPackage() == null) continue; // パッケージなし（デフォルトパッケージ）は一旦無視あるいは別途考慮

            String qName = ref.getQualifiedName();
            if (qName == null || qName.indexOf('.') == -1) continue;

            // java., javax. などはスキップ (必要なら調整)
            if (qName.startsWith("java.") || qName.startsWith("javax.") || qName.startsWith("sun.") || qName.startsWith("jdk.")) {
                continue;
            }

            // 宣言が見つからない == 未解決
            if (ref.getDeclaration() == null) {
                // Genericsのパラメータ (T, Eなど) は除外
                if (ref.isGenerics()) continue;

                // 既にソースにあるかチェック（念のため）
                if (model.getAllTypes().stream().anyMatch(t -> t.getQualifiedName().equals(qName))) {
                    continue;
                }

                if (!unresolvedTypeNames.contains(qName)) {
                    unresolvedTypeNames.add(qName);
                    unresolvedRefs.add(ref);
                }
            }
        }

        System.out.println("検出された未解決型: " + unresolvedTypeNames.size() + "個");
        if (debugMode) {
            unresolvedTypeNames.forEach(n -> System.out.println("  - " + n));
        }

        // 5. スタブの生成
        for (String typeName : unresolvedTypeNames) {
            createStubType(typeName, model);
        }

        // 6. 出力
        File outputDirFile = new File(outputDir);
        if (!outputDirFile.exists()) {
            outputDirFile.mkdirs();
        }

        stubLauncher.setSourceOutputDirectory(outputDirFile);
        stubLauncher.prettyprint();

        System.out.println("スタブ生成完了: " + outputDir);
    }

    /**
     * クラスパスを展開（ディレクトリ指定の場合、そのディレクトリと中のすべてのJARファイルを追加）
     */
    private List<String> expandClasspath(String[] cpPaths) {
        List<String> result = new ArrayList<>();

        for (String cpPath : cpPaths) {
            cpPath = cpPath.trim();
            Path path = Paths.get(cpPath);

            try {
                if (Files.isDirectory(path)) {
                    // ディレクトリ自体を追加
                    result.add(cpPath);
                    System.out.println("ディレクトリ追加: " + cpPath);

                    // その中のすべてのJARファイルを追加
                    try (var stream = Files.list(path)) {
                        stream.filter(p -> p.toString().endsWith(".jar"))
                                .map(Path::toString)
                                .forEach(e -> {
                                    result.add(e);
                                    System.out.println("  JAR追加: " + e);
                                });
                    }
                } else if (Files.isRegularFile(path)) {
                    // ファイルの場合、そのまま追加
                    result.add(cpPath);
                    System.out.println("ファイル追加: " + cpPath);
                } else if (!Files.exists(path)) {
                    System.out.println("警告: パスが存在しません: " + cpPath);
                }
            } catch (IOException e) {
                System.err.println("クラスパス処理エラー (" + cpPath + "): " + e.getMessage());
            }
        }

        return result;
    }

    private void createStubType(String qualifiedName, CtModel originalModel) {
        if (processedTypes.contains(qualifiedName)) return;
        processedTypes.add(qualifiedName);

        String packageName = "";
        String simpleName = qualifiedName;
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot != -1) {
            packageName = qualifiedName.substring(0, lastDot);
            simpleName = qualifiedName.substring(lastDot + 1);
        }

        // パッケージ作成
        CtPackage pkg = stubFactory.Package().getOrCreate(packageName);

        // クラス作成 (とりあえずClassとして作成、Interfaceの可能性もあるが)
        // 呼び出し状況からInterfaceかClassか推測できればベストだが、Classにしておけば概ね動く
        CtClass<?> stubClass = stubFactory.Class().create(qualifiedName);
        stubClass.setSimpleName(simpleName);
        stubClass.addModifier(ModifierKind.PUBLIC);

        // メソッドの推論
        inferMethods(stubClass, qualifiedName, originalModel);

        // フィールドの推論
        inferFields(stubClass, qualifiedName, originalModel);

        // 親クラス・インターフェースの推論 (extends/implementsされている場合)
        // これは難しいので、とりあえずObject継承のままにする
    }

    private void inferMethods(CtClass<?> stubClass, String targetTypeQName, CtModel originalModel) {
        // 対象の型に対するメソッド呼び出しを検索
        // targetTypeQNameを持つ変形あるいは式に対するInvocationを探す

        List<CtInvocation<?>> invocations = originalModel.getElements(new TypeFilter<>(CtInvocation.class));

        Set<String> addedSignatures = new HashSet<>();

        for (CtInvocation<?> invocation : invocations) {
            CtExpression<?> target = invocation.getTarget();
            if (target != null && target.getType() != null) {
                if (target.getType().getQualifiedName().equals(targetTypeQName)) {
                    // この型に対する呼び出し
                    String methodName = invocation.getExecutable().getSimpleName();
                    List<CtExpression<?>> args = invocation.getArguments();

                    // シグネチャ生成 (簡易)
                    StringBuilder sigBuilder = new StringBuilder(methodName);
                    List<CtTypeReference<?>> paramTypes = new ArrayList<>();

                    for (CtExpression<?> arg : args) {
                        CtTypeReference<?> argType = arg.getType();
                        if (argType == null) {
                            argType = stubFactory.Type().objectType();
                        }
                        paramTypes.add(argType);
                        sigBuilder.append("_").append(argType.getSimpleName());
                    }

                    String signature = sigBuilder.toString();
                    if (addedSignatures.contains(signature)) continue;
                    addedSignatures.add(signature);

                    // メソッド作成
                    CtMethod<?> method = stubFactory.Core().createMethod();
                    method.setSimpleName(methodName);
                    method.addModifier(ModifierKind.PUBLIC);

                    // 戻り値の推論
                    // Invocationが使われている場所から推論したいが、Spoonの推論が効かない場合nullになる
                    // デフォルトはvoidかObjectにする
                    // 親がAssignmentなら、左辺の型が戻り値
                    CtTypeReference<?> returnType = stubFactory.Type().voidType();

                    // 親要素をチェックして戻り値を推論
                    // (Spoon 11系での親取得)
                    if (invocation.getParent() instanceof CtAssignment) {
                        CtAssignment<?,?> assignment = (CtAssignment<?,?>) invocation.getParent();
                        if (assignment.getAssigned() != null && assignment.getAssigned().getType() != null) {
                             // クローンしないと元のモデルの参照を持ってしまう可能性があるが、TypeReferenceなら大丈夫
                             // ただしFactoryが違うので作成しなおすのが安全
                             returnType = createTypeReferenceInStubFactory(assignment.getAssigned().getType());
                        }
                    } else if (invocation.getParent() instanceof CtLocalVariable) {
                        CtLocalVariable<?> localVar = (CtLocalVariable<?>) invocation.getParent();
                        if (localVar.getType() != null) {
                            returnType = createTypeReferenceInStubFactory(localVar.getType());
                        }
                    } else if (invocation.getParent() instanceof CtReturn) {
                        // メソッドの戻り値として使われている
                         CtMethod<?> parentMethod = invocation.getParent(CtMethod.class);
                         if (parentMethod != null && parentMethod.getType() != null) {
                             returnType = createTypeReferenceInStubFactory(parentMethod.getType());
                         }
                    }

                    method.setType((CtTypeReference) returnType);

                    // パラメータ追加
                    int paramIndex = 0;
                    for (CtTypeReference<?> pType : paramTypes) {
                        CtParameter<?> param = stubFactory.Core().createParameter();
                        param.setSimpleName("arg" + paramIndex++);
                        param.setType((CtTypeReference) createTypeReferenceInStubFactory(pType));
                        method.addParameter(param);
                    }

                    // body (return null or void)
                    CtBlock<?> body = stubFactory.Core().createBlock();
                    if (!returnType.equals(stubFactory.Type().voidType())) {
                        CtReturn ret = stubFactory.Core().createReturn();
                        CtExpression returnExpr;
                        if (returnType.isPrimitive()) {
                            if (returnType.getSimpleName().equals("boolean")) {
                                returnExpr = stubFactory.Code().createLiteral(false);
                            } else {
                                returnExpr = stubFactory.Code().createLiteral(0);
                            }
                        } else {
                            returnExpr = stubFactory.Code().createLiteral(null);
                        }
                        ret.setReturnedExpression(returnExpr);
                        body.addStatement(ret);
                    }
                    method.setBody(body);

                    stubClass.addMethod(method);
                }
            }
        }
    }

    private void inferFields(CtClass<?> stubClass, String targetTypeQName, CtModel originalModel) {
        // フィールドアクセスを検索
        List<CtFieldAccess<?>> accesses = originalModel.getElements(new TypeFilter<>(CtFieldAccess.class));
        Set<String> addedFields = new HashSet<>();

        for (CtFieldAccess<?> access : accesses) {
            CtExpression<?> target = access.getTarget();
            if (target != null && target.getType() != null && target.getType().getQualifiedName().equals(targetTypeQName)) {
                String fieldName = access.getVariable().getSimpleName();
                if (addedFields.contains(fieldName)) continue;
                addedFields.add(fieldName);

                CtField<?> field = stubFactory.Core().createField();
                field.setSimpleName(fieldName);
                field.addModifier(ModifierKind.PUBLIC);

                // 型推論 (使われ方から)
                // FieldReadなら、その親がAssignmentの右辺なら... 難しいのでObjectか、
                // あるいはFieldWriteなら右辺の型
                CtTypeReference<?> fieldType = stubFactory.Type().objectType();

                if (access instanceof CtFieldWrite) {
                     CtFieldWrite<?> write = (CtFieldWrite<?>) access;
                     // write.getParent() が Assignment
                     if (write.getParent() instanceof CtAssignment) {
                         CtAssignment<?,?> assign = (CtAssignment<?,?>) write.getParent();
                         if (assign.getAssignment() != null && assign.getAssignment().getType() != null) {
                             fieldType = createTypeReferenceInStubFactory(assign.getAssignment().getType());
                         }
                     }
                } else {
                    // Readの場合、代入先などから推論
                    if (access.getParent() instanceof CtAssignment) {
                         CtAssignment<?,?> assign = (CtAssignment<?,?>) access.getParent();
                         // 左辺に代入されている場合 (Readが右辺)
                         if (assign.getAssigned() != null && assign.getAssigned().getType() != null) {
                             fieldType = createTypeReferenceInStubFactory(assign.getAssigned().getType());
                         }
                    } else if (access.getParent() instanceof CtLocalVariable) {
                        CtLocalVariable<?> var = (CtLocalVariable<?>) access.getParent();
                        if (var.getType() != null) {
                            fieldType = createTypeReferenceInStubFactory(var.getType());
                        }
                    }
                }

                field.setType((CtTypeReference) fieldType);
                stubClass.addField(field);
            }
        }
    }

    // 別FactoryのTypeReferenceをこちらのFactory用に作り直す（または単純な参照にする）
    private CtTypeReference<?> createTypeReferenceInStubFactory(CtTypeReference<?> originalRef) {
        if (originalRef == null) return stubFactory.Type().objectType();

        // プリミティブ
        if (originalRef.isPrimitive()) {
            return stubFactory.Type().createReference(originalRef.getSimpleName());
        }

        // 配列
        if (originalRef.isArray()) {
            CtArrayTypeReference<?> arrayRef = stubFactory.Core().createArrayTypeReference();
            arrayRef.setComponentType(createTypeReferenceInStubFactory(((CtArrayTypeReference<?>) originalRef).getComponentType()));
            return arrayRef;
        }

        // 通常の型
        return stubFactory.Type().createReference(originalRef.getQualifiedName());
    }
}
