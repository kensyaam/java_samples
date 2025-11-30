#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
呼び出しツリー可視化スクリプト
TSVファイルから呼び出しツリーを生成します
"""

import csv
import re
import sys
from collections import defaultdict
from typing import Dict, List, Optional, Set

import openpyxl
from openpyxl.styles import Font
from openpyxl.utils import column_index_from_string, get_column_letter


class CallTreeVisualizer:
    def __init__(self, tsv_file: str):
        self.tsv_file: str = tsv_file
        self.forward_calls: Dict[str, List[Dict[str, str]]] = defaultdict(list)
        self.reverse_calls: Dict[str, List[str]] = defaultdict(list)
        self.method_info: Dict[str, Dict[str, Optional[str]]] = {}
        self.class_info: Dict[str, List[str]] = {}
        self.load_data()

    def load_data(self):
        """TSVファイルを読み込む"""
        with open(self.tsv_file, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f, delimiter="\t")
            for row in reader:
                caller = row["呼び出し元メソッド"]
                callee = row["呼び出し先メソッド"]
                direction = row["方向"]

                # print(f"row: {row}")

                # メソッド情報を保存
                if caller and (
                    caller not in self.method_info
                    or self.method_info[caller]["visibility"] == ""
                ):
                    if caller not in self.method_info:
                        self.method_info[caller] = {}
                    self.method_info[caller] |= {
                        "class": row["呼び出し元クラス"],
                        "parent": row["呼び出し元の親クラス"],
                        "visibility": row.get("可視性", ""),
                        "is_static": row.get("Static", "") == "Yes",
                        "is_entry_point": row.get("エントリーポイント候補", "")
                        == "Yes",
                        "entry_type": row.get("エントリータイプ", ""),
                        "annotations": row.get("アノテーション", ""),
                        "class_annotations": row.get("クラスアノテーション", ""),
                    }

                    # クラス階層情報を保存
                    if row["呼び出し元クラス"]:
                        parents = [
                            p.strip()
                            for p in row["呼び出し元の親クラス"].split(",")
                            if p.strip()
                        ]
                        self.class_info[row["呼び出し元クラス"]] = parents

                if callee:
                    if callee not in self.method_info:
                        self.method_info[callee] = {
                            "class": row["呼び出し先クラス"],
                            "parent": "",
                            "sql": (
                                row.get("SQL文", "") if direction == "Forward" else ""
                            ),
                            "visibility": "",
                            "is_static": False,
                            "is_entry_point": False,
                            "annotations": "",
                            "javadoc": (
                                row.get("メソッドJavadoc", "")
                                if direction == "Forward"
                                else ""
                            ),
                        }
                    else:
                        if not self.method_info[callee].get("sql"):
                            # SQL文は呼び出し先の情報に基づく
                            self.method_info[callee] |= {"sql": row.get("SQL文", "")}
                        if not self.method_info[callee].get("javadoc"):
                            self.method_info[callee] |= {
                                "javadoc": row.get("メソッドJavadoc", "")
                            }

                    # クラス階層情報を保存（呼び出し先）
                    callee_class = row["呼び出し先クラス"]
                    callee_parents_str = row.get("呼び出し先の親クラス", "")
                    if callee_class:
                        parents = [
                            p.strip() for p in callee_parents_str.split(",") if p.strip()
                        ]
                        if callee_class not in self.class_info or not self.class_info[callee_class]:
                            self.class_info[callee_class] = parents

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
        max_depth: int = 50,
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

        visited: set[str] = set()
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
        self,
        target_method: str,
        max_depth: int = 50,
        show_class: bool = True,
        follow_overrides: bool = True,
    ):
        """呼び出し先からのツリー（誰がこのメソッドを呼んでいるか）を表示

        Args:
            target_method: 対象メソッド
            max_depth: 最大深度
            show_class: クラス情報を表示するか
            follow_overrides: オーバーライド元/インターフェースメソッドも追跡するか
        """
        print(f"\n{'=' * 80}")
        print(f"逆引きツリー (対象: {target_method})")
        print(f"{'=' * 80}\n")

        visited: set[str] = set()
        self._print_reverse_tree_recursive(
            target_method, 0, max_depth, visited, show_class, follow_overrides
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

                indent = "    " * (depth + 1)

                # 親クラスメソッドの情報を表示
                if callee_info["is_parent_method"] == "Yes":
                    print(f"{indent}↓ [親クラスメソッド]")

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

                # 実装クラス候補の情報を表示
                if callee_info["implementations"]:
                    implementations = [
                        impl.strip()
                        for impl in callee_info["implementations"].split(",")
                        if impl.strip()
                    ]

                    annotations = []
                    for impl_class_info in implementations:
                        annotations.append(f"実装: {impl_class_info}")

                    for annotation in annotations:
                        indent = "    " * (depth + 1)
                        print(f"{indent}↑ [{annotation}]")

                    # 実装クラス候補がある場合、それらも追跡
                    if follow_implementations:
                        # implementationsの各要素は、「<クラス名> + " [<追加情報>]"」の形式かもしれないので、クラス名だけ抽出
                        implementations = [
                            impl.split(" ")[0] for impl in implementations
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

    def _print_reverse_tree_recursive(
        self,
        method: str,
        depth: int,
        max_depth: int,
        visited: Set[str],
        show_class: bool,
        follow_overrides: bool,
    ):
        """逆引きツリーを再帰的に表示"""
        if depth > max_depth:
            return

        # 循環参照チェック
        if method in visited:
            self._print_node(method, depth, show_class, False, is_circular=True)
            return

        visited.add(method)
        self._print_node(method, depth, show_class, False)

        callers = self.reverse_calls.get(method, [])

        # 呼び出し元がない場合、オーバーライド元/インターフェースメソッドを探す
        if not callers and follow_overrides:
            parent_methods = self._find_parent_methods(method)
            if parent_methods:
                indent = "    " * (depth)
                print(f"{indent}↓ [オーバーライド元/インターフェースメソッドを展開]")
                for parent_method in parent_methods:
                    self._print_reverse_tree_recursive(
                        parent_method,
                        depth,
                        max_depth,
                        visited.copy(),
                        show_class,
                        follow_overrides,
                    )
        else:
            # 通常の呼び出し元を表示
            for caller in callers:
                self._print_reverse_tree_recursive(
                    caller,
                    depth + 1,
                    max_depth,
                    visited.copy(),
                    show_class,
                    follow_overrides,
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
            class_name = info.get("class", "")
            print(f"{indent}    クラス: {class_name}")
            if info.get("parent"):
                parent_class = info.get("parent", "")
                print(f"{indent}    親クラス: {parent_class}")

        # SQL情報を表示（全文表示）
        if show_sql and info.get("sql"):
            sql_text = info.get("sql", "") or ""
            print(f"{indent}    SQL: {sql_text}")

    def _find_parent_methods(self, method: str) -> List[str]:
        """メソッドのオーバーライド元/インターフェースメソッドを探す

        Args:
            method: 対象メソッドのシグネチャ

        Returns:
            親メソッドのシグネチャのリスト
        """
        if "#" not in method:
            return []

        info = self.method_info.get(method, {})
        method_part = method.split("#", 1)[1]  # メソッド名+引数部分
        parent_classes_str = info.get("parent", "")

        if not parent_classes_str:
            return []

        # 親クラスをカンマで分割
        parent_classes = [p.strip() for p in parent_classes_str.split(",") if p.strip()]

        parent_methods = []
        for parent_class in parent_classes:
            # 親クラスの同じシグネチャのメソッドを探す
            for method_sig, method_info in self.method_info.items():
                if method_info.get("class") == parent_class:
                    if "#" in method_sig:
                        sig_method_part = method_sig.split("#", 1)[1]
                        if sig_method_part == method_part:
                            parent_methods.append(method_sig)

        return parent_methods

    def _find_implementation_method(
        self, abstract_method: str, impl_class: str
    ) -> Optional[str]:
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
        # 1. 直接の実装を探す
        for method_sig, info in self.method_info.items():
            if info.get("class") == impl_class:
                if "#" in method_sig:
                    sig_method_part = method_sig.split("#", 1)[1]
                    if sig_method_part == method_part:
                        return method_sig

        # 2. 親クラスを辿って実装を探す
        current_class = impl_class
        visited_classes = set()
        
        while current_class:
            if current_class in visited_classes:
                break
            visited_classes.add(current_class)
            
            parents = self.class_info.get(current_class, [])
            if not parents:
                break
                
            # 親クラス（インターフェース含む）の中から実装を探す
            # 優先順位: クラス > インターフェース だが、ここでは見つかった順
            # Javaの単一継承を考えると、親クラスは1つ（+インターフェース複数）
            # ここでは単純に見つかったものを返す
            found_in_parent = False
            for parent in parents:
                # 親クラスでメソッドを探す
                for method_sig, info in self.method_info.items():
                    if info.get("class") == parent:
                        if "#" in method_sig:
                            sig_method_part = method_sig.split("#", 1)[1]
                            if sig_method_part == method_part:
                                return method_sig
                
                # 見つからなければ、次のループのために親クラスを更新したいが、
                # 複数の親（インターフェース）があるため、幅優先探索すべきか？
                # ここでは単純化して、最初の親（おそらくスーパークラス）を優先して探索する
                # ただし、parentsリストの順序は保証されていないかもしれない
                pass

            # 幅優先探索で次の階層へ
            # parents の中から次の current_class を選ぶ必要があるが、
            # ここでは簡易的に、parents のすべてを次の探索候補にするBFSにする
            
            # 上のループで return していないので、ここに来たということは
            # 直近の親には実装がない。
            # 次の階層（親の親）を探す
            
            # BFS queue logic implementation
            # Since we are inside a while loop designed for single path, let's refactor to BFS queue
            break # Break inner loop to switch to BFS structure below
        
        # BFSで親クラスを探索
        queue = list(self.class_info.get(impl_class, []))
        visited_classes = {impl_class}
        
        while queue:
            current_parent = queue.pop(0)
            if current_parent in visited_classes:
                continue
            visited_classes.add(current_parent)
            
            # この親クラスにメソッドがあるか確認
            for method_sig, info in self.method_info.items():
                if info.get("class") == current_parent:
                    if "#" in method_sig:
                        sig_method_part = method_sig.split("#", 1)[1]
                        if sig_method_part == method_part:
                            return method_sig
            
            # 次の親を追加
            queue.extend(self.class_info.get(current_parent, []))

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
                    # implementationsの各要素は、「<クラス名> + " [<追加情報>]"」の形式かもしれないので、クラス名だけ抽出
                    implementations = [impl.split(" ")[0] for impl in implementations]

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
            strict: True の場合、アノテーションやmainメソッドなど厳密に判定（デフォルト）
        """
        print(f"\n{'=' * 80}")
        if strict:
            print("エントリーポイント候補（厳密モード - デフォルト）")
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
                    entry_type = self._determine_entry_type(method, info)
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
                    entry_type = self._determine_entry_type(method, info)
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

            # HTTP / SOAP の場合、アノテーション等からエンドポイントの path を抽出して表示
            if entry_type and ("HTTP Endpoint" in entry_type or "SOAP" in entry_type):
                endpoint_path = self._extract_endpoint_path(info)
                if endpoint_path:
                    print(f"   エンドポイント: {endpoint_path}")

            print(f"   呼び出し数: {call_count}")
            print()

    def _extract_endpoint_path(self, info: dict) -> str:
        """アノテーションやクラスアノテーションからエンドポイントの path を抽出する

        戻り値: 見つかれば path（例: /api/foo や https://... など）、見つからなければ空文字
        """
        if not info:
            return ""

        text = ""
        text += str(info.get("annotations", "")) + " "
        text += str(info.get("class_annotations", ""))

        # よく使われるマッピングアノテーションのパターン
        patterns = [
            r"\w*Mapping\(\s*[\"']([^\"']+)[\"']",  # GetMapping("/x"), RequestMapping("/x") 等
            r"RequestMapping\(.*?path\s*=\s*[\"']([^\"']+)[\"']",  # path = "/x"
            r"RequestMapping\(.*?value\s*=\s*[\"']([^\"']+)[\"']",  # value = "/x"
            r"Path\(\s*[\"']([^\"']+)[\"']",  # JAX-RS @Path
            r"[\"'](\/[^\"']+)[\"']",  # 汎用: 引用された /... パス
            r"[\"'](https?://[^\"']+)[\"']",  # フルURL
        ]

        for p in patterns:
            m = re.search(p, text)
            if m:
                return m.group(1)

        # SOAP系: class アノテーションに serviceName や targetNamespace があれば返す
        m2 = re.search(
            r"(?:serviceName|targetNamespace)\s*=\s*[\"']([^\"']+)[\"']", text
        )
        if m2:
            return m2.group(1)

        return ""

    def _determine_entry_type(self, method: str, info: dict) -> str:
        """エントリーポイントの種別を判定"""
        candidates = []

        # 1. 解析時に判定されたエントリータイプ
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
            candidates.append(type_map.get(entry_type, entry_type))

        # 2. アノテーションから判定
        entry_type = self._check_entry_type_from_info(info)
        if entry_type:
            candidates.append(entry_type)

        # 3. 親メソッド（インターフェース/スーパークラス）のアノテーションを確認
        parent_methods = self._find_parent_methods(method)
        for parent_method in parent_methods:
            parent_info = self.method_info.get(parent_method, {})
            if parent_info:
                entry_type = self._check_entry_type_from_info(parent_info)
                if entry_type:
                    candidates.append(entry_type)

        if not candidates:
            return ""

        # 優先順位でソート（数値が小さい方が優先）
        candidates.sort(key=lambda x: self._entry_priority(x))
        return candidates[0]

    def _check_entry_type_from_info(self, info: dict) -> str:
        """メソッド情報からエントリータイプを判定（ヘルパー）"""
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

    def export_tree_to_excel(
        self,
        entry_points_file: Optional[str],
        output_file: str,
        max_depth: int = 20,
        follow_implementations: bool = True,
    ):
        """Excel形式でツリーをエクスポート

        以下のフォーマットで出力する。
        - A列：エントリーポイントのメソッド（fully qualified name）
        - B列：呼び出し先メソッド（fully qualified name）
        - C列：呼び出し先メソッドのパッケージ名
        - D列：呼び出し先メソッドのクラス名
        - E列：呼び出し先メソッドのメソッド名以降
        - F列：呼び出し先メソッドのJavadoc
        - G列：呼び出し先が呼び元の親クラスのメソッドなら"親クラス"、実装クラスへ展開したものなら"実装クラスへの展開"
        - H列：SQL文がある場合はSQL文
        - L列以降：呼び出しツリー
        """
        entry_points: List[str] = []

        if entry_points_file:
            with open(entry_points_file, "r", encoding="utf-8") as f:
                entry_points = [line.strip() for line in f if line.strip()]
        else:
            all_callees = set()
            for callees in self.forward_calls.values():
                for callee_info in callees:
                    all_callees.add(callee_info["method"])

            for method, info in self.method_info.items():
                if method not in all_callees and info.get("is_entry_point"):
                    entry_points.append(method)

        wb: openpyxl.Workbook = openpyxl.Workbook()
        ws: openpyxl.worksheet.worksheet.Worksheet = wb.active
        if not isinstance(ws, openpyxl.worksheet.worksheet.Worksheet):
            raise TypeError("Active sheet is not a Worksheet")

        font = Font(name="游ゴシック等幅")

        headers = [
            "エントリーポイント",
            "呼び出し先メソッド",
            "パッケージ名",
            "クラス名",
            "メソッド名以降",
            "Javadoc",
            "呼び出し先タイプ",
            "SQL文",
        ]
        ws.append(headers)

        for col in ws.iter_cols(min_row=1, max_row=1):
            for cell in col:
                cell.font = font

        # -----------------------------------------
        # L列以降の列幅を5に設定
        # （例: L列から40列分）
        # -----------------------------------------
        tree_start_col: int = column_index_from_string("L")
        for col_idx in range(tree_start_col, tree_start_col + 40):
            letter = get_column_letter(col_idx)
            ws.column_dimensions[letter].width = 3

        current_row_idx = 2  # データ開始行

        def write_tree(
            start_row_idx: int,
            method: str,
            depth: int,
            parent_type: str = "",
        ) -> None:
            """呼び出しツリーを書き込む再帰関数
            Args:
                ws: 書き込み先のワークシート
                start_row_idx: 書き込み開始行
                method: 現在のメソッド
                depth: 現在の深度
                parent_type: 呼び出し元が親クラスメソッドの場合のタイプ表記
            """
            nonlocal current_row_idx

            if depth > max_depth:
                return

            if depth == 0:
                pass

            callees = self.forward_calls.get(method, [])
            for callee_info in callees:
                callee = callee_info["method"]
                info = self.method_info.get(callee, {})

                # -----------------------------------------
                # 行ヘッダ部を書き込む
                # -----------------------------------------
                row_header = [
                    method if depth == 0 else "",
                    callee,
                    (info.get("class") or "").rsplit(".", 1)[0],
                    info.get("class", ""),
                    callee.split("#", 1)[-1],
                    info.get("javadoc", ""),
                    (
                        "親クラス"
                        if callee_info["is_parent_method"] == "Yes"
                        else parent_type
                    ),
                    info.get("sql", ""),
                ]
                for col_idx, value in enumerate(row_header, start=1):
                    cell = ws.cell(row=current_row_idx, column=col_idx, value=value)
                    cell.font = font

                # -----------------------------------------
                # ツリー部を書き込む
                # -----------------------------------------
                col_idx = tree_start_col + depth  # 階層に応じて右にずらす（L, M, N...）
                value = method if depth == 0 else callee
                cell = ws.cell(row=current_row_idx, column=col_idx, value=value)
                cell.font = font
                print(
                    f"Writing tree - row: {current_row_idx} depth: {depth} method: {value}"
                )

                current_row_idx += 1

                # 実装クラス候補がある場合
                if follow_implementations and callee_info["implementations"]:
                    implementations = [
                        impl.strip()
                        for impl in callee_info["implementations"].split(",")
                        if impl.strip()
                    ]
                    for impl_class in implementations:
                        impl_method = self._find_implementation_method(
                            callee, impl_class
                        )
                        if impl_method:
                            write_tree(
                                current_row_idx,
                                impl_method,
                                depth + 1,
                                "実装クラスへの展開",
                            )

                # 再帰的に呼び出しツリーを記載
                write_tree(
                    current_row_idx,
                    callee,
                    depth + 1,
                )

            return

        # エントリーポイントごとにツリーを出力
        for entry_point in entry_points:
            write_tree(
                current_row_idx,
                entry_point,
                0,
            )

        # Excelファイルの保存
        wb.save(output_file)
        print(f"ツリーを {output_file} にエクスポートしました")


def main():
    if len(sys.argv) < 2:
        print("使い方:")
        print("  python call_tree_visualizer.py <TSVファイル> [オプション]")
        print("\nオプション:")
        print("  --list [--no-strict]  エントリーポイント候補を表示")
        print("                        デフォルトは厳密モード、--no-strictで緩和")
        print("  --search <keyword>  キーワードでメソッドを検索")
        print("  --forward <method>  指定メソッドからの呼び出しツリーを表示")
        print("  --reverse <method>  指定メソッドへの呼び出し元ツリーを表示")
        print("  --export <method> <o> [format]  ツリーをファイルにエクスポート")
        print("                      format: text, markdown, html (default: text)")
        print(
            "  --export-excel <entry_points_file|- > <output_file>  ツリーをExcelにエクスポート"
        )
        print("  --depth <n>         ツリーの最大深度 (default: 50)")
        print("  --min-calls <n>     エントリーポイントの最小呼び出し数 (default: 1)")
        print("  --no-follow-impl    実装クラス候補を追跡しない")
        print("  --no-follow-override  逆引き時にオーバーライド元を追跡しない")
        print("\n例:")
        print("  python call_tree_visualizer.py call-tree.tsv --list")
        print(
            "  python call_tree_visualizer.py call-tree.tsv --list --no-strict --min-calls 5"
        )
        print(
            "  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Main#main(String[])'"
        )
        print(
            "  python call_tree_visualizer.py call-tree.tsv --reverse 'com.example.Service#process()'"
        )
        print(
            "  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Service#process()' --no-follow-impl"
        )
        print(
            "  python call_tree_visualizer.py call-tree.tsv --export 'com.example.Main#main(String[])' tree.html html"
        )
        print(
            "  python call_tree_visualizer.py call-tree.tsv --export-excel entry_points.txt call_trees.xlsx"
        )
        print(
            "  python call_tree_visualizer.py call-tree.tsv --export-excel - call_trees.xlsx"
        )
        sys.exit(1)

    tsv_file = sys.argv[1]
    visualizer = CallTreeVisualizer(tsv_file)

    max_depth = 50
    min_calls = 1
    strict = "--no-strict" not in sys.argv  # デフォルトは厳密モード
    follow_implementations = "--no-follow-impl" not in sys.argv
    follow_overrides = "--no-follow-override" not in sys.argv

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
            visualizer.print_reverse_tree(
                method, max_depth, follow_overrides=follow_overrides
            )

    elif "--export" in sys.argv:
        idx = sys.argv.index("--export")
        if idx + 2 < len(sys.argv):
            method = sys.argv[idx + 1]
            output_file = sys.argv[idx + 2]
            format = sys.argv[idx + 3] if idx + 3 < len(sys.argv) else "text"
            visualizer.export_tree_to_file(
                method, output_file, max_depth, format, follow_implementations
            )

    elif "--export-excel" in sys.argv:
        idx = sys.argv.index("--export-excel")
        if idx + 2 < len(sys.argv):
            entry_points_file = sys.argv[idx + 1] if sys.argv[idx + 1] != "-" else None
            output_file = sys.argv[idx + 2]
            visualizer.export_tree_to_excel(
                entry_points_file, output_file, max_depth, follow_implementations
            )

    else:
        print("オプションを指定してください。--help で使い方を確認できます")


if __name__ == "__main__":
    main()
