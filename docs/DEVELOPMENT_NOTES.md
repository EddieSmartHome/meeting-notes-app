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
- **背景錄音（v2.0.4）**：自製原生外掛 `RecordingService`（`native/android/java/`），錄音開始時啟動麥克風類型的前景服務（常駐通知＋PARTIAL_WAKE_LOCK），結束時停止；編譯時複製到 Android 專案並覆蓋 `MainActivity` 以註冊外掛，Manifest 權限與 `<service>` 由編譯流程加入。「錄音時保持螢幕開啟」為設定 `cfg.keepScreen`（預設 "1"）
- **錄音**：MediaRecorder 同時跑兩個錄製器
  - 主錄製器：完整錄音，每 3 秒存一塊到 IndexedDB（32 kbps，一小時約 15 MB）
  - 片段錄製器：依音量偵測切段，滿 10 秒且停頓 0.5 秒切段，最長 25 秒；沒有人聲的片段丟棄
- **儲存（IndexedDB `meeting-notes`）**
  - `meetings`：會議資訊、逐字稿 `segs[{s,e,text,key}]`、會議記錄、長度
  - `audio`：完整錄音的分塊 `[mid, i]`
  - `chunks`：待轉錄片段 `[mid, start]`，轉錄成功後刪除
- **轉錄佇列**：依序送出。請求用 `fetchT` 加逾時（轉錄 120 秒、會議記錄 300 秒）。網路錯誤（`e.net`）與 429／5xx（`e.transient`）不計失敗次數，以 2、4、8… 秒最長 60 秒的間隔持續重試（v2.0.5 起，因實機常在網路切換、螢幕關閉時失敗）；其他錯誤最多 3 次後標為失敗。等待可被打斷：恢復連線、回到 App 畫面、按「重試」都會立刻再送。v2.0.6 起：整段轉錄另有 180 秒上限（`Promise.race`，防止卡在 fetch 以外的步驟）；每 30 秒檢查佇列沒在跑就重啟；畫面顯示這段已等秒數（`qStart`）
- **辨識方式選單與重新轉錄（v2.2.0）**：逐字稿上方 `#tr-engine`（gemini／gtx／groq（有 Groq 金鑰才列）／local）。選 local＝`cfg.localMode="always"`；選雲端時改 `cfg.sttEngine`，若原本是 always 則改成 fallback。重新轉錄 `splitRecording()`：把整場錄音（`getAudioBlob`）用 `AudioContext({sampleRate:16000})` 解碼，以和錄音時相同的音量規則切段（滿 10 秒且停頓 0.5 秒、最長 25 秒、無人聲略過），每段存成 16 kHz WAV 放進 `chunks` 佇列。開始前刪掉該會議未完成的片段，舊逐字稿存在 `m.segsPrev`，「復原上一版」會互換 `segs` 與 `segsPrev` 並停止未完成的重新轉錄。整場解碼一小時約需 230 MB 記憶體，長會議在手機上需實測
- **雲端硬碟同步（v2.4.0）**：原生外掛 `DriveAuth`（`native/android/java/DriveAuthPlugin.java`，play-services-auth 22.0.0 的 AuthorizationClient）取得 `drive.appdata` 權杖（非敏感範圍，不需 Google 審核）；需要在 Google Cloud 專案建立「Android」OAuth 用戶端：套件名稱 `tw.eddiehsu.meetingnotes`、SHA-1 `E8:B0:7D:AA:8E:CF:FC:EE:B4:C4:E5:8C:7C:4C:2C:B3:DD:A0:69:9F`（簽章金鑰的憑證指紋，公開資訊），並啟用 Google Drive API、設定 OAuth 同意畫面（發布狀態改為「正式版」，否則測試模式的授權約 7 天就要重新同意）。網頁端用原生 fetch（`CapacitorWebFetch`）直接呼叫 Drive REST v3，檔案放在 appDataFolder：`m_<id>.json`（appProperties.u＝版本時間）、`a_<id>.<副檔名>`（錄音，續傳上傳）、`meta.json`（分類、設定、刪除紀錄 `deleted{id:時間}`，保留 180 天）。同步規則：雲端版本 ≠ 本機記錄的 `m.su` 就下載並逐欄位合併（`m.ft` 記錄各欄位修改時間，由 `saveM` 比對 `mSnap` 快照產生），合併後本機仍有較新欄位就再上傳；只有本機改過就上傳。錄音依 `@capacitor/network` 的連線類型與 `cfg.syncAudioWifi`／`syncAudioCell` 決定。觸發：啟動、回到 App（距上次超過 1 分鐘）、`saveM` 後 20 秒、刪除後 3 秒、每 5 分鐘檢查。play-services-auth 22.0.0 要求 minSdk 24，編譯時把 `android/variables.gradle` 的 minSdkVersion 改成 24；minSdk 24 起 APK 只有 v2/v3 簽章，keytool 讀不到，簽章檢查改用 build-tools 的 apksigner。電腦上以模擬雲端硬碟測過兩台裝置的上傳、下載、逐欄位合併、錄音同步、刪除同步；實機登入需完成上述 Google Cloud 設定
- **分類與設定同步（v2.4.1）**：v2.4.0 分類整份以 `catAt` 較新者為準，升級前建立的分類兩邊 `catAt` 都是 0 時永遠不會同步，改為逐個分類合併。`saveCfg` 比對快照（`cfgSnap`／`catSnap`）自動記錄修改時間：設定在 `cfg.setAt[key]`、分類在 `c.at`、刪除分類在 `cfg.catDel[id]`（保留一年）。`mergeSettings(meta)` 逐項取較新者，時間相同時以雲端為準（兩台收斂成先同步那台的內容），本機空值、雲端有值時用雲端。兩台各自建了同名分類時保留 id 較小的，另一個寫進 `cfg.catAlias`（舊 id→新 id）並當成已刪除，會議的 `cat` 隨之改掉；空白格式會帶入被合併分類的格式。同步的設定列在 `SYNC_CFG`（API 金鑰、專有名詞、辨識服務、模型、思考程度、會議記錄服務與語言、自動產生、錄音模式／格式／位元率、螢幕常亮）；`localMode`（要先下載模型）、`syncAudioWifi/Cell`、模型清單不同步。meta.json 仍寫 `categories`＋`catAt` 讓 v2.4.0 讀得懂。以模擬雲端硬碟測過：兩台既有分類合併、同名分類合併與會議改分類、金鑰帶到空白的一台、專有名詞與錄音模式同步、刪除分類同步
- **手動修改會議記錄（v2.5.0）**：會議記錄分頁的「編輯」（沒有內容時為「手動輸入」）把 `#minutes` 換成 `#min-edit` 文字框編輯 Markdown 原文，`minEditId` 記錄正在編輯的會議（切換會議時關閉、不存檔；草稿會議不能編輯）。`setMinutes(m,text,edited)` 是唯一改 `m.minutes` 的地方：換內容前把舊內容存到 `m.minPrev`／`m.minPrevEdited`，「復原」互換兩版。`m.minEdited` 為 true 時「重新產生」要按兩次（`genArm`）。這三個欄位加進 `SYNC_FIELDS`。自動產生（`maybeAuto`）略過正在手動輸入的會議
- **逐字稿／會議記錄分頁（v2.4.5）**：`.tabs`（`#tab-tr`、`#tab-min`）切換 `#sec-tr`、`#sec-min`，`setTab(t)` 記在變數 `tab`（不存檔）；`generate()` 成功且仍是目前會議時切到 min，`openMeeting`、「新會議」、開始錄音切回 tr。段數 `#seg-count` 移到分頁上，`#min-mark` 有會議記錄時顯示 ✓。逐字稿工具列 `.tr-tools` 一行（辨識方式下拉＋「復原」「重新轉錄」），`TR_ROWS` 改為 5
- **播放列與逐字稿版面（v2.4.4）**：`#playbar` 是 sticky 常駐，原本四行（播放器、自動捲動、錄音資訊、存到手機／分享）在手機上約占 1/3 畫面；改為播放器＋一行 `.playrow`（「自動捲動」、`#play-note` 單行省略、點一下加 `.full` 展開、「存檔」「分享」），`#au-status` 空白時隱藏。逐字稿每行的來源標籤從 `.acts` 移到時間下方的 `.tcol`，按鈕與間距縮小，390 px 寬時文字欄約從 130 px 增為 230 px
- **逐字稿限高（v2.4.3）**：`#transcript` 可在區塊內捲動（CSS 上限 65vh），`fitTranscript()` 把 max-height 設成前 `TR_ROWS`=10 句的實際高度（仍不超過 65vh）；畫面隱藏時量不到高度就略過，`showView("meeting")` 與視窗大小改變時重算。播放標亮與錄音中新增段落改用 `boxScroll()` 只捲動區塊本身（原本 `scrollIntoView` 會帶動整頁）
- **舊會議欄位合併（v2.4.2）**：v2.4.0 之前建立的會議沒有 `m.ft`，沒改過的欄位合併時原本以整場 `updatedAt`（雲端為上傳時間 `u`）當修改時間，所以另一台只要改過這場會議任何欄位，本機剛改的名稱／地點／與會者就被舊值蓋回。改為：只有一邊有該欄位的 `ft` 就以那邊為準，兩邊都沒有才比 `updatedAt`，且以 `updatedAt` 判定時不寫進 `ft`。`renderInfo` 不覆蓋同一場會議中正在輸入的欄位。以模擬雲端硬碟重現（舊程式：A 改名稱、B 改與會者後名稱被蓋回）並確認修正後兩台都保留
- **拖曳改分類（v2.3.0）**：`attachDrag()` 綁在左側清單每個 `.mitem`。觸控用 touch 事件：長按 500 ms 才進入拖曳，之前移動超過 8 px 就取消（照常捲動），拖曳中 touchmove `preventDefault` 擋住捲動；滑鼠按住 500 ms 後改用 document 的 mousemove／mouseup。拖曳時顯示 `#cdrop`（未分類＋各分類），放開時以 `elementFromPoint` 判斷落點，更新 `m.cat`。拖曳結束 400 ms 內忽略 click，避免放開時順便開啟會議
- **收音與格式（v2.2.2）**：使用者回報音質悶、遠處小聲、忽大忽小、斷續、雜音。原本 getUserMedia 開 echoCancellation／noiseSuppression／autoGainControl（通話用處理），位元率 32 kbps。改為 `cfg.recMode`：meeting（預設，EC/NS 關、AGC 開）、raw（全關）、call（全開）；`cfg.recKbps` 預設 64；`cfg.recFormat` auto 時優先 `audio/mp4`（m4a），不支援才用 WebM。每場錄音存 `m.recInfo`（mime、kbps、mode、實際 sampleRate／EC／NS／AGC），最近一次也存在 localStorage `last-rec-info`。電腦版 Chrome 測試 m4a：播放長度、拖曳、轉錄、重新轉錄（decodeAudioData）皆正常；Android WebView 是否支援 m4a 錄音需實機確認（設定頁會顯示可錄格式）
- **重新轉錄只用選定方式（v2.2.1）**：重新轉錄建立的片段帶 `engine`，`chunkEngine(c)` 優先使用它；這類片段不走「雲端失敗改用本機」、Gemini Transcribe 不改用其他模型（回傳空白先重試一次，仍空白才當作沒有內容），雲端請求間隔 `RETR_GAP`=6.5 秒。起因：2026-10-20 實測重新轉錄時，多數片段碰到 429 後被本機接手，三份逐字稿混了不同來源（見專案文件「本機辨識測試紀錄」）。每段逐字稿存 `src`（gemini／gtx／local／groq），合併短段落時只合併同來源，畫面顯示來源標籤
- **錄音存檔（v2.2.1）**：`exportAudio()` 存到 Documents/會議速記/錄音/ 或用 Share 分享（Cache 暫存）；播放器加 `controlsList="nodownload"`（WebView 不支援 blob 下載）
- **本機辨識（v2.1.0）**：原生外掛 `LocalAsr`（`native/android/java/LocalAsrPlugin.java`），用 sherpa-onnx 執行 Whisper Small int8。sherpa-onnx 1.13.8 有中文掉字問題（byte-level BPE 的一個字被拆成兩個 token 時被丟掉，上游 2026-09-20 #3965 修正，尚未發版），所以 v2.1.1 起由 `build-local-asr.yml` 從指定 commit（預設 db749e7）自行編譯 AAR（arm64-v8a、armeabi-v7a），連同模型檔與 SHA256SUMS 放在本 repo 的 Release「local-asr-v1」（prerelease、不設為 latest，避免干擾 App 更新檢查）；該流程只在修改 build-local-asr.yml 並推送、或手動執行時跑，APK 編譯只下載現成 AAR（AAR 是 Kotlin 寫的，另加 kotlin-stdlib）。2026-10-20 用使用者提供的 7 分鐘錄音在電腦上比較：1.13.8 明顯掉字，修正後的解碼正確，但同音字錯誤仍多，且最後一段出現無限重複（App 以 `dedupeRepeats` 刪除連續 4 次以上的重複）。模型從 Release「local-asr-v1」下載 `small-encoder.int8.onnx`、`small-decoder.int8.onnx`、`small-tokens.txt`（約 375 MB）到 App 私有目錄 `files/whisper-small/`，下載進度用 `progress` 事件回報。語言固定 `zh`。網頁端用 `decodeAudioData`＋`OfflineAudioContext` 把片段轉成 16 kHz 單聲道 16-bit PCM（base64）送進去；文字依句尾標點分句、時間依字數平均分配（`timedSplit`）。設定 `cfg.localMode`：off／fallback（雲端網路錯誤或 429／5xx 時該段改用本機）／always；沒雲端金鑰但模型已下載時也用本機。原生部分只經雲端編譯驗證，需實機測試
- **Gemini Transcribe（v2.0.8）**：`cfg.sttEngine==="gtx"`。`POST /v1beta/interactions`，body `{model:"gemini-3.5-transcribe",input:[{type:"audio",data:<base64>,mime_type}],generation_config:{transcription_config:{mode:"verbatim",custom_vocabulary}}}`，標頭 `Api-Revision: 2026-05-20`（被擋時改不帶重送）。不開逐字時間點（會降低準確度且不能和專有名詞並用），文字依句尾標點分句、時間依字數平均分配到片段。回應文字取 `output_text`，沒有就走訪回應收集 `text`（REST 回應結構文件沒寫清楚，2026-10-20 使用者以比較工具實測效果良好）。5xx 時該段改用 `cfg.gemFallback` 走 generateContent；404 視為致命錯誤。v2.1.1 同版補充：實機常掉句，原因是回傳空白時被當成無人聲直接刪掉；改為先把片段轉成 16 kHz WAV（`blobToPcm16`＋`pcmWav`）再送，回傳空白時改用 Gemini 主模型（generateContent）重轉，並把最近一次空白回應存在 localStorage `gtx-last-empty`，設定頁可看。設定欄位 `data-eng` 可列多個引擎（以空白分隔）
- **Gemini 思考程度（v2.0.6）**：轉錄請求帶 `generationConfig.thinkingConfig.thinkingLevel`，設定 `cfg.gemThink` 預設 "low"（"medium" 時不送，用模型預設）；模型回 400 且訊息含 think 時，記住該模型並改為不送再重試。會議記錄不套用。依 Google 文件，Gemini 3.8 Flash 預設 medium、不支援 minimal
- **備用模型（v2.0.6）**：轉錄時主模型回 5xx，該段立刻改用備用模型；備用模型回 404 則本次執行期間不再使用
- **模型選單（v2.0.7）**：主模型 `cfg.gemModel`、備用模型 `cfg.gemFallback`（空字串＝不使用）皆為下拉選單。清單由 `GET /v1beta/models` 取得，只留支援 generateContent 的 `gemini-*`，排除 tts／image／embed／live／audio 等，且只留免費方案可用的 Flash／Flash-Lite（`gemFree`，依名稱判斷：模型清單 API 沒有免費標示，2026-10-20 對照價格頁，Pro、Omni、圖片類不免費）；依版本號排序，preview／exp 排後。存在 `cfg.gemModels`、`cfg.gemModelsAt`。同步時機：填入金鑰、開啟設定（超過 1 小時）、開啟 App（超過 1 天）、手動按鈕。選用的模型不在清單時自動換成推薦模型（最新正式版 Flash／Flash-Lite）
- **Gemini 轉錄**：`generateContent`，音訊以 inline base64 送出，要求結構化 JSON `{segments:[{start,end,text}]}`；時間為片段內相對秒數，再加上片段起點
- **Groq 轉錄**：`/audio/transcriptions`，`verbose_json`，依 `no_speech_prob` 過濾
- **後處理**：OpenCC 簡轉台灣繁體、過濾常見幻覺字句、合併過短片段
- **播放**：把錄音分塊組回 Blob，用 fix-webm-duration 補上長度，讓播放器可拖曳；`timeupdate` 時標亮對應段落
- **會議記錄**：同一份提示詞（`buildPrompt`）給 Gemini、Claude 或「複製提示詞」使用
- **設定**：存在 `localStorage` 的 `mtg-cfg-v2`
- **備份與還原（v1.0.8）**：JSZip 打包成 .zip，結構為 `backup.json`（格式版本、會議索引、設定）＋ `meetings/<日期_名稱>/`（`meeting.json`、`逐字稿.txt`、`會議記錄.md`、`錄音.webm`、`待轉錄/`）。存到手機用 Filesystem 寫入 `Documents/會議速記/`，大檔分段以 base64 append 寫入；分享先寫到 Cache 再用 Share 外掛分享。還原以會議 id 比對，可略過或覆蓋
- **版本顯示**：`APP_VERSION` 在編譯時由 `VERSION` 檔替換，顯示在側邊選單最下方
- **會議分類（v1.0.9）**：分類存在設定 `cfg.categories[{id,name,template}]`，會議以 `m.cat` 記錄分類 id；`buildPrompt` 依分類的 `template` 取代預設輸出格式。左側清單篩選存在 `cfg.catFilter`
- **App 更新**：開啟 App 時呼叫公開的 `GET /repos/{repo}/releases/latest`（不帶權杖），比對 tag 與 `APP_VERSION`；有新版就用 Filesystem `downloadFile` 從 `browser_download_url` 下載 APK 到 Cache，再以 file-opener 開啟安裝程式。錄音中延後到錄音結束。repo 名稱在編譯時由 `github.repository` 寫入 `APP_REPO`。2026-10-26 GitHub 帳號由 `hsueddiese` 改名為 `EddieSmartHome`：App 依使用者要求先試舊 repo（`OLD_REPO`），查不到或連線失敗再改用 `APP_REPO`；本機辨識模型下載也同樣先舊後新。需 `REQUEST_INSTALL_PACKAGES` 權限；首次安裝時 Android 會要求允許本 App 安裝不明來源應用程式。v1.0.9 曾支援私人 repo 權杖，v2.0.1 移除

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
| App 內自動更新（開啟 App 自動下載並安裝） | 2026-10-20 實機驗證通過（v2.0.2 → v2.0.3） |
| 會議資訊直接新增分類（v2.0.3） | 2026-10-20 實機驗證通過 |
| Android 實機錄音＋Gemini 轉錄 | 2026-10-20 實機驗證通過 |
| 備份與還原 | 2026-10-20 實機驗證通過 |
| 螢幕關閉時錄音 | v2.0.3 實機確認：錄音不會中斷，但錄不到聲音。v2.0.4 加入前景服務，**待實機驗證** |

## 待辦

- [ ] 實機驗證 v2.1.0 本機辨識：模型下載、辨識速度與準確度（Samsung Galaxy）、錄音同時辨識是否影響錄音
- [ ] 實機驗證 v2.0.4：螢幕關閉、切到其他 App 時是否錄得到聲音、逐字稿切段是否正常
- [ ] 視需要：已結束的會議若沒有錄音（由舊版轉入），隱藏「開始錄音」按鈕

## 已處理的事項

- **錄音中音量偵測卡住（2026-10-30，v2.5.2）**：實機錄 28 分鐘課程沒有任何逐字稿、音量條完全不動。原因是偵測音量的 AudioContext 處於暫停狀態，AnalyserNode 讀到全是 0，每段都被判定為無聲而丟棄（完整錄音不受影響）。修正：開始／繼續錄音、回到前景時呼叫 `resume()`；讀值全為 0 或 AudioContext 不在 running 超過 2 秒，改為只依時間切段送出。已用無頭 Chrome 模擬暫停的 AudioContext 驗證：舊版 30 秒內 0 段排入佇列，新版 1 段
- **簽章問題（2026-10-19 修正）**：原本只把 `signing/debug.keystore` 複製到 `~/.android/`，雲端編譯實際沒用到，每次都產生隨機金鑰，導致每一版簽章不同、無法覆蓋安裝。改為在 `app/build.gradle` 明確指定固定金鑰，並加入「Verify APK signature」步驟，簽章不符就讓編譯失敗。固定金鑰 SHA-256 開頭為 `39:22:B3:F0`。使用者需移除舊版一次（會刪除 App 內資料），之後即可正常更新

- 文件截圖與 App 內範例文字已全部改為虛構內容
- 2026-10-20 repo 改為公開前：Git 歷史整併為單一提交（作者改為 Claude 署名，不公開個人 Email），舊的 Release 與 Tag 全部移除，版號從 v2.0.0 重新開始（高於開發期間的 v1.0.9，已安裝 v1.0.9 的手機可自動更新）；開發過程記錄在本文件與 `docs/CHANGELOG.md`
- App 內「與會者」「常用專有名詞」的範例文字已改為通用範例
