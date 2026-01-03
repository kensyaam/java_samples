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
        stubLauncher.getEnvironment().setComplianceLevel(complianceLevel);
        stubLauncher.getEnvironment().setEncoding(Charset.forName(encoding));
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
        // ネストされたクラスのためにソート（親クラスから先に生成するため）
        List<String> sortedTypes = new ArrayList<>(unresolvedTypeNames);
        Collections.sort(sortedTypes);

        for (String typeName : sortedTypes) {
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

        // ネストクラス対応
        CtType<?> declaringType = null;
        String simpleName = qualifiedName;
        String packageName = "";

        // $が含まれる場合 (内部クラスのバイナリ名慣習)
        if (qualifiedName.contains("$")) {
            String parentName = qualifiedName.substring(0, qualifiedName.lastIndexOf('$'));
            simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('$') + 1);
            declaringType = ensureTypeExists(parentName, originalModel);
        }
        // ドット区切りだが、プレフィックスが既に型として存在する場合 (ソースコード上の慣習)
        else {
            // 親となりうる部分を探す
            String parentCandidate = null;
            int lastDot = qualifiedName.lastIndexOf('.');
            while (lastDot != -1) {
                String candidate = qualifiedName.substring(0, lastDot);
                if (processedTypes.contains(candidate)) {
                    parentCandidate = candidate;
                    break;
                }
                lastDot = candidate.lastIndexOf('.');
            }

            if (parentCandidate != null) {
                // 親が見つかった -> ネストクラス
                declaringType = stubFactory.Type().get(parentCandidate);
                simpleName = qualifiedName.substring(parentCandidate.length() + 1); // .Inner
            } else {
                // 通常のトップレベルクラス
                lastDot = qualifiedName.lastIndexOf('.');
                if (lastDot != -1) {
                    packageName = qualifiedName.substring(0, lastDot);
                    simpleName = qualifiedName.substring(lastDot + 1);
                }
                stubFactory.Package().getOrCreate(packageName);
            }
        }

        // 型の種類を推論 (Interface, Annotation, Enum, Class)
        CtType<?> stubType = createTypeBasedOnUsage(qualifiedName, simpleName, originalModel);
        stubType.addModifier(ModifierKind.PUBLIC);

        // ネストクラスとして追加 または パッケージに追加
        if (declaringType != null) {
            stubType.addModifier(ModifierKind.STATIC); // デフォルトでstatic inner classにする
            declaringType.addNestedType(stubType);
        }

        // メンバの推論
        inferMethods(stubType, qualifiedName, originalModel);
        inferFields(stubType, qualifiedName, originalModel);
    }

    private CtType<?> ensureTypeExists(String typeName, CtModel model) {
        CtType<?> type = stubFactory.Type().get(typeName);
        if (type == null) {
            createStubType(typeName, model);
            type = stubFactory.Type().get(typeName);
        }
        return type;
    }

    private CtType<?> createTypeBasedOnUsage(String qualifiedName, String simpleName, CtModel model) {
        // アノテーションとして使われているか確認
        boolean isAnnotation = model.getElements(new TypeFilter<>(CtAnnotation.class)).stream()
                .anyMatch(a -> a.getAnnotationType().getQualifiedName().equals(qualifiedName));

        if (isAnnotation) {
            CtAnnotationType<?> annType = stubFactory.Core().createAnnotationType();
            annType.setSimpleName(simpleName);
            // 通常のクラスとして登録されないようにパッケージに追加する必要があるが、
            // SpoonのFactory.createAnnotationTypeなどは自動でやってくれない場合がある
            // ここでは簡易的に処理
            if (!qualifiedName.contains("$")) {
                String packageName = "";
                if (qualifiedName.contains(".")) {
                    packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));
                }
                stubFactory.Package().getOrCreate(packageName).addType(annType);
            }
            return annType;
        }

        // インターフェースとして使われているか確認 (implementsされているか)
        boolean isInterface = model.getElements(new TypeFilter<>(CtType.class)).stream()
                .flatMap(t -> t.getSuperInterfaces().stream())
                .anyMatch(ref -> ref.getQualifiedName().equals(qualifiedName));

        if (isInterface) {
             CtInterface<?> iface = stubFactory.Core().createInterface();
             iface.setSimpleName(simpleName);
             if (!qualifiedName.contains("$")) {
                 String packageName = "";
                 if (qualifiedName.contains(".")) {
                     packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));
                 }
                 stubFactory.Package().getOrCreate(packageName).addType(iface);
             }
             return iface;
        }

        // Enumとして使われているか (簡易判定: switchのcaseに使われている、あるいはEnumSetなどで使われている...は難しいので、
        // 名前や特定のメソッド呼び出し(values, valueOf)で判定する手もあるが、誤検知のリスクあり)
        // ここでは安全のためClassとして生成するが、もしEnum定数のようなフィールドアクセスがあればEnumにするなどのロジックも追加可能
        // 現状はClassでフォールバック

        CtClass<?> clazz = stubFactory.Core().createClass();
        clazz.setSimpleName(simpleName);
        if (!qualifiedName.contains("$")) {
             String packageName = "";
             if (qualifiedName.contains(".")) {
                 packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));
             }
             stubFactory.Package().getOrCreate(packageName).addType(clazz);
        }
        return clazz;
    }

    private void inferMethods(CtType<?> stubType, String targetTypeQName, CtModel originalModel) {
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
                    CtMethod<?> method;
                    CtTypeReference<?> returnType = stubFactory.Type().voidType();

                    // 親要素をチェックして戻り値を推論
                    // (Spoon 11系での親取得)
                    if (!invocation.getTypeCasts().isEmpty()) {
                        // キャストされている場合 ((String) unknown.method())
                        returnType = createTypeReferenceInStubFactory(invocation.getTypeCasts().get(0));
                    } else if (invocation.getParent() instanceof CtAssignment) {
                        CtAssignment<?,?> assignment = (CtAssignment<?,?>) invocation.getParent();
                        if (assignment.getAssigned() != null && assignment.getAssigned().getType() != null) {
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
                    } else if (invocation.getParent() instanceof CtInvocation) {
                        CtInvocation<?> parentInv = (CtInvocation<?>) invocation.getParent();
                        if (parentInv.getTarget() == invocation) {
                            // メソッドチェーンのターゲットとして使われている -> voidではない
                            // 型が特定できない場合はObjectとする
                            returnType = stubFactory.Type().objectType();
                        }
                    }

                    if (addedSignatures.contains(signature)) {
                        // 既にメソッドが存在する場合、戻り値がvoidで、今回推論できた型がvoidでなければ更新する
                        method = stubType.getMethods().stream()
                            .filter(m -> {
                                StringBuilder sb = new StringBuilder(m.getSimpleName());
                                for (CtParameter<?> p : m.getParameters()) {
                                    sb.append("_").append(p.getType().getSimpleName());
                                }
                                return sb.toString().equals(signature);
                            })
                            .findFirst()
                            .orElse(null);

                        if (method != null && method.getType().equals(stubFactory.Type().voidType()) && !returnType.equals(stubFactory.Type().voidType())) {
                            method.setType((CtTypeReference) returnType);
                            // ボディも更新
                            CtBlock<?> body = stubFactory.Core().createBlock();
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
                            method.setBody(body);
                        }
                        continue;
                    }

                    addedSignatures.add(signature);

                    // メソッド作成
                    method = stubFactory.Core().createMethod();
                    method.setSimpleName(methodName);
                    method.addModifier(ModifierKind.PUBLIC);
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

                    stubType.addMethod(method);
                }
            }
        }
    }

    private void inferFields(CtType<?> stubType, String targetTypeQName, CtModel originalModel) {
        // フィールドアクセスを検索
        List<CtFieldAccess<?>> accesses = originalModel.getElements(new TypeFilter<>(CtFieldAccess.class));
        Set<String> addedFields = new HashSet<>();

        for (CtFieldAccess<?> access : accesses) {
            CtExpression<?> target = access.getTarget();
            if (target != null && target.getType() != null && target.getType().getQualifiedName().equals(targetTypeQName)) {
                String fieldName = access.getVariable().getSimpleName();

                // 型推論 (使われ方から)
                CtTypeReference<?> fieldType = stubFactory.Type().objectType();

                if (access instanceof CtFieldWrite) {
                     CtFieldWrite<?> write = (CtFieldWrite<?>) access;
                     if (write.getParent() instanceof CtAssignment) {
                         CtAssignment<?,?> assign = (CtAssignment<?,?>) write.getParent();
                         if (assign.getAssignment() != null && assign.getAssignment().getType() != null) {
                             fieldType = createTypeReferenceInStubFactory(assign.getAssignment().getType());
                         }
                     }
                } else {
                    if (access.getParent() instanceof CtAssignment) {
                         CtAssignment<?,?> assign = (CtAssignment<?,?>) access.getParent();
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

                if (addedFields.contains(fieldName)) {
                    // 既にフィールドが存在する場合、型がObjectで、今回推論できた型がObjectでなければ更新する
                    CtField<?> field = stubType.getField(fieldName);
                    if (field != null && field.getType().equals(stubFactory.Type().objectType()) && !fieldType.equals(stubFactory.Type().objectType())) {
                        field.setType((CtTypeReference) fieldType);
                    }
                    continue;
                }

                addedFields.add(fieldName);

                CtField<?> field = stubFactory.Core().createField();
                field.setSimpleName(fieldName);
                field.addModifier(ModifierKind.PUBLIC);
                field.setType((CtTypeReference) fieldType);
                stubType.addField(field);
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
