<template>
  <section class="reschedule-box" aria-label="活动评价">
    <h3>活动评价</h3>
    <button class="ghost" :disabled="busy" @click="load">{{ panel ? '刷新评价' : '查看评价' }}</button>
    <p v-if="message" role="status">{{ message }}</p>
    <template v-if="panel">
      <p v-if="panel.total">平均 {{ Number(panel.average).toFixed(1) }} 分，共 {{ panel.total }} 份</p>
      <p v-else class="meta">暂无评价。</p>
      <form v-if="panel.editable" @submit.prevent="save">
        <label>评分
          <select v-model.number="score" required>
            <option value="" disabled>选择分数</option>
            <option v-for="value in 5" :key="value" :value="value">{{ value }} 分</option>
          </select>
        </label>
        <label>评价内容 <textarea v-model="content" maxlength="500"></textarea></label>
        <button class="primary" :disabled="busy">保存评价</button>
      </form>
      <p v-else-if="panel.own">我的评价　{{ panel.own.score }} 分　{{ panel.own.content }}</p>
      <p v-else class="hint">出勤核对为到场的成员，可在活动结束后 7 日内评价。</p>
      <template v-if="panel.entries?.length">
        <h4>参与者反馈</h4>
        <p v-for="entry in panel.entries" :key="entry.id" class="message-item">{{ entry.score }} 分　{{ entry.content }}</p>
      </template>
    </template>
  </section>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({ activityId: [Number, String], request: Function })
const panel = ref(null), score = ref(''), content = ref('')
const busy = ref(false), message = ref('')
const endpoint = () => '/api/activities/' + props.activityId + '/feedback'

async function refresh() {
  const data = await props.request(endpoint())
  panel.value = data
  score.value = data.own?.score ?? ''
  content.value = data.own?.content || ''
}

async function run(action) {
  if (busy.value) return
  busy.value = true
  message.value = ''
  try { await action() } catch (error) { message.value = error.message }
  finally { busy.value = false }
}
function load() { return run(refresh) }
function save() {
  return run(async () => {
    await props.request(endpoint(), 'PUT', { score: score.value, content: content.value })
    await refresh()
    message.value = '评价已保存'
  })
}
</script>
