# Improvement of RAG-based AI system_Marina_Safronova

### Github: [https://github.com/safronovamy/genai-rag-orda/tree/advanced_rag](https://github.com/safronovamy/genai-rag-orda/tree/advanced_rag)

(branch: advanced_rag)

---

## 1. Project Context

This project implements a Retrieval-Augmented Generation (RAG) system called **Skincare AI RAG Assistant**.
The assistant provides skincare recommendations strictly grounded in a curated dataset of K-beauty products, ingredients, and routine rules.

The system is implemented using:

- **Backend**: Java 17, Spring Boot 3
- **Vector DB**: Qdrant (Cosine similarity)
- **Embeddings**: OpenAI `text-embedding-3-small` (1536 dimensions)
- **LLM**: OpenAI GPT model
- **UI**: Simple web interface

The goal of this phase is to **define valuable evaluation metrics**, **establish a reliable evaluation setup**, and **improve the RAG subsystem** in a measurable way.

---

## 2. Business Value & Constraints

The assistant must:

- Provide **accurate**, **safe**, **grounded** skincare recommendations
- Use **ONLY curated products** from the box
- Match routines to:
    - age range
    - skin type
    - concerns
    - morning/evening context
- Avoid hallucinations (e.g., nonexistent ingredients or unsafe mixing advice)

Proper retrieval is **critical** because:

- **Routines** are structured by skin type, age, and concerns
- **Products** have detailed metadata required for recommendations
- **Ingredients** have interaction constraints

If the wrong documents are retrieved, the final answer becomes **irrelevant** or potentially **unsafe**.

Therefore, the core KPI for the RAG subsystem is **retrieval accuracy**.

---

## 3. System Context and Document Model

The system answers user questions related to skincare routines, ingredients, and products.

All knowledge in the system is represented using **three document types only**:

- **Routine documents** — describe recommended sequences of actions, constraints, and usage rules.
- **Ingredient documents** — explain properties, effects, and compatibility of skincare ingredients.
- **Product documents** — describe specific commercial products.

Correct answer generation in this domain is **safety-sensitive**:

- Missing routines may lead to unsafe recommendations.
- Product-only context is insufficient for correct reasoning.

---

## 4. Dataset Preparation and Expansion

### 4.1 Dataset Expansion Before Baseline

Before baseline evaluation, the dataset was intentionally expanded to better reflect real-world conditions.

Actions taken:

- Increased the number of product and routine documents.
- Implemented a custom **HTML parser** to extract structured data (ingredients, usage instructions, descriptions) from product pages.
- Normalized extracted content to ensure consistent chunk structure.

**Rationale**:

Dataset expansion was performed **once**, prior to baseline measurement, to avoid evaluating retrieval quality on an artificially small or incomplete corpus.

The dataset was **not modified during later optimization iterations**, ensuring metric comparability.

---

## 5. Retrieval and Evaluation Setup

### 5.1 Retrieval Depth vs. Evaluation Depth

- The retrieval subsystem returns **top-5 documents** for each query.
- **Evaluation metrics are calculated on the top-3 documents**.

This design allows:

- richer candidate retrieval (top-5),
- while enforcing **precision** in evaluation (top-3).

---

### 5.2 Evaluation Configuration

- Golden dataset: 30 fixed questions
- Document types: routine / ingredient / product
- Same dataset, embeddings, and evaluation runner across all experiments
- Five independent runs per configuration
- LLM temperature fixed at **0** (see Section 10)

---

### 5.3 Evaluation Pipeline

Evaluation is implemented in `EvaluationRunner.java`.

Supported modes:

- `baseline`
- `hyde`
- `hyde_stepback_fallback`

Current execution behavior:

- Running `EvaluationRunner.main()` **without parameters** sequentially evaluates three modes.

---

### 5.4 How to Run (Reproducibility)

**Run from IDE**

1. Open `EvaluationRunner.java`
2. Run the `main()` method
3. The runner will execute:
    - baseline
    - hyde
    - hyde_stepback_fallback
4. The evaluation report JSON files will be saved to the **project root**.

---

## 6. Metric Selection

### 6.1 Primary Metric: Recall@3

**Recall@3** was selected as the **primary optimization metric**.

**Why Recall@3**:

- Correct answers usually require **multiple complementary documents**.
- Hit@K metrics are insufficient for multi-document reasoning.
- Recall@3 directly measures **context completeness** within a constrained context window.

This metric best reflects:

- answer correctness,
- safety,
- and real system behavior.

---

### 6.2 Supporting Metrics

### RulePresence@3

Indicates whether at least one **routine document** appears in the top-3 results.

- This metric captures **coverage of governing routines**.
- It is safety-aligned but **not sufficient alone**.

### ProductPresence@3

Indicates whether at least one product document appears in the top-3 results.

---

## 7. Baseline Results

**Baseline configuration**: dense vector retrieval.

Stable results across all five runs:

| Metric | Value |
| --- | --- |
| Recall@3 | **0.583** |
| RulePresence@3 | 0.692 |
| ProductPresence@3 | 1.00 |

**Observed failure mode**:

- Product documents are reliably retrieved.
- Routine documents are frequently missing from top-3.
- This leads to incomplete and potentially unsafe answer contexts.

---

## 8. Iterative Improvements

### 8.1 Iteration 1 — HyDE (Hypothetical Document Expansion)

HyDE was introduced to reduce semantic mismatch between user questions and stored documents.

**Effect**:

- Significant improvement in semantic alignment.
- Stable Recall@3 improvement across all runs.

Typical Recall@3 after HyDE:

- **≈ 0.80**

Relative improvement over baseline:

```
(0.80 −0.583) /0.583 ≈37%

```

---

### 8.2 Iteration 2 — HyDE + Step-Back Fallback

A **Step-Back fallback** mechanism was added.

**How Step-Back works**:

- Triggered **only if no routine document appears in top-3**.
- Reformulates the query toward higher-level conceptual intent.
- Targets retrieval of governing routines rather than surface matches.

---

### Why Step-Back Is a Qualitative Improvement

- It changes **retrieval reasoning**, not just scoring.
- It addresses a **specific safety failure mode**.
- It is conditional, preventing retrieval noise.
- It improves routine coverage without degrading Recall@3.

This represents a **qualitative system improvement**.

---

## 9. Rejected Approaches

### 9.1 Hybrid BM25 + Dense Retrieval

Hybrid retrieval was evaluated and rejected.

Observed effects:

- Recall@3 degraded below baseline.
- Routine coverage decreased.
- Product documents dominated top-K due to lexical overlap.

**Conclusion**:

BM25 amplifies surface-level keyword matching and suppresses abstraction-level reasoning, making it unsuitable for this domain.

---

### 9.2 HyDE + Hybrid Retrieval

Combining HyDE with BM25 resulted in semantic interference.

- HyDE introduced abstraction.
- BM25 reintroduced surface bias.
- Net effect: degradation of all core metrics.

---

## 10. Temperature Configuration (Temperature = 0)

LLM temperature was fixed at **0** for all evaluation runs.

**Rationale**:

- Ensures deterministic query rewriting and Step-Back behavior.
- Eliminates randomness in metric comparison.
- Guarantees reproducibility of automated evaluation.

Temperature control was required for **valid metric measurement**, not answer creativity.

---

## 11. Comparative Results

### Comparative Evaluation Results (Top-3 Metrics)

| Metric | Baseline | HyDE | HyDE + Step-Back |
| --- | --- | --- | --- |
| **Recall@3** | **0.583** | **≈ 0.80** | **≈ 0.80** |
| RulePresence@3 | 0.692 | ≈ 0.87 | **≈ 0.92** |
| ProductPresence@3 | 1.00 | ≈ 0.90 | ≈ 0.90 |

---

## 12. Acceptance Criteria Validation

- Primary metric: **Recall@3**
- Relative improvement: **≈ 37%**
- Improvement is stable across five independent runs
- Both quantitative and qualitative gains are demonstrated

---

## 13. Final Conclusion

This project demonstrates a complete and methodologically sound RAG improvement cycle:

- A truly valuable metric was selected and improved.
- Dataset quality was increased prior to baseline measurement.
- Automated, reproducible evaluation was implemented.
- Multiple enhancement strategies were tested and analyzed.
- Ineffective approaches were explicitly rejected with evidence.
- A qualitative improvement addressed a real safety failure mode.

The final system retrieves more complete, safer, and more reliable contexts for answer generation.