# 餐厅评价 RAG 智能问答系统 — 完整设计文档

**日期：** 2026-05-09  
**状态：** 已批准

---

## 项目背景

用户选餐厅时面临大量碎片化评价，难以快速找到关键信息（如"辣不辣""适合约会吗""停车方便吗"）。本系统基于 RAG 架构，将 Yelp 公开评价数据集（取 10 万条子集做 Demo）向量化入库，结合 LLM 实现面向自然语言提问的精准问答。

---

## 技术栈

| 层次 | 技术 |
|------|------|
| 后端 | Spring Boot 3 + Spring AI |
| LLM | OpenAI GPT-4o（生成答案 + Embedding） |
| 向量数据库 | ChromaDB（HTTP API，Docker 部署） |
| 缓存 | Redis 7 |
| 关系数据库 | MySQL 8 |
| 前端 | Vue 3 + SSE 流式打字效果 |
| 数据预处理 | Python（clean → chunk → ingest） |
| 部署 | Docker Compose 一键启动 |
| 数据来源 | Yelp 公开数据集，取 10 万条英文评价 |

---

## 第一节：整体架构

```
┌──────────────────────────────────────────────────────────┐
│                      Vue 3 前端                           │
│   [城市/餐厅筛选面板]        [流式打字聊天界面 (SSE)]      │
└──────────────────┬──────────────────┬────────────────────┘
                   │ REST / SSE        │
┌──────────────────▼──────────────────▼────────────────────┐
│                 Spring Boot 后端                          │
│                                                          │
│  RestaurantController   /api/restaurants  (筛选餐厅)     │
│  QuestionController     /api/ask          (RAG 问答 SSE) │
│  IngestionController    /api/ingest       (数据入库)      │
│                                                          │
│  RAGService    ←→  Spring AI (检索+生成链)               │
│  CacheService  ←→  Redis (高频问题缓存)                  │
└────────┬────────────────────┬──────────────┬─────────────┘
         │                    │              │
   ┌─────▼─────┐      ┌───────▼──────┐  ┌───▼──────────┐
   │   Redis   │      │   ChromaDB   │  │  OpenAI API  │
   │  缓存层   │      │  向量数据库  │  │  GPT-4o      │
   │  TTL:1h   │      │  HTTP API    │  │  Embeddings  │
   └───────────┘      └──────────────┘  └──────────────┘
                              ▲
               ┌──────────────┴──────────────┐
               │       数据预处理脚本 (Python) │
               │  Yelp JSON → 清洗 → 分块     │
               │  → OpenAI Embed → ChromaDB  │
               └─────────────────────────────┘
```

### 问答数据流

1. 用户选城市/餐厅 → `GET /api/restaurants?city=Las Vegas`
2. 用户输问题 → `POST /api/ask` 返回 SSE 流
3. Spring AI 用 OpenAI Embedding 将问题向量化
4. **先查 Redis 缓存**（命中 → 直接返回，省去约 70% 延迟）
5. 未命中 → ChromaDB 检索 Top-5 相关评价（按 restaurant_id 过滤）
6. 拼接 Prompt → GPT-4o 流式生成答案
7. 写入 Redis 缓存，同时 SSE 推送给 Vue 前端

---

## 第二节：核心组件详细设计

### 后端模块结构

```
src/main/java/com/restaurant/rag/
├── controller/
│   ├── RestaurantController.java
│   ├── QuestionController.java
│   └── IngestionController.java
├── service/
│   ├── RAGService.java
│   ├── EmbeddingService.java
│   ├── CacheService.java
│   └── IngestionService.java
├── model/
│   ├── Restaurant.java
│   ├── Review.java
│   └── AskRequest.java
└── config/
    ├── SpringAIConfig.java
    └── RedisConfig.java
```

### RAGService 核心流程

```java
public Flux<String> ask(String restaurantId, String question) {
    String cacheKey = restaurantId + ":" + md5(question);

    String cached = cacheService.get(cacheKey);
    if (cached != null) return Flux.just(cached);

    List<Document> docs = vectorStore.similaritySearch(
        SearchRequest.query(question)
                     .withTopK(5)
                     .withFilterExpression("restaurant_id == '" + restaurantId + "'")
    );

    String context = docs.stream()
        .map(Document::getContent)
        .collect(joining("\n---\n"));

    PromptTemplate template = new PromptTemplate(SYSTEM_PROMPT);
    Prompt prompt = template.create(Map.of("context", context, "question", question));

    return chatClient.stream(prompt)
        .doOnComplete(() -> cacheService.set(cacheKey, fullAnswer, Duration.ofHours(1)));
}
```

### 数据预处理脚本（Python）

```
scripts/
├── requirements.txt
├── clean_yelp.py        # 过滤英文评价，去噪，抽取 10 万条
├── chunk_reviews.py     # 每条评价按 512 token 滑动窗口分块，重叠 64 token
└── ingest_chroma.py     # 批量 embed + 写入 ChromaDB，batch_size=100
```

### Vue 前端核心组件

```
src/
├── views/HomeView.vue
├── components/
│   ├── RestaurantPanel.vue   # 城市/餐厅搜索筛选
│   ├── ChatWindow.vue        # 聊天消息列表
│   └── MessageBubble.vue     # 单条消息（含流式打字动画）
└── api/restaurant.js         # axios 封装 + EventSource SSE
```

SSE 流式打字效果（POST + ReadableStream，因 EventSource 仅支持 GET）：

```js
const response = await fetch('/api/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ restaurantId: id, question: q })
})
const reader = response.body.getReader()
const decoder = new TextDecoder()
while (true) {
    const { done, value } = await reader.read()
    if (done) break
    currentMessage.value += decoder.decode(value)  // 逐 token 追加
}
```

---

## 第三节：数据设计 + Redis 缓存策略

### ChromaDB Document 结构

```json
{
  "id": "review_abc123",
  "content": "The spicy noodles here are incredible...",
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

**分块策略：** 512 token 滑动窗口，重叠 64 token。

### Redis 缓存三大问题解决方案

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

### MySQL 餐厅元数据表

```sql
CREATE TABLE restaurants (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    city        VARCHAR(100),
    state       VARCHAR(50),
    stars       DECIMAL(2,1),
    review_count INT,
    categories  VARCHAR(500)
);
```

> 餐厅元数据存 MySQL，评价向量存 ChromaDB，通过 `restaurant_id` 关联。

---

## 第四节：Docker 部署 + 性能指标

### docker-compose.yml

```yaml
services:
  frontend:
    build: ./frontend
    ports: ["80:80"]
    depends_on: [backend]

  backend:
    build: ./backend
    ports: ["8080:8080"]
    environment:
      OPENAI_API_KEY: ${OPENAI_API_KEY}
      SPRING_REDIS_HOST: redis
      CHROMA_HOST: chromadb
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/restaurant_rag
    depends_on: [redis, chromadb, mysql]

  chromadb:
    image: chromadb/chroma:latest
    ports: ["8000:8000"]
    volumes: ["chroma_data:/chroma/chroma"]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    command: redis-server --maxmemory 512mb --maxmemory-policy allkeys-lru

  mysql:
    image: mysql:8
    environment:
      MYSQL_DATABASE: restaurant_rag
      MYSQL_ROOT_PASSWORD: ${MYSQL_PASSWORD}
    volumes: ["mysql_data:/var/lib/mysql"]

volumes:
  chroma_data:
  mysql_data:
```

### 性能指标目标

| 指标 | 目标值 | 实现手段 |
|------|--------|----------|
| 平均响应时间 | ≤ 1s | Redis 缓存 + ChromaDB 预热 |
| 缓存命中率 | ≥ 70% | 高频问题 key 标准化 |
| 问答准确率 | ≥ 82% | Top-5 检索 + 精准 Prompt |
| 并发支持 | 50 QPS | Spring WebFlux 非阻塞 + SSE |
| 数据入库速度 | ~1000 条/分钟 | Python 批量 embed，batch_size=100 |

### Prompt 模板

```
你是一个餐厅评价分析助手。根据以下真实用户评价回答问题。
只基于提供的评价内容作答，不要编造信息。
如果评价中没有相关信息，请明确告知。

【餐厅评价】
{context}

【用户问题】
{question}

请用简洁的中文回答，并在末尾注明参考了几条评价。
```

---

## 第五节：完整目录结构 + 开发顺序

### 项目目录

```
restaurant-rag/
├── docker-compose.yml
├── .env
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/restaurant/rag/
│       ├── RagApplication.java
│       ├── config/
│       ├── controller/
│       ├── service/
│       └── model/
├── frontend/
│   ├── Dockerfile
│   ├── package.json
│   └── src/
│       ├── views/
│       ├── components/
│       └── api/
└── scripts/
    ├── requirements.txt
    ├── clean_yelp.py
    ├── chunk_reviews.py
    └── ingest_chroma.py
```

### 推荐开发顺序（7步）

| 步骤 | 内容 |
|------|------|
| Step 1 | Docker Compose 启动基础设施（ChromaDB + Redis + MySQL） |
| Step 2 | Python 脚本处理 Yelp 数据（clean → chunk → ingest，约 30 分钟） |
| Step 3 | Spring Boot 骨架 + SpringAIConfig，验证连通性 |
| Step 4 | RAGService 核心逻辑（embedding → 检索 → GPT-4o，先 REST 再改 SSE） |
| Step 5 | RestaurantController + CacheService（搜索接口 + Redis 缓存） |
| Step 6 | Vue 前端（筛选面板 → ChatWindow → SSE 流式打字效果） |
| Step 7 | 联调 + Docker 全量打包部署，`docker compose up -d` 验证 |
