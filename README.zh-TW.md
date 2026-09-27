# LevinRAG

[English](README.md) | 繁體中文

> 本文為英文版的翻譯；內容不一致時，以英文版為準。

**可驗證的權限感知 RAG 伺服器。**

針對一個 Markdown 資料夾，給出帶引用的回答；適用於內部知識庫。一個 JVM process、一個嵌入式資料庫，不需要拼接任何服務。

## 特色

- **權限是檢索的一部分**：每一組「文件 × 無權限使用者」都有測試；設定寫錯時 fail closed。
- **可解釋**：每個候選在每個階段的名次與分數，都顯示在 Debug 面板。
- **可重建**：索引只是衍生資料；`bb reindex` 從原始文件重建。
- **開箱即用的中文檢索**：不需詞典的 bigram 斷詞，已與 HanLP 比較（[spike](docs/spikes/cjk-analyzer.md)）；網頁介面為繁體中文。
- **AI coding agent 可驗證**：一個 repo、一個 process，測試使用 stub 模型，[快速上手](docs/howto/quick-start.zh-TW.md)可由 agent 逐步執行。
- **有量測**：`bb eval` 在你自己的題目上輸出 recall@k 與 MRR@10。

流程：詞彙＋語意＋連結圖多路召回 → RRF 融合 → cross-encoder rerank → 脈絡擴展 → 帶 `[n]` 引用的回答。模型透過 OpenAI 相容 API 呼叫（GPU 上的 vLLM，或筆電上的 llama.cpp）。

## 設計理念

常見的 RAG 系統由向量資料庫、全文搜尋引擎、圖資料庫、metadata 資料庫與框架拼接而成。每個元件都成熟，代價出在接縫：

- 同一份文件被複製到多個系統，新增與刪除沒有交易保護；
- 權限要在每個儲存各自實作，漏掉一處就是洩漏；
- 系統行為散落在設定檔、框架內部與外部服務之間，端到端驗證得先啟動一整排服務。對 AI coding agent 而言更是如此。

LevinRAG 把檢索當成一個**資料系統**來設計：語料目錄是唯一來源，chunk、全文索引、向量、權限與連結圖都是它的衍生資料。這不是新想法，而是把資料庫、資訊檢索與資料工程的既有做法，收斂成一個一致的 RAG 架構。

```mermaid
flowchart LR
  subgraph A[拼接式]
    D1[文件] --> V[(向量 DB)]
    D1 --> S[(搜尋引擎)]
    D1 --> G[(圖 DB)]
    D1 --> M[(metadata／權限 DB)]
  end
  subgraph B[LevinRAG]
    D2[語料] --> T[每份文件一個交易：<br/>chunk・全文索引・<br/>向量・權限] --> X[(Datalevin)]
  end
```

- **一份文件的衍生資料在同一個交易中產生**；索引只是衍生資料，隨時可以丟掉重建。
- **權限是檢索的不變式**：寫入時算好，在檢索層內過濾，不靠介面把關；安全測試由索引推導出「文件 × 無權限使用者」矩陣，逐一驗證。規則見[管理者手冊](docs/howto/admin.zh-TW.md#文件權限)。
- **檢索可以解釋**：每個候選在每個階段的名次與分數都記入 trace，能回答「為什麼是這份、為什麼不是那份」。
- **本機即可完整執行**：一個 JVM 加三個模型端點；測試用 stub 模型。

這些主張不依賴 Datalevin，換成 PostgreSQL 加 pgvector 大多仍然成立。選 Datalevin，是因為它把全文、向量與 Datalog 放進同一個嵌入式引擎，不必另外架設服務，schema 也可以逐步演進。

代價同樣明確：設計目標約十萬個 chunk（數千份文件），實際上限尚未量測；不做水平擴展；依賴一個維護者集中的資料庫；功能廣度不及通用框架。在設計目標的規模內，簡單與可驗證比極致的吞吐量更值得。

完整的論證（與各框架的比較、為什麼選 Datalevin、適用範圍、待驗證的問題）見 [docs/design/rationale.zh-TW.md](docs/design/rationale.zh-TW.md)。

## 從哪裡開始

| 你想要… | 閱讀 |
|---|---|
| 用自己的語料評估 LevinRAG、檢驗它的主張，不需要懂 Clojure | [快速上手](docs/howto/quick-start.zh-TW.md)——**從這裡開始** |
| 在網頁上提問 | [使用者手冊](docs/howto/user.zh-TW.md) |
| 管理使用者、群組、文件權限與匯入 | [管理者手冊](docs/howto/admin.zh-TW.md) |
| 部署、監控、備份 | [維運手冊](docs/howto/ops.zh-TW.md) |
| 架設三個模型端點（GPU 上的 vLLM，或筆電上的 llama.cpp） | [VLLM_SETUP.zh-TW.md](VLLM_SETUP.zh-TW.md) |
| 開發 LevinRAG | [開發者指南](docs/howto/dev.zh-TW.md)；AI coding agent 另讀 [CLAUDE.md](CLAUDE.md) |

## 參考資料

- [SPEC.zh-TW.md](SPEC.zh-TW.md)：現行規格，含尚未完成的工作（§21）。
- [docs/decisions.md](docs/decisions.md)：每一處設計調整的理由（英文）。
- [docs/design/rationale.zh-TW.md](docs/design/rationale.zh-TW.md)：完整的設計論證。
- [docs/design/2026-09-22-initial-spec.md](docs/design/2026-09-22-initial-spec.md)：初版規格（凍結）。
