# Dynasched (Android ネイティブ版)

React Native をやめ、**素の Kotlin + Android 標準API** だけで作り直したスケジューラアプリです。
外部サービス依存を極力排し、ビルドが安定し、エラーが出ても直しやすい構成にしています。

## 特徴 / 依存関係の少なさ

- **通信**: Android標準の `HttpURLConnection` + `org.json`（外部ライブラリ不要）
- **通知**: 端末内の `AlarmManager` で各予定の開始時刻にローカル通知。
  FCM等のプッシュサーバを使わないので、**アプリを閉じていても・通信が無くても鳴る**。
- **ログイン**: 既存バックエンドのGoogle認証をChrome Custom Tabで開き、
  `dynasched://auth?token=...` のディープリンクでトークンを受け取る。
- 使用ライブラリは AndroidX と Material の安定版のみ。バージョン整合の地獄がない。

## ビルド方法（Android Studio）

1. Android Studio を開く（無ければ https://developer.android.com/studio からインストール）。
2. 「Open」でこの `dynasched-android` フォルダを開く。
3. 初回は Gradle と Android SDK の同期が走る（数分）。プロンプトが出たら承認。
   - AGP/Gradle のアップグレードを勧められたら「承認(Update)」でOK。素のアプリなので問題は起きません。
4. 上部の緑の ▶（Run）で、接続した実機 or エミュレータにインストール。
   - APKだけ欲しい場合: メニュー Build → Build Bundle(s)/APK(s) → Build APK(s)。
     生成先は `app/build/outputs/apk/debug/app-debug.apk`。

### コマンドラインでビルドする場合

```
# Windows
gradlew.bat assembleDebug
# Mac/Linux
./gradlew assembleDebug
```

## 接続先サーバの変更

`app/build.gradle.kts` の以下1行だけ書き換えればOK:

```
buildConfigField("String", "API_BASE_URL", "\"https://api.togar.dev\"")
```

## バックエンド側の前提

このアプリは以下のエンドポイントを使います（既存サーバに実装済み）:

- `GET  /auth/google` → `{ "url": "..." }`
- `GET  /auth/callback` → 認証後 `dynasched://auth?token=...&email=...&name=...` にリダイレクト（要設定）
- `GET  /schedule?date=YYYY-MM-DD` → 7日分の予定配列
- `POST /schedule/:id/complete` → タスク完了（PATCHではなくPOST）
- `POST /scheduler/run` → スケジューラ再実行

## 画面構成

- **今日**: 今日の予定一覧。「完了」ボタンで完了。表示時に通知を自動予約。
- **週間**: 今日から7日分の予定一覧。
- **設定**: ユーザー情報、通知の再設定、スケジューラ再実行、ログアウト。
