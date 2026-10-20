# 開發紀錄與交接

這份文件記錄會議速記 App 從 2026-10-17 到 2026-10-19 的開發過程：每個決定的原因、目前架構、發布流程，以及尚未完成的事項。之後接手開發時先讀這份。

## 需求（使用者提出的）

1. 中文、英文會議的語音轉文字，加上會議記錄整理
2. 做成 Android APK
3. 中英文要能依語音自動切換，因為會議中常中英夾雜
4. 每場會議保存原始錄音；左側選單可選之前的會議、播放錄音
5. 點逐字稿跳到該段錄音；錄音播到哪段，就標示對應的逐字稿
6. 不想額外花錢，要用免費的 API
7. 圖示要有「筆記」意象
8. 對外文件與截圖不可出現個人或公司資料，一律用虛構範例
9. 只改說明文件時，不要推進版號
10. 修改任何功能前，先詢問是否推進版本號（或由使用者主動指示）；推進時須比對與上一版的差異，再補上新版說明
11. 會議記錄要能自訂分類，分類可綁定會議記錄格式（v1.0.9）
12. 提供 App 內版本更新按鈕，查 GitHub 最新版並自動下載安裝（v1.0.9）
13. 開啟 App 時自動檢查並自動更新；repo 改為公開（v2.0.0）
14. repo 公開後移除 GitHub 存取權杖功能，設定頁不要出現用不到的說明（v2.0.1）

## 關鍵決定與原因

| 決定 | 原因 |
|---|---|
| 從網頁工具改成 APK | Claude 的網頁 artifact 不開放麥克風；Android 需要獨立 App |
| 用 Capacitor 包 `www/index.html` | 介面可直接用瀏覽器測試；GitHub Actions 可在雲端編譯 |
| 在 GitHub Actions 編譯 | 開發環境連不到 Android SDK、Gradle 與 Google Maven |
| 放棄 Android 內建語音辨識（v1.0.2 用過） | 一次只能指定一種語言，無法處理中英夾雜；而且佔用麥克風，無法同時錄下原始音檔 |
| 改為 App 自行錄音、依停頓切段送轉錄（v1.0.3 起） | 同時取得原始錄音與帶時間點的逐字稿，才能做播放同步；多語言模型可直接處理中英夾雜 |
| 轉錄預設 Google Gemini 免費方案（v1.0.4 起） | 使用者不想付費；Groq 申請時出現 `signup_blocked`（以 Google 帳號登入仍被擋） |
| 保留 Groq Whisper、Claude 為選項 | 日後若能申請或願意付費可直接切換 |
| 保留「複製提示詞」 | 不用 API，貼到 Claude App 整理，零額外成本 |
| 金鑰欄位不寫開頭格式 | 金鑰格式不一定固定，避免誤導 |
| 版號由 `VERSION` 檔決定 | 只有真的出新版才改版號；同版號重新編譯會覆蓋同一個 Release 的 APK |
| 簽章金鑰與密碼放在 GitHub Secret `SIGNING_KEYSTORE_BASE64`、`SIGNING_PASSWORD` | 金鑰外流的話，任何人都能做出可覆蓋安裝的假版本。編譯時檢查 APK 指紋必須是 `22:03:74:5A…` |
| 2026-10-20 更換簽章金鑰 | repo 從建立起就是公開的（當時誤以為私人），舊金鑰曾在公開歷史中；整併歷史後舊提交仍可依編號存取，因此換新金鑰（RSA 4096、強密碼）。已安裝的舊版需備份、移除、重裝、還原一次 |
| `versionCode` 使用 Actions 執行次數 | 一定遞增，任何時候都能覆蓋安裝（包括從曾經發布過的 v1.0.8/v1.0.9 覆蓋回 v1.0.7） |
| `README.md`、`docs/**` 不觸發編譯 | 改說明不產生新 APK |

## 架構

- **單一檔案**：`www/index.html` 包含全部介面、樣式與邏輯
- **錄音**：MediaRecorder 同時跑兩個錄製器
  - 主錄製器：完整錄音，每 3 秒存一塊到 IndexedDB（32 kbps，一小時約 15 MB）
  - 片段錄製器：依音量偵測切段，滿 10 秒且停頓 0.5 秒切段，最長 25 秒；沒有人聲的片段丟棄
- **儲存（IndexedDB `meeting-notes`）**
  - `meetings`：會議資訊、逐字稿 `segs[{s,e,text,key}]`、會議記錄、長度
  - `audio`：完整錄音的分塊 `[mid, i]`
  - `chunks`：待轉錄片段 `[mid, start]`，轉錄成功後刪除
- **轉錄佇列**：依序送出；失敗最多重試 3 次；429 等待後重試；恢復連線時自動重試
- **Gemini 轉錄**：`generateContent`，音訊以 inline base64 送出，要求結構化 JSON `{segments:[{start,end,text}]}`；時間為片段內相對秒數，再加上片段起點
- **Groq 轉錄**：`/audio/transcriptions`，`verbose_json`，依 `no_speech_prob` 過濾
- **後處理**：OpenCC 簡轉台灣繁體、過濾常見幻覺字句、合併過短片段
- **播放**：把錄音分塊組回 Blob，用 fix-webm-duration 補上長度，讓播放器可拖曳；`timeupdate` 時標亮對應段落
- **會議記錄**：同一份提示詞（`buildPrompt`）給 Gemini、Claude 或「複製提示詞」使用
- **設定**：存在 `localStorage` 的 `mtg-cfg-v2`
- **備份與還原（v1.0.8）**：JSZip 打包成 .zip，結構為 `backup.json`（格式版本、會議索引、設定）＋ `meetings/<日期_名稱>/`（`meeting.json`、`逐字稿.txt`、`會議記錄.md`、`錄音.webm`、`待轉錄/`）。存到手機用 Filesystem 寫入 `Documents/會議速記/`，大檔分段以 base64 append 寫入；分享先寫到 Cache 再用 Share 外掛分享。還原以會議 id 比對，可略過或覆蓋
- **版本顯示**：`APP_VERSION` 在編譯時由 `VERSION` 檔替換，顯示在側邊選單最下方
- **會議分類（v1.0.9）**：分類存在設定 `cfg.categories[{id,name,template}]`，會議以 `m.cat` 記錄分類 id；`buildPrompt` 依分類的 `template` 取代預設輸出格式。左側清單篩選存在 `cfg.catFilter`
- **App 更新**：開啟 App 時呼叫公開的 `GET /repos/{repo}/releases/latest`（不帶權杖），比對 tag 與 `APP_VERSION`；有新版就用 Filesystem `downloadFile` 從 `browser_download_url` 下載 APK 到 Cache，再以 file-opener 開啟安裝程式。錄音中延後到錄音結束。repo 名稱在編譯時由 `github.repository` 寫入 `APP_REPO`。需 `REQUEST_INSTALL_PACKAGES` 權限；首次安裝時 Android 會要求允許本 App 安裝不明來源應用程式。v1.0.9 曾支援私人 repo 權杖，v2.0.1 移除

## 發布流程

> 版本號規則（使用者指定）：修改任何功能前先詢問是否推進版本號，或由使用者主動指示；推進時須比對與上一版的實際差異再撰寫新版說明。詳見根目錄 `CLAUDE.md`。

1. 修改 App → 推送到 `main` → GitHub Actions 編譯
2. 要出新版：先改 `VERSION`，並在 `docs/CHANGELOG.md` 新增 `## vX.Y.Z（日期）` 段落（Release 說明會自動擷取）
3. 不改 `VERSION`：重新編譯會覆蓋同版 Release 的 APK
4. 比對上一版差異時，基準是「上一版 Release 最後一次成功編譯的提交」（到 Actions 查），不一定是 Tag 指向的提交：不推版的重新編譯不會移動 Tag
5. 圖示：修改 `tools/make_icon.py` 後執行，產生 `assets/` 下的三個檔案

## 驗證狀況

| 項目 | 狀態 |
|---|---|
| 雲端編譯 | 每版都成功 |
| 錄音、切段、轉錄、簡轉繁、播放同步 | 在電腦版無頭 Chrome 以模擬麥克風與模擬 API 回應測試通過 |
| Android 實機錄音 | **尚未驗證** |
| 真實 Gemini 金鑰呼叫 | **尚未驗證**（模型名稱預設 `gemini-3.8-flash`；若不可用改 `gemini-3.5-flash-lite`） |
| 背景／鎖定螢幕時錄音 | 已知可能中斷，未處理 |

## 待辦

- [ ] 實機測試錄音與 Gemini 轉錄，依結果調整
- [ ] 實機測試備份（存到手機、分享到雲端硬碟）與還原
- [ ] 實機測試 App 內更新（需 v1.0.9 以上版本再發布下一版才能驗證完整流程）
- [ ] 視需要：錄音改為前景服務，讓鎖定螢幕時也能持續錄音
- [ ] 視需要：已結束的會議若沒有錄音（由舊版轉入），隱藏「開始錄音」按鈕

## 已處理的事項

- **簽章問題（2026-10-19 修正）**：原本只把 `signing/debug.keystore` 複製到 `~/.android/`，雲端編譯實際沒用到，每次都產生隨機金鑰，導致每一版簽章不同、無法覆蓋安裝。改為在 `app/build.gradle` 明確指定固定金鑰，並加入「Verify APK signature」步驟，簽章不符就讓編譯失敗。固定金鑰 SHA-256 開頭為 `39:22:B3:F0`。使用者需移除舊版一次（會刪除 App 內資料），之後即可正常更新

- 文件截圖與 App 內範例文字已全部改為虛構內容
- 2026-10-20 repo 改為公開前：Git 歷史整併為單一提交（作者改為 Claude 署名，不公開個人 Email），舊的 Release 與 Tag 全部移除，版號從 v2.0.0 重新開始（高於開發期間的 v1.0.9，已安裝 v1.0.9 的手機可自動更新）；開發過程記錄在本文件與 `docs/CHANGELOG.md`
- App 內「與會者」「常用專有名詞」的範例文字已改為通用範例
