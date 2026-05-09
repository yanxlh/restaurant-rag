import axios from 'axios'

export async function searchRestaurants(city, name = '') {
  const params = { city }
  if (name) params.name = name
  const { data } = await axios.get('/api/restaurants', { params })
  return data
}

/**
 * 发送问题到后端 SSE 流式接口。
 * 因 EventSource 不支持 POST，使用 fetch + ReadableStream。
 * @param {string} restaurantId
 * @param {string} question
 * @param {(token: string) => void} onToken  - 每个 token 回调
 * @param {() => void} onDone                - 流结束回调
 */
export async function askQuestion(restaurantId, question, onToken, onDone) {
  const response = await fetch('/api/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ restaurantId, question })
  })

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    const chunk = decoder.decode(value, { stream: true })
    // Spring WebFlux TEXT_EVENT_STREAM 格式：每条 "data: <token>\n\n"
    for (const line of chunk.split('\n')) {
      if (line.startsWith('data:')) {
        const token = line.slice(5).trim()
        if (token && token !== '[DONE]') onToken(token)
      }
    }
  }
  onDone()
}
