# 第五节：完整目录结构 + 开发顺序

## 完整项目目录

```
restaurant-rag/
├── docker-compose.yml
├── .env                          # OPENAI_API_KEY, MYSQL_PASSWORD
│
├── backend/                      # Spring Boot
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/restaurant/rag/
│       ├── RagApplication.java
│       ├── config/
│       │   ├── SpringAIConfig.java       # OpenAI + ChromaDB Bean
│       │   └── RedisConfig.java
│       ├── controller/
│       │   ├── RestaurantController.java
│       │   ├── QuestionController.java   # SSE 流式接口
│       │   └── IngestionController.java
│       ├── service/
│       │   ├── RAGService.java           # 核心检索生成
│       │   ├── CacheService.java         # Redis 封装
│       │   └── IngestionService.java
│       └── model/
│           ├── Restaurant.java
│           └── AskRequest.java
│
├── frontend/                     # Vue 3
│   ├── Dockerfile
│   ├── package.json
│   └── src/
│       ├── main.js
│       ├── App.vue
│       ├── views/HomeView.vue
│       ├── components/
│       │   ├── RestaurantPanel.vue   # 左侧筛选
│       │   ├── ChatWindow.vue        # 右侧聊天
│       │   └── MessageBubble.vue     # 流式打字气泡
│       └── api/restaurant.js
│
└── scripts/                      # Python 数据处理
    ├── requirements.txt
    ├── clean_yelp.py             # 清洗过滤 10 万条
    ├── chunk_reviews.py          # 512 token 分块
    └── ingest_chroma.py          # 批量入库 ChromaDB
```

---

## 推荐开发顺序（7步）

```
Step 1  ── Docker Compose 启动基础设施
           (ChromaDB + Redis + MySQL 全部就绪)

Step 2  ── Python 脚本处理 Yelp 数据
           clean → chunk → ingest（约 30 分钟）

Step 3  ── Spring Boot 骨架 + SpringAIConfig
           验证能连上 ChromaDB + OpenAI

Step 4  ── RAGService 核心逻辑
           embedding → 检索 → GPT-4o 生成（先用 REST，再改 SSE）

Step 5  ── RestaurantController + CacheService
           餐厅搜索接口 + Redis 缓存接入

Step 6  ── Vue 前端
           筛选面板 → ChatWindow → SSE 流式打字效果

Step 7  ── 联调 + Docker 打包全量部署
           docker compose up -d 一键验证
```
