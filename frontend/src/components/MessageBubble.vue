<template>
  <div :class="['bubble', role]">
    <p class="text">
      {{ content }}<span v-if="streaming" class="cursor">▋</span>
    </p>
  </div>
</template>

<script setup>
defineProps({
  role:      { type: String,  required: true },   // 'user' | 'assistant'
  content:   { type: String,  required: true },
  streaming: { type: Boolean, default: false }
})
</script>

<style scoped>
.bubble { display: flex; margin: 8px 16px; }
.bubble.user      { justify-content: flex-end; }
.bubble.assistant { justify-content: flex-start; }
.text {
  max-width: 72%;
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.user .text {
  background: #1a73e8;
  color: #fff;
  border-bottom-right-radius: 4px;
}
.assistant .text {
  background: #fff;
  border: 1px solid #e0e0e0;
  border-bottom-left-radius: 4px;
  color: #333;
}
.cursor {
  animation: blink 1s step-start infinite;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0; }
}
</style>
