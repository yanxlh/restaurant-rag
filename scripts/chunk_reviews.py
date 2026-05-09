"""
将 cleaned_reviews.jsonl 中每条评价按 512 token 滑动窗口分块（重叠 64 token）
输出 chunked_reviews.jsonl，每行一个 chunk

使用方法：先运行 clean_yelp.py，再运行：
    python chunk_reviews.py
"""
import json
import tiktoken
from pathlib import Path

IN_FILE  = Path("data/cleaned_reviews.jsonl")
OUT_FILE = Path("data/chunked_reviews.jsonl")
CHUNK_SIZE    = 512
CHUNK_OVERLAP = 64

enc = tiktoken.get_encoding("cl100k_base")


def chunk_text(text: str) -> list:
    tokens = enc.encode(text)
    chunks = []
    start = 0
    while start < len(tokens):
        end = min(start + CHUNK_SIZE, len(tokens))
        chunks.append(enc.decode(tokens[start:end]))
        if end == len(tokens):
            break
        start += CHUNK_SIZE - CHUNK_OVERLAP
    return chunks


def main():
    total_chunks = 0
    with IN_FILE.open() as fin, OUT_FILE.open("w") as fout:
        for line in fin:
            r = json.loads(line)
            for i, chunk in enumerate(chunk_text(r["text"])):
                record = {
                    "id":          f"{r['review_id']}_chunk{i}",
                    "content":     chunk,
                    "review_id":   r["review_id"],
                    "business_id": r["business_id"],
                    "stars":       r["stars"],
                    "date":        r["date"],
                    "chunk_index": i,
                }
                fout.write(json.dumps(record) + "\n")
                total_chunks += 1
            if total_chunks % 10_000 == 0:
                print(f"  Chunked {total_chunks:,}...")

    print(f"Done. {total_chunks:,} chunks → {OUT_FILE}")


if __name__ == "__main__":
    main()
