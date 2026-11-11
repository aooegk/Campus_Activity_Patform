<template>
  <section class="reschedule-box" aria-label="签到与出勤">
    <h3>签到与出勤</h3>
    <button class="ghost" :disabled="busy" @click="refresh">{{ panel ? '刷新出勤' : '查看签到与出勤' }}</button>
    <p v-if="message" role="status">{{ message }}</p>
    <template v-if="panel">
      <p v-if="panel.own.checked_in">已签到　{{ formatTime(panel.own.checked_in_at) }}</p>
      <p v-if="panel.own.review_status">
        出勤结果　{{ labels[panel.own.review_status] }}
        <span v-if="panel.own.review_reason">　{{ panel.own.review_reason }}</span>
      </p>
      <template v-if="panel.check_in_open">
        <template v-if="organizer">
          <button class="ghost" :disabled="busy" @click="showCode">获取当前签到码</button>
          <p v-if="code && codeAvailable"><b>{{ code.code }}</b>　有效至 {{ formatTime(code.expires_at) }}</p>
          <p v-else-if="code" class="hint">签到码已轮换，请重新获取。</p>
        </template>
        <form v-if="panel.eligible && !panel.own.checked_in" @submit.prevent="submitCode">
          <label>签到码 <input v-model.trim="enteredCode" maxlength="6" inputmode="numeric" pattern="[0-9]{6}" required /></label>
          <button class="primary" :disabled="busy">签到</button>
        </form>
      </template>
      <p v-else-if="!panel.own.checked_in && !panel.review_open" class="hint">签到仅在活动开始前 15 分钟至开始后 30 分钟内开放，活动结束时提前关闭。</p>
      <template v-if="organizer && panel.rows?.length">
        <h4>正式成员出勤</h4>
        <div v-for="row in panel.rows" :key="row.user_id" class="message-item">
          <p>{{ row.nickname }}　{{ row.checked_in_at ? '已签到 ' + formatTime(row.checked_in_at) : '无签到记录' }}</p>
          <p v-if="row.review_status" class="hint">{{ labels[row.review_status] }}<span v-if="row.review_reason">　{{ row.review_reason }}</span></p>
          <form v-if="panel.review_open" @submit.prevent="saveReview(row)">
            <label>核对结果
              <select v-model="drafts[row.user_id].status" required>
                <option value="" disabled>选择核对结果</option>
                <option v-for="(label, key) in labels" :key="key" :value="key">{{ label }}</option>
              </select>
            </label>
            <label>核对依据 <input v-model="drafts[row.user_id].reason" maxlength="500" placeholder="补正、缺席或豁免需填写依据" /></label>
            <button class="ghost" :disabled="busy">保存结果</button>
          </form>
        </div>
      </template>
    </template>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({ activityId: [Number, String], organizer: Boolean, clock: Number, request: Function, formatTime: Function })
const labels = { PRESENT: '到场', ABSENT: '核实缺席', EXCUSED: '豁免', UNVERIFIED: '未核实' }
const panel = ref(null), code = ref(null), enteredCode = ref(''), drafts = ref({})
const message = ref(''), busy = ref(false)
const codeAvailable = computed(() => code.value && Date.parse(code.value.expires_at + '+08:00') > props.clock)
const endpoint = () => '/api/activities/' + props.activityId + '/attendance'

async function loadPanel() {
  panel.value = await props.request(endpoint())
  drafts.value = Object.fromEntries((panel.value.rows || []).map(row => [
    row.user_id, { status: row.review_status || '', reason: row.review_reason || '' },
  ]))
}

async function run(action) {
  if (busy.value) return
  busy.value = true
  message.value = ''
  try { await action() } catch (error) { message.value = error.message }
  finally { busy.value = false }
}

function refresh() { return run(loadPanel) }
function showCode() { return run(async () => { code.value = await props.request(endpoint() + '/code', 'POST') }) }
function submitCode() {
  return run(async () => {
    await props.request(endpoint() + '/check-in', 'POST', { code: enteredCode.value })
    enteredCode.value = ''
    await loadPanel()
    message.value = '签到成功'
  })
}
function saveReview(row) {
  return run(async () => {
    const draft = drafts.value[row.user_id]
    const result = await props.request(endpoint() + '/' + row.user_id, 'PUT', { ...draft, version: row.version })
    row.version = result.version
    row.review_status = result.review_status
    row.review_reason = draft.reason
    message.value = '出勤结果已保存'
  })
}
</script>
