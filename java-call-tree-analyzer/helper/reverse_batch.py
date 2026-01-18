#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
逆引き呼び出し元の最終到達点を一括CSV出力するヘルパースクリプト

対象メソッドをテキストファイルで指定し、各メソッドの逆引き最終到達点
（最上位呼び元メソッド）とそのJavadocをCSVに出力します。
"""

import argparse
import csv
import json
import sys
from collections import defaultdict
from typing import Dict, List, Optional, Set


def find_parent_methods(
    method: str, methods_data: List[Dict], class_data: Dict[str, Dict]
) -> List[str]:
    """
    メソッドのオーバーライド元/インターフェースメソッドを探す

    Args:
        method: 対象メソッドのシグネチャ
        methods_data: メソッドデータのリスト
        class_data: クラスデータの辞書

    Returns:
        親メソッドのシグネチャのリスト
    """
    parent_methods = []
    # メソッドからクラス名とメソッド部分を抽出
    if "#" not in method:
        return parent_methods

    parts = method.split("#", 1)
    class_name = parts[0]
    method_part = parts[1]

    # クラス情報を取得
    class_info = class_data.get(class_name, {})

    # 親クラスでの同名メソッドを探索
    parent_class = class_info.get("superClass")
    if parent_class:
        parent_method_sig = f"{parent_class}#{method_part}"
        # メソッドが存在するか確認
        for m in methods_data:
            if m.get("method") == parent_method_sig:
                parent_methods.append(parent_method_sig)
                break

    # インターフェースでの同名メソッドを探索
    interfaces = class_info.get("allInterfaces", [])
    for iface in interfaces:
        interface_method_sig = f"{iface}#{method_part}"
        for m in methods_data:
            if m.get("method") == interface_method_sig:
                parent_methods.append(interface_method_sig)
                break

    return parent_methods


def find_final_endpoints(
    target_method: str,
    reverse_calls: Dict[str, List[str]],
    methods_data: List[Dict],
    class_data: Dict[str, Dict],
    max_depth: int = 50,
    follow_overrides: bool = True,
) -> Set[str]:
    """
    指定メソッドから逆引きで最終到達点（呼び元がないメソッド）を収集する

    Args:
        target_method: 対象メソッド
        reverse_calls: 逆引き呼び出し関係
        methods_data: メソッドデータのリスト
        class_data: クラスデータの辞書
        max_depth: 最大深度
        follow_overrides: オーバーライド元を追跡するか

    Returns:
        最終到達点メソッドのセット
    """
    final_endpoints: Set[str] = set()
    visited: Set[str] = set()

    def _find_recursive(method: str, depth: int) -> None:
        if depth > max_depth:
            return

        if method in visited:
            return

        visited.add(method)

        callers = reverse_calls.get(method, [])

        if not callers and follow_overrides:
            # オーバーライド元/インターフェースメソッドを探す
            parent_methods = find_parent_methods(method, methods_data, class_data)
            if parent_methods:
                for parent_method in parent_methods:
                    _find_recursive(parent_method, depth)
            else:
                # オーバーライド元もない場合は最終到達点
                final_endpoints.add(method)
        elif not callers:
            # 呼び出し元がない場合は最終到達点
            final_endpoints.add(method)
        else:
            # 通常の呼び出し元を探索
            for caller in callers:
                _find_recursive(caller, depth + 1)

    _find_recursive(target_method, 0)
    return final_endpoints


def main():
    """メイン関数"""
    parser = argparse.ArgumentParser(
        description="逆引き呼び出し元の最終到達点を一括CSV出力"
    )
    parser.add_argument(
        "-i",
        "--input",
        dest="input_file",
        default="analyzed_result.json",
        help="入力JSONファイルのパス (デフォルト: analyzed_result.json)",
    )
    parser.add_argument(
        "-m",
        "--methods",
        dest="methods_file",
        required=True,
        help="対象メソッドリストファイル（改行区切り）",
    )
    parser.add_argument(
        "-o",
        "--output",
        dest="output_file",
        required=True,
        help="出力CSVファイルのパス",
    )
    parser.add_argument(
        "--depth",
        type=int,
        default=50,
        help="ツリーの最大深度 (デフォルト: 50)",
    )
    parser.add_argument(
        "--no-follow-override",
        action="store_false",
        dest="follow_override",
        help="オーバーライド元を追跡しない",
    )

    args = parser.parse_args()

    # JSONデータの読み込み
    print(f"JSONファイルを読み込み中: {args.input_file}")
    try:
        with open(args.input_file, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        print(f"エラー: JSONファイルの読み込みに失敗しました: {e}", file=sys.stderr)
        sys.exit(1)

    # データ構造の構築
    reverse_calls: Dict[str, List[str]] = defaultdict(list)
    method_info: Dict[str, Dict[str, Optional[str]]] = {}
    methods_data = data.get("methods", [])

    for method_entry in methods_data:
        # JSONでは "method" キーを使用
        signature = method_entry.get("method", "")
        if signature:
            method_info[signature] = {
                "class": method_entry.get("class"),
                "parent": method_entry.get("parentClasses"),
                "javadoc": method_entry.get("javadoc"),
                "annotations": method_entry.get("annotations", []),
            }

            # 呼び出し関係の構築
            # callsは辞書の配列なので、各辞書からmethodを取得
            calls = method_entry.get("calls", [])
            for call_entry in calls:
                if isinstance(call_entry, dict):
                    callee_sig = call_entry.get("method", "")
                else:
                    callee_sig = call_entry
                if callee_sig:
                    reverse_calls[callee_sig].append(signature)

    # クラスデータの構築
    class_data: Dict[str, Dict] = {}
    for class_info in data.get("classes", []):
        class_name = class_info.get("className")
        if class_name:
            class_data[class_name] = {
                "superClass": class_info.get("superClass"),
                "allInterfaces": class_info.get("allInterfaces", []),
            }

    # 対象メソッドリストの読み込み
    print(f"対象メソッドリストを読み込み中: {args.methods_file}")
    try:
        with open(args.methods_file, "r", encoding="utf-8") as f:
            target_methods = [
                line.strip()
                for line in f
                if line.strip() and not line.strip().startswith("#")
            ]
    except Exception as e:
        print(
            f"エラー: 対象メソッドリストの読み込みに失敗しました: {e}",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"対象メソッド数: {len(target_methods)}")

    # CSV出力データの収集
    csv_rows: List[Dict[str, str]] = []

    for target_method in target_methods:
        print(f"処理中: {target_method}")

        # 最終到達点を探索
        final_endpoints = find_final_endpoints(
            target_method,
            reverse_calls,
            methods_data,
            class_data,
            args.depth,
            args.follow_override,
        )

        if final_endpoints:
            for endpoint in sorted(final_endpoints):
                info = method_info.get(endpoint, {})
                javadoc = info.get("javadoc", "") or ""
                csv_rows.append(
                    {
                        "対象メソッド": target_method,
                        "最終到達点のメソッド": endpoint,
                        "最終到達点のメソッドのjavadoc": javadoc,
                    }
                )
        else:
            # 最終到達点が見つからない場合も記録
            csv_rows.append(
                {
                    "対象メソッド": target_method,
                    "最終到達点のメソッド": "(到達点なし)",
                    "最終到達点のメソッドのjavadoc": "",
                }
            )

    # CSV出力
    print(f"CSVファイルを出力中: {args.output_file}")
    try:
        with open(args.output_file, "w", encoding="cp932", newline="") as f:
            fieldnames = [
                "対象メソッド",
                "最終到達点のメソッド",
                "最終到達点のメソッドのjavadoc",
            ]
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(csv_rows)

        print(f"完了: {len(csv_rows)} 行を出力しました")

    except Exception as e:
        print(f"エラー: CSVファイルの出力に失敗しました: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
