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

    <button @click="search" :disabled="loading || !city.trim()">
      {{ loading ? '搜索中...' : '搜索' }}
    </button>

    <p v-if="error" class="error">{{ error }}</p>

    <ul class="result-list" v-if="restaurants.length">
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

    <p v-else-if="searched" class="empty-hint">未找到餐厅，请尝试其他城市</p>
  </aside>
</template>

<script setup>
import { ref } from 'vue'
import { searchRestaurants } from '../api/restaurant.js'

defineProps({ selected: Object })
defineEmits(['select'])

const city        = ref('')
const nameFilter  = ref('')
const restaurants = ref([])
const loading     = ref(false)
const error       = ref('')
const searched    = ref(false)

async function search() {
  if (!city.value.trim()) return
  loading.value = true
  error.value   = ''
  searched.value = false
  try {
    restaurants.value = await searchRestaurants(city.value, nameFilter.value)
    searched.value    = true
  } catch {
    error.value = '搜索失败，请检查后端服务是否启动'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.panel {
  width: 280px;
  min-width: 280px;
  background: #fff;
  padding: 20px;
  border-right: 1px solid #e0e0e0;
  height: 100vh;
  overflow-y: auto;
}
h2 { font-size: 18px; margin-bottom: 16px; color: #333; }
.field { margin-bottom: 12px; }
label { display: block; font-size: 13px; color: #666; margin-bottom: 4px; }
input {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  outline: none;
}
input:focus { border-color: #1a73e8; }
button {
  width: 100%;
  padding: 10px;
  background: #1a73e8;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  margin-top: 4px;
}
button:disabled { background: #aaa; cursor: not-allowed; }
.error { color: #d32f2f; font-size: 13px; margin-top: 8px; }
.empty-hint { color: #999; font-size: 13px; margin-top: 16px; text-align: center; }
.result-list { list-style: none; margin-top: 16px; }
.result-list li {
  padding: 10px;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #f0f0f0;
}
.result-list li:hover { background: #f0f4ff; }
.result-list li.active { background: #e8f0fe; }
.name { font-size: 14px; }
.stars { font-size: 13px; color: #888; }
</style>
