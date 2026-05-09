"""
将 chunked_reviews.jsonl 批量 embed 并写入 ChromaDB
将 restaurants.jsonl 通过后端 /api/ingest/restaurants 导入 MySQL

前置条件：
  1. 后端 Spring Boot 已启动（localhost:8080）
  2. ChromaDB 已启动（localhost:8000）
  3. 已运行 clean_yelp.py 和 chunk_reviews.py

使用方法：
    OPENAI_API_KEY=sk-xxx python ingest_chroma.py
"""
import json
import os
import requests
from pathlib import Path
from tqdm import tqdm
import chromadb
from chromadb.utils.embedding_functions import OllamaEmbeddingFunction

CHROMA_HOST  = os.getenv("CHROMA_HOST", "localhost")
OLLAMA_URL   = os.getenv("OLLAMA_URL", "http://localhost:11434")
BATCH_SIZE   = 50    # Ollama embedding 本地推理，batch 小一点更稳
COLLECTION   = "restaurant_reviews"
CHUNKED_FILE = Path("data/chunked_reviews.jsonl")
RESTAURANTS  = Path("data/restaurants.jsonl")
BACKEND_URL  = os.getenv("BACKEND_URL", "http://localhost:8080")


def ingest_restaurants():
    records = [json.loads(l) for l in RESTAURANTS.read_text().splitlines() if l.strip()]
    # 分批发送，避免请求体过大
    batch_size = 500
    total = 0
    for i in range(0, len(records), batch_size):
        batch = records[i:i + batch_size]
        resp = requests.post(f"{BACKEND_URL}/api/ingest/restaurants", json=batch, timeout=30)
        resp.raise_for_status()
        total += resp.json()["imported"]
    print(f"Imported {total:,} restaurants into MySQL")


def ingest_reviews():
    client = chromadb.HttpClient(host=CHROMA_HOST, port=8000)
    embed_fn = OllamaEmbeddingFunction(
        url=f"{OLLAMA_URL}/api/embeddings",
        model_name="nomic-embed-text"
    )
    collection = client.get_or_create_collection(
        name=COLLECTION,
        embedding_function=embed_fn,
        metadata={"hnsw:space": "cosine"}
    )

    lines = [l for l in CHUNKED_FILE.read_text().splitlines() if l.strip()]
    for i in tqdm(range(0, len(lines), BATCH_SIZE), desc="Ingesting reviews"):
        batch = [json.loads(l) for l in lines[i:i + BATCH_SIZE]]
        collection.upsert(
            ids       = [r["id"] for r in batch],
            documents = [r["content"] for r in batch],
            metadatas = [{
                "restaurant_id": r["business_id"],
                "stars":         r["stars"],
                "date":          r["date"],
                "chunk_index":   r["chunk_index"],
            } for r in batch],
        )

    print(f"Done. Total vectors in collection: {collection.count():,}")


if __name__ == "__main__":
    print("Step 1: Importing restaurants into MySQL...")
    ingest_restaurants()
    print("Step 2: Ingesting review vectors into ChromaDB...")
    ingest_reviews()
    print("All done!")
