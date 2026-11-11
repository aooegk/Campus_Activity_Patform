<template>
  <nav v-if="total > size" :aria-label="label">
    <button class="ghost" :disabled="busy || page <= 1" @click="$emit('change', page - 1)">上一页</button>
    <span class="meta">第 {{ page }} 页，共 {{ Math.ceil(total / size) }} 页</span>
    <button class="ghost" :disabled="busy || page * size >= total" @click="$emit('change', page + 1)">下一页</button>
  </nav>
</template>

<script setup>
defineProps({ page: Number, size: Number, total: Number, label: String, busy: Boolean })
defineEmits(['change'])
</script>
