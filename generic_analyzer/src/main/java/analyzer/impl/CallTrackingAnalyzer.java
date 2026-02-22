package analyzer.impl;

import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import analyzer.Analyzer;
import analyzer.util.ConditionExtractor;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * メソッドおよびコンストラクタの呼び出しを追跡し、そのルートの分岐条件を抽出するアナライザ。
 */
public class CallTrackingAnalyzer implements Analyzer {

    private static final String CATEGORY = "Call Route Tracking";

    // プロジェクト全体における実行可能要素（メソッド・コンストラクタ宣言）に対する、
    // それらの呼び出し箇所（CtInvocation または CtConstructorCall）のキャッシュ。
    // キー: 呼び出される側の宣言、バリュー: 呼び出し元の要素のリスト
    private Map<CtExecutable<?>, List<CtElement>> invocationCache = null;

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        Pattern pattern = context.getTrackCallPattern();
        // 追跡対象のパターンが指定されていなければ何もしない
        if (pattern == null) {
            return;
        }

        if (element instanceof CtInvocation<?> || element instanceof CtConstructorCall<?>) {
            CtExecutableReference<?> executableRef;

            // メソッド呼び出しかコンストラクタ呼び出しかでターゲットの参照を取得
            if (element instanceof CtInvocation<?>) {
                executableRef = ((CtInvocation<?>) element).getExecutable();
            } else {
                executableRef = ((CtConstructorCall<?>) element).getExecutable();
            }

            // 参照が解決できない場合はスキップ
            if (executableRef == null) {
                return;
            }

            // メソッドの完全修飾シグネチャと簡易名を取得
            String name = AnalysisResult.getExecutableSignature(executableRef);

            // ユーザー指定のパターン（正規表現）にマッチするか確認
            if (pattern.matcher(name).matches() || pattern.matcher(getShortName(executableRef)).matches()) {
                // 初回のマッチ時に、プロジェクト全体の呼び出し関係をキャッシュする
                if (invocationCache == null) {
                    buildInvocationCache(element.getFactory().getModel().getRootPackage());
                }

                // 起点からバックトラック（呼び出し元への遡り）を開始
                traceCall(element, context, name);
            }
        }
    }

    /**
     * 指定された要素（呼び出し箇所）を起点として、呼び出し経路を追跡し結果を登録する。
     *
     * @param startElement 起点となるメソッド/コンストラクタ呼び出し要素
     * @param context      解析コンテキスト
     * @param targetName   ターゲット要素の名前
     */
    private void traceCall(CtElement startElement, AnalysisContext context, String targetName) {
        StringBuilder allRoutes = new StringBuilder();
        // 再帰呼び出し（無限ループ）を防ぐための訪問済み管理セット
        Set<CtExecutable<?>> visited = new HashSet<>();
        List<RouteStep> currentPath = new ArrayList<>();
        int[] routeCount = { 0 };
        Set<String> entryPoints = new LinkedHashSet<>();

        // バックトラックしてルートを構築・出力
        traverseUp(startElement, currentPath, allRoutes, visited, targetName, routeCount, entryPoints);

        String entryPointsSummary = String.join("\n", entryPoints);

        CallTrackingResult result = CallTrackingResult.fromElement(
                startElement, CATEGORY, "呼び出しルート追跡: " + targetName, context, allRoutes.toString().trim(),
                entryPointsSummary);
        context.addResult(result);
    }

    /**
     * 経路情報の一要素を保持するクラス。
     */
    private static class RouteStep {
        final String callerName;
        final List<String> conditions;
        final String note;

        RouteStep(String callerName, List<String> conditions, String note) {
            this.callerName = callerName;
            this.conditions = conditions;
            this.note = note;
        }
    }

    /**
     * 現在の呼び出し箇所から自身を包むメソッドを取得し、さらにその呼び出し元を遡る処理を再帰的に実施する。
     * 行き止まり（エントリーポイントや再帰）に到達した時点で、ルート情報を上から下（エントリーポイントからターゲット）へフォーマットして出力する。
     *
     * @param currentElement 現在見ている呼び出し要素(`CtInvocation` 等)
     * @param currentPath    現在蓄積している経路(Target側が先頭、Entry Point側が末尾)
     * @param allRoutes      トレース結果全体を構築するStringBuilder
     * @param visited        再帰呼び出し（循環）検知のためのセット
     * @param targetName     最終ターゲットの名称
     * @param routeCount     ルート番号（インクリメント用）
     */
    private void traverseUp(CtElement currentElement, List<RouteStep> currentPath, StringBuilder allRoutes,
            Set<CtExecutable<?>> visited, String targetName, int[] routeCount, Set<String> entryPoints) {

        // 対象呼び出しを含むステートメントにおける分岐条件を抽出
        List<String> conditions = ConditionExtractor.extractConditionList(currentElement);

        // この呼び出しを行っている親の実行可能要素（メソッド/コンストラクタ）を取得
        CtExecutable<?> caller = currentElement.getParent(CtExecutable.class);
        if (caller == null) {
            // 親メソッドがない場合（トップレベルなど）は遡及終了
            currentPath.add(new RouteStep("(Top level)", conditions, null));
            entryPoints.add("(Top level)");
            formatPath(currentPath, allRoutes, targetName, ++routeCount[0]);
            currentPath.remove(currentPath.size() - 1);
            return;
        }

        String callerName = AnalysisResult.getExecutableSignature(caller.getReference());

        // 循環呼び出し防止：1度通った親メソッドを再度辿らない
        if (!visited.add(caller)) {
            currentPath.add(new RouteStep(callerName, conditions, "(Recursive call)"));
            formatPath(currentPath, allRoutes, targetName, ++routeCount[0]);
            currentPath.remove(currentPath.size() - 1);
            return;
        }

        // 親メソッド自体が、別の場所でどこから呼び出されているかをキャッシュから取得
        List<CtElement> callerInvocations = invocationCache.getOrDefault(caller, new ArrayList<>());

        if (callerInvocations.isEmpty()) {
            // どこからも呼ばれていない場合はエントリーポイントとみなす
            currentPath.add(new RouteStep(callerName, conditions, "(Entry point)"));
            entryPoints.add(callerName);
            formatPath(currentPath, allRoutes, targetName, ++routeCount[0]);
            currentPath.remove(currentPath.size() - 1);
        } else {
            // 親メソッドを呼び出しているすべての箇所についてさらに遡及する
            currentPath.add(new RouteStep(callerName, conditions, null));
            for (CtElement inv : callerInvocations) {
                traverseUp(inv, currentPath, allRoutes, visited, targetName, routeCount, entryPoints);
            }
            currentPath.remove(currentPath.size() - 1);
        }

        // 行きがけのみ状態を維持し、帰りがけに visited から削除する（他の経路での通過を許容）
        visited.remove(caller);
    }

    /**
     * 確立した1つの呼び出しルート（エントリーポイント〜ターゲット）を文字列として上から下に整形する。
     */
    private void formatPath(List<RouteStep> path, StringBuilder allRoutes, String targetName, int routeNumber) {
        allRoutes.append("[Route ").append(routeNumber).append("]\n");
        int depth = 0;

        // リストには Target の直接の親(index 0)から Entry point (index N)の順で積まれているため、逆順に処理する
        for (int i = path.size() - 1; i >= 0; i--) {
            RouteStep step = path.get(i);
            String indent = "  ".repeat(depth);

            // 呼び出し元メソッド名の出力
            allRoutes.append(indent).append(step.callerName);
            if (step.note != null) {
                allRoutes.append(" ").append(step.note);
            }
            allRoutes.append("\n");

            // 条件の出力 (次のメソッドやTargetを呼ぶための条件)
            for (String condition : step.conditions) {
                depth++;
                String condIndent = "  ".repeat(depth);
                allRoutes.append(condIndent).append(condition).append("\n");
            }
            depth++;
        }

        // 最後にTargetを出力
        String finalIndent = "  ".repeat(depth);
        allRoutes.append(finalIndent).append("[Target] ").append(targetName).append("\n\n");
    }

    /**
     * プロジェクト内のすべてのメソッドおよびコンストラクタ呼び出しをスキャンし、
     * 「呼び出される側」をキーとして「呼び出している部分」をリストで保持するキャッシュを構築する。
     *
     * @param rootNode スキャン開始するASTのルートノード
     */
    private void buildInvocationCache(CtElement rootNode) {
        invocationCache = new HashMap<>();

        // メソッド呼び出し(CtInvocation)を全件取得してマップ化
        List<CtInvocation<?>> invocations = rootNode.getElements(new TypeFilter<>(CtInvocation.class));
        for (CtInvocation<?> inv : invocations) {
            CtExecutable<?> dec = inv.getExecutable().getExecutableDeclaration();
            if (dec != null) {
                invocationCache.computeIfAbsent(dec, k -> new ArrayList<>()).add(inv);
            }
        }

        // コンストラクタ呼び出し(CtConstructorCall)を全件取得してマップ化
        List<CtConstructorCall<?>> constructorCalls = rootNode.getElements(new TypeFilter<>(CtConstructorCall.class));
        for (CtConstructorCall<?> call : constructorCalls) {
            CtExecutable<?> dec = call.getExecutable().getExecutableDeclaration();
            if (dec != null) {
                invocationCache.computeIfAbsent(dec, k -> new ArrayList<>()).add(call);
            }
        }
    }

    /**
     * 実行可能要素の単純なメソッド名（またはコンストラクタの型名）を取得する。
     */
    private String getShortName(CtExecutableReference<?> ref) {
        if (ref == null)
            return "Unknown";
        String methodName = ref.getSimpleName();
        if ("<init>".equals(methodName)) {
            methodName = ref.getDeclaringType() != null ? ref.getDeclaringType().getSimpleName() : "Constructor";
        }
        return methodName;
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
