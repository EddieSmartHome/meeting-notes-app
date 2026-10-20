# 會議速記 App：協作規則

開始工作前先讀 `docs/DEVELOPMENT_NOTES.md`（需求、決策、架構、待辦）。

## 版本號規則（使用者指定，必須遵守）

1. **修改任何 App 功能之前，先問使用者要不要推進版本號。** 使用者主動說要推進時，才推進，不必再問。
2. **推進版本時：**
   - 先比對和上一版的實際差異（`git diff <上一版最後一次成功編譯的提交> -- . ':(exclude)docs' ':(exclude)README.md'`），依差異整理更新內容，不憑記憶。基準提交到 Actions 查上一版 Release 最後一次成功的編譯；不推版的重新編譯不會移動 Tag，所以不要只看 Tag
   - 修改 `VERSION`
   - 在 `docs/CHANGELOG.md` 最上方（`# 版本更新紀錄` 之下）新增 `## vX.Y.Z（YYYY-MM-DD）` 段落，條列使用者看得懂的變更（Release 說明會自動擷取這段）
   - 日期要和星期核對一致
3. **不推進版本時：** 版號維持不變，編譯結果會覆蓋同版 Release 的 APK；變更補記到 `docs/CHANGELOG.md` 目前版本的段落。
4. **只改說明文件時（`README.md`、`docs/`、`CLAUDE.md`）不要觸發編譯**：`README.md` 與 `docs/` 已設定不觸發；其他純文件提交在提交訊息加上 `[skip ci]`。

## 其他規則

- 對外文件、截圖、App 內範例文字一律用虛構內容，不可出現使用者本人、公司或客戶的名稱與業務資料
- App 只使用免費服務為預設（目前為 Gemini 免費方案）；付費服務僅作為選項
- 金鑰欄位不假設金鑰的開頭格式
- 提交訊息不可加上 `Claude-Session` 對話連結（使用者指定）；提交署名使用 Claude <noreply@anthropic.com>，不使用使用者的 Email
- 簽章金鑰與密碼只存在 GitHub Secret `SIGNING_KEYSTORE_BASE64`、`SIGNING_PASSWORD`，絕不可加入 repo、輸出到記錄或文件；不可再更換金鑰（會導致已安裝的 App 無法更新）。2026-10-20 曾因舊金鑰在公開 repo 中外流而換過一次
- 這個 repo 是公開的：任何提交內容（含歷史）都視為公開資訊
