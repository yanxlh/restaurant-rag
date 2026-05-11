# 第三节：数据设计 + Redis 缓存策略

## ChromaDB Collection 结构

每条入库 Document：

```json
{
  "id": "review_abc123",
  "content": "The spicy noodles here are incredible, but parking is a nightmare on weekends...",
  "metadata": {
    "restaurant_id": "biz_xyz",
    "restaurant_name": "Golden Dragon",
    "city": "Las Vegas",
    "state": "NV",
    "stars": 4,
    "date": "2023-06-15",
    "chunk_index": 0
  }
}
```

**分块策略：** 每条 Yelp 评价按 512 token 滑动窗口分块，重叠 64 token，保留上下文连贯性。

---

## Redis 缓存三大问题解决方案

| 问题 | 场景 | 解决方案 |
|------|------|----------|
| **缓存穿透** | 查询不存在的餐厅 | 缓存空值，TTL 5 分钟 |
| **缓存击穿** | 热门餐厅缓存同时过期 | 随机 TTL（55~65 分钟）+ 互斥锁 |
| **缓存雪崩** | 大量 key 同时失效 | TTL 加随机抖动 ±10% |

```java
// 随机 TTL 防雪崩
long ttl = 3600 + ThreadLocalRandom.current().nextLong(-360, 360);
redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttl));

// 空值防穿透
if (result == null) {
    redisTemplate.opsForValue().set(key, "NULL", Duration.ofMinutes(5));
    return null;
}
```

---

## 餐厅数据存储（MySQL）

```sql
CREATE TABLE restaurants (
    id          VARCHAR(64) PRIMARY KEY,   -- Yelp business_id
    name        VARCHAR(255) NOT NULL,
    city        VARCHAR(100),
    state       VARCHAR(50),
    stars       DECIMAL(2,1),
    review_count INT,
    categories  VARCHAR(500)               -- "Chinese, Noodles, Asian"
);
```

> 餐厅元数据存 MySQL，评价向量存 ChromaDB，两者通过 `restaurant_id` 关联。
