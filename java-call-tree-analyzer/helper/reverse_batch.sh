#!/bin/bash
# -*- coding: utf-8 -*-
#
# 逆引き呼び出し元の最終到達点を一括CSV出力するヘルパースクリプト
#
# 対象メソッドをテキストファイルで指定し、各メソッドの逆引き最終到達点
# （最上位呼び元メソッド）とそのJavadocをCSVに出力します。
#
# 使用方法:
#   ./reverse_batch.sh [入力JSONファイル] [対象メソッドリストファイル] [出力CSVファイル]
#
# 例:
#   ./reverse_batch.sh analyzed_result.json target_methods.txt output.csv
#
# 出力CSVのエンコーディングはcp932です。
#

set -e

# デフォルト値
INPUT_JSON="analyzed_result.json"
METHODS_FILE=""
OUTPUT_CSV=""

# 引数の解析
usage() {
    echo "使用方法: $0 [入力JSONファイル] <対象メソッドリストファイル> <出力CSVファイル>"
    echo ""
    echo "引数:"
    echo "  入力JSONファイル       解析結果JSONファイル (デフォルト: analyzed_result.json)"
    echo "  対象メソッドリストファイル  対象メソッドを改行区切りで記載したファイル"
    echo "  出力CSVファイル        出力先CSVファイルパス"
    echo ""
    echo "例:"
    echo "  $0 analyzed_result.json target_methods.txt output.csv"
    echo "  $0 target_methods.txt output.csv  # デフォルトのJSONファイルを使用"
    exit 1
}

# 引数の数に応じて解析
if [ $# -eq 3 ]; then
    INPUT_JSON="$1"
    METHODS_FILE="$2"
    OUTPUT_CSV="$3"
elif [ $# -eq 2 ]; then
    METHODS_FILE="$1"
    OUTPUT_CSV="$2"
else
    usage
fi

# 必須ファイルの存在チェック
if [ ! -f "$INPUT_JSON" ]; then
    echo "エラー: 入力JSONファイルが見つかりません: $INPUT_JSON" >&2
    exit 1
fi

if [ ! -f "$METHODS_FILE" ]; then
    echo "エラー: 対象メソッドリストファイルが見つかりません: $METHODS_FILE" >&2
    exit 1
fi

# スクリプトのディレクトリを取得
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VISUALIZER="${SCRIPT_DIR}/../call_tree_visualizer.py"

if [ ! -f "$VISUALIZER" ]; then
    echo "エラー: call_tree_visualizer.py が見つかりません: $VISUALIZER" >&2
    exit 1
fi

# 一時ファイルを使用（処理終了時に削除）
TEMP_FILE=$(mktemp)
trap "rm -f $TEMP_FILE" EXIT

# ヘッダー行を書き込み
echo "対象メソッド,最終到達点のメソッド,最終到達点のメソッドのjavadoc" > "$TEMP_FILE"

echo "対象メソッドリストを読み込み中: $METHODS_FILE"

# 対象メソッドを1行ずつ処理
while IFS= read -r method || [ -n "$method" ]; do
    # 空行をスキップ
    [ -z "$method" ] && continue
    # コメント行をスキップ
    [[ "$method" =~ ^# ]] && continue

    echo "処理中: $method"

    # reverseサブコマンドを実行し、最終到達点のメソッド一覧を抽出
    # --verbose で Javadoc を表示
    # --tab でタブ区切りにして解析しやすくする
    # CRを除去してWindows改行に対応
    output=$(python "$VISUALIZER" -i "$INPUT_JSON" reverse "$method" --verbose --tab 2>/dev/null | tr -d '\r' || true)

    # 「最終到達点のメソッド一覧」セクションを抽出
    # セクションの開始を検出し、その後の行を収集
    in_section=false
    has_endpoint=false

    while IFS= read -r line; do
        # セクションの開始を検出
        if [[ "$line" == "最終到達点のメソッド一覧"* ]]; then
            in_section=true
            continue
        fi

        # セクション開始前の行はスキップ
        if ! $in_section; then
            continue
        fi

        # セクション内で区切り線が来たらスキップ
        if [[ "$line" == "="* ]]; then
            continue
        fi

        # 空行はスキップ
        if [ -z "$line" ]; then
            continue
        fi

        # セクション内のデータ行を処理（先頭にスペースがある行がデータ行）
        if [[ "$line" =~ ^[[:space:]] ]]; then
            has_endpoint=true

            # 行から先頭の空白を除去
            trimmed_line=$(echo "$line" | sed 's/^[[:space:]]*//')

            # タブで区切ってメソッドとJavadocを抽出
            # フォーマット: "メソッド\t〓Javadoc" または "メソッド"
            if [[ "$trimmed_line" == *$'\t'* ]]; then
                endpoint=$(echo "$trimmed_line" | cut -f1)
                # 〓を除去してJavadocを取得
                javadoc=$(echo "$trimmed_line" | cut -f2- | sed 's/^〓//')
            else
                endpoint="$trimmed_line"
                javadoc=""
            fi

            # CSVエスケープ（ダブルクオートを二重化、フィールドをダブルクオートで囲む）
            escape_csv() {
                local val="$1"
                val="${val//\"/\"\"}"
                echo "\"$val\""
            }

            csv_method=$(escape_csv "$method")
            csv_endpoint=$(escape_csv "$endpoint")
            csv_javadoc=$(escape_csv "$javadoc")

            echo "${csv_method},${csv_endpoint},${csv_javadoc}" >> "$TEMP_FILE"
        fi
    done <<< "$output"

    # 最終到達点が見つからなかった場合
    if ! $has_endpoint; then
        echo "\"$method\",\"(到達点なし)\",\"\"" >> "$TEMP_FILE"
    fi

done < "$METHODS_FILE"

# UTF-8からcp932に変換して出力
if command -v iconv &> /dev/null; then
    iconv -f UTF-8 -t CP932 "$TEMP_FILE" > "$OUTPUT_CSV" 2>/dev/null || cp "$TEMP_FILE" "$OUTPUT_CSV"
else
    # iconvがない場合はそのまま出力
    cp "$TEMP_FILE" "$OUTPUT_CSV"
fi

# 行数をカウント（ヘッダーを除く）
line_count=$(($(wc -l < "$OUTPUT_CSV") - 1))
echo "完了: $line_count 行を出力しました -> $OUTPUT_CSV"
