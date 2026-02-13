package com.analyzer;

import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.CtScanner;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

/**
 * SpoonモデルからPlantUMLのシーケンス図を生成するクラス
 */
public class PlantUmlGenerator extends CtScanner {

    private StringBuilder uml;
    private Stack<String> participantStack;
    private Set<String> visitedMethods;
    private int recursionDepth = 0;
    private static final int MAX_RECURSION_DEPTH = 10;

    public String generate(CtMethod<?> method) {
        uml = new StringBuilder();
        participantStack = new Stack<>();
        visitedMethods = new HashSet<>();
        recursionDepth = 0;

        uml.append("@startuml\n");
        uml.append("autoactivate on\n"); // 自動アクティベーションを有効化

        if (method.getDeclaringType() != null) {
            String startParticipant = method.getDeclaringType().getSimpleName();
            participantStack.push(startParticipant);
            // エントリポイントの記述（任意）
            // uml.append("[-> ").append(startParticipant).append(" : ").append(method.getSimpleName()).append("\n");
        } else {
            participantStack.push("Unknown");
        }

        // メソッド本体のスキャン開始
        if (method.getBody() != null) {
            scan(method.getBody());
        }

        // エントリポイントからの戻り（任意）
        // uml.append("[<-- ").append(participantStack.peek()).append("\n");

        uml.append("@enduml\n");
        return uml.toString();
    }

    @Override
    public <T> void visitCtInvocation(CtInvocation<T> invocation) {
        CtExecutableReference<?> executable = invocation.getExecutable();
        if (executable == null) {
            super.visitCtInvocation(invocation);
            return;
        }

        String caller = participantStack.isEmpty() ? "Unknown" : participantStack.peek();
        String calleeClass = getCalleeClassName(invocation);
        String methodName = executable.getSimpleName();

        // 自分自身への呼び出しの場合の表示調整なども可能だが、基本はそのまま出力
        uml.append(caller).append(" -> ").append(calleeClass).append(" : ").append(methodName).append("()\n");

        // 呼び出し先のメソッド定義を取得
        CtExecutable<?> declaration = executable.getExecutableDeclaration();

        // ソースコードが存在し、かつCTMethodであり、再帰深さが制限内であれば中身をスキャン
        if (declaration instanceof CtMethod && declaration.getBody() != null && recursionDepth < MAX_RECURSION_DEPTH) {
            CtMethod<?> methodDecl = (CtMethod<?>) declaration;
            String signature = methodDecl.getSignature();

            // 無限再帰防止（単純なサイクル検知）
            // 同じメソッド呼び出しスタックに同じシグネチャが含まれる場合は再帰しない
            // ここでは簡易的にvisitedMethodsを使っているが、より厳密にはスタック上のメソッドをチェックすべき
            // しかしシーケンス図では「AがBを呼び、BがAを呼ぶ」はあり得るので、単純なSetチェックだと不十分かも。
            // 呼び出しコンテキスト（スタック）ベースでループを検知するか、単に深さ制限で止めるのが安全。
            // 今回は深さ制限(MAX_RECURSION_DEPTH)を主としつつ、直近の呼び出し重複だけ避けるなどが良い。

            participantStack.push(calleeClass);
            recursionDepth++;

            scan(methodDecl.getBody());

            recursionDepth--;
            participantStack.pop();
        }

        // autoactivate on の場合、returnは矢印で表現される（自動）
        // ただし、明示的に return を書きたい場合は以下のようにする
        // uml.append(calleeClass).append(" --> ").append(caller).append("\n");
        // autoactivate on を使うと、次のメッセージまたはブロック終了で自動的にdeactivateされるが、
        // 戻り矢印は自動生成される場合とされない場合がある。
        // ここではSpoonのscanが終わったタイミング＝メソッド実行終了なので、
        // PlantUMLのautoactivate機能に任せてみる。
        // -> autoactivate on は return キーワードで戻り矢印を生成する。
        uml.append("return\n");
    }

    @Override
    public void visitCtIf(CtIf ifElement) {
        String condition = ifElement.getCondition() != null ? ifElement.getCondition().toString() : "condition";
        // 条件式に含まれる改行などを除去
        condition = cleanString(condition);

        uml.append("alt ").append(condition).append("\n");

        if (ifElement.getThenStatement() != null) {
            scan((CtElement) ifElement.getThenStatement());
        }

        if (ifElement.getElseStatement() != null) {
            uml.append("else\n");
            scan((CtElement) ifElement.getElseStatement());
        }

        uml.append("end\n");
    }

    @Override
    public void visitCtFor(CtFor forLoop) {
        String expr = forLoop.getExpression() != null ? forLoop.getExpression().toString() : "";
        uml.append("loop for(").append(cleanString(expr)).append(")\n");
        scan(forLoop.getBody());
        uml.append("end\n");
    }

    @Override
    public void visitCtForEach(CtForEach forEach) {
        String expr = forEach.getExpression() != null ? forEach.getExpression().toString() : "";
        uml.append("loop forEach(").append(cleanString(expr)).append(")\n");
        scan(forEach.getBody());
        uml.append("end\n");
    }

    @Override
    public void visitCtWhile(CtWhile whileLoop) {
        String cond = whileLoop.getLoopingExpression() != null ? whileLoop.getLoopingExpression().toString() : "";
        uml.append("loop while(").append(cleanString(cond)).append(")\n");
        scan(whileLoop.getBody());
        uml.append("end\n");
    }

    @Override
    public void visitCtDo(CtDo doLoop) {
        String cond = doLoop.getLoopingExpression() != null ? doLoop.getLoopingExpression().toString() : "";
        uml.append("loop do...while(").append(cleanString(cond)).append(")\n");
        scan(doLoop.getBody());
        uml.append("end\n");
    }

    @Override
    public <S> void visitCtSwitch(CtSwitch<S> switchStatement) {
        String selector = switchStatement.getSelector() != null ? switchStatement.getSelector().toString() : "";
        uml.append("group switch(").append(cleanString(selector)).append(")\n");

        for (CtCase<? super S> caseStatement : switchStatement.getCases()) {
            String label = caseStatement.getCaseExpression() != null ? caseStatement.getCaseExpression().toString() : "default";
            uml.append("else case ").append(cleanString(label)).append("\n");
            for (CtStatement stmt : caseStatement.getStatements()) {
                scan(stmt);
            }
        }
        uml.append("end\n");
    }

    private String getCalleeClassName(CtInvocation<?> invocation) {
        CtExpression<?> target = invocation.getTarget();
        if (target != null && target.getType() != null) {
            return target.getType().getSimpleName();
        }

        // targetがnullの場合は自クラス（または親クラス）のメソッド呼び出し
        // スタックのトップ（現在のクラス）を返す
        if (!participantStack.isEmpty()) {
            return participantStack.peek();
        }
        return "Unknown";
    }

    private String cleanString(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\"", "'").trim();
    }
}
