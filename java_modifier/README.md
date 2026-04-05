# Java Modifier

Spoonを利用し、Javaのソースコードを自動解析・変換するCLIツールです。
元のソースコードのフォーマット（インデント、コメントなど）を極力維持しつつ、パッケージの移動やアノテーションの付与・一括置換などを行うことができます。

<!-- @import "[TOC]" {cmd="toc" depthFrom=2 depthTo=6 orderedList=false} -->

<!-- code_chunk_output -->

- [ビルド方法](#ビルド方法)
- [使用方法](#使用方法)
  - [必須オプション](#必須オプション)
  - [環境設定プション](#環境設定プション)
  - [変換オプション](#変換オプション)
  - [アノテーション操作オプション](#アノテーション操作オプション)
- [注意事項 (フォーマット崩れの検知とリカバリ)](#注意事項-フォーマット崩れの検知とリカバリ)

<!-- /code_chunk_output -->


## ビルド方法

Java 21以上の環境が必要です。
以下のコマンドでビルドし、実行可能なJarファイルを生成します。

```bash
./gradlew shadowJar
```

ビルド後、プロジェクト直下に `java_modifier.jar` が生成されます。

## 使用方法

コマンドラインから以下の形式で実行します。

```bash
java -jar java_modifier.jar [オプション]
```

### 必須オプション
- `-s, --source <dir>`: 変換対象のソースディレクトリ。カンマ区切りで複数指定可能。

### 環境設定プション
- `-d, --destination <dir>`: 変換後のソースコード出力先ディレクトリ (デフォルト: `./output`)
- `-cp, --classpath <path>`: 依存するライブラリやJARファイル群のパス。ディレクトリを指定した場合は直下のJARファイルもすべて読み込まれます。
- `-e, --encoding <enc>`: ソースファイルの文字エンコーディング (デフォルト: `UTF-8`)
- `--newline <LF|CRLF>`: 出力されるソースコードの改行コード (デフォルト: `LF`)
- `-cl, --compliance <level>`: コンプライアンスレベル（デフォルト: 21）

### 変換オプション
最低1つ以上の変換オプションを指定する必要があります。

- `--replace-import <oldPrefix>:<newPrefix>`
  指定されたパッケージプレフィックスの参照を新しいプレフィックスに置換します。
  例: `--replace-import javax:jakarta`

- `--relocate-class <fqcnRegex>:<newPackage>`
  正規表現にマッチするクラスを、新しいパッケージに移動させます。移動対象のクラスに対する参照元も自動的に更新されます。
  例: `--relocate-class "com\.old\..*":com.new`

- `--remove-param <fqcn>`
  指定されたFQCNを型に持つ引数をメソッドやコンストラクタから削除し、対応するJavadocの `@param` も削除します。
  例: `--remove-param com.example.RemovedType`

- `--fqcn-to-import <fqcnRegex>`
  コード内で直接FQCNで参照されている型で、正規表現にマッチするものを、import文を利用した単純名での参照に変更します。
  例: `--fqcn-to-import "java\.util\..*"`

### アノテーション操作オプション
- `--add-annotation-field <typeRegex>:<annFqcn>`
  指定された型の正規表現にマッチするフィールドに対してアノテーションを付与します。
- `--add-annotation-type <classOrMethodRegex>:<annFqcn>`
  指定された正規表現の名前を持つクラス・インタフェース・メソッドに対してアノテーションを付与します。
- `--replace-annotation <oldAnnRegex>:<newAnnFqcn>`
  既存のアノテーションのうち、正規表現に完全一致するものを新しいアノテーション（FQCN）に置換します。
- `--remove-annotation <annRegex>`
  指定された正規表現に一致するアノテーションを削除します。
- `--add-annotation-by-annotation <targetAnnRegex>:<annFqcn>`
  指定されたアノテーションが付与されている構成要素（クラス、メソッド、フィールド、引数）に対して、新たなアノテーションを付与します。

## 注意事項 (フォーマット崩れの検知とリカバリ)
本ツールは元のフォーマットの維持を試みるため、内部で `SniperJavaPrettyPrinter` を使用しています。
クラスのパッケージ移動など、ASTの大規模な破壊的変更が生じた際、出力フォーマットの破綻（構文エラーや例外）を検知することがあります。
その場合、ツールは一部の対象クラスについて警告ログ（`WARN`）を出力し、標準の `DefaultJavaPrettyPrinter` へフォールバックしてASTから安全にコードを再生成する「リカバリ処置」を自動的に行います。
