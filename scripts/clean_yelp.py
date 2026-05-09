"""
从 Yelp 原始数据中提取 10 万条英文评价，输出 cleaned_reviews.jsonl
同时提取对应餐厅元数据，输出 restaurants.jsonl

使用方法：
  将 yelp_academic_dataset_review.json 和 yelp_academic_dataset_business.json
  放入 data/raw/ 目录，然后运行：
    python clean_yelp.py
"""
import json
import re
from pathlib import Path

RAW_REVIEW = Path("data/raw/yelp_academic_dataset_review.json")
RAW_BIZ    = Path("data/raw/yelp_academic_dataset_business.json")
OUT_REVIEW = Path("data/cleaned_reviews.jsonl")
OUT_BIZ    = Path("data/restaurants.jsonl")
MAX_REVIEWS  = 100_000
MIN_TEXT_LEN = 50


def is_english(text: str) -> bool:
    ascii_ratio = sum(1 for c in text if ord(c) < 128) / max(len(text), 1)
    return ascii_ratio > 0.9


def clean_text(text: str) -> str:
    return re.sub(r'\s+', ' ', text).strip()


def clean_reviews():
    Path("data").mkdir(exist_ok=True)
    biz_ids = set()
    written = 0

    with RAW_REVIEW.open() as fin, OUT_REVIEW.open("w") as fout:
        for line in fin:
            if written >= MAX_REVIEWS:
                break
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            text = clean_text(r.get("text", ""))
            if len(text) < MIN_TEXT_LEN or not is_english(text):
                continue
            record = {
                "review_id":   r["review_id"],
                "business_id": r["business_id"],
                "stars":       r["stars"],
                "text":        text,
                "date":        r["date"],
            }
            fout.write(json.dumps(record) + "\n")
            biz_ids.add(r["business_id"])
            written += 1
            if written % 10_000 == 0:
                print(f"  Cleaned {written:,} reviews...")

    print(f"Reviews done: {written:,} → {OUT_REVIEW}")
    return biz_ids


def extract_restaurants(biz_ids: set):
    written = 0
    with RAW_BIZ.open() as fin, OUT_BIZ.open("w") as fout:
        for line in fin:
            try:
                b = json.loads(line)
            except json.JSONDecodeError:
                continue
            if b["business_id"] not in biz_ids:
                continue
            record = {
                "id":           b["business_id"],
                "name":         b["name"],
                "city":         b.get("city", ""),
                "state":        b.get("state", ""),
                "stars":        b.get("stars", 0),
                "review_count": b.get("review_count", 0),
                "categories":   b.get("categories", "") or "",
            }
            fout.write(json.dumps(record) + "\n")
            written += 1

    print(f"Restaurants done: {written:,} → {OUT_BIZ}")


if __name__ == "__main__":
    print("Step 1: Cleaning reviews...")
    biz_ids = clean_reviews()
    print("Step 2: Extracting restaurant metadata...")
    extract_restaurants(biz_ids)
    print("All done.")
