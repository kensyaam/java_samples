#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
呼び出しツリー可視化スクリプト
TSVファイルから呼び出しツリーを生成します
"""

import csv
import sys
from collections import defaultdict
from typing import Dict, List, Set, Tuple


class CallTreeVisualizer:
    def __init__(self, tsv_file: str):
        self.tsv_file = tsv_file
        self.forward_calls = defaultdict(list)  # 呼び出し元 -> 呼び出し先のリスト
        self.reverse_calls = defaultdict(list)  # 呼び出し先 -> 呼び出し元のリスト
        self.method_info = {}  # メソッド -> (クラス, 親クラス, SQL, etc.)
        self.load_data()

    def load_data(self):
        """TSVファイルを読み込む"""
        with open(self.tsv_file, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f, delimiter="\t")
            for row in reader:
                caller = row["呼び出し元メソッド"]
                callee = row["呼び出し先メソッド"]
                direction = row["方向"]

                # メソッド情報を保存
                if caller and caller not in self.method_info:
                    self.method_info[caller] = {
                        "class": row["呼び出し元クラス"],
                        "parent": row["呼び出し元の親クラス"],
                        "sql": row.get("SQL文", ""),
                        "visibility": row.get("可視性", ""),
                        "is_static": row.get("Static", "") == "Yes",
                        "is_entry_point": row.get("エントリーポイント候補", "")
                        == "Yes",
                        "entry_type": row.get("エントリータイプ", ""),
                        "annotations": row.get("アノテーション", ""),
                        "class_annotations": row.get("クラスアノテーション", ""),
                    }

                if callee and callee not in self.method_info:
                    self.method_info[callee] = {
                        "class": row["呼び出し先クラス"],
                        "parent": "",
                        "sql": "",
                        "visibility": "",
                        "is_static": False,
                        "is_entry_point": False,
                        "annotations": "",
                    }

                # 呼び出し関係を保存
                if direction == "Forward" and caller and callee:
                    self.forward_calls[caller].append(
                        {
                            "method": callee,
                            "is_parent_method": row["呼び出し先は親クラスのメソッド"],
                            "implementations": row["呼び出し先の実装クラス候補"],
                        }
                    )
                elif direction == "Reverse" and caller and callee:
                    self.reverse_calls[caller].append(callee)

    def print_forward_tree(
        self,
        root_method: str,
        max_depth: int = 10,
        show_class: bool = True,
        show_sql: bool = True,
        follow_implementations: bool = True,
    ):
        """呼び出し元からのツリーを表示

        Args:
            root_method: 起点メソッド
            max_depth: 最大深度
            show_class: クラス情報を表示するか
            show_sql: SQL情報を表示するか
            follow_implementations: 実装クラス候補がある場合、それも追跡するか
        """
        print(f"\n{'=' * 80}")
        print(f"呼び出しツリー (起点: {root_method})")
        print(f"{'=' * 80}\n")

        visited = set()
        self._print_tree_recursive(
            root_method,
            0,
            max_depth,
            visited,
            show_class,
            show_sql,
            is_forward=True,
            follow_implementations=follow_implementations,
        )

    def print_reverse_tree(
        self, target_method: str, max_depth: int = 10, show_class: bool = True
    ):
        """呼び出し先からのツリー（誰がこのメソッドを呼んでいるか）を表示"""
        print(f"\n{'=' * 80}")
        print(f"逆引きツリー (対象: {target_method})")
        print(f"{'=' * 80}\n")

        visited = set()
        self._print_tree_recursive(
            target_method, 0, max_depth, visited, show_class, False, is_forward=False
        )

    def _print_tree_recursive(
        self,
        method: str,
        depth: int,
        max_depth: int,
        visited: Set[str],
        show_class: bool,
        show_sql: bool,
        is_forward: bool,
        follow_implementations: bool = True,
    ):
        """ツリーを再帰的に表示"""
        if depth > max_depth:
            return

        # 循環参照チェック
        if method in visited:
            self._print_node(method, depth, show_class, show_sql, is_circular=True)
            return

        visited.add(method)
        self._print_node(method, depth, show_class, show_sql)

        # 子ノードを表示
        if is_forward:
            callees = self.forward_calls.get(method, [])
            for callee_info in callees:
                callee = callee_info["method"]

                # 親クラスメソッドや実装クラス候補の情報を表示
                annotations = []
                if callee_info["is_parent_method"] == "Yes":
                    annotations.append("親クラスメソッド")
                if callee_info["implementations"]:
                    annotations.append(f"実装: {callee_info['implementations']}")

                if annotations:
                    indent = "    " * (depth + 1)
                    print(f"{indent}↓ [{', '.join(annotations)}]")

                # 呼び出し先を再帰的に表示
                self._print_tree_recursive(
                    callee,
                    depth + 1,
                    max_depth,
                    visited.copy(),
                    show_class,
                    show_sql,
                    is_forward,
                    follow_implementations,
                )

                # 実装クラス候補がある場合、それらも追跡
                if follow_implementations and callee_info["implementations"]:
                    implementations = [
                        impl.strip()
                        for impl in callee_info["implementations"].split(",")
                        if impl.strip()
                    ]

                    for impl_class in implementations:
                        # 実装クラスの対応するメソッドを探す
                        impl_method = self._find_implementation_method(
                            callee, impl_class
                        )
                        if impl_method:
                            indent = "    " * (depth + 1)
                            print(f"{indent}↓ [実装クラスへの展開: {impl_class}]")

                            self._print_tree_recursive(
                                impl_method,
                                depth + 1,
                                max_depth,
                                visited.copy(),
                                show_class,
                                show_sql,
                                is_forward,
                                follow_implementations,
                            )
        else:
            callers = self.reverse_calls.get(method, [])
            for caller in callers:
                self._print_tree_recursive(
                    caller,
                    depth + 1,
                    max_depth,
                    visited.copy(),
                    show_class,
                    False,
                    is_forward,
                    follow_implementations,
                )

    def _print_node(
        self,
        method: str,
        depth: int,
        show_class: bool,
        show_sql: bool,
        is_circular: bool = False,
    ):
        """ノード情報を表示"""
        indent = "    " * depth
        prefix = "├── " if depth > 0 else ""

        info = self.method_info.get(method, {})

        # メソッド名を表示
        display = f"{indent}{prefix}{method}"
        if is_circular:
            display += " [循環参照]"
        print(display)

        # クラス情報を表示
        if show_class and info.get("class"):
            print(f"{indent}    クラス: {info['class']}")
            if info.get("parent"):
                print(f"{indent}    親クラス: {info['parent']}")

        # SQL情報を表示
        if show_sql and info.get("sql"):
            sql_lines = info["sql"].split(";")
            for sql in sql_lines[:3]:  # 最初の3つまで表示
                if sql.strip():
                    print(f"{indent}    SQL: {sql.strip()[:80]}...")

    def _find_implementation_method(self, abstract_method: str, impl_class: str) -> str:
        """抽象メソッドに対応する実装クラスのメソッドを探す

        Args:
            abstract_method: 抽象メソッドのシグネチャ
            impl_class: 実装クラス名

        Returns:
            実装メソッドのシグネチャ（見つからない場合はNone）
        """
        # メソッドシグネチャからメソッド名と引数を抽出
        # 例: "com.example.Interface#method(String, int)" -> "method(String, int)"
        if "#" not in abstract_method:
            return None

        method_part = abstract_method.split("#", 1)[1]

        # 実装クラスの同じシグネチャのメソッドを探す
        for method_sig, info in self.method_info.items():
            if info.get("class") == impl_class:
                # クラス名を除いたメソッド部分が一致するか確認
                if "#" in method_sig:
                    sig_method_part = method_sig.split("#", 1)[1]
                    if sig_method_part == method_part:
                        return method_sig

        return None

    def export_tree_to_file(
        self,
        root_method: str,
        output_file: str,
        max_depth: int = 10,
        format: str = "text",
        follow_implementations: bool = True,
    ):
        """ツリーをファイルにエクスポート"""
        if format == "text":
            self._export_text_tree(
                root_method, output_file, max_depth, follow_implementations
            )
        elif format == "markdown":
            self._export_markdown_tree(
                root_method, output_file, max_depth, follow_implementations
            )
        elif format == "html":
            self._export_html_tree(
                root_method, output_file, max_depth, follow_implementations
            )

    def _export_text_tree(
        self,
        root_method: str,
        output_file: str,
        max_depth: int,
        follow_implementations: bool,
    ):
        """テキスト形式でエクスポート"""
        with open(output_file, "w", encoding="utf-8") as f:
            original_stdout = sys.stdout
            sys.stdout = f
            self.print_forward_tree(
                root_method, max_depth, follow_implementations=follow_implementations
            )
            sys.stdout = original_stdout
        print(f"ツリーを {output_file} にエクスポートしました")

    def _export_markdown_tree(
        self,
        root_method: str,
        output_file: str,
        max_depth: int,
        follow_implementations: bool,
    ):
        """Markdown形式でエクスポート"""
        with open(output_file, "w", encoding="utf-8") as f:
            f.write(f"# 呼び出しツリー\n\n")
            f.write(f"**起点メソッド:** `{root_method}`\n\n")
            f.write("```\n")

            original_stdout = sys.stdout
            sys.stdout = f
            self.print_forward_tree(
                root_method,
                max_depth,
                show_class=False,
                show_sql=False,
                follow_implementations=follow_implementations,
            )
            sys.stdout = original_stdout

            f.write("```\n")
        print(f"ツリーを {output_file} にエクスポートしました")

    def _export_html_tree(
        self,
        root_method: str,
        output_file: str,
        max_depth: int,
        follow_implementations: bool,
    ):
        """HTML形式でエクスポート（インタラクティブなツリー）"""
        html = f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>呼び出しツリー - {root_method}</title>
    <style>
        body {{ font-family: 'Courier New', monospace; padding: 20px; }}
        .tree {{ list-style-type: none; padding-left: 20px; }}
        .tree li {{ margin: 5px 0; }}
        .method {{ color: #0066cc; cursor: pointer; }}
        .method:hover {{ text-decoration: underline; }}
        .class-info {{ color: #666; font-size: 0.9em; margin-left: 20px; }}
        .sql {{ color: #008800; font-size: 0.9em; margin-left: 20px; }}
        .circular {{ color: #cc0000; }}
        .parent-method {{ color: #ff6600; }}
        .implementation {{ color: #9966cc; font-style: italic; }}
    </style>
</head>
<body>
    <h1>呼び出しツリー</h1>
    <p><strong>起点メソッド:</strong> {root_method}</p>
    <ul class="tree">
"""

        visited = set()
        html += self._generate_html_tree(
            root_method, 0, max_depth, visited, follow_implementations
        )

        html += """
    </ul>
</body>
</html>
"""

        with open(output_file, "w", encoding="utf-8") as f:
            f.write(html)
        print(f"ツリーを {output_file} にエクスポートしました")

    def _generate_html_tree(
        self,
        method: str,
        depth: int,
        max_depth: int,
        visited: Set[str],
        follow_implementations: bool,
    ) -> str:
        """HTML形式のツリーを生成"""
        if depth > max_depth:
            return ""

        html = ""
        info = self.method_info.get(method, {})

        if method in visited:
            html += f'<li><span class="method circular">{method} [循環参照]</span></li>'
            return html

        visited.add(method)

        html += f'<li><span class="method">{method}</span>'

        if info.get("class"):
            html += f'<div class="class-info">クラス: {info["class"]}</div>'

        callees = self.forward_calls.get(method, [])
        if callees:
            html += '<ul class="tree">'
            for callee_info in callees:
                html += self._generate_html_tree(
                    callee_info["method"],
                    depth + 1,
                    max_depth,
                    visited.copy(),
                    follow_implementations,
                )

                # 実装クラス候補がある場合
                if follow_implementations and callee_info["implementations"]:
                    implementations = [
                        impl.strip()
                        for impl in callee_info["implementations"].split(",")
                        if impl.strip()
                    ]

                    for impl_class in implementations:
                        impl_method = self._find_implementation_method(
                            callee_info["method"], impl_class
                        )
                        if impl_method:
                            html += f'<li><span class="implementation">→ 実装: {impl_class}</span>'
                            html += self._generate_html_tree(
                                impl_method,
                                depth + 2,
                                max_depth,
                                visited.copy(),
                                follow_implementations,
                            )
                            html += "</li>"

            html += "</ul>"

        html += "</li>"
        return html

    def list_entry_points(self, min_calls: int = 1, strict: bool = True):
        """エントリーポイント候補をリストアップ

        Args:
            min_calls: 最小呼び出し数
            strict: True の場合、アノテーションやmainメソッドなど厳密に判定
        """
        print(f"\n{'=' * 80}")
        if strict:
            print(f"エントリーポイント候補（厳密モード）")
        else:
            print(f"エントリーポイント候補 (呼び出し先が{min_calls}個以上)")
        print(f"{'=' * 80}\n")

        # すべての呼び出し先を収集
        all_callees = set()
        for callees in self.forward_calls.values():
            for callee_info in callees:
                all_callees.add(callee_info["method"])

        entry_points = []

        for method, info in self.method_info.items():
            # 他から呼ばれていないメソッドのみ
            if method in all_callees:
                continue

            call_count = len(self.forward_calls.get(method, []))

            # 厳密モードの場合
            if strict:
                if info.get("is_entry_point"):
                    entry_type = self._determine_entry_type(info)
                    entry_points.append(
                        (
                            method,
                            call_count,
                            info.get("class", ""),
                            entry_type,
                            info.get("annotations", ""),
                            info.get("visibility", ""),
                        )
                    )
            else:
                # 非厳密モードの場合は呼び出し数で判定
                if call_count >= min_calls:
                    entry_type = self._determine_entry_type(info)
                    entry_points.append(
                        (
                            method,
                            call_count,
                            info.get("class", ""),
                            entry_type,
                            info.get("annotations", ""),
                            info.get("visibility", ""),
                        )
                    )

        # エントリータイプと呼び出し数でソート
        entry_points.sort(key=lambda x: (self._entry_priority(x[3]), -x[1]))

        # 結果を表示
        if not entry_points:
            print("エントリーポイント候補が見つかりませんでした")
            return

        for i, (
            method,
            call_count,
            class_name,
            entry_type,
            annotations,
            visibility,
        ) in enumerate(entry_points, 1):
            print(f"{i}. {method}")
            print(f"   クラス: {class_name}")
            print(f"   種別: {entry_type}")
            print(f"   可視性: {visibility}")
            if annotations:
                print(f"   メソッドアノテーション: {annotations}")

            # クラスアノテーションも表示
            info = self.method_info.get(method, {})
            class_annotations = info.get("class_annotations", "")
            if class_annotations:
                print(f"   クラスアノテーション: {class_annotations}")

            print(f"   呼び出し数: {call_count}")
            print()

    def _determine_entry_type(self, info: dict) -> str:
        """エントリーポイントの種別を判定"""
        # まず、解析時に判定されたエントリータイプを使用
        entry_type = info.get("entry_type", "")
        if entry_type:
            type_map = {
                "Main": "Main Method",
                "Test": "Test Method",
                "HTTP": "HTTP Endpoint",
                "SOAP": "SOAP Endpoint",
                "Scheduled": "Scheduled Job",
                "Event": "Event Listener",
                "Lifecycle": "Lifecycle Method",
                "Servlet": "Servlet",
                "SpringBoot": "Spring Boot Runner",
                "Thread": "Runnable/Callable",
                "Bean": "Bean Factory",
            }
            return type_map.get(entry_type, entry_type)

        # フォールバック：アノテーションから判定
        annotations = info.get("annotations", "")
        class_annotations = info.get("class_annotations", "")

        # main メソッド
        if info.get("is_static") and "main" in info.get("class", "").lower():
            return "Main Method"

        # テストメソッド
        if any(
            test in annotations
            for test in [
                "Test",
                "TestTemplate",
                "ParameterizedTest",
                "RepeatedTest",
                "TestFactory",
            ]
        ):
            return "Test Method"

        # Spring Controller
        if any(
            ctrl in annotations
            for ctrl in [
                "RequestMapping",
                "GetMapping",
                "PostMapping",
                "PutMapping",
                "DeleteMapping",
                "PatchMapping",
            ]
        ):
            return "HTTP Endpoint (Spring)"

        # クラスレベルのController
        if any(ctrl in class_annotations for ctrl in ["Controller", "RestController"]):
            return "HTTP Endpoint (Spring)"

        # JAX-RS REST API
        if any(
            jax in annotations
            for jax in ["Path", "GET", "POST", "PUT", "DELETE", "PATCH"]
        ):
            return "HTTP Endpoint (JAX-RS)"

        # SOAP Webサービス
        if "WebMethod" in annotations:
            return "SOAP Endpoint (JAX-WS)"

        if any(ws in class_annotations for ws in ["WebService", "WebServiceProvider"]):
            return "SOAP Endpoint (JAX-WS)"

        # Scheduled Job
        if any(sched in annotations for sched in ["Scheduled", "Schedules", "Async"]):
            return "Scheduled Job"

        # Event Listener
        if any(
            evt in annotations
            for evt in [
                "EventListener",
                "TransactionalEventListener",
                "JmsListener",
                "RabbitListener",
                "KafkaListener",
                "StreamListener",
                "MessageMapping",
                "SubscribeMapping",
            ]
        ):
            return "Event Listener"

        # Lifecycle
        if any(
            lc in annotations
            for lc in [
                "PostConstruct",
                "PreDestroy",
                "BeforeAll",
                "AfterAll",
                "BeforeEach",
                "AfterEach",
                "Before",
                "After",
                "BeforeClass",
                "AfterClass",
            ]
        ):
            return "Lifecycle Method"

        # Bean Factory Method
        if "Bean" in annotations:
            return "Bean Factory"

        # Servlet
        if "WebServlet" in annotations or "WebServlet" in class_annotations:
            return "Servlet"

        # その他のpublicメソッド
        if info.get("visibility") == "public":
            return "Public Method"

        return "Unknown"

    def _entry_priority(self, entry_type: str) -> int:
        """エントリータイプの優先順位（小さいほど優先度が高い）"""
        priority_map = {
            "Main Method": 1,
            "HTTP Endpoint (Spring)": 2,
            "HTTP Endpoint (JAX-RS)": 3,
            "SOAP Endpoint (JAX-WS)": 4,
            "Servlet": 5,
            "Spring Boot Runner": 6,
            "Scheduled Job": 7,
            "Event Listener": 8,
            "Runnable/Callable": 9,
            "Lifecycle Method": 10,
            "Test Method": 11,
            "Bean Factory": 12,
            "Public Method": 13,
            "Unknown": 14,
        }
        return priority_map.get(entry_type, 14)

    def search_methods(self, keyword: str):
        """キーワードでメソッドを検索"""
        print(f"\n検索結果: '{keyword}'")
        print(f"{'=' * 80}\n")

        matches = []
        for method in self.method_info.keys():
            if keyword.lower() in method.lower():
                info = self.method_info[method]
                matches.append((method, info.get("class", "")))

        if not matches:
            print("該当するメソッドが見つかりませんでした")
            return

        for i, (method, class_name) in enumerate(matches, 1):
            print(f"{i}. {method}")
            print(f"   クラス: {class_name}")
            print()


def main():
    if len(sys.argv) < 2:
        print("使い方:")
        print("  python call_tree_visualizer.py <TSVファイル> [オプション]")
        print("\nオプション:")
        print("  --list [--strict]   エントリーポイント候補を表示")
        print("                      --strict: アノテーション等で厳密に判定")
        print("  --search <keyword>  キーワードでメソッドを検索")
        print("  --forward <method>  指定メソッドからの呼び出しツリーを表示")
        print("  --reverse <method>  指定メソッドへの呼び出し元ツリーを表示")
        print("  --export <method> <o> [format]  ツリーをファイルにエクスポート")
        print("                      format: text, markdown, html (default: text)")
        print("  --depth <n>         ツリーの最大深度 (default: 10)")
        print("  --min-calls <n>     エントリーポイントの最小呼び出し数 (default: 1)")
        print("  --no-follow-impl    実装クラス候補を追跡しない")
        print("\n例:")
        print("  python call_tree_visualizer.py call-tree.tsv --list --strict")
        print("  python call_tree_visualizer.py call-tree.tsv --list --min-calls 5")
        print(
            "  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Main#main(String[])'"
        )
        print(
            "  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Service#process()' --no-follow-impl"
        )
        print(
            "  python call_tree_visualizer.py call-tree.tsv --export 'com.example.Main#main(String[])' tree.html html"
        )
        sys.exit(1)

    tsv_file = sys.argv[1]
    visualizer = CallTreeVisualizer(tsv_file)

    max_depth = 10
    min_calls = 1
    strict = "--strict" in sys.argv
    follow_implementations = "--no-follow-impl" not in sys.argv

    # 深度オプションの処理
    if "--depth" in sys.argv:
        idx = sys.argv.index("--depth")
        if idx + 1 < len(sys.argv):
            max_depth = int(sys.argv[idx + 1])

    # 最小呼び出し数オプションの処理
    if "--min-calls" in sys.argv:
        idx = sys.argv.index("--min-calls")
        if idx + 1 < len(sys.argv):
            min_calls = int(sys.argv[idx + 1])

    # コマンド処理
    if "--list" in sys.argv:
        visualizer.list_entry_points(min_calls, strict)

    elif "--search" in sys.argv:
        idx = sys.argv.index("--search")
        if idx + 1 < len(sys.argv):
            keyword = sys.argv[idx + 1]
            visualizer.search_methods(keyword)

    elif "--forward" in sys.argv:
        idx = sys.argv.index("--forward")
        if idx + 1 < len(sys.argv):
            method = sys.argv[idx + 1]
            visualizer.print_forward_tree(
                method, max_depth, follow_implementations=follow_implementations
            )

    elif "--reverse" in sys.argv:
        idx = sys.argv.index("--reverse")
        if idx + 1 < len(sys.argv):
            method = sys.argv[idx + 1]
            visualizer.print_reverse_tree(method, max_depth)

    elif "--export" in sys.argv:
        idx = sys.argv.index("--export")
        if idx + 2 < len(sys.argv):
            method = sys.argv[idx + 1]
            output_file = sys.argv[idx + 2]
            format = sys.argv[idx + 3] if idx + 3 < len(sys.argv) else "text"
            visualizer.export_tree_to_file(
                method, output_file, max_depth, format, follow_implementations
            )

    else:
        print("オプションを指定してください。--help で使い方を確認できます")


if __name__ == "__main__":
    main()
