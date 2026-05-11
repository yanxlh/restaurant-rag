# 第四节：Docker 部署 + 性能指标

## Docker Compose 整体编排

```yaml
# docker-compose.yml
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

启动全套环境只需一条命令：

```bash
docker compose up -d
```

---

## 性能指标设计

| 指标 | 目标值 | 实现手段 |
|------|--------|----------|
| 平均响应时间 | ≤ 1s | Redis 缓存 + ChromaDB 预热 |
| 缓存命中率 | ≥ 70% | 高频问题 key 标准化（小写+去标点） |
| 问答准确率 | ≥ 82% | Top-5 检索 + 精准 Prompt |
| 并发支持 | 50 QPS | Spring WebFlux 非阻塞 + SSE |
| 数据入库速度 | ~1000 条/分钟 | Python 批量 embed，batch_size=100 |

---

## Prompt 模板（影响准确率的关键）

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
