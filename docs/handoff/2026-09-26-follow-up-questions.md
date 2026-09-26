# 交接：網頁問答的多輪追問（2026-09-26）

## 工作目錄

`/Users/laurencechen/ForceUnion/levinrag`，branch `main`。

## 這次 session 做了什麼

1. **釐清需求，調整 SPEC 的優先順序**（commit `81cf228`）
   - 原本要做 §21.2 的 T5.4 `/ask` SSE 串流。討論後發現，使用者真正想要的是：
     (1) 在現有網頁問答上**追問**；(2) 加分項：**能接 LibreChat**。SSE 本身這兩件事都做不到。
   - SPEC §21：「網頁問答的追問」列為優先第 1、標為「下一項」；T5.4 保留在 §21.2，但拿掉優先標記；
     §21.3 新增「OpenAI 相容 `/v1/chat/completions`（接 LibreChat）」作為加分項；
     §1.3 把「把追問改寫成獨立查詢」列為 query rewriting 非目標的例外。
   - 原因記在 `docs/decisions.md`「2026-09-26 — Follow-ups replace SSE streaming as the next feature」。
2. **追問的設計稿**（commit `e2c9ba4`，使用者已審核通過）
   `docs/superpowers/specs/2026-09-26-follow-up-questions-design.md`
3. **實作計畫**（本交接文件同一個 commit）
   `docs/superpowers/plans/2026-09-26-follow-up-questions.md`：共 6 個 task。
   **使用者尚未審閱計畫，也還沒選擇執行方式。**

實作一行都還沒開始。

## 使用者做的設計決定（不要再問一次）

| 問題 | 決定 |
|---|---|
| 對話存在哪裡 | 只存在頁面上（隱藏欄位），伺服器不存；重新整理或按「新對話」就清空 |
| 追問怎麼變成查詢 | 用 LLM 改寫（condense question），改寫失敗時退回原問題，並標記 `rewrite_failed` |
| 回答步驟看到什麼 | 只看改寫後的獨立問題，加上這一輪的來源；§10.1 的 prompt 不動 |
| API | `POST /api/v1/ask` 也接受 `history` |
| 不做 | 伺服器端保存對話、過去對話清單、SSE、LibreChat 端點、追問專用的 eval 題組 |

## 下一步

1. 請使用者審閱計畫，並選擇執行方式：
   - **Subagent-driven**：每個 task 由一個新的 subagent 實作，再由一個新的 reviewer 檢查。
   - **Native**：在同一個 session 裡自己做完，最後做一次 whole-branch review。
   - 我的建議：**Native**。6 個 task 彼此緊密依賴（rewrite → answer → API → web），計畫裡已經寫了完整的程式碼；SPEC §0.8 規定的整體 review 放在 Task 6 Step 8。
2. 依照計畫逐一執行；記憶檔的規則是「每個 task commit 後繼續做，整個 Phase 做完才停下來給使用者 review」。
3. Task 6 要檢查並更新 HowTo／README 等文件（這是使用者明確要求的），中英兩份同一個 commit，並跑 `bb docs:check`。

## 環境

- 使用者之前把 tmux 都關了；這次已經重新啟動：`bb dev:models`（tmux `embed`／`chat`／`rerank`），
  `bb dev:up`（tmux `levinrag` 跑 server :8000，`nrepl` 跑 :1667），`bb doctor` 全部 OK。
- 重開機後：先 `bb dev:models` 再 `bb dev:up`。不要手動組 `llama-server` 或 tmux 指令。
- 快速測試迴圈用 nREPL（見 `CLAUDE.md`）；commit 前跑 `clj-kondo --lint src test bb`；
  最後的 task 前跑 `clojure -X:jvm-opts:test`。

## 已驗證、計畫裡直接用的事實

- `ring.util.codec/form-encode` 把 vector 編成重複的 key，`form-decode` 會把重複的 key 還原成 vector，單一個則是字串（Task 4 讀隱藏欄位靠這個行為）。
- `layout/render` 的簽名是 `[request title & body]`。
- 沒有 CSP，但計畫仍然把 JS 放在 `app.js`，不用 inline `hx-on`。
- 測試裡的 stub chat 用「user message 是否以 `<history>` 開頭」來分辨改寫呼叫和回答呼叫。

## 要注意的地方

- `answer.clj` 的 `content`（private）和 `strip-think` 由 `ask!` 在改寫時共用。`rewrite.clj` 刻意寫成純函式，不 require `answer`，避免循環依賴。
- `search/degraded-json` 會改成通用的 snake_case 轉換（`:rewrite-failed` → `"rewrite_failed"`）；`rerank_failed` 的輸出不變。
- 網頁的錨點從 `#src-1` 改成 `#t<trace 前 8 碼>-src-1`，`dev/browser/check.mjs` 的 selector 要跟著改。
- §18.3 新增的安全測試（Task 5）要能證明它會失敗（negative control），做法寫在計畫裡。
