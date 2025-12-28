#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
呼び出しツリー可視化スクリプト
TSVファイルから呼び出しツリーを生成します
"""

import csv
import json
import re
import sys
from collections import defaultdict
from typing import Dict, List, Optional, Set

import openpyxl
from openpyxl.styles import Font
from openpyxl.utils import column_index_from_string, get_column_letter

# Git Bash上でパイプを使うと、stdoutがCP932として扱われるのを防ぐ
sys.stdout.reconfigure(encoding="utf-8")


class ExclusionRuleManager:
    """除外ルールを管理するクラス"""

    def __init__(self, exclusion_file: Optional[str] = None):
        """
        コンストラクタ

        Args:
            exclusion_file: 除外ルールファイルのパス（Noneの場合はデフォルトファイルを使用）
        """
        self.include_exclusions: Set[str] = set()  # Iモード: 対象自体を除外
        self.exclude_children: Set[str] = set()  # Eモード: 配下のみ除外

        # デフォルトファイル名
        if exclusion_file is None:
            exclusion_file = "exclusion_rules.txt"

        # ファイルが存在する場合のみ読み込む
        import os

        if os.path.exists(exclusion_file):
            self.load_rules(exclusion_file)

    def load_rules(self, file_path: str) -> None:
        """
        除外ルールをファイルから読み込む

        Args:
            file_path: 除外ルールファイルのパス
        """
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                for line_num, line in enumerate(f, 1):
                    line = line.strip()
                    if not line or line.startswith("#"):
                        # 空行とコメント行はスキップ
                        continue

                    parts = line.split("\t")
                    if len(parts) != 2:
                        print(
                            f"警告: 行 {line_num} のフォーマットが不正です: {line}",
                            file=sys.stderr,
                        )
                        continue

                    target, mode = parts
                    target = target.strip()
                    mode = mode.strip().upper()

                    if mode == "I":
                        self.include_exclusions.add(target)
                    elif mode == "E":
                        self.exclude_children.add(target)
                    else:
                        print(
                            f"警告: 行 {line_num} の除外モードが不正です: {mode} (I または E を指定してください)",
                            file=sys.stderr,
                        )

            print(f"除外ルールを読み込みました: {file_path}", file=sys.stderr)
            print(
                f"  Iモード(対象除外): {len(self.include_exclusions)} 件",
                file=sys.stderr,
            )
            print(
                f"  Eモード(配下除外): {len(self.exclude_children)} 件", file=sys.stderr
            )

        except Exception as e:
            print(f"除外ルールファイルの読み込みに失敗しました: {e}", file=sys.stderr)

    def should_include(self, method_or_class: str) -> bool:
        """
        メソッド/クラスがIモード（対象自体を除外）の対象かチェック

        Args:
            method_or_class: メソッドシグネチャまたはクラス名

        Returns:
            True: 表示すべき（除外対象ではない）
            False: 除外すべき（除外対象）
        """
        # メソッドシグネチャ全体が除外対象か
        if method_or_class in self.include_exclusions:
            return False

        # クラス名を抽出して除外対象かチェック
        class_name = self._extract_class_name(method_or_class)
        if class_name and class_name in self.include_exclusions:
            return False

        # クラス名を除くメソッド部分のみを抽出して除外対象かチェック
        method_part = self._extract_method_part(method_or_class)
        if method_part and method_part in self.include_exclusions:
            return False

        return True

    def should_exclude_children(self, method_or_class: str) -> bool:
        """
        メソッド/クラスがEモード（配下のみ除外）の対象かチェック

        Args:
            method_or_class: メソッドシグネチャまたはクラス名

        Returns:
            True: 配下を除外すべき
            False: 配下も展開すべき
        """
        # メソッドシグネチャ全体が除外対象か
        if method_or_class in self.exclude_children:
            return True

        # クラス名を抽出して除外対象かチェック
        class_name = self._extract_class_name(method_or_class)
        if class_name and class_name in self.exclude_children:
            return True

        # クラス名を除くメソッド部分のみを抽出して除外対象かチェック
        method_part = self._extract_method_part(method_or_class)
        if method_part and method_part in self.exclude_children:
            return True

        return False

    def _extract_class_name(self, method_signature: str) -> Optional[str]:
        """
        メソッドシグネチャからクラス名を抽出

        Args:
            method_signature: メソッドシグネチャ (例: "com.example.Class#method()")

        Returns:
            クラス名 (例: "com.example.Class")、抽出できない場合はNone
        """
        if "#" in method_signature:
            return method_signature.split("#")[0]
        return None

    def _extract_method_part(self, method_signature: str) -> Optional[str]:
        """
        メソッドシグネチャからクラスを除去したメソッド部分のみを抽出

        Args:
            method_signature: メソッドシグネチャ (例: "com.example.Class#method()")

        Returns:
            クラスを除去したメソッド部分 (例: "method()")、抽出できない場合はNone
        """
        if "#" in method_signature:
            method_part = method_signature.split("#")[1]
            # # 引数部分を除去
            # if "(" in method_part:
            #     return method_part.split("(")[0]
            return method_part
        return None


class CallTreeVisualizer:
    def __init__(
        self,
        input_file: str,
        exclusion_file: Optional[str] = None,
        output_tsv_encoding: str = "Shift_JIS",
    ):
        """
        コンストラクタ

        Args:
            input_file: 入力ファイルのパス（JSONまたはTSV）
            exclusion_file: 除外ルールファイルのパス（Noneの場合はデフォルト）
            output_tsv_encoding: 出力TSVファイルのエンコーディング
        """
        self.input_file: str = input_file
        self.forward_calls: Dict[str, List[Dict[str, str]]] = defaultdict(list)
        self.reverse_calls: Dict[str, List[str]] = defaultdict(list)
        self.method_info: Dict[str, Dict[str, Optional[str]]] = {}
        self.class_info: Dict[str, List[str]] = {}
        self.exclusion_manager: ExclusionRuleManager = ExclusionRuleManager(
            exclusion_file
        )
        self.output_tsv_encoding: str = output_tsv_encoding
        self.load_data()

    def load_data(self):
        """入力ファイルを読み込む（JSON/TSV自動判定）"""
        if self.input_file.lower().endswith(".json"):
            self._load_json_data()
        else:
            self._load_tsv_data()

    def _load_json_data(self):
        """JSON形式（統合形式）からデータを読み込む"""
        with open(self.input_file, "r", encoding="utf-8") as f:
            data = json.load(f)

        methods = data.get("methods", [])
        for method in methods:
            method_sig = method.get("method", "")
            if not method_sig:
                continue

            class_name = method.get("class", "")
            parent_classes_str = method.get("parentClasses", "")

            # メソッド情報を保存
            self.method_info[method_sig] = {
                "class": class_name,
                "parent": parent_classes_str,  # 親クラス情報（カンマ区切り文字列）
                "visibility": method.get("visibility", ""),
                "is_static": method.get("isStatic", False),
                "is_entry_point": method.get("isEntryPoint", False),
                "entry_type": method.get("entryType", ""),
                "annotations": ",".join(method.get("annotations", [])),
                "class_annotations": ",".join(method.get("classAnnotations", [])),
                "sql": " ||| ".join(method.get("sqlStatements", [])),
                "javadoc": method.get("javadoc", ""),
                "hit_words": ",".join(method.get("hitWords", [])),
            }

            # クラス階層情報を保存（parentClassesから取得した全親クラス・インターフェース）
            if class_name and parent_classes_str:
                parents = [p.strip() for p in parent_classes_str.split(",") if p.strip()]
                # 既存の情報がない場合、または新しい情報がある場合は更新
                if class_name not in self.class_info or not self.class_info[class_name]:
                    self.class_info[class_name] = parents
                else:
                    # 既存のリストに新しい親を追加
                    existing = set(self.class_info[class_name])
                    for p in parents:
                        if p not in existing:
                            self.class_info[class_name].append(p)

            # 呼び出し関係を保存（callsから詳細情報を取得）
            calls_data = method.get("calls", [])
            for call_item in calls_data:
                # 新形式：オブジェクト配列
                if isinstance(call_item, dict):
                    callee = call_item.get("method", "")
                    if callee:
                        is_parent = "Yes" if call_item.get("isParentMethod", False) else "No"
                        impls = call_item.get("implementations", "")
                        self.forward_calls[method_sig].append({
                            "method": callee,
                            "is_parent_method": is_parent,
                            "implementations": impls,
                        })
                else:
                    # 後方互換性：文字列配列
                    self.forward_calls[method_sig].append({
                        "method": call_item,
                        "is_parent_method": "No",
                        "implementations": "",
                    })

            # 逆引き呼び出し関係を保存
            for caller in method.get("calledBy", []):
                self.reverse_calls[method_sig].append(caller)


    def _load_tsv_data(self):
        """TSV形式からデータを読み込む（後方互換性）"""
        with open(self.input_file, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f, delimiter="\t")
            for row in reader:
                caller = row["呼び出し元メソッド"]
                callee = row["呼び出し先メソッド"]
                direction = row["方向"]

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
                            p.strip()
                            for p in callee_parents_str.split(",")
                            if p.strip()
                        ]
                        if (
                            callee_class not in self.class_info
                            or not self.class_info[callee_class]
                        ):
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
        final_endpoints: set[str] = set()  # 最終到達点のメソッドを収集
        self._print_reverse_tree_recursive(
            target_method,
            0,
            max_depth,
            visited,
            show_class,
            follow_overrides,
            final_endpoints,
        )

        # 最終到達点のメソッド一覧を表示
        if final_endpoints:
            print(f"\n{'=' * 80}")
            print(f"最終到達点のメソッド一覧 (最上位の呼び元メソッド)")
            print(f"{'=' * 80}\n")
            for endpoint in sorted(final_endpoints):
                print(f"  {endpoint}")
            print()

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

        # Iモード: 除外対象の場合、ノード自体を表示せずスキップ
        if not self.exclusion_manager.should_include(method):
            return

        # 循環参照チェック
        if method in visited:
            self._print_node(method, depth, show_class, show_sql, is_circular=True)
            return

        visited.add(method)
        self._print_node(method, depth, show_class, show_sql)

        # Eモード: 除外対象の場合、配下の展開を停止
        if self.exclusion_manager.should_exclude_children(method):
            indent = "    " * (depth + 1)
            print(f"{indent}[配下の呼び出しを除外]")
            return

        # 子ノードを表示
        if is_forward:
            callees = self.forward_calls.get(method, [])
            for callee_info in callees:
                callee = callee_info["method"]

                indent = "    " * (depth + 1)

                # Iモード: 除外対象の場合、ノード自体を表示せずスキップ
                if not self.exclusion_manager.should_include(callee):
                    continue

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
                        # Iモード: 除外対象の場合、ノード自体を表示せずスキップ
                        impl_class = impl_class_info.split(" ")[0]
                        if not self.exclusion_manager.should_include(impl_class):
                            continue
                        annotations.append(f"実装: {impl_class_info}")

                    for annotation in annotations:
                        indent = "    " * (depth + 1)
                        print(f"{indent}^ [{annotation}]")

                    # 実装クラス候補がある場合、それらも追跡
                    if follow_implementations:
                        # implementationsの各要素は、「<クラス名> + " [<追加情報>]"」の形式かもしれないので、クラス名だけ抽出
                        implementations = [
                            impl.split(" ")[0] for impl in implementations
                        ]

                        # Eモード: 除外対象の場合、実装クラスへの展開を停止
                        if self.exclusion_manager.should_exclude_children(callee):
                            indent = "    " * (depth + 1)
                            print(f"{indent}[実装クラスへの展開を除外]")
                            continue

                        for impl_class in implementations:
                            # 実装クラスの対応するメソッドを探す
                            impl_method = self._find_implementation_method(
                                callee, impl_class
                            )
                            if impl_method:
                                # Iモード: 除外対象の場合、ノード自体を表示せずスキップ
                                if not self.exclusion_manager.should_include(
                                    impl_method
                                ):
                                    continue

                                indent = "    " * (depth + 1)
                                print(f"{indent}> [実装クラスへの展開: {impl_class}]")

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
        final_endpoints: Optional[Set[str]] = None,
    ):
        """逆引きツリーを再帰的に表示"""
        if depth > max_depth:
            return

        # Iモード: 除外対象の場合、ノード自体を表示せずスキップ
        if not self.exclusion_manager.should_include(method):
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
                print(f"{indent}> [オーバーライド元/インターフェースメソッドを展開]")
                for parent_method in parent_methods:
                    self._print_reverse_tree_recursive(
                        parent_method,
                        depth,
                        max_depth,
                        visited.copy(),
                        show_class,
                        follow_overrides,
                        final_endpoints,
                    )
            else:
                # オーバーライド元もない場合は最終到達点
                if final_endpoints is not None:
                    final_endpoints.add(method)
        elif not callers:
            # 呼び出し元がない場合は最終到達点
            if final_endpoints is not None:
                final_endpoints.add(method)
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
                    final_endpoints,
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
        prefix = "|-- " if depth > 0 else ""

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
            break  # Break inner loop to switch to BFS structure below

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

        # Iモード: 除外対象の場合、ノード自体をスキップ
        if not self.exclusion_manager.should_include(method):
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

        # Eモード: 配下の展開を停止
        if self.exclusion_manager.should_exclude_children(method):
            html += '<div class="class-info">[配下の呼び出しを除外]</div>'
            html += "</li>"
            return html

        callees = self.forward_calls.get(method, [])
        if callees:
            html += '<ul class="tree">'
            for callee_info in callees:

                # Iモード: 除外対象の場合、ノード自体を表示せずスキップ
                if not self.exclusion_manager.should_include(callee_info["method"]):
                    continue

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
                            # Iモード: 除外対象の場合、ノード自体を表示せずスキップ
                            if not self.exclusion_manager.should_include(impl_method):
                                continue

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
            print("エントリーポイント候補（厳密モード）")
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
                            info.get("javadoc", ""),
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
                            info.get("javadoc", ""),
                        )
                    )

        # エントリータイプと呼び出し数でソート
        entry_points.sort(key=lambda x: (self._entry_priority(x[3]), -x[1]))

        # 結果を表示
        if not entry_points:
            print("エントリーポイント候補が見つかりませんでした", file=sys.stderr)
            return

        for i, (
            method,
            call_count,
            class_name,
            entry_type,
            annotations,
            visibility,
            javadoc,
        ) in enumerate(entry_points, 1):
            print(f"{i}. {method}")
            print(f"   クラス: {class_name}")
            print(f"   種別: {entry_type}")
            print(f"   Javadoc: {javadoc}")
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

    def list_entry_points_tsv(self, min_calls: int = 1, strict: bool = True) -> None:
        """エントリーポイント候補をTSV形式で出力

        Args:
            min_calls: 最小呼び出し数
            strict: True の場合、アノテーションやmainメソッドなど厳密に判定（デフォルト）
        """
        #  clipでコピーした結果をExcelに貼り付けられるにはShift_JISで出力する
        sys.stdout.reconfigure(encoding=self.output_tsv_encoding)

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
                            info.get("javadoc", ""),
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
                            info.get("javadoc", ""),
                        )
                    )

        # エントリータイプと呼び出し数でソート
        entry_points.sort(key=lambda x: (self._entry_priority(x[3]), -x[1]))

        # TSVヘッダーを出力
        print(
            "メソッド\tクラス\tメソッド名\tエンドポイント\tjavadoc\t種別\tメソッドアノテーション\tクラスアノテーション"
        )

        # 結果をTSV形式で出力
        for (
            method,
            call_count,
            class_name,
            entry_type,
            annotations,
            visibility,
            javadoc,
        ) in entry_points:
            # メソッド名（クラスや引数を含めないメソッド名のみ）を抽出
            method_name_only = ""
            if "#" in method:
                # クラス#メソッド(引数) の形式からメソッド名のみを抽出
                method_part = method.split("#", 1)[1]
                # 引数部分を除去
                if "(" in method_part:
                    method_name_only = method_part.split("(", 1)[0]
                else:
                    method_name_only = method_part
            else:
                # #がない場合はそのまま
                if "(" in method:
                    method_name_only = method.split("(", 1)[0]
                else:
                    method_name_only = method

            # エンドポイントを抽出
            info = self.method_info.get(method, {})
            endpoint_path = ""
            if entry_type and ("HTTP Endpoint" in entry_type or "SOAP" in entry_type):
                endpoint_path = self._extract_endpoint_path(info)

            # クラスアノテーションを取得
            class_annotations = info.get("class_annotations", "")

            # TSV行を出力
            print(
                f"{method}\t{class_name}\t{method_name_only}\t{endpoint_path}\t{javadoc}\t{entry_type}\t{annotations}\t{class_annotations}"
            )

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
            print("該当するメソッドが見つかりませんでした", file=sys.stderr)
            return

        for i, (method, class_name) in enumerate(matches, 1):
            print(f"{i}. {method}")
            print(f"   クラス: {class_name}")
            print()

    def extract_sql_to_files(self, output_dir: str = "./found_sql") -> None:
        """
        SQL文を抽出してファイルに出力

        Args:
            output_dir: SQL出力先ディレクトリ
        """
        import os
        from pathlib import Path

        # 出力ディレクトリを作成
        Path(output_dir).mkdir(parents=True, exist_ok=True)

        # メソッド毎にSQL文を集約
        method_sqls: Dict[str, List[str]] = defaultdict(list)

        for method, info in self.method_info.items():
            sql = info.get("sql", "")
            if sql and sql.strip():
                # SQL文を分割 (複数ある場合は " ||| " で連結されている)
                sqls = [s.strip() for s in sql.split(" ||| ") if s.strip()]
                method_sqls[method].extend(sqls)

        if not method_sqls:
            print("SQL文が見つかりませんでした", file=sys.stderr)
            return

        # ファイル出力
        file_count = 0
        for method, sqls in method_sqls.items():
            safe_name = self._sanitize_method_name(method)

            if len(sqls) == 1:
                filename = f"{safe_name}.sql"
                self._write_sql_file(os.path.join(output_dir, filename), sqls[0])
                file_count += 1
            else:
                for idx, sql in enumerate(sqls, 1):
                    filename = f"{safe_name}_{idx}.sql"
                    self._write_sql_file(os.path.join(output_dir, filename), sql)
                    file_count += 1

        print(f"\n{file_count} 個のSQLファイルを {output_dir} に出力しました")

    def _sanitize_method_name(self, method_signature: str) -> str:
        """
        メソッドシグネチャをファイル名として安全な文字列に変換

        Args:
            method_signature: メソッドシグネチャ (例: "com.example.Class#method()")

        Returns:
            ファイル名として安全な文字列
        """
        name = method_signature.replace(
            "#", "."
        )  # メソッドとクラスの区切りをドットに変換
        # Windowsでファイル名として安全でない文字をアンダースコアに変換
        name = re.sub(r'[<>:"/\\|?*]', "_", name)
        name = re.sub(r"_+", "_", name)  # 連続するアンダースコアを1つに
        name = name.strip("_")
        return name[:200]  # ファイル名の長さを制限

    def _split_by_comma_outside_parens(self, text: str) -> list[str]:
        """
        括弧の外側にあるカンマのみで文字列を分割する。
        関数内のカンマ(例: COALESCE(a, b))では分割しない。

        Args:
            text: 分割対象の文字列

        Returns:
            分割された文字列のリスト
        """
        parts = []
        current_part = []
        paren_depth = 0

        i = 0
        while i < len(text):
            char = text[i]

            if char == "(":
                paren_depth += 1
                current_part.append(char)
            elif char == ")":
                paren_depth -= 1
                current_part.append(char)
            elif char == "," and paren_depth == 0:
                # 括弧の外側のカンマを発見
                # カンマの後のスペースもスキップ
                parts.append("".join(current_part))
                current_part = []
                # カンマの後のスペースをスキップ
                i += 1
                while i < len(text) and text[i] == " ":
                    i += 1
                continue
            else:
                current_part.append(char)

            i += 1

        # 最後の部分を追加
        if current_part:
            parts.append("".join(current_part))

        return parts

    def _format_sql(self, sql_text: str) -> str:
        """
        SQL文を整形（カスタムルール適用）
        - SELECTやFROMなどのキーワードの後は改行してインデント
        - サブクエリーなどの部分はインデントをネスト
        - カラムやテーブルの指定は1行に1つ

        Args:
            sql_text: 整形前のSQL文

        Returns:
            整形後のSQL文
        """
        try:
            import sqlparse

            # 0. コメント除去 (/* ... */)
            # sqlparseの整形前に除去しないと、整形によってコメントの位置がおかしくなる可能性があるため
            sql_text = re.sub(r"/\*[\s\S]*?\*/", "", sql_text)

            # 1. sqlparseで基本整形
            formatted = sqlparse.format(
                sql_text,
                reindent=True,
                keyword_case="upper",
                indent_width=2,
                wrap_after=80,
            )

            # 2. カスタムルール適用（キーワード後の改行とインデント調整）
            lines = formatted.splitlines()
            new_lines = []
            keywords = [
                "SELECT",
                "FROM",
                "WHERE",
                "GROUP BY",
                "ORDER BY",
                "HAVING",
                "SET",
                "VALUES",
                "JOIN",
                "LEFT JOIN",
                "RIGHT JOIN",
                "INNER JOIN",
                "OUTER JOIN",
            ]

            current_alignment_indent = None
            replacement_indent = None

            for line in lines:
                # 整形によるインデント調整ブロック内かチェック
                if current_alignment_indent is not None:
                    if line.startswith(current_alignment_indent):
                        content = line[len(current_alignment_indent) :]

                        # 括弧の外側のカンマのみで分割して1行1つにする
                        parts = self._split_by_comma_outside_parens(content)
                        for i, part in enumerate(parts):
                            if i < len(parts) - 1:
                                new_lines.append(replacement_indent + part + ",")
                            else:
                                new_lines.append(replacement_indent + part)
                        continue
                    else:
                        # インデントが変わったのでブロック終了
                        current_alignment_indent = None
                        replacement_indent = None

                stripped = line.lstrip()
                base_indent = line[: len(line) - len(stripped)]

                matched_keyword = None
                prefix = ""

                # キーワード判定（(SELECT のようなケースも考慮）
                for kw in keywords:
                    if stripped.startswith(kw + " "):
                        matched_keyword = kw
                        prefix = ""
                        break
                    if stripped.startswith("(" + kw + " "):
                        matched_keyword = kw
                        prefix = "("
                        break

                if matched_keyword:
                    # コンテンツは "PREFIX KEYWORD " の後ろから
                    start_index = len(prefix) + len(matched_keyword) + 1
                    content = stripped[start_index:]

                    if content.strip():
                        # キーワード行を出力
                        new_lines.append(base_indent + prefix + matched_keyword)

                        # 括弧の外側のカンマのみで分割して1行1つにする
                        parts = self._split_by_comma_outside_parens(content)

                        # 新しいインデントはベース + 2スペース
                        new_indent = base_indent + "  "

                        for i, part in enumerate(parts):
                            if i < len(parts) - 1:
                                new_lines.append(new_indent + part + ",")
                            else:
                                new_lines.append(new_indent + part)

                        # sqlparseの整形で揃えられた後続行をキャッチするためのインデント長
                        # sqlparseはコンテンツの開始位置に合わせてインデントする
                        align_len = len(base_indent) + start_index
                        current_alignment_indent = " " * align_len
                        replacement_indent = new_indent
                    else:
                        new_lines.append(line)
                else:
                    new_lines.append(line)

            return "\n".join(new_lines)

        except ImportError:
            print(
                "警告: sqlparseがインストールされていません。SQL整形をスキップします。",
                file=sys.stderr,
            )
            print("  インストール: pip install sqlparse", file=sys.stderr)
            return sql_text
        except Exception as e:
            print(f"警告: SQL整形中にエラーが発生しました: {e}", file=sys.stderr)
            return sql_text

    def _write_sql_file(self, filepath: str, sql_text: str) -> None:
        """
        SQL文をファイルに書き込み

        Args:
            filepath: 出力ファイルパス
            sql_text: SQL文
        """
        try:
            formatted_sql = self._format_sql(sql_text)
            with open(filepath, "w", encoding="utf-8") as f:
                f.write(formatted_sql)
        except Exception as e:
            print(
                f"警告: SQLファイルの書き込みに失敗しました ({filepath}): {e}",
                file=sys.stderr,
            )

    def analyze_table_usage(
        self, sql_dir: str = "./found_sql", table_list_file: str = "./table_list.tsv"
    ) -> None:
        """
        SQLファイルから使用テーブルを検出

        Args:
            sql_dir: SQLファイルが格納されているディレクトリ
            table_list_file: テーブル一覧TSVファイルのパス
        """
        import os
        from pathlib import Path

        # テーブル一覧を読み込み
        if not os.path.exists(table_list_file):
            print(
                f"エラー: テーブル一覧ファイルが見つかりません: {table_list_file}",
                file=sys.stderr,
            )
            return

        table_list = self._load_table_list(table_list_file)

        if not table_list:
            print(f"警告: テーブル一覧が空です", file=sys.stderr)
            return

        # SQLファイルを走査
        sql_dir_path = Path(sql_dir)
        if not sql_dir_path.exists():
            print(
                f"エラー: SQLディレクトリが見つかりません: {sql_dir}", file=sys.stderr
            )
            return

        sql_files = sorted(sql_dir_path.glob("*.sql"))

        if not sql_files:
            print(f"警告: SQLファイルが見つかりません: {sql_dir}", file=sys.stderr)
            return

        #  clipでコピーした結果をExcelに貼り付けられるにはShift_JISで出力する
        sys.stdout.reconfigure(encoding=self.output_tsv_encoding)

        # ヘッダーを出力
        print("SQLファイル名\t物理テーブル名\t論理テーブル名\t補足情報")

        # 各SQLファイルを解析
        for sql_file in sql_files:
            try:
                with open(sql_file, "r", encoding="utf-8") as f:
                    sql_content = f.read()

                # テーブルを検出
                found_tables = self._find_tables_in_sql(sql_content, table_list)

                # 結果を出力
                if found_tables:
                    for physical_name, logical_name, note in found_tables:
                        print(
                            f"{sql_file.name}\t{physical_name}\t{logical_name}\t{note}"
                        )
                else:
                    # テーブルが見つからない場合も1行出力
                    print(f"{sql_file.name}\t\t\t")

            except Exception as e:
                print(
                    f"エラー: SQLファイルの読み込みに失敗しました ({sql_file.name}): {e}",
                    file=sys.stderr,
                )

    def _load_table_list(self, table_list_file: str) -> List[tuple[str, str, str]]:
        """
        テーブル一覧をTSVファイルから読み込み

        Args:
            table_list_file: テーブル一覧TSVファイルのパス

        Returns:
            (物理テーブル名, 論理テーブル名, 補足情報)のリスト
        """
        table_list = []

        try:
            with open(table_list_file, "r", encoding="utf-8") as f:
                reader = csv.reader(f, delimiter="\t")
                for row in reader:
                    # コメント行や空行をスキップ
                    if not row or (row[0] and row[0].startswith("#")):
                        continue

                    # 最低でも物理テーブル名が必要
                    if len(row) >= 1 and row[0].strip():
                        physical_name = row[0].strip()
                        logical_name = row[1].strip() if len(row) >= 2 else ""
                        note = row[2].strip() if len(row) >= 3 else ""
                        table_list.append((physical_name, logical_name, note))

        except Exception as e:
            print(
                f"エラー: テーブル一覧ファイルの読み込みに失敗しました: {e}",
                file=sys.stderr,
            )

        return table_list

    def _find_tables_in_sql(
        self, sql_content: str, table_list: List[tuple[str, str, str]]
    ) -> List[tuple[str, str, str]]:
        """
        SQL文からテーブルを検出

        Args:
            sql_content: SQL文
            table_list: テーブル一覧

        Returns:
            検出されたテーブル情報のリスト
        """
        # SQL文を大文字化して検索
        sql_upper = sql_content.upper()

        found_tables = []
        seen_tables = set()  # 重複を避けるため

        for physical_name, logical_name, note in table_list:
            # テーブル名を大文字化して検索
            table_upper = physical_name.upper()

            # 単語境界を考慮してテーブル名を検索
            # テーブル名の前後が英数字でないことを確認
            pattern = r"\b" + re.escape(table_upper) + r"\b"

            if re.search(pattern, sql_upper):
                if physical_name not in seen_tables:
                    found_tables.append((physical_name, logical_name, note))
                    seen_tables.add(physical_name)

        return found_tables

    def _extract_method_signature_parts(self, method_signature: str) -> Dict[str, str]:
        """
        メソッドシグネチャを分解

        Args:
            method_signature: メソッドシグネチャ (例: "com.example.service.UserService#getUser(String)")

        Returns:
            各要素を含む辞書
        """
        if "#" not in method_signature:
            return {
                "package": "",
                "class": "",
                "simple_class": "",
                "method": method_signature,
                "full_signature": method_signature,
            }

        class_part, method_part = method_signature.split("#", 1)

        # パッケージ名とクラス名を分離
        if "." in class_part:
            package = ".".join(class_part.split(".")[:-1])
            simple_class = class_part.split(".")[-1]
        else:
            package = ""
            simple_class = class_part

        return {
            "package": package,
            "class": class_part,
            "simple_class": simple_class,
            "method": method_part,
            "full_signature": method_signature,
        }

    def _format_tree_display(self, method_signature: str) -> str:
        """
        L列以降に表示するツリー用のメソッド名を生成
        パッケージ名を除外し、SimpleClassName#methodName(params)形式

        Args:
            method_signature: メソッドシグネチャ

        Returns:
            ツリー表示用のメソッド名
        """
        parts = self._extract_method_signature_parts(method_signature)
        if parts["simple_class"]:
            return f"{parts['simple_class']}#{parts['method']}"
        return parts["method"]

    def _collect_tree_data(
        self,
        root_method: str,
        max_depth: int,
        follow_implementations: bool,
        visited: Optional[Set[str]] = None,
        depth: int = 0,
        parent_relation: str = "",
    ) -> List[Dict[str, any]]:
        """
        1つの呼び出しツリーを再帰的にトラバースし、全メソッド情報を収集

        Args:
            root_method: ルートメソッド
            max_depth: 最大深度
            follow_implementations: 実装クラス候補を追跡するか
            visited: 訪問済みメソッド集合（循環参照チェック用）
            depth: 現在の深度
            parent_relation: 親との関係（"親クラス" / "実装クラスへの展開" / ""）

        Returns:
            各メソッドの情報を含む辞書のリスト
        """
        if visited is None:
            visited = set()

        result = []

        if depth > max_depth:
            return result

        # 除外ルールチェック
        if not self.exclusion_manager.should_include(root_method):
            return result

        # 循環参照チェック
        is_circular = root_method in visited

        # メソッド情報を取得
        info = self.method_info.get(root_method, {})
        parts = self._extract_method_signature_parts(root_method)

        # 現在のメソッドを結果に追加
        result.append(
            {
                "depth": depth,
                "method": root_method,
                "package": parts["package"],
                "class": parts["class"],
                "simple_method": parts["method"],
                "javadoc": info.get("javadoc", ""),
                "parent_relation": parent_relation,
                "sql": info.get("sql", ""),
                "is_circular": is_circular,
                "tree_display": self._format_tree_display(root_method),
            }
        )

        # 循環参照の場合は子ノードを展開しない
        if is_circular:
            return result

        visited_copy = visited.copy()
        visited_copy.add(root_method)

        # 除外ルールで配下を除外する場合
        if self.exclusion_manager.should_exclude_children(root_method):
            return result

        # 子ノードを再帰的に処理
        callees = self.forward_calls.get(root_method, [])
        for callee_info in callees:
            callee = callee_info["method"]

            # Iモード: 除外対象の場合、ノード自体を表示せずスキップ
            if not self.exclusion_manager.should_include(callee):
                continue

            # 親クラスメソッドかどうか
            relation = "親クラス" if callee_info["is_parent_method"] == "Yes" else ""

            # 呼び出し先を再帰的に収集
            result.extend(
                self._collect_tree_data(
                    callee,
                    max_depth,
                    follow_implementations,
                    visited_copy.copy(),
                    depth + 1,
                    relation,
                )
            )

            # 実装クラス候補がある場合
            if follow_implementations and callee_info["implementations"]:
                implementations = [
                    impl.strip().split(" ")[0]
                    for impl in callee_info["implementations"].split(",")
                    if impl.strip()
                ]

                for impl_class in implementations:
                    impl_method = self._find_implementation_method(callee, impl_class)
                    if impl_method:
                        # Iモード: 除外対象の場合、ノード自体を表示せずスキップ
                        if not self.exclusion_manager.should_include(impl_method):
                            continue

                        result.extend(
                            self._collect_tree_data(
                                impl_method,
                                max_depth,
                                follow_implementations,
                                visited_copy.copy(),
                                depth + 1,
                                "実装クラスへの展開",
                            )
                        )

        return result

    def export_tree_to_excel(
        self,
        entry_points_file: Optional[str],
        output_file: str,
        max_depth: int = 20,
        follow_implementations: bool = True,
        include_tree: bool = True,
        include_sql: bool = True,
    ) -> None:
        """
        Excel形式でツリーをエクスポート

        Args:
            entry_points_file: エントリーポイントファイル（Noneの場合は厳密モードのエントリーポイント）
            output_file: 出力ファイル名
            max_depth: 最大深度
            follow_implementations: 実装クラス候補を追跡するか
            include_tree: L列以降の呼び出しツリーを出力するか
            include_sql: AZ列のSQL文を出力するか
        """
        # エントリーポイントの決定
        entry_points: List[str] = []

        if entry_points_file:
            try:
                with open(entry_points_file, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        # 空行とコメント行をスキップ
                        if line and not line.startswith("#"):
                            entry_points.append(line)
            except Exception as e:
                print(
                    f"エラー: エントリーポイントファイルの読み込みに失敗しました: {e}",
                    file=sys.stderr,
                )
                return
        else:
            # 厳密モードのエントリーポイントを取得
            all_callees = set()
            for callees in self.forward_calls.values():
                for callee_info in callees:
                    all_callees.add(callee_info["method"])

            for method, info in self.method_info.items():
                if method not in all_callees and info.get("is_entry_point"):
                    entry_points.append(method)

        if not entry_points:
            print("警告: エントリーポイントが見つかりませんでした", file=sys.stderr)
            return

        print(f"エントリーポイント数: {len(entry_points)}")

        # Excelワークブックの作成
        wb = openpyxl.Workbook()
        ws = wb.active
        if not isinstance(ws, openpyxl.worksheet.worksheet.Worksheet):
            raise TypeError("Active sheet is not a Worksheet")

        font = Font(name="Meiryo UI")

        # L列以降の列幅を5に設定
        tree_start_col = column_index_from_string("L")
        for col_idx in range(tree_start_col, tree_start_col + 50):
            letter = get_column_letter(col_idx)
            ws.column_dimensions[letter].width = 5

        # AZ列のインデックス
        az_col = column_index_from_string("AZ")

        # 各エントリーポイントについてツリーを収集して出力
        current_row = 1

        # ヘッダ行を出力
        #   A～H列
        ws.cell(row=current_row, column=1, value="エントリーポイント").font = font
        ws.cell(row=current_row, column=2, value="呼び出しメソッド").font = font
        ws.cell(row=current_row, column=3, value="パッケージ名").font = font
        ws.cell(row=current_row, column=4, value="クラス名").font = font
        ws.cell(row=current_row, column=5, value="メソッド名").font = font
        ws.cell(row=current_row, column=6, value="Javadoc").font = font
        ws.cell(
            row=current_row, column=7, value="親クラス / 実装クラスへの展開"
        ).font = font
        ws.cell(row=current_row, column=8, value="SQL有無").font = font
        #   L列（呼び出しツリーを出力する場合のみ）
        if include_tree:
            ws.cell(
                row=current_row, column=tree_start_col, value="呼び出しツリー"
            ).font = font
        #   AZ列（SQL文を出力する場合のみ）
        if include_sql:
            ws.cell(row=current_row, column=az_col, value="SQL文").font = font

        current_row += 1

        for entry_point in entry_points:
            print(f"処理中: {entry_point}")

            # ツリーデータを収集
            tree_data = self._collect_tree_data(
                entry_point, max_depth, follow_implementations
            )

            # Excelに書き込み
            for node in tree_data:
                # A列: エントリーポイント（すべての行に出力）
                ws.cell(row=current_row, column=1, value=entry_point).font = font

                # B列: 呼び出しメソッド（fully qualified name）
                ws.cell(row=current_row, column=2, value=node["method"]).font = font

                # C列: パッケージ名
                ws.cell(row=current_row, column=3, value=node["package"]).font = font

                # D列: クラス名
                ws.cell(row=current_row, column=4, value=node["class"]).font = font

                # E列: メソッド名（simple name）
                ws.cell(row=current_row, column=5, value=node["simple_method"]).font = (
                    font
                )

                # F列: Javadoc
                ws.cell(row=current_row, column=6, value=node["javadoc"]).font = font

                # G列: 親クラス / 実装クラスへの展開
                ws.cell(
                    row=current_row, column=7, value=node["parent_relation"]
                ).font = font

                # H列: SQL有無
                sql_marker = "●" if node["sql"] else ""
                ws.cell(row=current_row, column=8, value=sql_marker).font = font

                # L列以降: 呼び出しツリー（include_treeがTrueの場合のみ）
                if include_tree:
                    tree_col = tree_start_col + node["depth"]
                    tree_text = str(node["tree_display"] or "")
                    if node["is_circular"] and tree_text:
                        tree_text = tree_text + " [循環参照]"
                    ws.cell(row=current_row, column=tree_col, value=tree_text).font = (
                        font
                    )

                # AZ列: SQL文（include_sqlがTrueの場合のみ）
                if include_sql and node["sql"]:
                    ws.cell(row=current_row, column=az_col, value=node["sql"]).font = (
                        font
                    )

                current_row += 1

        # Excelファイルの保存
        try:
            wb.save(output_file)
            print(f"ツリーを {output_file} にエクスポートしました")
            print(f"総行数: {current_row - 1}")
        except Exception as e:
            print(f"エラー: Excelファイルの保存に失敗しました: {e}", file=sys.stderr)


# サブコマンドハンドラー関数


def handle_list(args, visualizer: CallTreeVisualizer) -> None:
    """listサブコマンドの処理"""
    if args.tsv:
        visualizer.list_entry_points_tsv(args.min_calls, args.strict)
    else:
        visualizer.list_entry_points(args.min_calls, args.strict)


def handle_search(args, visualizer: CallTreeVisualizer) -> None:
    """searchサブコマンドの処理"""
    visualizer.search_methods(args.keyword)


def handle_forward(args, visualizer: CallTreeVisualizer) -> None:
    """forwardサブコマンドの処理"""
    visualizer.print_forward_tree(
        args.method,
        args.depth,
        show_class=args.show_class,
        show_sql=args.show_sql,
        follow_implementations=args.follow_impl,
    )


def handle_reverse(args, visualizer: CallTreeVisualizer) -> None:
    """reverseサブコマンドの処理"""
    visualizer.print_reverse_tree(
        args.method,
        args.depth,
        show_class=args.show_class,
        follow_overrides=args.follow_override,
    )


def handle_export(args, visualizer: CallTreeVisualizer) -> None:
    """exportサブコマンドの処理"""
    visualizer.export_tree_to_file(
        args.method,
        args.output_file,
        args.depth,
        args.format,
        args.follow_impl,
    )


def handle_export_excel(args, visualizer: CallTreeVisualizer) -> None:
    """export-excelサブコマンドの処理"""
    visualizer.export_tree_to_excel(
        args.entry_points,
        args.output_file,
        args.depth,
        args.follow_impl,
        args.include_tree,
        args.include_sql,
    )


def handle_extract_sql(args, visualizer: CallTreeVisualizer) -> None:
    """extract-sqlサブコマンドの処理"""
    visualizer.extract_sql_to_files(args.output_dir)


def handle_analyze_tables(args, visualizer: CallTreeVisualizer) -> None:
    """analyze-tablesサブコマンドの処理"""
    visualizer.analyze_table_usage(args.sql_dir, args.table_list)


class StructureVisualizer:
    """構造情報（クラス階層、インターフェース実装）を可視化するクラス"""

    def __init__(self, json_file: str):
        self.json_file = json_file
        try:
            with open(json_file, "r", encoding="utf-8") as f:
                self.data = json.load(f)
        except Exception as e:
            print(f"JSONファイルの読み込みに失敗しました: {e}", file=sys.stderr)
            sys.exit(1)

    def print_class_tree(
        self,
        root_filter: Optional[str] = None,
        max_depth: int = 50,
        verbose: bool = False,
    ):
        """クラス階層ツリーを表示"""
        classes = self.data.get("classes", [])

        # データの構築
        class_map = {c["className"]: c for c in classes}
        children_map = defaultdict(list)
        roots = set()

        # 全クラス名をセットに（存在チェック用）
        all_class_names = set(c["className"] for c in classes)

        # 親子関係の構築とルートの特定
        for c in classes:
            class_name = c["className"]
            super_class = c.get("superClass")

            if super_class:
                children_map[super_class].append(class_name)
                # 親クラスがデータセットに含まれていない場合、その親クラスもルート候補とする
                if super_class not in all_class_names:
                    roots.add(super_class)
            else:
                # 親クラスがない場合はルート
                roots.add(class_name)

        # フィルタリング適用
        if root_filter:
            target_roots = [r for r in roots if root_filter in r]
            # ルートそのものでなくても、ツリーの途中にあるクラスも指定できるようにする
            if not target_roots and root_filter in all_class_names:
                target_roots = [root_filter]

            if not target_roots:
                # 部分一致で探す
                target_roots = [c for c in all_class_names if root_filter in c]
        else:
            target_roots = sorted(list(roots))

        if not target_roots:
            print(f"該当するクラスが見つかりませんでした: {root_filter}")
            return

        print(f"\n{'=' * 80}")
        print(f"クラス継承ツリー (JSON: {self.json_file})")
        print(f"{'=' * 80}\n")

        for root in target_roots:
            self._print_class_node_recursive(
                root, 0, max_depth, verbose, class_map, children_map, set()
            )
            print()  # ツリー間の改行

    def _print_class_node_recursive(
        self,
        class_name: str,
        depth: int,
        max_depth: int,
        verbose: bool,
        class_map: Dict[str, Dict],
        children_map: Dict[str, List[str]],
        visited: Set[str],
    ):
        if depth > max_depth:
            return

        if class_name in visited:
            indent = "    " * depth
            print(f"{indent}|-- {class_name} [循環参照]")
            return

        visited.add(class_name)

        indent = "    " * depth
        prefix = "|-- " if depth > 0 else ""

        # 表示情報の構築
        display = f"{indent}{prefix}{class_name}"

        # 詳細情報の表示
        info = class_map.get(class_name)
        if verbose and info:
            extras = []
            if info.get("javadoc"):
                extras.append(f"Javadoc: {info.get('javadoc')}")
            else:
                extras.append("Javadoc: (なし)")

            annotations = info.get("annotations", [])
            if annotations:
                anns_str = ", ".join([f"@{a.split('.')[-1]}" for a in annotations])
                extras.append(f"Annotations: [{anns_str}]")
            else:
                extras.append("Annotations: (なし)")

            if extras:
                # クラス名の右側にタブ区切りで表示
                # javadocとアノテーションの間もタブ区切りで表示
                display += f"\t{'\t'.join(extras)}"

        # 外部親クラスの場合の表示
        if not info and depth == 0:
            display += " [外部親クラス]"

        print(display)

        # 子クラスの表示
        children = sorted(children_map.get(class_name, []))
        for child in children:
            self._print_class_node_recursive(
                child,
                depth + 1,
                max_depth,
                verbose,
                class_map,
                children_map,
                visited.copy(),
            )

    def print_interface_impls(
        self, filter_str: Optional[str] = None, verbose: bool = False
    ):
        """インターフェース実装一覧を表示"""
        interfaces = self.data.get("interfaces", [])

        print(f"\n{'=' * 80}")
        print(f"インターフェース実装一覧 (JSON: {self.json_file})")
        print(f"{'=' * 80}\n")

        count = 0
        for iface in interfaces:
            interface_name = iface["interfaceName"]

            # フィルタリング
            if filter_str and filter_str not in interface_name:
                continue

            implementations = iface.get("implementations", [])
            if not implementations:
                continue

            count += 1
            if verbose:
                # verboseモードではインターフェースの詳細情報も表示
                extras = []
                if iface.get("javadoc"):
                    extras.append(f"Javadoc: {iface.get('javadoc')}")
                else:
                    extras.append("Javadoc: (なし)")
                annotations = iface.get("annotations", [])
                if annotations:
                    anns_str = ", ".join([f"@{a.split('.')[-1]}" for a in annotations])
                    extras.append(f"Annotations: [{anns_str}]")
                else:
                    extras.append("Annotations: (なし)")
                # superInterfaces表示
                super_ifaces = iface.get("superInterfaces", [])
                if super_ifaces:
                    extras.append(f"Extends: [{', '.join(super_ifaces)}]")
                # hitWords表示
                hit_words = iface.get("hitWords", [])
                if hit_words:
                    extras.append(f"HitWords: [{', '.join(hit_words)}]")
                print(f"Interface: {interface_name}\t{'\t'.join(extras)}")
            else:
                print(f"Interface: {interface_name}")

            # 実装クラスをタイプ（direct/indirect）とクラス名でソート
            sorted_impls = sorted(
                implementations, key=lambda x: (x["type"] != "direct", x["className"])
            )  # directが先

            for impl in sorted_impls:
                class_name = impl["className"]
                impl_type = impl["type"]
                type_mark = "[Direct]  " if impl_type == "direct" else "[Indirect]"

                display = f"    {type_mark} {class_name}"

                if verbose:
                    extras = []
                    if impl.get("javadoc"):
                        extras.append(f"Javadoc: {impl.get('javadoc')}")
                    else:
                        extras.append("Javadoc: (なし)")

                    annotations = impl.get("annotations", [])
                    if annotations:
                        anns_str = ", ".join(
                            [f"@{a.split('.')[-1]}" for a in annotations]
                        )
                        extras.append(f"Annotations: [{anns_str}]")
                    else:
                        extras.append("Annotations: (なし)")

                    if extras:
                        display += f"\t{'\t'.join(extras)}"

                print(display)
            print()  # インターフェースごとの空行

        if count == 0 and filter_str:
            print(f"該当するインターフェースが見つかりませんでした: {filter_str}")


def handle_class_tree(args) -> None:
    """class-treeサブコマンドの処理"""
    visualizer = StructureVisualizer(args.input_file)
    # root_filterかfilterのどちらかを使用
    f = args.root_filter if args.root_filter else args.filter
    visualizer.print_class_tree(root_filter=f, verbose=args.verbose)


def handle_interface_impls(args) -> None:
    """interface-implsサブコマンドの処理"""
    visualizer = StructureVisualizer(args.input_file)
    visualizer.print_interface_impls(filter_str=args.filter_str, verbose=args.verbose)


def main():
    """メイン関数"""
    import argparse

    # メインパーサーの作成
    parser = argparse.ArgumentParser(
        description="呼び出しツリー可視化スクリプト - JSONファイルから可視化を行います",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
除外ルールファイルのフォーマット:
  <クラス名 or メソッド名><TAB><I|E>
  I: 対象自体を除外
  E: 対象は表示するが、配下の呼び出しを除外

テーブルリストファイル (table_list.tsv) のフォーマット:
  <物理テーブル名><TAB><論理テーブル名><TAB><補足情報>

使用例:
  %(prog)s list
  %(prog)s list --no-strict --min-calls 5
  %(prog)s forward 'com.example.Main#main(String[])'
  %(prog)s reverse 'com.example.Service#process()'
  %(prog)s export 'com.example.Main#main(String[])' tree.html --format html
  %(prog)s export-excel call_trees.xlsx --entry-points entry_points.txt
  %(prog)s extract-sql --output-dir ./output/sqls
  %(prog)s analyze-tables --sql-dir ./output/sqls
  %(prog)s class-tree --filter 'com.example'
  %(prog)s interface-impls --interface 'MyService'
        """,
    )

    parser.add_argument(
        "-i", "--input",
        dest="input_file",
        default="analyzed_result.json",
        help="入力ファイル（JSONまたはTSV）のパス (デフォルト: analyzed_result.json)",
    )
    parser.add_argument(
        "--exclusion-file",
        help="除外ルールファイルのパス (デフォルト: exclusion_rules.txt)",
    )
    parser.add_argument(
        "--output-tsv-encoding",
        default="Shift_JIS",
        help="出力するTSVのエンコーディング (デフォルト: Shift_JIS (Excelへの貼付けを考慮))",
    )

    # サブコマンドの作成
    subparsers = parser.add_subparsers(dest="command", help="サブコマンド")

    # list サブコマンド
    parser_list = subparsers.add_parser("list", help="エントリーポイント候補を表示")
    parser_list.add_argument(
        "--no-strict",
        action="store_false",
        dest="strict",
        help="緩和モード（デフォルトは厳密モード）",
    )
    parser_list.add_argument(
        "--min-calls",
        type=int,
        default=1,
        help="エントリーポイントの最小呼び出し数 (デフォルト: 1)",
    )
    parser_list.add_argument(
        "--tsv",
        action="store_true",
        help="TSV形式で出力",
    )

    # search サブコマンド
    parser_search = subparsers.add_parser("search", help="キーワードでメソッドを検索")
    parser_search.add_argument("keyword", help="検索キーワード")

    # forward サブコマンド
    parser_forward = subparsers.add_parser(
        "forward", help="指定メソッドからの呼び出しツリーを表示"
    )
    parser_forward.add_argument("method", help="起点メソッド")
    parser_forward.add_argument(
        "--depth", type=int, default=50, help="ツリーの最大深度 (デフォルト: 50)"
    )
    parser_forward.add_argument(
        "--no-class",
        action="store_false",
        dest="show_class",
        help="クラス情報を非表示",
    )
    parser_forward.add_argument(
        "--no-sql", action="store_false", dest="show_sql", help="SQL情報を非表示"
    )
    parser_forward.add_argument(
        "--no-follow-impl",
        action="store_false",
        dest="follow_impl",
        help="実装クラス候補を追跡しない",
    )

    # reverse サブコマンド
    parser_reverse = subparsers.add_parser(
        "reverse", help="指定メソッドへの呼び出し元ツリーを表示"
    )
    parser_reverse.add_argument("method", help="対象メソッド")
    parser_reverse.add_argument(
        "--depth", type=int, default=50, help="ツリーの最大深度 (デフォルト: 50)"
    )
    parser_reverse.add_argument(
        "--no-class",
        action="store_false",
        dest="show_class",
        help="クラス情報を非表示",
    )
    parser_reverse.add_argument(
        "--no-follow-override",
        action="store_false",
        dest="follow_override",
        help="オーバーライド元を追跡しない",
    )

    # export サブコマンド
    parser_export = subparsers.add_parser(
        "export", help="ツリーをファイルにエクスポート"
    )
    parser_export.add_argument("method", help="起点メソッド")
    parser_export.add_argument("output_file", help="出力ファイル名")
    parser_export.add_argument(
        "--format",
        choices=["text", "markdown", "html"],
        default="text",
        help="出力形式 (デフォルト: text)",
    )
    parser_export.add_argument(
        "--depth", type=int, default=50, help="ツリーの最大深度 (デフォルト: 50)"
    )
    parser_export.add_argument(
        "--no-follow-impl",
        action="store_false",
        dest="follow_impl",
        help="実装クラス候補を追跡しない",
    )

    # export-excel サブコマンド
    parser_export_excel = subparsers.add_parser(
        "export-excel", help="ツリーをExcelにエクスポート"
    )
    parser_export_excel.add_argument("output_file", help="出力Excelファイル名")
    parser_export_excel.add_argument(
        "--entry-points",
        help="エントリーポイントファイル（指定しない場合は厳密モードのエントリーポイントを使用）",
    )
    parser_export_excel.add_argument(
        "--depth", type=int, default=20, help="ツリーの最大深度 (デフォルト: 20)"
    )
    parser_export_excel.add_argument(
        "--no-follow-impl",
        action="store_false",
        dest="follow_impl",
        help="実装クラス候補を追跡しない",
    )
    parser_export_excel.add_argument(
        "--no-tree",
        action="store_false",
        dest="include_tree",
        help="L列以降の呼び出しツリーを出力しない",
    )
    parser_export_excel.add_argument(
        "--no-sql",
        action="store_false",
        dest="include_sql",
        help="AZ列のSQL文を出力しない",
    )

    # extract-sql サブコマンド
    parser_extract_sql = subparsers.add_parser(
        "extract-sql", help="SQL文を抽出してファイル出力"
    )
    parser_extract_sql.add_argument(
        "--output-dir",
        default="./found_sql",
        help="SQL出力先ディレクトリ (デフォルト: ./found_sql)",
    )

    # analyze-tables サブコマンド
    parser_analyze_tables = subparsers.add_parser(
        "analyze-tables", help="SQLファイルから使用テーブルを検出"
    )
    parser_analyze_tables.add_argument(
        "--sql-dir",
        default="./found_sql",
        help="SQLディレクトリ (デフォルト: ./found_sql)",
    )
    parser_analyze_tables.add_argument(
        "--table-list",
        default="./table_list.tsv",
        help="テーブルリストファイル (デフォルト: ./table_list.tsv)",
    )

    # class-tree サブコマンド
    parser_class_tree = subparsers.add_parser(
        "class-tree", help="クラス階層ツリーを表示"
    )
    parser_class_tree.add_argument(
        "--filter",
        help="フィルタリングパターン（パッケージ名やクラス名の一部）",
    )
    parser_class_tree.add_argument(
        "--root",
        dest="root_filter",
        help="ルートクラス指定（--filterのエイリアス）",
    )
    parser_class_tree.add_argument(
        "--verbose",
        action="store_true",
        help="詳細表示（Javadocやアノテーションを表示）",
    )

    # interface-impls サブコマンド
    parser_interface_impls = subparsers.add_parser(
        "interface-impls", help="インターフェース実装一覧を表示"
    )
    parser_interface_impls.add_argument(
        "--interface",
        dest="filter_str",
        help="特定のインターフェースでフィルタリング",
    )
    parser_interface_impls.add_argument(
        "--verbose",
        action="store_true",
        help="詳細表示（Javadocやアノテーションを表示）",
    )

    # 引数を解析
    args = parser.parse_args()

    # サブコマンドが指定されていない場合
    if not args.command:
        parser.print_help()
        sys.exit(1)

    # class-tree と interface-impls サブコマンドは CallTreeVisualizer を使わない
    if args.command == "class-tree":
        handle_class_tree(args)
        return
    elif args.command == "interface-impls":
        handle_interface_impls(args)
        return

    # Visualizerの初期化 (TSV処理)
    visualizer = CallTreeVisualizer(
        args.input_file, args.exclusion_file, args.output_tsv_encoding
    )

    # サブコマンドに応じた処理を実行
    if args.command == "list":
        handle_list(args, visualizer)
    elif args.command == "search":
        handle_search(args, visualizer)
    elif args.command == "forward":
        handle_forward(args, visualizer)
    elif args.command == "reverse":
        handle_reverse(args, visualizer)
    elif args.command == "export":
        handle_export(args, visualizer)
    elif args.command == "export-excel":
        handle_export_excel(args, visualizer)
    elif args.command == "extract-sql":
        handle_extract_sql(args, visualizer)
    elif args.command == "analyze-tables":
        handle_analyze_tables(args, visualizer)


if __name__ == "__main__":
    main()
