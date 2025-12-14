#!/bin/bash
# ============================================================
# call-tree.json からメソッドを fzf で選択するスクリプト
# ============================================================
# 使用方法:
#   ./select_method.sh [input.json]
#
# 引数:
#   input.json : CallTreeAnalyzerで出力したJSONファイル（省略時: call-tree.json）
#
# 出力:
#   選択されたメソッドシグネチャを標準出力に出力
#
# 使用例:
#   METHOD=$(./helper/select_method.sh)
#   python call_tree_visualizer.py call-tree.tsv forward "$METHOD"
# ============================================================

set -e

INPUT_FILE="${1:-call-tree.json}"

# 入力ファイルの存在チェック
if [ ! -f "$INPUT_FILE" ]; then
    echo "エラー: ファイルが見つかりません: $INPUT_FILE" >&2
    exit 1
fi

# jqコマンドの存在チェック
if ! command -v jq &> /dev/null; then
    echo "エラー: jq コマンドが必要です。" >&2
    echo "  Windowsではscoopを使ってインストール可能です。" >&2
    echo "  scoopがインストールされていない場合は、https://scoop.sh/ を参照してインストールしてください。" >&2
    echo "  scoopでインストール: scoop install jq" >&2
    exit 1
fi

# fzfコマンドの存在チェック
if ! command -v fzf &> /dev/null; then
    echo "エラー: fzf コマンドが必要です。" >&2
    echo "  Windowsではscoopを使ってインストール可能です。" >&2
    echo "  scoopがインストールされていない場合は、https://scoop.sh/ を参照してインストールしてください。" >&2
    echo "  scoopでインストール: scoop install fzf" >&2
    exit 1
fi

# メソッド一覧を取得してfzfで選択
jq -r '.methods[].method' "$INPUT_FILE" | sort | fzf --height=40% --reverse --prompt="メソッドを選択: "
