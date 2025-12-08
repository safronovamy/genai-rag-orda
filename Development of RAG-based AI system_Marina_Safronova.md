# Development of RAG-based AI system_Marina_Safronova

### Demo Video: [https://youtu.be/KxDLRFm6t60](https://youtu.be/KxDLRFm6t60)

### Github: [https://github.com/safronovamy/genai-rag-orda](https://github.com/safronovamy/genai-rag-orda)

# Skincare AI RAG Assistant

*A Retrieval-Augmented Generation system for personalized K-beauty skincare recommendations*

---

## 1. Main Idea

The main idea of the project originates from a Korean company specializing in the curation and sale of skincare beauty boxes featuring new and trending products on the market.
The dataset used in this RAG system reflects the structure and content of such curated boxes, focusing on the specific products, ingredient compositions, and skincare principles promoted within this ecosystem.

The Skincare AI RAG Assistant is designed to provide personalized skincare recommendations strictly based on this product selection.
Because the tool operates on a controlled, company-specific dataset, it can deliver precise and relevant advice aligned with the contents of the beauty boxes.

With this foundation, the AI system is capable of:

- Recommending products only from the curated box assortment based on skin concerns, age, or sensitivity
- Building structured morning and evening routines using the company’s products
- Explaining how the included skincare ingredients work
- Validating ingredient compatibility (e.g., retinol + acids, vitamin C + niacinamide)
- Providing K-beauty layering guidance specific to the product lineup
- Suggesting safe introduction patterns for actives using formulations featured in the boxes

The entire solution demonstrates how a full Retrieval-Augmented Generation (RAG) pipeline can support a personalized skincare assistant tailored to a specific company's product ecosystem—potentially serving as the foundation for a customer-facing chatbot or virtual skincare consultant.

## 2. Core Concepts

### Retrieval-Augmented Generation

The model receives:

1. The user query
2. The most relevant documents retrieved via vector similarity

The LLM generates a final answer enhanced with factual domain data.

### Embeddings

All dataset items are converted to embeddings using `text-embedding-3-small`.

These embeddings support semantic search for product descriptions, ingredient behaviors, and compatibility rules.

### Vector Database (Qdrant)

Qdrant stores embeddings and performs vector search.

Uses cosine similarity to find relevant skincare knowledge.

### Domain Dataset

A structured dataset of:

- real K-beauty products
- ingredients with mechanisms
- expert rules
- predefined skincare routines

This dataset enriches the LLM and provides factual grounding.

### Lightweight UI

A minimal chat interface built with HTML/CSS/JS.

Supports:

- markdown rendering
- AI typing indicator
- user-friendly conversation flow

---

## 3. Dataset Concept

The dataset is stored in `skincare_dataset.json` and consists of 4 main types of documents:

### **1) Products**

Each includes:

- brand, name
- detailed description
- active ingredients
- concentration (if available)
- targeted skin concerns
- expected effects

### **2) Ingredients**

Each entry describes:

- mechanism of action
- benefits
- sensitivity warnings
- synergy with other ingredients
- incompatibilities

### **3) Rules**

This includes:

- K-beauty layering sequences
- exfoliation rules
- ingredient interaction rules
- patch test instructions
- routines for different skin types and ages

### **4) Pre-built Routines**

Examples:

- oily/acne-prone (18–25)
- dry/sensitive (35+)
- anti-aging
- pigmentation control

**Total dataset size: 28 well-annotated documents.**

---

## 4. System Design and Architecture

### **High-level Pipeline**

User sends a question via the Web UI →

Spring Boot backend receives it through `/api/ask` →

The query is converted into a 1536-dimensional embedding →

Qdrant performs semantic vector search →

System retrieves the most relevant skincare documents →

The LLM receives both the query and retrieved context →

The model generates a grounded, dataset-based response →

The UI displays the answer to the user

### **Backend Components**

| Component | Description |
| --- | --- |
| `EmbeddingClient` | Generates embeddings for dataset & queries |
| `QdrantService` | Creates collection, upserts vectors, performs search |
| `DatasetIngestionRunner` | Converts dataset to embeddings and uploads to Qdrant |
| `RagService` | Combines query, retrieved context, and LLM call |
| Spring MVC Controller | Exposes `/api/ask` endpoint |

### **Vector DB**

- Qdrant running in Docker
- Vector size: **1536**
- Distance: **cosine**
- Collection: `skincare_box`

### **Frontend**

- Pure HTML + CSS + JavaScript
- Markdown rendering
- Typing indicator
- Auto-scroll chat window

---

## 5. Technical Stack

### Backend

- Java 17
- Spring Boot 3
- Maven
- OpenAI API

### Frontend

- HTML
- CSS
- JavaScript

### Infrastructure

- Docker (for Qdrant)
- Local development environment

---

## 6. How to Run the System

### Step 1 — Start Qdrant

```bash
docker run -p 6333:6333 qdrant/qdrant
```

### Step 2 — Set OpenAI API Key

Windows PowerShell:

```powershell
$env:OPENAI_API_KEY="sk-..."
```

### Step 3 — Run Ingestion Module

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="com.example.rag.ingest.DatasetIngestionRunner"
```

### Step 4 — Start Spring Boot Application

```bash
mvn spring-boot:run
```

### Step 5 — Open Web UI

```
http://localhost:8080/index.html
```

---

## 7. Requirements

- Java 17
- Docker installed
- Stable internet connection for embedding & LLM calls
- OpenAI API key
- Browser for UI

---

## 8. Limitations

- Only the dataset content is used for grounding — no external sources
- No session memory or user preference tracking
- No authentication layer
- Embedding generation requires paid OpenAI usage

---