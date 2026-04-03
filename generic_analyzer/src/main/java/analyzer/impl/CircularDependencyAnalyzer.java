package analyzer.impl;

import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import analyzer.Analyzer;

import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 循環参照を検出するAnalyzer。
 * @Autowired などのアノテーションや、レガシーなセッターインジェクション等を考慮し、
 * コンストラクタ、フィールド、セッターメソッドから依存関係グラフを構築する。
 * 全要素のAST走査後にDFSによる閉路（Cycle）検出を行い、サイクル内に含まれる
 * セッターメソッド等を @Lazy 候補として出力する。
 */
public class CircularDependencyAnalyzer implements Analyzer {

    private enum DependencyType {
        CONSTRUCTOR, FIELD, SETTER
    }

    private static class DependencyEdge {
        final DependencyType type;
        final CtElement location;

        DependencyEdge(DependencyType type, CtElement location) {
            this.type = type;
            this.location = location;
        }
    }

    // クラスの完全修飾名 -> (依存先クラス名 -> その依存先へのエッジのリスト)
    private final Map<String, Map<String, List<DependencyEdge>>> graph = new HashMap<>();

    @Override
    public String getCategory() {
        return "Circular Reference";
    }

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        if (!context.isCheckCircularDependency()) {
            return;
        }

        if (element instanceof CtField<?>) {
            CtField<?> field = (CtField<?>) element;
            processDependency(field.getDeclaringType(), field.getType(), DependencyType.FIELD, field);
        } else if (element instanceof CtMethod<?>) {
            CtMethod<?> method = (CtMethod<?>) element;
            // set始まりで引数が1つのメソッドはセッターインジェクションの候補とする
            if (method.getSimpleName().startsWith("set") && method.getParameters().size() == 1) {
                processDependency(method.getDeclaringType(), method.getParameters().get(0).getType(), DependencyType.SETTER, method);
            }
        } else if (element instanceof CtConstructor<?>) {
            CtConstructor<?> constructor = (CtConstructor<?>) element;
            constructor.getParameters().forEach(param -> {
                processDependency(constructor.getDeclaringType(), param.getType(), DependencyType.CONSTRUCTOR, constructor);
            });
        }
    }

    private void processDependency(CtType<?> fromType, CtTypeReference<?> toTypeRef, DependencyType type, CtElement location) {
        if (fromType == null || toTypeRef == null) {
            return;
        }
        
        String fromClassName = fromType.getQualifiedName();
        String toClassName = getBaseTypeName(toTypeRef);
        
        if (toClassName == null || !isCandidateType(toClassName)) {
            return;
        }

        graph.computeIfAbsent(fromClassName, k -> new HashMap<>())
             .computeIfAbsent(toClassName, k -> new ArrayList<>())
             .add(new DependencyEdge(type, location));
    }

    private String getBaseTypeName(CtTypeReference<?> typeRef) {
        if (typeRef == null) return null;
        if (typeRef.isArray()) {
            return getBaseTypeName(((spoon.reflect.reference.CtArrayTypeReference<?>) typeRef).getComponentType());
        }
        // コレクション等のジェネリクス型パラメータがある場合は取得を試みる
        if (typeRef.getActualTypeArguments() != null && !typeRef.getActualTypeArguments().isEmpty()) {
            return getBaseTypeName(typeRef.getActualTypeArguments().get(0));
        }
        return typeRef.getQualifiedName();
    }

    private boolean isCandidateType(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) return false;
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) return false;
        // プリミティブ型判定（基本型名は小文字スタート）
        if (qualifiedName.indexOf('.') == -1 && Character.isLowerCase(qualifiedName.charAt(0))) return false;
        return true;
    }

    @Override
    public void postProcess(AnalysisContext context) {
        if (!context.isCheckCircularDependency()) {
            return;
        }

        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        List<String> path = new ArrayList<>();
        Set<String> uniqueCycles = new HashSet<>(); // 重複出力防止のため

        for (String node : graph.keySet()) {
            dfs(node, visited, recursionStack, path, uniqueCycles, context);
        }
    }

    private void dfs(String node, Set<String> visited, Set<String> recursionStack, 
                     List<String> path, Set<String> uniqueCycles, AnalysisContext context) {
        if (recursionStack.contains(node)) {
            // サイクル発見
            int startIndex = path.indexOf(node);
            if (startIndex != -1) {
                List<String> cycleNodeNames = new ArrayList<>(path.subList(startIndex, path.size()));
                reportCycle(cycleNodeNames, uniqueCycles, context);
            }
            return;
        }
        if (visited.contains(node)) {
            return;
        }

        visited.add(node);
        recursionStack.add(node);
        path.add(node);

        for (String toNode : graph.getOrDefault(node, Collections.emptyMap()).keySet()) {
            dfs(toNode, visited, recursionStack, path, uniqueCycles, context);
        }

        path.remove(path.size() - 1);
        recursionStack.remove(node);
    }

    private void reportCycle(List<String> cycleNodeNames, Set<String> uniqueCycles, AnalysisContext context) {
        // サイクルの正規化 (重複検知用)
        List<String> sortedNodes = new ArrayList<>(cycleNodeNames);
        Collections.sort(sortedNodes);
        String cycleKey = String.join(",", sortedNodes);

        if (!uniqueCycles.add(cycleKey)) {
            return; // 既に報告済みのサイクル
        }

        // サイクルの文字列表現構築 (クラス名の簡略化)
        String cycleDesc = cycleNodeNames.stream()
                .map(this::simplifyName)
                .collect(Collectors.joining(" -> ")) 
                + " -> " + simplifyName(cycleNodeNames.get(0));

        // サイクルを構成するすべてのエッジを報告する
        for (int i = 0; i < cycleNodeNames.size(); i++) {
            String fromNode = cycleNodeNames.get(i);
            String toNode = cycleNodeNames.get((i + 1) % cycleNodeNames.size());

            List<DependencyEdge> edges = graph.get(fromNode).get(toNode);
            for (DependencyEdge edge : edges) {
                String methodType = "Injection Point (" + edge.type.name() + ")";
                if (edge.type == DependencyType.SETTER) {
                    methodType = "Setter Candidate for @Lazy";
                }
                
                String matchedElementMsg = String.format("%s | Cycle: %s", methodType, cycleDesc);
                AnalysisResult result = AnalysisResult.fromElement(edge.location, getCategory(), matchedElementMsg, context);
                context.addResult(result);
            }
        }
    }
    
    private String simplifyName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot != -1) {
            return qualifiedName.substring(lastDot + 1);
        }
        return qualifiedName;
    }
}
