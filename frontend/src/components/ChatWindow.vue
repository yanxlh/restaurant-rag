<template>
  <div class="chat">
    <div class="header">
      <template v-if="restaurant">
        <span class="restaurant-name">{{ restaurant.name }}</span>
        <span class="meta"> · {{ restaurant.city }}, {{ restaurant.state }} · ⭐ {{ restaurant.stars }}</span>
      </template>
      <span v-else class="hint">请先在左侧选择一家餐厅</span>
    </div>

    <div class="messages" ref="messagesEl">
      <template v-if="messages.length">
        <MessageBubble
          v-for="(msg, i) in messages"
          :key="i"
          :role="msg.role"
          :content="msg.content"
          :streaming="msg.streaming"
        />
      </template>
      <div v-else class="empty">
        <p>{{ restaurant ? '有什么想问的？例如：' : '选好餐厅后，可以问：' }}</p>
        <p class="hint-examples">🌶 这里辣吗？ &nbsp;🅿️ 停车方便吗？ &nbsp;💑 适合约会吗？</p>
      </div>
    </div>

    <div class="input-row">
      <input
        v-model="inputText"
        :disabled="!restaurant || loading"
        placeholder="输入问题，按 Enter 发送..."
        @keyup.enter="sendQuestion"
      />
      <button
        @click="sendQuestion"
        :disabled="!restaurant || loading || !inputText.trim()"
      >
        {{ loading ? '...' : '发送' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, watch, nextTick } from 'vue'
import MessageBubble from './MessageBubble.vue'
import { askQuestion } from '../api/restaurant.js'

const props = defineProps({ restaurant: Object })

const messages   = ref([])
const inputText  = ref('')
const loading    = ref(false)
const messagesEl = ref(null)

watch(() => props.restaurant, () => { messages.value = [] })

async function sendQuestion() {
  const q = inputText.value.trim()
  if (!q || !props.restaurant || loading.value) return

  messages.value.push({ role: 'user', content: q })
  inputText.value = ''
  loading.value   = true

  const assistantMsg = reactive({ role: 'assistant', content: '', streaming: true })
  messages.value.push(assistantMsg)
  await scrollBottom()

  try {
    await askQuestion(
      props.restaurant.id,
      q,
      async (token) => {
        assistantMsg.content += token
        await scrollBottom()
      },
      () => { assistantMsg.streaming = false }
    )
  } catch (e) {
    assistantMsg.content  = `请求失败：${e.message}`
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
.chat { flex: 1; display: flex; flex-direction: column; height: 100vh; overflow: hidden; }
.header {
  padding: 14px 20px;
  background: #fff;
  border-bottom: 1px solid #e0e0e0;
  font-size: 15px;
  min-height: 50px;
}
.restaurant-name { font-weight: 600; color: #1a73e8; }
.meta { color: #888; font-size: 13px; }
.hint { color: #aaa; font-size: 14px; }
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px 0 8px;
  background: #f5f5f5;
}
.empty {
  text-align: center;
  margin-top: 60px;
  color: #bbb;
  font-size: 14px;
  line-height: 2;
}
.hint-examples { font-size: 13px; color: #ccc; }
.input-row {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  background: #fff;
  border-top: 1px solid #e0e0e0;
}
input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 24px;
  font-size: 14px;
  outline: none;
}
input:focus { border-color: #1a73e8; }
input:disabled { background: #f9f9f9; }
button {
  padding: 10px 20px;
  background: #1a73e8;
  color: #fff;
  border: none;
  border-radius: 24px;
  cursor: pointer;
  font-size: 14px;
  min-width: 64px;
}
button:disabled { background: #ccc; cursor: not-allowed; }
</style>
