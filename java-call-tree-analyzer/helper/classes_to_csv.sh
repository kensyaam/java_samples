#!/bin/bash
# ============================================================
# analyzed_result.json の classes セクションを CSV/TSV に変換するスクリプト
# ============================================================
# 使用方法:
#   ./classes_to_csv.sh [input.json] [output_basename]
#
# 引数:
#   input.json       : analyzed_result.json (デフォルト: analyzed_result.json)
#   output_basename  : 出力ファイルのベース名（省略時はclass_list）
#
# 出力:
#   <output_basename>.csv : CSV形式のファイル
#   <output_basename>.tsv : TSV形式のファイル
#
# 出力項目:
#   - className, javadoc, annotations, superClass, hitWords
# ============================================================

set -e

# 引数処理（両方オプショナル）
INPUT_FILE="${1:-analyzed_result.json}"
OUTPUT_BASENAME="${2:-class_list}"

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

# CSV形式で出力（カンマ区切り）
OUTPUT_CSV="${OUTPUT_BASENAME}.csv"
echo "CSV出力: $OUTPUT_CSV"

# ヘッダー行
echo '"className","javadoc","annotations","superClass","hitWords"' > "$OUTPUT_CSV"
# classes の場合
jq -r '.classes[] | [
    .className // "",
    .javadoc // "",
    ((.annotations // []) | join(",")),
    .superClass // "",
    ((.hitWords // []) | join(","))
] | @csv' "$INPUT_FILE" >> "$OUTPUT_CSV"

# TSV形式で出力（タブ区切り）
OUTPUT_TSV="${OUTPUT_BASENAME}.tsv"
echo "TSV出力: $OUTPUT_TSV"

# ヘッダー行
echo -e "className\tjavadoc\tannotations\tsuperClass\thitWords" > "$OUTPUT_TSV"
# classes の場合
jq -r '.classes[] | [
    .className // "",
    (.javadoc // "" | gsub("\t"; " ") | gsub("\n"; " ")),
    ((.annotations // []) | join(",") | gsub("\t"; " ")),
    .superClass // "",
    ((.hitWords // []) | join(","))
] | @tsv' "$INPUT_FILE" >> "$OUTPUT_TSV"

echo "完了!"
echo "  CSV: $OUTPUT_CSV"
echo "  TSV: $OUTPUT_TSV"
