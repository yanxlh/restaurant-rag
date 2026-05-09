# 餐厅评价 RAG 智能问答系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建基于 RAG 的餐厅评价智能问答系统，支持自然语言提问、流式打字回答、Redis 缓存，Docker 一键部署。

**Architecture:** Python 脚本清洗 Yelp 数据并向量化入 ChromaDB；Spring Boot + Spring AI 后端提供 SSE 流式问答接口，Redis 缓存高频问题；Vue 3 前端左筛选右聊天，逐 token 流式打字效果。

**Tech Stack:** Spring Boot 3.3 + Spring AI 1.0.0 + Spring WebFlux / OpenAI GPT-4o + text-embedding-3-small / ChromaDB / Redis 7 / MySQL 8 / Vue 3 + Vite / Python 3.11 + chromadb + openai / Docker Compose

---

## File Map

```
restaurant-rag/
├── docker-compose.yml
├── .env.example
├── scripts/
│   ├── requirements.txt
│   ├── clean_yelp.py
│   ├── chunk_reviews.py
│   └── ingest_chroma.py
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/restaurant/rag/
│       │   │   ├── RagApplication.java
│       │   │   ├── config/
│       │   │   │   ├── SpringAIConfig.java
│       │   │   │   └── RedisConfig.java
│       │   │   ├── model/
│       │   │   │   ├── Restaurant.java
│       │   │   │   └── AskRequest.java
│       │   │   ├── repository/
│       │   │   │   └── RestaurantRepository.java
│       │   │   ├── service/
│       │   │   │   ├── CacheService.java
│       │   │   │   ├── RAGService.java
│       │   │   │   └── IngestionService.java
│       │   │   └── controller/
│       │   │       ├── RestaurantController.java
│       │   │       ├── QuestionController.java
│       │   │       └── IngestionController.java
│       │   └── resources/
│       │       └── application.yml
│       └── test/java/com/restaurant/rag/
│           ├── service/
│           │   ├── CacheServiceTest.java
│           │   └── RAGServiceTest.java
│           └── controller/
│               └── RestaurantControllerTest.java
└── frontend/
    ├── Dockerfile
    ├── nginx.conf
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── main.js
        ├── App.vue
        ├── views/HomeView.vue
        ├── components/
        │   ├── RestaurantPanel.vue
        │   ├── ChatWindow.vue
        │   └── MessageBubble.vue
        └── api/restaurant.js
```

---

## Task 1: 项目脚手架 + Docker Compose 基础设施

**Files:**
- Create: `restaurant-rag/docker-compose.yml`
- Create: `restaurant-rag/.env.example`

- [ ] **Step 1: 创建项目根目录结构**

```bash
mkdir -p restaurant-rag/{scripts,backend,frontend}
cd restaurant-rag
```

- [ ] **Step 2: 创建 docker-compose.yml**

```yaml
# restaurant-rag/docker-compose.yml
services:
  chromadb:
    image: chromadb/chroma:latest
    ports: ["8000:8000"]
    volumes: ["chroma_data:/chroma/chroma"]
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/api/v1/heartbeat"]
      interval: 10s
      retries: 5

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    command: redis-server --maxmemory 512mb --maxmemory-policy allkeys-lru
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      retries: 5

  mysql:
    image: mysql:8
    ports: ["3306:3306"]
    environment:
      MYSQL_DATABASE: restaurant_rag
      MYSQL_ROOT_PASSWORD: ${MYSQL_PASSWORD}
    volumes: ["mysql_data:/var/lib/mysql"]
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      retries: 10

  backend:
    build: ./backend
    ports: ["8080:8080"]
    environment:
      OPENAI_API_KEY: ${OPENAI_API_KEY}
      SPRING_REDIS_HOST: redis
      CHROMA_HOST: chromadb
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/restaurant_rag
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
    depends_on:
      redis: { condition: service_healthy }
      chromadb: { condition: service_healthy }
      mysql: { condition: service_healthy }

  frontend:
    build: ./frontend
    ports: ["80:80"]
    depends_on: [backend]

volumes:
  chroma_data:
  mysql_data:
```

- [ ] **Step 3: 创建 .env.example**

```bash
# restaurant-rag/.env.example
OPENAI_API_KEY=sk-your-key-here
MYSQL_PASSWORD=yourpassword
```

```bash
cp .env.example .env
# 填入真实的 OPENAI_API_KEY 和 MYSQL_PASSWORD
```

- [ ] **Step 4: 启动基础设施（仅 chromadb + redis + mysql，跳过 backend/frontend）**

```bash
docker compose up -d chromadb redis mysql
docker compose ps
```

预期输出：三个服务均为 `healthy`

- [ ] **Step 5: 验证各服务连通**

```bash
# 验证 ChromaDB
curl http://localhost:8000/api/v1/heartbeat
# 预期：{"nanosecond heartbeat": ...}

# 验证 Redis
docker exec -it $(docker compose ps -q redis) redis-cli ping
# 预期：PONG

# 验证 MySQL
docker exec -it $(docker compose ps -q mysql) mysql -uroot -p${MYSQL_PASSWORD} -e "SHOW DATABASES;"
# 预期：包含 restaurant_rag
```

- [ ] **Step 6: Commit**

```bash
git init
git add docker-compose.yml .env.example
git commit -m "feat: add docker compose infrastructure"
```

---

## Task 2: Python 数据清洗脚本

**Files:**
- Create: `scripts/requirements.txt`
- Create: `scripts/clean_yelp.py`

前置：从 Yelp Dataset 官网下载 `yelp_academic_dataset_review.json` 和 `yelp_academic_dataset_business.json`，放入 `scripts/data/raw/`。

- [ ] **Step 1: 创建 requirements.txt**

```
# scripts/requirements.txt
openai==1.30.0
chromadb==0.5.0
tiktoken==0.7.0
tqdm==4.66.4
```

```bash
cd scripts
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
```

- [ ] **Step 2: 创建 clean_yelp.py**

```python
# scripts/clean_yelp.py
"""
从 Yelp 原始数据中提取 10 万条英文评价，输出 cleaned_reviews.jsonl
每行格式：{"review_id", "business_id", "stars", "text", "date"}
"""
import json
import re
from pathlib import Path

RAW_REVIEW = Path("data/raw/yelp_academic_dataset_review.json")
OUT_FILE    = Path("data/cleaned_reviews.jsonl")
MAX_REVIEWS = 100_000
MIN_TEXT_LEN = 50

def is_english(text: str) -> bool:
    ascii_ratio = sum(1 for c in text if ord(c) < 128) / max(len(text), 1)
    return ascii_ratio > 0.9

def clean_text(text: str) -> str:
    text = re.sub(r'\s+', ' ', text)
    return text.strip()

def main():
    Path("data").mkdir(exist_ok=True)
    written = 0
    with RAW_REVIEW.open() as fin, OUT_FILE.open("w") as fout:
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
            written += 1
            if written % 10_000 == 0:
                print(f"Cleaned {written} reviews...")
    print(f"Done. Total: {written} reviews → {OUT_FILE}")

if __name__ == "__main__":
    main()
```

- [ ] **Step 3: 运行并验证**

```bash
python clean_yelp.py
wc -l data/cleaned_reviews.jsonl
# 预期：100000
head -1 data/cleaned_reviews.jsonl | python -m json.tool
# 预期：包含 review_id, business_id, stars, text, date
```

- [ ] **Step 4: 提取餐厅元数据**

```python
# 在 clean_yelp.py 末尾追加，或单独运行此代码片段
# 提取 cleaned_reviews 中出现的 business_id，从 business.json 提取对应餐厅

import json
from pathlib import Path

RAW_BIZ = Path("data/raw/yelp_academic_dataset_business.json")
OUT_BIZ  = Path("data/restaurants.jsonl")

# 收集已清洗评价中的 business_id
biz_ids = set()
with open("data/cleaned_reviews.jsonl") as f:
    for line in f:
        biz_ids.add(json.loads(line)["business_id"])

with RAW_BIZ.open() as fin, OUT_BIZ.open("w") as fout:
    for line in fin:
        b = json.loads(line)
        if b["business_id"] in biz_ids:
            record = {
                "id":           b["business_id"],
                "name":         b["name"],
                "city":         b.get("city", ""),
                "state":        b.get("state", ""),
                "stars":        b.get("stars", 0),
                "review_count": b.get("review_count", 0),
                "categories":   b.get("categories", ""),
            }
            fout.write(json.dumps(record) + "\n")

print(f"Extracted {sum(1 for _ in open(OUT_BIZ))} restaurants → {OUT_BIZ}")
```

```bash
python -c "
import json
from pathlib import Path
# 运行上面代码片段
"
wc -l data/restaurants.jsonl
```

- [ ] **Step 5: Commit**

```bash
cd ..
git add scripts/requirements.txt scripts/clean_yelp.py
git commit -m "feat: add yelp data cleaning script"
```

---

## Task 3: Python 分块 + 向量入库脚本

**Files:**
- Create: `scripts/chunk_reviews.py`
- Create: `scripts/ingest_chroma.py`

- [ ] **Step 1: 创建 chunk_reviews.py**

```python
# scripts/chunk_reviews.py
"""
将 cleaned_reviews.jsonl 中每条评价按 512 token 滑动窗口分块（重叠 64 token）
输出 chunked_reviews.jsonl，每行一个 chunk
"""
import json
import tiktoken
from pathlib import Path

IN_FILE  = Path("data/cleaned_reviews.jsonl")
OUT_FILE = Path("data/chunked_reviews.jsonl")
CHUNK_SIZE    = 512
CHUNK_OVERLAP = 64

enc = tiktoken.get_encoding("cl100k_base")

def chunk_text(text: str) -> list[str]:
    tokens = enc.encode(text)
    chunks = []
    start = 0
    while start < len(tokens):
        end = min(start + CHUNK_SIZE, len(tokens))
        chunk_tokens = tokens[start:end]
        chunks.append(enc.decode(chunk_tokens))
        if end == len(tokens):
            break
        start += CHUNK_SIZE - CHUNK_OVERLAP
    return chunks

def main():
    total_chunks = 0
    with IN_FILE.open() as fin, OUT_FILE.open("w") as fout:
        for line in fin:
            r = json.loads(line)
            chunks = chunk_text(r["text"])
            for i, chunk in enumerate(chunks):
                record = {
                    "id":            f"{r['review_id']}_chunk{i}",
                    "content":       chunk,
                    "review_id":     r["review_id"],
                    "business_id":   r["business_id"],
                    "stars":         r["stars"],
                    "date":          r["date"],
                    "chunk_index":   i,
                }
                fout.write(json.dumps(record) + "\n")
                total_chunks += 1
    print(f"Done. {total_chunks} chunks → {OUT_FILE}")

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 运行分块并验证**

```bash
cd scripts
python chunk_reviews.py
wc -l data/chunked_reviews.jsonl
# 预期：100000~150000（多数评价分 1-2 块）
```

- [ ] **Step 3: 创建 ingest_chroma.py**

```python
# scripts/ingest_chroma.py
"""
读取 chunked_reviews.jsonl，批量 embed + 写入 ChromaDB
同时将 restaurants.jsonl 导入 MySQL（通过 REST 调用后端 /api/ingest/restaurants）
"""
import json
import requests
from pathlib import Path
from tqdm import tqdm
import chromadb
from chromadb.utils.embedding_functions import OpenAIEmbeddingFunction
import os

CHROMA_HOST    = os.getenv("CHROMA_HOST", "localhost")
OPENAI_API_KEY = os.environ["OPENAI_API_KEY"]
BATCH_SIZE     = 100
COLLECTION     = "restaurant_reviews"
CHUNKED_FILE   = Path("data/chunked_reviews.jsonl")
BACKEND_URL    = "http://localhost:8080"

def ingest_restaurants():
    records = [json.loads(l) for l in open("data/restaurants.jsonl")]
    resp = requests.post(f"{BACKEND_URL}/api/ingest/restaurants", json=records)
    resp.raise_for_status()
    print(f"Imported {len(records)} restaurants into MySQL")

def ingest_reviews():
    client = chromadb.HttpClient(host=CHROMA_HOST, port=8000)
    embed_fn = OpenAIEmbeddingFunction(
        api_key=OPENAI_API_KEY,
        model_name="text-embedding-3-small"
    )
    collection = client.get_or_create_collection(
        name=COLLECTION,
        embedding_function=embed_fn,
        metadata={"hnsw:space": "cosine"}
    )

    lines = CHUNKED_FILE.read_text().splitlines()
    for i in tqdm(range(0, len(lines), BATCH_SIZE), desc="Ingesting"):
        batch = [json.loads(l) for l in lines[i:i+BATCH_SIZE]]
        collection.upsert(
            ids       = [r["id"] for r in batch],
            documents = [r["content"] for r in batch],
            metadatas = [{
                "restaurant_id":   r["business_id"],
                "stars":           r["stars"],
                "date":            r["date"],
                "chunk_index":     r["chunk_index"],
            } for r in batch],
        )
    print(f"Done. Total in collection: {collection.count()}")

if __name__ == "__main__":
    # 先启动后端再运行此脚本
    ingest_restaurants()
    ingest_reviews()
```

- [ ] **Step 4: Commit（脚本，数据文件不提交）**

```bash
cd ..
echo "scripts/data/" >> .gitignore
git add scripts/chunk_reviews.py scripts/ingest_chroma.py .gitignore
git commit -m "feat: add chunking and chroma ingestion scripts"
```

---

## Task 4: Spring Boot 项目骨架

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/restaurant/rag/RagApplication.java`
- Create: `backend/src/main/resources/application.yml`

- [ ] **Step 1: 创建 pom.xml**

```xml
<!-- backend/pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>

  <groupId>com.restaurant</groupId>
  <artifactId>rag</artifactId>
  <version>0.0.1-SNAPSHOT</version>

  <properties>
    <java.version>21</java.version>
    <spring-ai.version>1.0.0</spring-ai.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- Web (WebFlux for SSE streaming) -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
    <!-- JPA + MySQL -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <!-- Redis -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <!-- Spring AI -->
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-chroma-store-spring-boot-starter</artifactId>
    </dependency>
    <!-- Lombok -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <!-- Test -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.projectreactor</groupId>
      <artifactId>reactor-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <excludes>
            <exclude>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </exclude>
          </excludes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: 创建 RagApplication.java**

```java
// backend/src/main/java/com/restaurant/rag/RagApplication.java
package com.restaurant.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 application.yml**

```yaml
# backend/src/main/resources/application.yml
spring:
  application:
    name: restaurant-rag

  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/restaurant_rag}
    username: root
    password: ${MYSQL_PASSWORD:root}
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: update
    database-platform: org.hibernate.dialect.MySQLDialect
    show-sql: false

  data:
    redis:
      host: ${SPRING_REDIS_HOST:localhost}
      port: 6379

  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
      embedding:
        options:
          model: text-embedding-3-small
    vectorstore:
      chroma:
        client:
          host: ${CHROMA_HOST:localhost}
          port: 8000
        collection-name: restaurant_reviews
        initialize-schema: true

server:
  port: 8080
```

- [ ] **Step 4: 验证编译通过**

```bash
cd backend
mvn clean compile -q
# 预期：BUILD SUCCESS（无编译错误）
```

- [ ] **Step 5: Commit**

```bash
cd ..
git add backend/pom.xml backend/src/main/java/com/restaurant/rag/RagApplication.java \
        backend/src/main/resources/application.yml
git commit -m "feat: add spring boot skeleton with spring ai dependencies"
```

---

## Task 5: 配置 Bean + 领域模型

**Files:**
- Create: `backend/src/main/java/com/restaurant/rag/config/SpringAIConfig.java`
- Create: `backend/src/main/java/com/restaurant/rag/config/RedisConfig.java`
- Create: `backend/src/main/java/com/restaurant/rag/model/Restaurant.java`
- Create: `backend/src/main/java/com/restaurant/rag/model/AskRequest.java`
- Create: `backend/src/main/java/com/restaurant/rag/repository/RestaurantRepository.java`

- [ ] **Step 1: 创建 SpringAIConfig.java**

```java
// backend/src/main/java/com/restaurant/rag/config/SpringAIConfig.java
package com.restaurant.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class SpringAIConfig implements WebFluxConfigurer {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
```

- [ ] **Step 2: 创建 RedisConfig.java**

```java
// backend/src/main/java/com/restaurant/rag/config/RedisConfig.java
package com.restaurant.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
```

- [ ] **Step 3: 创建 Restaurant.java（JPA 实体）**

```java
// backend/src/main/java/com/restaurant/rag/model/Restaurant.java
package com.restaurant.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "restaurants")
@Data
@NoArgsConstructor
public class Restaurant {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String name;

    private String city;
    private String state;
    private Double stars;
    private Integer reviewCount;

    @Column(length = 500)
    private String categories;
}
```

- [ ] **Step 4: 创建 AskRequest.java**

```java
// backend/src/main/java/com/restaurant/rag/model/AskRequest.java
package com.restaurant.rag.model;

import lombok.Data;

@Data
public class AskRequest {
    private String restaurantId;
    private String question;
}
```

- [ ] **Step 5: 创建 RestaurantRepository.java**

```java
// backend/src/main/java/com/restaurant/rag/repository/RestaurantRepository.java
package com.restaurant.rag.repository;

import com.restaurant.rag.model.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestaurantRepository extends JpaRepository<Restaurant, String> {
    List<Restaurant> findByCityIgnoreCase(String city);
    List<Restaurant> findByCityIgnoreCaseAndNameContainingIgnoreCase(String city, String name);
}
```

- [ ] **Step 6: 验证编译**

```bash
cd backend && mvn clean compile -q
# 预期：BUILD SUCCESS
```

- [ ] **Step 7: Commit**

```bash
cd ..
git add backend/src/main/java/com/restaurant/rag/config/ \
        backend/src/main/java/com/restaurant/rag/model/ \
        backend/src/main/java/com/restaurant/rag/repository/
git commit -m "feat: add config beans, domain models and repository"
```

---

## Task 6: CacheService（TDD）

**Files:**
- Create: `backend/src/main/java/com/restaurant/rag/service/CacheService.java`
- Create: `backend/src/test/java/com/restaurant/rag/service/CacheServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
// backend/src/test/java/com/restaurant/rag/service/CacheServiceTest.java
package com.restaurant.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private CacheService cacheService;

    @BeforeEach
    void setup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void get_returns_null_for_missing_key() {
        when(valueOps.get("k")).thenReturn(null);
        assertNull(cacheService.get("k"));
    }

    @Test
    void get_returns_null_for_null_sentinel() {
        when(valueOps.get("k")).thenReturn("NULL");
        assertNull(cacheService.get("k"));
    }

    @Test
    void get_returns_value_when_present() {
        when(valueOps.get("k")).thenReturn("answer");
        assertEquals("answer", cacheService.get("k"));
    }

    @Test
    void set_stores_value_with_ttl_within_10_percent_of_one_hour() {
        cacheService.set("k", "v");
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("k"), eq("v"), ttlCaptor.capture());
        long secs = ttlCaptor.getValue().getSeconds();
        assertTrue(secs >= 3240 && secs <= 3960, "TTL should be 3600 ±10%: " + secs);
    }

    @Test
    void setNullSentinel_stores_NULL_with_5_minute_ttl() {
        cacheService.setNullSentinel("k");
        verify(valueOps).set(eq("k"), eq("NULL"), eq(Duration.ofMinutes(5)));
    }
}
```

- [ ] **Step 2: 运行确认测试失败**

```bash
cd backend
mvn test -pl . -Dtest=CacheServiceTest -q 2>&1 | tail -5
# 预期：COMPILATION ERROR 或 ClassNotFoundException（CacheService 不存在）
```

- [ ] **Step 3: 实现 CacheService.java**

```java
// backend/src/main/java/com/restaurant/rag/service/CacheService.java
package com.restaurant.rag.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class CacheService {

    private final StringRedisTemplate redisTemplate;

    public String get(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || "NULL".equals(value)) return null;
        return value;
    }

    public void set(String key, String value) {
        // 随机 TTL 防雪崩：3600s ± 10%
        long jitter = ThreadLocalRandom.current().nextLong(-360, 361);
        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(3600 + jitter));
    }

    public void setNullSentinel(String key) {
        redisTemplate.opsForValue().set(key, "NULL", Duration.ofMinutes(5));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -Dtest=CacheServiceTest -q
# 预期：Tests run: 5, Failures: 0, Errors: 0
```

- [ ] **Step 5: Commit**

```bash
cd ..
git add backend/src/main/java/com/restaurant/rag/service/CacheService.java \
        backend/src/test/java/com/restaurant/rag/service/CacheServiceTest.java
git commit -m "feat: add CacheService with anti-avalanche TTL jitter (TDD)"
```

---

## Task 7: RAGService（TDD）

**Files:**
- Create: `backend/src/main/java/com/restaurant/rag/service/RAGService.java`
- Create: `backend/src/test/java/com/restaurant/rag/service/RAGServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
// backend/src/test/java/com/restaurant/rag/service/RAGServiceTest.java
package com.restaurant.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
class RAGServiceTest {

    @Autowired
    private RAGService ragService;

    @MockBean
    private CacheService cacheService;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private ChatClient chatClient;

    @Test
    void ask_returns_cached_answer_without_hitting_vector_store() {
        when(cacheService.get(any())).thenReturn("cached answer");

        StepVerifier.create(ragService.ask("biz1", "is it spicy?"))
                .expectNext("cached answer")
                .verifyComplete();

        verifyNoInteractions(vectorStore);
    }

    @Test
    void ask_on_cache_miss_queries_vector_store_and_streams_response() {
        when(cacheService.get(any())).thenReturn(null);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("Great spicy noodles!")));

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("Yes", " it", " is", " spicy."));

        StepVerifier.create(ragService.ask("biz1", "is it spicy?"))
                .expectNext("Yes", " it", " is", " spicy.")
                .verifyComplete();

        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd backend
mvn test -Dtest=RAGServiceTest -q 2>&1 | tail -5
# 预期：COMPILATION ERROR（RAGService 不存在）
```

- [ ] **Step 3: 实现 RAGService.java**

```java
// backend/src/main/java/com/restaurant/rag/service/RAGService.java
package com.restaurant.rag.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RAGService {

    private static final String SYSTEM_PROMPT = """
            你是一个餐厅评价分析助手。根据以下真实用户评价回答问题。
            只基于提供的评价内容作答，不要编造信息。
            如果评价中没有相关信息，请明确告知。
            请用简洁的中文回答，并在末尾注明参考了几条评价。
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final CacheService cacheService;

    public Flux<String> ask(String restaurantId, String question) {
        String cacheKey = restaurantId + ":" + md5(question);

        String cached = cacheService.get(cacheKey);
        if (cached != null) {
            return Flux.just(cached);
        }

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.query(question)
                        .withTopK(5)
                        .withFilterExpression(b.eq("restaurant_id", restaurantId).build())
        );

        String context = docs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n---\n"));

        String userPrompt = "【餐厅评价】\n" + context + "\n\n【用户问题】\n" + question;

        StringBuilder fullAnswer = new StringBuilder();
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullAnswer::append)
                .doOnComplete(() -> {
                    String answer = fullAnswer.toString();
                    if (!answer.isBlank()) {
                        cacheService.set(cacheKey, answer);
                    }
                });
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.toLowerCase().trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -Dtest=RAGServiceTest -q
# 预期：Tests run: 2, Failures: 0, Errors: 0
```

- [ ] **Step 5: Commit**

```bash
cd ..
git add backend/src/main/java/com/restaurant/rag/service/RAGService.java \
        backend/src/test/java/com/restaurant/rag/service/RAGServiceTest.java
git commit -m "feat: add RAGService with vector search and streaming (TDD)"
```

---

## Task 8: Controllers + IngestionService

**Files:**
- Create: `backend/src/main/java/com/restaurant/rag/service/IngestionService.java`
- Create: `backend/src/main/java/com/restaurant/rag/controller/RestaurantController.java`
- Create: `backend/src/main/java/com/restaurant/rag/controller/QuestionController.java`
- Create: `backend/src/main/java/com/restaurant/rag/controller/IngestionController.java`
- Create: `backend/src/test/java/com/restaurant/rag/controller/RestaurantControllerTest.java`

- [ ] **Step 1: 写 RestaurantController 测试**

```java
// backend/src/test/java/com/restaurant/rag/controller/RestaurantControllerTest.java
package com.restaurant.rag.controller;

import com.restaurant.rag.model.Restaurant;
import com.restaurant.rag.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.mockito.Mockito.when;

@WebFluxTest(RestaurantController.class)
class RestaurantControllerTest {

    @Autowired
    private WebTestClient webClient;

    @MockBean
    private RestaurantRepository restaurantRepository;

    @Test
    void search_by_city_returns_restaurant_list() {
        Restaurant r = new Restaurant();
        r.setId("biz1");
        r.setName("Golden Dragon");
        r.setCity("Las Vegas");
        when(restaurantRepository.findByCityIgnoreCase("Las Vegas")).thenReturn(List.of(r));

        webClient.get().uri("/api/restaurants?city=Las Vegas")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Restaurant.class)
                .hasSize(1);
    }

    @Test
    void search_with_name_filter_returns_filtered_list() {
        when(restaurantRepository.findByCityIgnoreCaseAndNameContainingIgnoreCase("Las Vegas", "Dragon"))
                .thenReturn(List.of());

        webClient.get().uri("/api/restaurants?city=Las Vegas&name=Dragon")
                .exchange()
                .expectStatus().isOk();
    }
}
```

- [ ] **Step 2: 实现 RestaurantController.java**

```java
// backend/src/main/java/com/restaurant/rag/controller/RestaurantController.java
package com.restaurant.rag.controller;

import com.restaurant.rag.model.Restaurant;
import com.restaurant.rag.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantRepository restaurantRepository;

    @GetMapping
    public List<Restaurant> search(
            @RequestParam String city,
            @RequestParam(defaultValue = "") String name) {
        if (name.isBlank()) {
            return restaurantRepository.findByCityIgnoreCase(city);
        }
        return restaurantRepository.findByCityIgnoreCaseAndNameContainingIgnoreCase(city, name);
    }
}
```

- [ ] **Step 3: 实现 QuestionController.java（SSE 流式）**

```java
// backend/src/main/java/com/restaurant/rag/controller/QuestionController.java
package com.restaurant.rag.controller;

import com.restaurant.rag.model.AskRequest;
import com.restaurant.rag.service.RAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QuestionController {

    private final RAGService ragService;

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ask(@RequestBody AskRequest request) {
        return ragService.ask(request.getRestaurantId(), request.getQuestion());
    }
}
```

- [ ] **Step 4: 实现 IngestionService.java + IngestionController.java**

```java
// backend/src/main/java/com/restaurant/rag/service/IngestionService.java
package com.restaurant.rag.service;

import com.restaurant.rag.model.Restaurant;
import com.restaurant.rag.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IngestionService {

    private final RestaurantRepository restaurantRepository;

    public int saveRestaurants(List<Restaurant> restaurants) {
        restaurantRepository.saveAll(restaurants);
        return restaurants.size();
    }
}
```

```java
// backend/src/main/java/com/restaurant/rag/controller/IngestionController.java
package com.restaurant.rag.controller;

import com.restaurant.rag.model.Restaurant;
import com.restaurant.rag.service.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping("/restaurants")
    public Map<String, Integer> ingestRestaurants(@RequestBody List<Restaurant> restaurants) {
        int count = ingestionService.saveRestaurants(restaurants);
        return Map.of("imported", count);
    }
}
```

- [ ] **Step 5: 运行所有测试**

```bash
cd backend
mvn test -q
# 预期：所有测试通过，0 failures
```

- [ ] **Step 6: Commit**

```bash
cd ..
git add backend/src/main/java/com/restaurant/rag/controller/ \
        backend/src/main/java/com/restaurant/rag/service/IngestionService.java \
        backend/src/test/java/com/restaurant/rag/controller/
git commit -m "feat: add REST controllers and ingestion service"
```

---

## Task 9: Vue 3 前端脚手架 + API 层

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.js`
- Create: `frontend/src/main.js`
- Create: `frontend/src/App.vue`
- Create: `frontend/src/api/restaurant.js`

- [ ] **Step 1: 创建 Vue 3 项目**

```bash
cd frontend
npm create vue@latest . -- --router --no-typescript --no-pinia --no-vitest --no-eslint
npm install
npm install axios
```

- [ ] **Step 2: 配置 vite.config.js（代理后端）**

```js
// frontend/vite.config.js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  }
})
```

- [ ] **Step 3: 创建 API 层 restaurant.js**

```js
// frontend/src/api/restaurant.js
import axios from 'axios'

export async function searchRestaurants(city, name = '') {
  const params = { city }
  if (name) params.name = name
  const { data } = await axios.get('/api/restaurants', { params })
  return data
}

export async function askQuestion(restaurantId, question, onToken, onDone) {
  const response = await fetch('/api/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ restaurantId, question })
  })

  if (!response.ok) {
    throw new Error(`HTTP error ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    const chunk = decoder.decode(value, { stream: true })
    // SSE 格式：每行 "data: <token>\n\n"，提取 token
    const lines = chunk.split('\n')
    for (const line of lines) {
      if (line.startsWith('data:')) {
        const token = line.slice(5).trim()
        if (token && token !== '[DONE]') onToken(token)
      }
    }
  }
  onDone()
}
```

- [ ] **Step 4: 精简 App.vue**

```vue
<!-- frontend/src/App.vue -->
<template>
  <RouterView />
</template>

<script setup>
import { RouterView } from 'vue-router'
</script>

<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f5f5f5; }
</style>
```

- [ ] **Step 5: Commit**

```bash
cd ..
git add frontend/package.json frontend/vite.config.js frontend/src/main.js \
        frontend/src/App.vue frontend/src/api/restaurant.js
git commit -m "feat: add vue3 scaffold and api layer"
```

---

## Task 10: Vue 核心组件

**Files:**
- Create: `frontend/src/components/RestaurantPanel.vue`
- Create: `frontend/src/components/MessageBubble.vue`
- Create: `frontend/src/components/ChatWindow.vue`
- Create: `frontend/src/views/HomeView.vue`

- [ ] **Step 1: 创建 RestaurantPanel.vue**

```vue
<!-- frontend/src/components/RestaurantPanel.vue -->
<template>
  <aside class="panel">
    <h2>选择餐厅</h2>

    <div class="field">
      <label>城市</label>
      <input v-model="city" placeholder="Las Vegas" @keyup.enter="search" />
    </div>

    <div class="field">
      <label>餐厅名称（可选）</label>
      <input v-model="nameFilter" placeholder="Golden Dragon" @keyup.enter="search" />
    </div>

    <button @click="search" :disabled="loading">
      {{ loading ? '搜索中...' : '搜索' }}
    </button>

    <p v-if="error" class="error">{{ error }}</p>

    <ul class="result-list">
      <li
        v-for="r in restaurants"
        :key="r.id"
        :class="{ active: selected?.id === r.id }"
        @click="$emit('select', r)"
      >
        <span class="name">{{ r.name }}</span>
        <span class="stars">⭐ {{ r.stars }}</span>
      </li>
    </ul>
  </aside>
</template>

<script setup>
import { ref } from 'vue'
import { searchRestaurants } from '../api/restaurant.js'

const props = defineProps({ selected: Object })
defineEmits(['select'])

const city = ref('')
const nameFilter = ref('')
const restaurants = ref([])
const loading = ref(false)
const error = ref('')

async function search() {
  if (!city.value.trim()) return
  loading.value = true
  error.value = ''
  try {
    restaurants.value = await searchRestaurants(city.value, nameFilter.value)
  } catch {
    error.value = '搜索失败，请检查后端服务'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.panel { width: 280px; background: #fff; padding: 20px; border-right: 1px solid #e0e0e0; height: 100vh; overflow-y: auto; }
h2 { font-size: 18px; margin-bottom: 16px; }
.field { margin-bottom: 12px; }
label { display: block; font-size: 13px; color: #666; margin-bottom: 4px; }
input { width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; }
button { width: 100%; padding: 10px; background: #1a73e8; color: #fff; border: none; border-radius: 6px; cursor: pointer; font-size: 14px; margin-top: 4px; }
button:disabled { background: #aaa; }
.error { color: #d32f2f; font-size: 13px; margin-top: 8px; }
.result-list { list-style: none; margin-top: 16px; }
.result-list li { padding: 10px; border-radius: 6px; cursor: pointer; display: flex; justify-content: space-between; }
.result-list li:hover { background: #f0f4ff; }
.result-list li.active { background: #e8f0fe; font-weight: 600; }
.name { font-size: 14px; }
.stars { font-size: 13px; color: #888; }
</style>
```

- [ ] **Step 2: 创建 MessageBubble.vue**

```vue
<!-- frontend/src/components/MessageBubble.vue -->
<template>
  <div :class="['bubble', role]">
    <p class="text">{{ content }}<span v-if="streaming" class="cursor">▋</span></p>
  </div>
</template>

<script setup>
defineProps({
  role: { type: String, required: true },  // 'user' | 'assistant'
  content: { type: String, required: true },
  streaming: { type: Boolean, default: false }
})
</script>

<style scoped>
.bubble { display: flex; margin: 8px 16px; }
.bubble.user { justify-content: flex-end; }
.bubble.assistant { justify-content: flex-start; }
.text {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.user .text { background: #1a73e8; color: #fff; border-bottom-right-radius: 4px; }
.assistant .text { background: #fff; border: 1px solid #e0e0e0; border-bottom-left-radius: 4px; }
.cursor { animation: blink 1s infinite; }
@keyframes blink { 0%, 100% { opacity: 1 } 50% { opacity: 0 } }
</style>
```

- [ ] **Step 3: 创建 ChatWindow.vue**

```vue
<!-- frontend/src/components/ChatWindow.vue -->
<template>
  <div class="chat">
    <div class="header">
      <span v-if="restaurant">{{ restaurant.name }} · {{ restaurant.city }}</span>
      <span v-else class="hint">请先在左侧选择餐厅</span>
    </div>

    <div class="messages" ref="messagesEl">
      <MessageBubble
        v-for="(msg, i) in messages"
        :key="i"
        :role="msg.role"
        :content="msg.content"
        :streaming="msg.streaming"
      />
      <p v-if="!restaurant" class="empty">选择餐厅后，您可以询问任何问题，例如："这里辣吗？"</p>
    </div>

    <div class="input-row">
      <input
        v-model="inputText"
        :disabled="!restaurant || loading"
        placeholder="输入问题，如：停车方便吗？"
        @keyup.enter="sendQuestion"
      />
      <button @click="sendQuestion" :disabled="!restaurant || loading || !inputText.trim()">
        发送
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import MessageBubble from './MessageBubble.vue'
import { askQuestion } from '../api/restaurant.js'

const props = defineProps({ restaurant: Object })

const messages = ref([])
const inputText = ref('')
const loading = ref(false)
const messagesEl = ref(null)

watch(() => props.restaurant, () => { messages.value = [] })

async function sendQuestion() {
  const q = inputText.value.trim()
  if (!q || !props.restaurant || loading.value) return

  messages.value.push({ role: 'user', content: q })
  inputText.value = ''
  loading.value = true

  const assistantMsg = { role: 'assistant', content: '', streaming: true }
  messages.value.push(assistantMsg)
  await scrollBottom()

  try {
    await askQuestion(
      props.restaurant.id,
      q,
      (token) => {
        assistantMsg.content += token
        scrollBottom()
      },
      () => { assistantMsg.streaming = false }
    )
  } catch {
    assistantMsg.content = '抱歉，请求失败，请稍后重试。'
    assistantMsg.streaming = false
  } finally {
    loading.value = false
  }
}

async function scrollBottom() {
  await nextTick()
  if (messagesEl.value) {
    messagesEl.value.scrollTop = messagesEl.value.scrollHeight
  }
}
</script>

<style scoped>
.chat { flex: 1; display: flex; flex-direction: column; height: 100vh; }
.header { padding: 16px 20px; background: #fff; border-bottom: 1px solid #e0e0e0; font-size: 16px; font-weight: 600; }
.hint { color: #aaa; font-weight: 400; }
.messages { flex: 1; overflow-y: auto; padding: 16px 0; background: #f5f5f5; }
.empty { text-align: center; color: #bbb; margin-top: 40px; font-size: 14px; }
.input-row { display: flex; gap: 8px; padding: 16px; background: #fff; border-top: 1px solid #e0e0e0; }
input { flex: 1; padding: 10px 14px; border: 1px solid #ddd; border-radius: 24px; font-size: 14px; outline: none; }
input:focus { border-color: #1a73e8; }
button { padding: 10px 20px; background: #1a73e8; color: #fff; border: none; border-radius: 24px; cursor: pointer; font-size: 14px; }
button:disabled { background: #aaa; }
</style>
```

- [ ] **Step 4: 创建 HomeView.vue**

```vue
<!-- frontend/src/views/HomeView.vue -->
<template>
  <div class="home">
    <RestaurantPanel :selected="selectedRestaurant" @select="selectedRestaurant = $event" />
    <ChatWindow :restaurant="selectedRestaurant" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import RestaurantPanel from '../components/RestaurantPanel.vue'
import ChatWindow from '../components/ChatWindow.vue'

const selectedRestaurant = ref(null)
</script>

<style scoped>
.home { display: flex; height: 100vh; overflow: hidden; }
</style>
```

- [ ] **Step 5: 启动前端开发服务器验证页面**

```bash
cd frontend
npm run dev
# 浏览器打开 http://localhost:5173
# 验证：左侧面板可输入城市，右侧聊天区提示"请先选择餐厅"
```

- [ ] **Step 6: Commit**

```bash
cd ..
git add frontend/src/components/ frontend/src/views/HomeView.vue
git commit -m "feat: add vue3 components - restaurant panel, chat window, message bubble"
```

---

## Task 11: Dockerfiles + Nginx 配置

**Files:**
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `frontend/nginx.conf`

- [ ] **Step 1: 创建 backend/Dockerfile**

```dockerfile
# backend/Dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && mvn clean package -DskipTests -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: 创建 frontend/nginx.conf**

```nginx
# frontend/nginx.conf
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # SSE 必须关闭 buffering
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
    }
}
```

- [ ] **Step 3: 创建 frontend/Dockerfile**

```dockerfile
# frontend/Dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

- [ ] **Step 4: Commit**

```bash
git add backend/Dockerfile frontend/Dockerfile frontend/nginx.conf
git commit -m "feat: add dockerfiles for backend and frontend"
```

---

## Task 12: 全量集成 + 端到端验证

- [ ] **Step 1: 构建并启动全部服务**

```bash
docker compose up -d --build
docker compose ps
# 预期：所有服务均为 running/healthy
```

- [ ] **Step 2: 运行 Python 数据入库**

```bash
cd scripts
source venv/bin/activate
# 先运行清洗（如尚未运行）
python clean_yelp.py
python chunk_reviews.py
# 确保后端已启动后运行入库
OPENAI_API_KEY=<your-key> python ingest_chroma.py
```

预期输出：
```
Imported XXXX restaurants into MySQL
100%|██████████| 1200/1200 [25:00<00:00]
Done. Total in collection: 120000
```

- [ ] **Step 3: 验证 ChromaDB 数据**

```bash
curl http://localhost:8000/api/v1/collections/restaurant_reviews
# 预期：包含 count > 0
```

- [ ] **Step 4: 验证后端问答接口**

```bash
# 先查询一个 business_id（从 MySQL 中取）
BIDS=$(curl -s "http://localhost:8080/api/restaurants?city=Las%20Vegas" | python -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")

# 测试流式问答
curl -N -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d "{\"restaurantId\":\"$BIDS\",\"question\":\"Is the food spicy?\"}"
# 预期：流式输出文字片段，最终为完整中文回答
```

- [ ] **Step 5: 浏览器端对端测试**

打开 `http://localhost`，执行以下验证：
1. 输入城市 `Las Vegas` → 点击搜索 → 列表出现餐厅
2. 点击一家餐厅 → 右侧聊天区显示餐厅名
3. 输入"这里停车方便吗？" → 点击发送
4. 确认：回答逐字流式打字出现，最终显示完整中文答案

- [ ] **Step 6: 验证 Redis 缓存**

```bash
# 发送相同问题两次，第二次应明显更快（<100ms vs ~1s）
time curl -s -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d "{\"restaurantId\":\"$BIDS\",\"question\":\"Is the food spicy?\"}" > /dev/null

# 查看 Redis 缓存键
docker exec -it $(docker compose ps -q redis) redis-cli KEYS "*"
# 预期：出现 biz_xxx:md5hash 格式的 key
```

- [ ] **Step 7: 最终 Commit**

```bash
cd ..
git add .
git commit -m "feat: complete restaurant rag qa system - end to end verified"
```

---

## 关键说明

| 组件 | 说明 |
|------|------|
| **Spring WebFlux** | 全项目使用 WebFlux（非 MVC），原因：Spring AI `chatClient.stream()` 返回 `Flux<String>`，WebFlux 可直接 SSE 推流 |
| **SSE vs EventSource** | 后端 `produces = TEXT_EVENT_STREAM_VALUE`，前端用 `fetch + ReadableStream`（非 EventSource），原因：EventSource 不支持 POST |
| **ChromaDB 过滤** | 使用 `FilterExpressionBuilder` 按 `restaurant_id` 过滤，避免跨餐厅混淆 |
| **缓存 key** | `restaurantId:md5(lowercase(question))`，标准化大小写和空格提升命中率 |
| **Nginx proxy_buffering off** | SSE 流式响应必须关闭 nginx 缓冲，否则前端收不到流式 token |
