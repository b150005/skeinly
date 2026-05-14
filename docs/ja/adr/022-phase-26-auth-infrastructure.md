# ADR-022 — Phase 26: 認証基盤 (OAuth + MFA + 生体認証再認証)

> **Status**: Accepted (2026-05-14)
> **Phase**: 26 (alpha launch 前 HARD-GATE)
> **Supersedes**: なし
> **Superseded by**: なし
> **Related**: ADR-005 (アカウント削除 RPC — 認証関連の cascade パスが新規 identity / MFA / 生体認証 state を生存させなければならない), ADR-017 (Push 通知 — KMP `expect/actual` + Supabase Edge Function パターンを本 ADR が OAuth ID-token 交換で再利用), ADR-021 (UGC モデレーション — Settings → プライバシーセクションが新規 MFA + ブロックユーザー + 生体認証コントロールをホスト), ADR-016 (サブスクリプション — マーケットプレイス文脈が金銭的価値発生前の auth-security 構築を動機付ける)
> **Tracking**: alpha launch tester invite の HARD-GATE。§6 に従い 7 サブスライス。サブスライス 26.0 (本 ADR + CLAUDE.md promotion) — コード変更なし。サブスライス 26.1 → 26.7 はそれぞれ独立してリリース可能、ただし TestFlight + Play Internal Testing tester invite 配信前に **全 7 つ** ship 必須。

EN 原典: [../../en/adr/022-phase-26-auth-infrastructure.md](../../en/adr/022-phase-26-auth-infrastructure.md)

## 1. 文脈

Skeinly の認証サーフェスは 2026-05-14 時点で **email/password のみ** (supabase-kt 3.6 `Auth` モジュール経由):

- `AuthRepository` (commonMain): `signInWithEmail` / `signUpWithEmail` / `signOut` / `deleteAccount` / `sendPasswordResetEmail` / `updatePassword` / `updateEmail` のみ。`signInWithIdToken` 呼び出し箇所なし。`mfa.enroll` / `challenge` / `verify` なし。生体認証統合ゼロ。
- `AuthRepositoryImpl`: Supabase Auth を `FlowType.PKCE` + `SettingsSessionManager` (Android `EncryptedSharedPreferences` per Pre-alpha A14 / iOS Keychain backed) でインスタンス化。
- `AuthViewModel`: 5 イベント sealed `AuthEvent` (`UpdateEmail` / `UpdatePassword` / `ToggleMode` / `Submit` / `ClearError` / `DismissEmailConfirmation`) のみ。OAuth-button / MFA-challenge / biometric-prompt イベントなし。
- `iosApp/iosApp/iosApp.entitlements`: `aps-environment = production` + `com.apple.developer.associated-domains` のみ。Apple Sign-In capability は App ID + Distribution Provisioning Profile レベルでは設定済み (vendor-setup A0a-1 / A0a-4) だが、**`com.apple.developer.applesignin` entitlement キーが app bundle に存在しない** — Apple Sign-In は構造的に非機能。
- Android 側 Google Sign-In 配線なし (`androidx.credentials` Credential Manager import / `play-services-auth` dep / Google client ID 登録すべて存在せず)。
- 唯一の「identity 記憶」パスは暗号化 Settings に永続化される supabase-kt refresh token のみ。ロックスクリーン形式の再認証は存在しない。

**ユーザーポリシーシフト 2026-05-14**: Phase 26 は元々 post-Phase-39-beta polish 項目として位置付け。Play Console A0d-6 Data Safety (アカウント作成方法 sub-question) 記入中、運用者が 2 つの収束圧力を surface:

1. **A0d-6 が宣言的回答を強制**。email/password 単独は alpha 時点では正直な回答だが、alpha を email/password にロックすると、alpha テスター期待値が元の product story と最も整合した時点での OAuth 宣言追加再提出を foreclose する。
2. **将来のユーザー間パターンマーケットプレイス**。運用者の戦略的方向 (2026-05-14): buyer / seller / creator アカウントが実金銭的価値 (購入履歴・支払い残高・出品収益) を持つマーケットプレイス計画。**マーケットプレイス開始後に確立ユーザーベースに auth-security をレトロフィット するのは、開始前構築より大幅に難しい** — 既存ユーザーは事後の強制 MFA enrollment に抵抗するし、レトロフィット window 中のアカウント乗っ取りインシデントは最も脆弱な市場形成 phase でトラストを毀損する。

エージェントチーム協議 (CLAUDE.md "### Planned — Phase 26 Auth Infrastructure" 2026-05-14) で決定: **完全な auth サーフェス — OAuth プロバイダ + MFA + 生体認証再認証 — を alpha launch 前に ship**。現時点の資産価値は MFA を必要としないことを明示的に認識した上での判断。**コスト・オブ・ナウ vs コスト・オブ・レイター**: ユーザーアカウント存在前に着地する auth コードの 1 行は、その同じ行が事後着地するより決定的に安価。

**ベンダー柱はほぼ準備済み** (Apple Developer Portal / Google Cloud / Supabase Dashboard 作業: Apple Sign-In は不要、Google Sign-In は部分作業必要):
- iOS Bundle ID は **Sign In with Apple capability** が App ID + Distribution Provisioning Profile に enable 済み。Phase 26.1 作業は entitlement キー追加 + `AuthenticationServices` 配線のみ — Portal 変更なし。
- Apple `.p8` + Team ID + Key ID は Phase 24 push 用に `APPLE_APNS_KEY_*` で登録済み。**Apple Sign-In には別の `.p8` が必要** (Apple 側 "service" 目的が異なる) — 既存 APNs `.p8` は再利用不可。
- Firebase project `Skeinly` (Blaze) の FCM SDK SHA-1 は debug + release signing key 両方で登録済み。**Google OAuth client ID 自体 (Web + iOS + Android) は Google Cloud Console の対応 GCP project に未プロビジョン**。Phase 26.2 で追加。
- Supabase Dashboard → Authentication → Providers は Email のみ enable。Phase 26.1 / 26.2 でそれぞれ Apple + Google プロバイダを enable。

## 2. 決定事項 (high-level)

1. **クロスプラットフォーム対称 OAuth カバレッジ**: Apple Sign-In + Google Sign-In を **両プラットフォーム** で。Apple Sign-In は App Store Review Guideline §4.8 により他の SSO 提供時 iOS で必須。Apple-on-Android はクロスプラットフォーム identity portability。Google-on-iOS は §4.8 対称可用性期待を満たす。Email/password は第三選択肢として保持。
2. **Supabase Auth 統合 = `signInWithIdToken(provider, idToken, nonce?)`** (両 OAuth プロバイダ); TOTP は native `auth.mfa.enroll({factorType: "totp"})` / `auth.mfa.challenge` / `auth.mfa.verify`。**生体認証はセッションゲート、primary identity factor ではない** — Supabase Auth はプラットフォーム生体認証を identity にネイティブバインドする手段を持たない。
3. **アカウントマージ意味論 = `linkIdentity` による明示的リンク**: OAuth ID-token email が既存 email/password ユーザーと一致した場合、「このメールは既に使用されています; リンクするにはまずパスワードでサインインしてください」画面を surface。自動マージは OWASP 古典の account-takeover ベクター。
4. **OAuth-first ユーザー identity フロー**: Apple "private relay" メール尊重 (相対メールが Supabase auth.users.email に surface、実メールは決して見えない)。Google は実 email + name + (時に) avatar URL を送信。**表示名ソース = first sign-in の OAuth-provided name → オンボーディングで確認プロンプト**、その後 Profile 画面で編集可能。Avatar = OAuth-provided picture URL を default として import (Phase 19 アバターアップロードで上書き可)。
5. **Sign In ボタン配置 = プラットフォーム慣例優先 + 副プロバイダ下配置**: iOS は Sign in with Apple を primary、"Continue with Google" を secondary。Android は Sign in with Google を primary (Credential Manager bottom sheet 経由)、"Continue with Apple" を secondary。**§4.8 対称可用性 + HIG / Material 慣例 primary**。
6. **MFA = alpha は opt-in; 将来マーケットプレイス seller/creator role はマーケットプレイス開始時 mandatory (Phase 40 GA ではない)**。alpha 理由: <10 テスターでの TOTP 採用率はゼロシグナル; mandatory TOTP の摩擦は alpha フィードバックループを能動的に害する。マーケットプレイス cutover: user-role transition で enforce (「seller になる」が seller-onboarding の一部として TOTP enrollment を促す); グローバル enforce は決してしない (既存ユーザーに事後 TOTP enrollment を強制するのは Phase 26 が回避するために存在するレトロフィットパターン)。
7. **TOTP リカバリーコード UX = 1 回限り表示 + スクリーンショット推奨警告 + デバイス紛失メール再バインド**。Regeneration パス: 現在の TOTP 入力 → サーバーが新リカバリーコード返却 + 旧コード無効化。デバイス紛失フロー: 元メールに magic-link 送信 → クリックで TOTP を一から再 enroll。**デバイス + メール両方紛失 = 設計上回復不能**。Admin-side break-glass なし: Skeinly に support model なし、social-engineering 攻撃サーフェスがレスキューアカウント数を上回る (<10K user 規模)。
8. **生体認証スコープ = 背景化閾値後の再認証ゲート + sensitive-action ゲート**。再認証閾値 default 5 分 (Settings 設定可); Settings → プライバシー → 生体認証 で opt-in。Sensitive-action ゲート (alpha): アカウント削除、MFA 無効化。Sensitive-action ゲート (将来マーケットプレイス): 購入確認、支払い方法変更。**Primary 生体認証ログインは延期** — supabase-kt は生体認証 template と refresh token をネイティブバインドしない; 再実装は client-side refresh-token 暗号化 (Android Keystore `setUserAuthenticationRequired(true)` / iOS Keychain `kSecAccessControlBiometryCurrentSet`) OR 生体認証 attestation header gate 付き Edge Function。Phase 26.6 でも 200-300 LOC + dedicated テストカバレッジ必要; alpha では value-add 小。Real signal surface 時に別 ADR で再開。

## 3. 詳細決定 (要約)

### 3.1 OAuth プロバイダスコープ
- product-manager: alpha は摩擦最小オンボーディング必要; marketplace lens で cross-platform portability 必須。
- architect: 実装サーフェスは Apple-iOS / Apple-Android / Google-iOS / Google-Android で対称; 4 path が 1 backend call (`signInWithIdToken`) に集約。
- security-reviewer: Apple Sign-In は最強 (Apple 強制 2FA); Google は次。Apple-on-Android web-OAuth は高摩擦だが strength 低下しない。対称カバレッジが正解。
- ui-ux-designer: HIG は iOS で Apple primary を要求。Material はそうではないが Credential Manager bottom sheet が de facto。
- implementer: Apple-on-Android は Apple Developer Portal で "web auth" service として Services ID 設定必要 + Custom Tabs (`androidx.browser:browser`)。Google-on-iOS は `googlesignin-ios` SwiftPM package が新規 dep。
- knitter: 編み手は median 38 歳、Apple/Google ボタンを password form より認識・信頼。

**決定**: Apple + Google を両プラットフォーム + email/password 保持。iOS native Apple flow + GIDSignIn for Google。Android native Credential Manager for Google + Custom Tabs/web-OAuth for Apple。

### 3.2 Supabase Auth 統合パス
- `signInWithIdToken` (no in-app redirect; native SDK で ID token 取得して Supabase に直接 POST) > `signInWithOAuth` (in-app browser hop + deep-link routing が Phase 39 W5b Universal Links と干渉)。
- MFA: `auth.mfa.enroll` (`secret` + `qr_code_svg` 返却) + 2-step verify (`challenge` returns challenge_id, `verify` accepts code)。
- 生体認証: Supabase 非関与、純 client-side LocalAuthentication / BiometricPrompt outcome。

**決定**: `signInWithIdToken` + `auth.mfa.{enroll,challenge,verify,unenroll,listFactors}`。生体認証は client-only。

### 3.3 アカウントマージ意味論
- security-reviewer: silent 自動マージは古典的 OAuth account-takeover ベクター (攻撃者が victim メールで OAuth provider 側に登録 → 自動マージで victim アカウントアクセス)。Hard veto。
- 設計: Supabase デフォルト email-collision は `{ "error": "user_already_exists" }` で reject。 `AuthRepositoryImpl` でキャッチ → 新規 domain state `AuthState.LinkIdentityRequired(email, pendingIdToken, provider)` を surface。LoginScreen が「このメールは既に使用; パスワードでサインインしてリンク」フォームにルート。 password sign-in 成功後 `client.auth.linkIdentity(provider)` が ID token を current session JWT と共に POST、サーバーが email 一致検証で takeover 防止。
- 摩擦は account-once-per-lifetime; UX dead-end (first-claim-wins で 2 アカウント作成) より遥かにマシ。

**決定**: 明示的リンク via `linkIdentity`。`AuthState.LinkIdentityRequired` が pending ID token を password sign-in step 経由で運ぶ。

### 3.4 OAuth-first ユーザー identity ハンドリング
- Apple privaterelay: `<random>@privaterelay.appleid.com` を Supabase に surface。Apple が実アドレスに proxy。永続。Settings → アカウントで relay status を surface (「Apple ID 経由 — Apple のプライベートメールリレーを使用中」)。
- Apple は user.user_metadata.name を **first sign-in のみ** に含める; その後は欠落。Google は毎回 `name` + `picture` + `email_verified`。
- 表示名: first sign-in の OAuth name で onboarding 事前入力 → ユーザー確認 → Profile 編集。欠落時はプロンプト。
- Avatar: Google `user_metadata.picture` がある場合は onboarding で「この画像を avatar に使う?」one-tap import + alternative。

**決定**: 表示名 OAuth-provided first-sign-in pre-fill + onboarding 確認。Avatar Google picture URL を default。Apple-relay は Settings で明示表示。

### 3.5 Sign In ボタン配置・順序

| プラットフォーム | Primary | Secondary | Tertiary |
|---|---|---|---|
| iOS | Sign in with Apple (HIG-conformant `SignInWithAppleButton`、フォーム上部) | Continue with Google (custom-styled `Button`) | Email/Password (デフォルトで「or sign in with email」トグル下に折り畳み) |
| Android | Continue with Google (Credential Manager 起動 `OutlinedButton`、フォーム上部) | Continue with Apple (Custom Tabs + Supabase web-OAuth) | Email/Password (同トグル) |

Email/password がトグル下に折り畳まれているのは OAuth へ誘導するためだが、同視覚階層内に discoverable。

### 3.6 MFA opt-in vs mandatory
- alpha = opt-in: 5-10 テスターに enrollment 強制 = 100% drop-off。
- security-reviewer: alpha 資産価値ほぼゼロ、強制は過保護。Marketplace seller/creator は実金銭 → role-transition で enforce が正解 boundary。
- architect: `AuthRepository.requireMfa()` が `auth.mfa.listFactors()` を非空かつ verified を check; seller-onboarding が gate。Phase 26 enrollment + verification 機構を ship、Phase 50+ がリクエスト gate を配線。

**決定**: alpha + Phase 40 GA 一般ユーザーは opt-in; マーケットプレイス seller/creator role は role-transition で mandatory (Phase 50+、別 ADR)。Settings → プライバシー → 2 要素認証 が alpha entry surface。

### 3.7 TOTP リカバリーコード UX
- security-reviewer: 1 回限り表示は譲歩不可。Client-side ストレージは目的を defeat。事後メール送信は email-compromise → account-compromise パス。Regeneration は現在の TOTP 要求 (盗まれたコードで legitimate user の他コード revoke 不可)。
- 実装: Supabase Auth は recovery code を native サポートせず。自前で `mfa_recovery_codes` 表 + 2 RPCs (`register_mfa_recovery_code(p_code_hash)` SECURITY DEFINER + `consume_mfa_recovery_code(p_code)` SECURITY DEFINER)。bcrypt hash のみ保存 (plaintext は決して保存せず)。single-use enforced。
- UI フロー: enrollment → QR 表示 + manual secret 表示 → 初回 6 桁コード verify → full-screen「リカバリーコードを保存してください」16 桁コード copyable + 「保存しました」CTA (5 秒間 disabled) + screenshot ヒント。非スキップ。
- Lost-device: magic-link 元メールへ → クリックで TOTP 再 enroll。**両方紛失 = 回復不能**。Admin-side break-glass なし — social-engineering 攻撃サーフェス vs <10K user 規模での rescued-account ≈ 0。

**決定**: 16 桁 base32, client-generated enrollment 時, bcrypt hash サーバー保存, 1 回限り plaintext 表示 (非スキップ)。Regeneration は現 TOTP 要求。Lost-device は magic-link 経由。Lost-both = 回復不能、admin-side break-glass なし。

### 3.8 生体認証スコープ
- security-reviewer: 生体認証は OS-mediated gate、Bool 返却のみ。Identity 識別せず、cryptographic material unlock せず。攻撃ベクター "睡眠中のユーザーの顔/指紋を使う" は bounded — 生体認証なしでも変わらない。
- 設計: `LAContext.evaluatePolicy(.deviceOwnerAuthentication)` / `BiometricPrompt.PromptInfo`。PIN/passcode fallback default 許可 (生体認証 enroll 不可ユーザーも使える)。
- 閾値: `Settings` (multiplatform-settings preference) keyed `biometric_reauth_threshold_seconds`、default 300 (5 分)。`BiometricGuardian` が foreground 復帰時 `(now - lastBackgroundedAt) > threshold` AND `biometricEnabled = true` で fire。
- Opt-in surface: Settings → プライバシー → 生体認証 トグル。
- Sensitive-action ゲート: アカウント削除、MFA 無効化 (alpha)。購入確認、支払い方法変更 (将来マーケットプレイス、Phase 50+)。
- Primary 生体認証ログイン延期: supabase-kt にネイティブバインドなし; 再実装 (Android Keystore biometric-protected key で refresh token wrap / iOS Keychain `kSecAccessControlBiometryCurrentSet`) は 200-300 LOC + dedicated テストサーフェス、alpha の value-add 小 (refresh token は backgrounding 跨いで永続)。

**決定**: 5 分閾値後の foreground 再認証 (設定可、opt-in)。Sensitive-action ゲート: アカウント削除 + MFA 無効化 (alpha)。Primary 生体認証ログイン延期 — real signal 時別 ADR。PIN/passcode fallback default 許可。

## 4. プライバシー + セキュリティ要約

- **OAuth プロバイダ identity データフロー**: Apple は first-sign-in のみ `user.user_metadata.name` 提供; subsequent は省略。Apple email は private relay 既定。Google は毎回 `name` + `email` + `picture` + `email_verified` 提供。両 flow とも ID token `sub` claim (provider user ID) を `auth.identities.identity_data` に保存、`profiles` 表には保存せず。**OAuth refresh token は決して見えない** — Supabase が内部管理。ID token は single-use (`signInWithIdToken` で 1 回消費)。
- **MFA enrollment artifacts**: TOTP secret は Supabase `auth.mfa_factors` に Supabase 管理 key で at-rest 暗号化保存。QR コードは client-side で TOTP secret URI から生成、ログ出力せず。リカバリーコード: 16 桁 base32、enrollment 時 1 回限り表示、bcrypt hash サーバー保存 via `register_mfa_recovery_code` RPC。Plaintext は client-side / server-side 双方で永続化せず。
- **生体認証データ**: デバイス外には決して出ない。LocalAuthentication / BiometricPrompt は app に success / failure のみ返却。生体認証 template は OS 管理 (iOS Secure Enclave / Android TEE)。App は template データ・指紋カウント・生体属性すべて受信せず。
- **アカウント削除 (ADR-005) 相互作用**: ADR-005 の `delete_own_account` RPC は `auth.users → identities + mfa_factors + sessions` カスケード。新規 `mfa_recovery_codes` 表は `user_id` で `ON DELETE CASCADE` なので自動クリーンアップ。Phase 27 data-wipe (アカウント保持) は identity / MFA factor を **wipe しない** (auth 側行は data wipe で保持される — Phase 27 ADR-023 §3.1 保持マトリックス参照)。
- **プライバシーポリシー更新 (Phase 26.7)**:
  - 新規 `<h3>`: 「OAuth サインイン (Apple, Google)」— 各プロバイダが送信する identity データ、Apple relay 意味論、ID token single-use、revocation OS-mediated を宣言。
  - 新規 `<h3>`: 「多要素認証」— TOTP secret 保管先 (Supabase 管理暗号化)、リカバリーコード 1 回限り表示 + hash-only 保存、デバイス紛失フロー、両方紛失 = 回復不能を開示。
  - 新規 `<h3>`: 「生体認証」— 生体認証データはデバイス外に出ない、app は success/failure のみ受信、template OS 管理を宣言。
  - JA mirror 3 subsection: `docs/public/ja/privacy-policy/index.html`。

## 5. サブスライス計画

7 実装サブスライス、各独立 ship 可能。**全 7 が TestFlight + Play Internal Testing tester invite 配信前に ship 必須** (HARD-GATE)。

### Phase 26.0 — ADR cut (本スライス)
- 本 ADR + JA summary。
- CLAUDE.md promotion を active roadmap へ (parent session が両 Phase 26 + Phase 27 ADR 戻り後にハンドル)。
- コードゼロ、テストゼロ、migration ゼロ、i18n ゼロ。

### Phase 26.1 — iOS Apple Sign-In 配線
**Ship**:
- `iosApp.entitlements`: `com.apple.developer.applesignin` = `["Default"]` 追加。
- `LoginScreen.swift`: フォーム上部に `SignInWithAppleButton(.signIn, ...)`。`request.requestedScopes = [.fullName, .email]` + `request.nonce = sha256(nonce)`。`onCompletion` で `ASAuthorizationAppleIDCredential.identityToken` を String へ decode、KoinHelper bridge へ forward。
- 新規 `AppleSignInBridge.swift`: SwiftUI ↔ Kotlin bridge。
- 新規 commonMain expect: `expect class OAuthClient`。
- 新規 `AuthRepository.signInWithApple(idToken, nonce)` + `AuthRepositoryImpl` 実装。
- Domain state 拡張: `AuthState.LinkIdentityRequired(email, pendingIdToken, provider)` data class。
- Supabase Dashboard: Apple provider enable (user-side action)。

**i18n keys**: `action_sign_in_with_apple`, `label_or_sign_in_with_email`, `body_email_already_used_oauth_link_prompt` (~3 keys)。
**Tests**: 12 commonTest。
**Release-secrets**: `APPLE_SIGNIN_KEY_P8` + `APPLE_SIGNIN_KEY_ID` + `APPLE_SIGNIN_SERVICES_ID` (Apple Developer Portal 別 `.p8`、APNs と異なる)。Supabase Dashboard provider config に登録、GitHub Secret には登録せず。
**Operator アクション**: Apple Developer Portal で Services ID 作成 + Sign In with Apple Key 作成 + Supabase Dashboard provider config 入力。

### Phase 26.2 — Android Google Sign-In 配線 + iOS Google サポート
**Ship**:
- `androidApp/build.gradle.kts`: `androidx.credentials:credentials:1.3.0`, `androidx.credentials:credentials-play-services-auth:1.3.0`, `com.google.android.libraries.identity.googleid:googleid:1.1.1`。
- 新規 `GoogleSignInActivityResultProvider.kt`: `CredentialManager` host + `getGoogleIdToken()` suspend method。
- `OAuthClient.android.kt` actual。
- 新規 `AuthRepository.signInWithGoogle(idToken, nonce?)`。
- Compose `LoginScreen.kt`: Google G logo の `OutlinedButton` + 「Continue with Google」label。
- iOS: `googlesignin-ios` (8.x) SwiftPM dep + `GoogleSignInBridge.swift`。LoginScreen.swift に secondary 「Continue with Google」ボタン。
- Supabase Dashboard: Google provider enable (Web client ID + secret + Android + iOS client ID)。

**i18n keys**: `action_sign_in_with_google`, `action_continue_with_google` (~2 keys)。
**Tests**: 14 commonTest。
**Release-secrets**: `GOOGLE_OAUTH_WEB_CLIENT_ID`, `GOOGLE_OAUTH_IOS_CLIENT_ID`, `GOOGLE_OAUTH_ANDROID_CLIENT_ID` (3 entries)。
**Operator アクション**: Google Cloud Console で 3 OAuth 2.0 client ID 作成 (Web + iOS + Android、Bundle ID / Package Name / SHA-1 を tied) + Supabase Dashboard 入力。

### Phase 26.3 — `linkIdentity` 経由のアカウントマージ
**Ship**:
- `AuthRepositoryImpl`: `signInWithIdToken` から `user_already_exists` catch、`AuthState.LinkIdentityRequired(email, pendingIdToken, provider)` surface。
- `LoginScreen` Compose + SwiftUI: `LinkIdentityRequired` state で inline link form。
- 新規 `AuthRepository.linkPendingIdentity(pendingIdToken, provider)`。
- 新規 `AuthEvent.SubmitLinkIdentity(password)` + `AuthEvent.DismissLinkIdentity`。
- `AuthViewModel`: 両 event ハンドル; submit で email sign-in → 成功で `linkPendingIdentity` → 両 identity attach。

**i18n keys**: `title_link_identity`, `body_email_already_used_link_prompt`, `action_link_identity`, `action_dismiss_link_identity`, `state_linking_identity` (~5 keys)。
**Tests**: 18 commonTest (full state machine + permutations including expired pending token + wrong-password lockout + retry-from-Settings)。

### Phase 26.4 — TOTP MFA enrollment + challenge + recovery
**Ship**:
- Migration 033: `mfa_recovery_codes` 表 + RLS + 2 RPCs (`register_mfa_recovery_code` / `consume_mfa_recovery_code`、ともに SECURITY DEFINER + `search_path = public, pg_temp` ロック)。
- 新規 `AuthRepository` methods: `enrollMfaTotp() → MfaEnrollmentResult` (`{ secret, qrCodeUri, recoveryCode }`), `verifyMfaChallenge`, `disableMfa`, `regenerateRecoveryCode`, `consumeRecoveryCode`。
- 新規 `MfaEnrollmentScreen` + `MfaChallengeScreen` + `MfaRecoveryCodeScreen` (Compose + SwiftUI)。
- Settings → プライバシー → 2 要素認証 row。
- LoginScreen: password sign-in 後 verified factor 存在で MfaChallengeScreen へ route → success で Supabase が session を AAL2 に昇格。

**i18n keys**: ~14 (enrollment + challenge + recovery + disable confirm 関連)。
**Tests**: 28 commonTest (12 RepositoryImpl + 8 EnrollmentVM + 8 ChallengeVM)。
**Operator アクション**: なし (Supabase Auth MFA は project setting で既定 enable、確認のみ)。

### Phase 26.5 — 生体認証再認証 + sensitive-action ゲート
**Ship**:
- `androidApp/build.gradle.kts`: `androidx.biometric:biometric:1.2.0-alpha05`。
- 新規 commonMain expect: `expect class BiometricAuthenticator`。
- Android actual: `BiometricPrompt.PromptInfo.Builder` via `FragmentActivity`。
- iOS actual: `LAContext.evaluatePolicy(.deviceOwnerAuthentication, ...)` via `suspendCancellableCoroutine`。
- 新規 `BiometricGuardian` shared service: `requireForResume()` + `requireForAction(SensitiveAction)`。
- App-lifecycle observer (commonMain) から `requireForResume()` 配線。
- `DeleteAccountUseCase` + `disableMfa` パスで sensitive-action gate 配線。
- 新規 `BiometricSettingsScreen`: toggle + 閾値ピッカー (1分 / 5分 / 15分 / 1時間)。
- Settings → プライバシー → 生体認証 row。

**i18n keys**: ~14 (settings / state / threshold value / reason / fallback / availability state)。
**Tests**: 16 commonTest (8 BiometricGuardian + 8 SettingsViewModel)。
**Operator アクション**: なし (client-only)。

### Phase 26.6 — Onboarding 統合 + 表示名 + avatar 処理
**Ship**:
- Onboarding 拡張: OAuth sign-in 成功後 `profiles.display_name` null かつ `user.user_metadata.name` 存在で onboarding 「お名前は?」画面に事前入力。
- Avatar import: `user.user_metadata.picture` URL 存在 (Google) で「この画像を avatar に使う?」one-tap import。
- 新規 `ImportOAuthAvatarUseCase`: picture URL → Supabase Storage `avatars` バケット → `profiles.avatar_url` 更新。
- Settings → アカウント: provider 別状態表示 (relay / Google / email)、linked identity リスト。

**i18n keys**: ~7。
**Tests**: 18 commonTest (UseCase 6 + Onboarding VM 6 + Settings 6)。

### Phase 26.7 — プライバシーポリシー更新 + smoke test
**Ship**:
- `docs/public/privacy-policy/index.html` (EN): 3 新規 `<h3>` subsection (OAuth Sign-In / MFA / Biometric)。
- `docs/public/ja/privacy-policy/index.html` (JA mirror): 同 3 subsection の日本語版。
- A0d-6 Data Safety vendor-setup checklist: 26.7 close 後にアカウント作成方法を email/password + OAuth (Apple, Google) + MFA で再提出する recipe 更新。
- `docs/en/ops/auth-smoke-test.md`: end-to-end smoke test recipe (Apple Sign-In on real iOS / Google on Android + iOS / link flow / TOTP / biometric / sensitive-action ゲート)。

**i18n keys**: 0 (HTML のみ)。
**Tests**: 0 commonTest delta。
**Operator アクション**: smoke test 実行 + Play Data Safety + App Store privacy nutrition fact 再提出。

## 6. commonTest delta 推定

| サブスライス | commonTest 追加 |
|---|---|
| 26.1 Apple Sign-In | 12 |
| 26.2 Google Sign-In | 14 |
| 26.3 linkIdentity | 18 |
| 26.4 TOTP MFA | 28 |
| 26.5 Biometric | 16 |
| 26.6 Onboarding | 18 |
| 26.7 Privacy + smoke | 0 |
| **合計** | **106** |

Baseline (CLAUDE.md Phase 39 W5b 時点): 1536 commonTest。Phase 26 完了後: ~1642 commonTest。

## 7. 検討した代替案 (要約)

- **A1. Passkey (FIDO2/WebAuthn)**: Supabase ネイティブサポートなし; post-Phase-40 GA で Supabase native support 着地時に再開。
- **A2. SMS-based MFA**: SIM-swap + SS7 + コスト。TOTP より厳密に弱い。再開なし。
- **A3. ハードウェアセキュリティキー**: 物理コスト + モバイルアプリで過剰エンジニアリング。再開なし。
- **A4. WebView カスタム auth UI**: HIG 違反。再開なし。
- **A5. 電話番号 + SMS-OTP プライマリ**: identity モダリティ重複; recovery story 断片化。Post-GA で emerging-market セグメント surface 時再開。
- **A6. Magic-link プライマリ**: 毎 sign-in 摩擦過大。MFA recovery で部分使用; プライマリ移行は post-GA。
- **A7. OAuth 完全スキップ (alpha email/password only)**: A0d-6 lock-in + マーケットプレイス retrofit 痛苦。2026-05-14 policy shift で settled。再開なし。
- **A8. 自動マージ**: account-takeover ベクター。Hard veto。再開なし。
- **A9. First-claim-wins (重複アカウント作成)**: UX dead-end。再開なし。

## 8. 帰結

**Positive**:
- Alpha tester は day-one で frictionless Apple/Google sign-in を体験。OAuth uptake が provider preference シグナルを提供しマーケットプレイス seller-onboarding UX に反映。
- Cross-platform identity portability built-in。Apple-iOS で sign up → Apple-Android で recover が機能。
- MFA 基盤がマーケットプレイス seller-onboarding mandatory enforcement (Phase 50+) でレトロフィット不要 ready。
- 生体認証基盤が account deletion / MFA disable で sensitive-action UX 改善。マーケットプレイスで purchase confirm / payout-method change 再利用。
- 4-provider × 2-platform OAuth が `signInWithIdToken` 両 path を alpha スケールで test、supabase-kt 3.6 quirks (nonce ハンドリング、identity_data 形状) を low-stakes で surface。
- プライバシーポリシー開示が App Store / Play Console reviewer に security maturity シグナル送信、Phase 40 GA 後の "user identity 取り扱いの確認?" 質問を pre-empt。
- A0d-6 Data Safety 再提出 = 26.7 後の 1 回イベント; account-creation-methods 完全宣言を事前 lock-in、後付け retrofitting なし。

**Negative**:
- 実装サーフェスは real: 7 サブスライス、~106 新規 commonTest、4 新規 UI 画面、両プラットフォーム LoginScreen 拡張、onboarding 拡張、Settings → プライバシー 拡張。
- 新規 Apple Developer Portal 依存: Services ID + Apple Sign-In 用別 `.p8` (既存 APNs `.p8` と異なる)。
- 新規 Google Cloud Console 依存: 3 OAuth 2.0 client (Web + iOS + Android)、各 Bundle ID / Package Name / SHA-1 fingerprint tied。
- 新規 Supabase Auth Dashboard provider config (Apple + Google) — migration-versioned ではない。`docs/en/ops/auth-provider-setup.md` で config recipe 再現。
- 6 新規 release-secrets entries (3 Apple + 3 Google) が credential-custody サーフェス拡大。Dashboard provider config 専用、client bundle 非埋め込み、CI 非消費。
- Lost-both-device-AND-email account-recovery dead-end が real user impact。Enrollment flow で「リカバリーコード を保存 AND メールを覚えておいてください」を prominent 警告。
- supabase-kt 3.6 MFA API は GA だが alpha プールで heavily 試行されていない。28 dedicated MFA commonTest + Phase 26.7 smoke test で mitigation。
- `mfa_recovery_codes` 表 + 2 RPCs が server サーフェス追加 (RLS + search_path lock + bcrypt computation 必要)。Migration 033 が確立 pattern に従う。

**Tracking**:
- Phase 26.0 = 本 ADR + CLAUDE.md `### Planned — Phase 26` (parent session が active section に promote)。
- Phase 26.1 → 26.7 はそれぞれ独立 commit。
- HARD-GATE: alpha-launch tester invite は Phase 26.7 smoke-test pass まで blocked。
- Phase 27 (data wipe, ADR-023) と並行 — 両 ADR は same session 2026-05-14 で cut。

## Revision history

| Date | Status | Author | Change |
|---|---|---|---|
| 2026-05-14 | Accepted | claude-opus-4-7[1m] | 初版。Pre-alpha HARD-GATE 指定 (operator policy shift 2026-05-14)。8 決定をエージェントチーム協議で resolve (CLAUDE.md "Output Quality Standard")。7 サブスライス計画 (26.1–26.7) — alpha tester invite 前に **全 ship 必須**。スコープカット: Passkey (A1)、SMS-MFA (A2)、ハードウェアキー (A3)、WebView カスタム UI (A4)、電話番号 OTP (A5)、Magic-link primary (A6)、email-only alpha (A7)、自動マージ (A8)、first-claim-wins (A9)。6 新規 release-secrets。~106 commonTest delta。1 新規 migration (033 `mfa_recovery_codes`)。1 新規 entitlement (`com.apple.developer.applesignin`)。1 新規 Apple `.p8` (APNs と別)。3 新規 Google Cloud OAuth 2.0 client (Web + iOS + Android)。 |
| 2026-05-14 | Amendment | claude-opus-4-7[1m] | Phase 26.6 ship 時の修正: §6.5 初版で「MainActivity は `ComponentActivity` で `FragmentActivity` を継承」と書いたが、AndroidX のクラス階層は **逆** (`FragmentActivity` が `ComponentActivity` を継承)。`BiometricPrompt(FragmentActivity, Executor, AuthenticationCallback)` constructor は `FragmentActivity` を要求するため、Phase 26.6 で `MainActivity : ComponentActivity()` → `MainActivity : FragmentActivity()` にピボット。既存の全 surface (`setContent` / `enableEdgeToEdge` / `registerForActivityResult` / `rememberNavController` 等) は無変更で動作 (`FragmentActivity` が `ComponentActivity` を継承するため)。あわせて §6.5 初版は「Primary biometric login」を将来 200–300 LOC で実装可能と記載していたが、Phase 26.6 は re-auth + sensitive-action gate surface のみを ship し、Primary biometric login は当初スコープ通り別 ADR で起票する。 |
