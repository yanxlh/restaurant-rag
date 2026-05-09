<template>
  <div class="home">
    <RestaurantPanel
      :selected="selectedRestaurant"
      @select="selectedRestaurant = $event"
    />
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
