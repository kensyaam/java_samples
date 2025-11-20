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
                        "sql": row["SQL文"],
                    }

                if callee and callee not in self.method_info:
                    self.method_info[callee] = {
                        "class": row["呼び出し先クラス"],
                        "parent": "",
                        "sql": "",
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
    ):
        """呼び出し元からのツリーを表示"""
        print(f"\n{'='*80}")
        print(f"呼び出しツリー (起点: {root_method})")
        print(f"{'='*80}\n")

        visited = set()
        self._print_tree_recursive(
            root_method, 0, max_depth, visited, show_class, show_sql, is_forward=True
        )

    def print_reverse_tree(
        self, target_method: str, max_depth: int = 10, show_class: bool = True
    ):
        """呼び出し先からのツリー（誰がこのメソッドを呼んでいるか）を表示"""
        print(f"\n{'='*80}")
        print(f"逆引きツリー (対象: {target_method})")
        print(f"{'='*80}\n")

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

                self._print_tree_recursive(
                    callee,
                    depth + 1,
                    max_depth,
                    visited.copy(),
                    show_class,
                    show_sql,
                    is_forward,
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

    def export_tree_to_file(
        self,
        root_method: str,
        output_file: str,
        max_depth: int = 10,
        format: str = "text",
    ):
        """ツリーをファイルにエクスポート"""
        if format == "text":
            self._export_text_tree(root_method, output_file, max_depth)
        elif format == "markdown":
            self._export_markdown_tree(root_method, output_file, max_depth)
        elif format == "html":
            self._export_html_tree(root_method, output_file, max_depth)

    def _export_text_tree(self, root_method: str, output_file: str, max_depth: int):
        """テキスト形式でエクスポート"""
        with open(output_file, "w", encoding="utf-8") as f:
            original_stdout = sys.stdout
            sys.stdout = f
            self.print_forward_tree(root_method, max_depth)
            sys.stdout = original_stdout
        print(f"ツリーを {output_file} にエクスポートしました")

    def _export_markdown_tree(self, root_method: str, output_file: str, max_depth: int):
        """Markdown形式でエクスポート"""
        with open(output_file, "w", encoding="utf-8") as f:
            f.write(f"# 呼び出しツリー\n\n")
            f.write(f"**起点メソッド:** `{root_method}`\n\n")
            f.write("```\n")

            original_stdout = sys.stdout
            sys.stdout = f
            self.print_forward_tree(
                root_method, max_depth, show_class=False, show_sql=False
            )
            sys.stdout = original_stdout

            f.write("```\n")
        print(f"ツリーを {output_file} にエクスポートしました")

    def _export_html_tree(self, root_method: str, output_file: str, max_depth: int):
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
    </style>
</head>
<body>
    <h1>呼び出しツリー</h1>
    <p><strong>起点メソッド:</strong> {root_method}</p>
    <ul class="tree">
"""

        visited = set()
        html += self._generate_html_tree(root_method, 0, max_depth, visited)

        html += """
    </ul>
</body>
</html>
"""

        with open(output_file, "w", encoding="utf-8") as f:
            f.write(html)
        print(f"ツリーを {output_file} にエクスポートしました")

    def _generate_html_tree(
        self, method: str, depth: int, max_depth: int, visited: Set[str]
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
                    callee_info["method"], depth + 1, max_depth, visited.copy()
                )
            html += "</ul>"

        html += "</li>"
        return html

    def list_entry_points(self, min_calls: int = 3):
        """エントリーポイント候補をリストアップ（他から呼ばれていないメソッド）"""
        print(f"\n{'=' * 80}")
        print(f"エントリーポイント候補 (呼び出し先が{min_calls}個以上)")
        print(f"{'=' * 80}\n")

        all_callees = set()
        for callees in self.forward_calls.values():
            for callee_info in callees:
                all_callees.add(callee_info["method"])

        entry_points = []
        for caller, callees in self.forward_calls.items():
            if caller not in all_callees and len(callees) >= min_calls:
                info = self.method_info.get(caller, {})
                entry_points.append((caller, len(callees), info.get("class", "")))

        # 呼び出し数でソート
        entry_points.sort(key=lambda x: x[1], reverse=True)

        for i, (method, call_count, class_name) in enumerate(entry_points[:20], 1):
            print(f"{i}. {method}")
            print(f"   クラス: {class_name}")
            print(f"   呼び出し数: {call_count}")
            print()

    def search_methods(self, keyword: str):
        """キーワードでメソッドを検索"""
        print(f"\n検索結果: '{keyword}'")
        print(f"{'='*80}\n")

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
        print("  --list              エントリーポイント候補を表示")
        print("  --search <keyword>  キーワードでメソッドを検索")
        print("  --forward <method>  指定メソッドからの呼び出しツリーを表示")
        print("  --reverse <method>  指定メソッドへの呼び出し元ツリーを表示")
        print("  --export <method> <output> [format]  ツリーをファイルにエクスポート")
        print("                      format: text, markdown, html (default: text)")
        print("  --depth <n>         ツリーの最大深度 (default: 10)")
        print("\n例:")
        print("  python call_tree_visualizer.py call-tree.tsv --list")
        print(
            "  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Main#main(String[])'"
        )
        print(
            "  python call_tree_visualizer.py call-tree.tsv --export 'com.example.Main#main(String[])' tree.html html"
        )
        sys.exit(1)

    tsv_file = sys.argv[1]
    visualizer = CallTreeVisualizer(tsv_file)

    max_depth = 10

    # 深度オプションの処理
    if "--depth" in sys.argv:
        idx = sys.argv.index("--depth")
        if idx + 1 < len(sys.argv):
            max_depth = int(sys.argv[idx + 1])

    # コマンド処理
    if "--list" in sys.argv:
        visualizer.list_entry_points()

    elif "--search" in sys.argv:
        idx = sys.argv.index("--search")
        if idx + 1 < len(sys.argv):
            keyword = sys.argv[idx + 1]
            visualizer.search_methods(keyword)

    elif "--forward" in sys.argv:
        idx = sys.argv.index("--forward")
        if idx + 1 < len(sys.argv):
            method = sys.argv[idx + 1]
            visualizer.print_forward_tree(method, max_depth)

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
            visualizer.export_tree_to_file(method, output_file, max_depth, format)

    else:
        print("オプションを指定してください。--help で使い方を確認できます")


if __name__ == "__main__":
    main()
