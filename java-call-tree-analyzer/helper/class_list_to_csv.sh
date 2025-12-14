#!/bin/bash
# ============================================================
# class-hierarchy.json / interface-implementations.json を CSV/TSV に変換するスクリプト
# ============================================================
# 使用方法:
#   ./class_list_to_csv.sh <input.json> [output_basename]
#
# 引数:
#   input.json       : class-hierarchy.json または interface-implementations.json
#   output_basename  : 出力ファイルのベース名（省略時はinputファイル名を使用）
#
# 出力:
#   <output_basename>.csv : CSV形式のファイル
#   <output_basename>.tsv : TSV形式のファイル
#
# 出力項目:
#   - name    : クラス名またはインターフェース名
#   - javadoc : Javadoc要約
# ============================================================

set -e

# 引数チェック
if [ $# -lt 1 ]; then
    echo "使用方法: $0 <input.json> [output_basename]"
    echo "例: $0 class-hierarchy.json class_list"
    echo "    $0 interface-implementations.json interface_list"
    exit 1
fi

INPUT_FILE="$1"
OUTPUT_BASENAME="${2:-$(basename "$INPUT_FILE" .json)}"

# 入力ファイルの存在チェック
if [ ! -f "$INPUT_FILE" ]; then
    echo "エラー: ファイルが見つかりません: $INPUT_FILE"
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

echo "処理中: $INPUT_FILE"

# JSONの種類を判定（classesキーがあればclass-hierarchy、interfacesキーがあればinterface-implementations）
JSON_TYPE=$(jq -r 'if .classes then "class" elif .interfaces then "interface" else "unknown" end' "$INPUT_FILE")

if [ "$JSON_TYPE" == "unknown" ]; then
    echo "エラー: JSONファイルの形式が認識できません。class-hierarchy.json または interface-implementations.json を指定してください。"
    exit 1
fi

# CSV形式で出力（カンマ区切り）
OUTPUT_CSV="${OUTPUT_BASENAME}.csv"
echo "CSV出力: $OUTPUT_CSV"

# ヘッダー行
echo '"name","javadoc"' > "$OUTPUT_CSV"

if [ "$JSON_TYPE" == "class" ]; then
    # class-hierarchy.json の場合
    jq -r '.classes[] | [
        .className // "",
        .javadoc // ""
    ] | @csv' "$INPUT_FILE" >> "$OUTPUT_CSV"
else
    # interface-implementations.json の場合
    jq -r '.interfaces[] | [
        .interfaceName // "",
        .javadoc // ""
    ] | @csv' "$INPUT_FILE" >> "$OUTPUT_CSV"
fi

# TSV形式で出力（タブ区切り）
OUTPUT_TSV="${OUTPUT_BASENAME}.tsv"
echo "TSV出力: $OUTPUT_TSV"

# ヘッダー行
echo -e "name\tjavadoc" > "$OUTPUT_TSV"

if [ "$JSON_TYPE" == "class" ]; then
    # class-hierarchy.json の場合
    jq -r '.classes[] | [
        .className // "",
        (.javadoc // "" | gsub("\t"; " ") | gsub("\n"; " "))
    ] | @tsv' "$INPUT_FILE" >> "$OUTPUT_TSV"
else
    # interface-implementations.json の場合
    jq -r '.interfaces[] | [
        .interfaceName // "",
        (.javadoc // "" | gsub("\t"; " ") | gsub("\n"; " "))
    ] | @tsv' "$INPUT_FILE" >> "$OUTPUT_TSV"
fi

echo "完了!"
echo "  CSV: $OUTPUT_CSV"
echo "  TSV: $OUTPUT_TSV"
