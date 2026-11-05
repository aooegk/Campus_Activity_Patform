<template>
  <header>
    <h1>找个搭子</h1>
    <nav>
      <button :class="{active: view==='hall'}" @click="go('hall')">活动大厅</button>
      <button :class="{active: view==='my'}" @click="go('my')">我的活动</button>
      <button :class="{active: view==='notif'}" @click="go('notif')">通知<span v-if="unread">（{{ unread }} 未读）</span></button>
      <button :class="{active: view==='publish'}" @click="go('publish')">发布活动</button>
    </nav>
    <span v-if="user" class="user-greet">你好，{{ user.nickname }}（{{ user.account }}）</span>
    <button v-if="user" class="ghost" @click="logout">退出</button>
    <button class="mini ghost" @click="resetDemo">恢复演示数据</button>
  </header>
  <main>
    <div v-if="msg" class="msg">{{ msg }}</div>

    <section v-if="!user" class="card login">
      <h3>登录（演示账号密码均为 123456）</h3>
      <input v-model="account" placeholder="账号" />
      <input v-model="password" type="password" placeholder="密码" />
      <input v-model="nickname" placeholder="昵称（仅注册时用）" />
      <button class="primary" @click="login">登录</button>
      <button class="ghost" @click="register">注册新账号</button>
      <button class="ghost" @click="quick('organizer')">组织者</button>
      <button class="ghost" @click="quick('demo01')">同学1</button>
      <button class="ghost" @click="quick('demo05')">同学5（桌游候补）</button>
      <input v-model="apiBase" placeholder="后端地址（手机 App 才填，如 http://192.168.1.5:8080，留空用当前地址）" @change="saveApiBase" />
      <p class="hint">种子活动：周六羽毛球（上限4，已有3正式）；桌游之夜（已满，同学5、6候补）；周末学习组。</p>
    </section>

    <section v-if="view==='hall'">
      <div class="card">
        <input v-model="q" placeholder="按活动名称搜索" @input="loadHall" />
        <select v-model="category" @change="loadHall">
          <option value="">全部</option><option>羽毛球</option><option>桌游</option><option>学习组</option><option>其他</option>
        </select>
      </div>
      <div v-if="detail" class="card">
        <h3>详情：{{ detail.title }}</h3>
        <p>{{ detail.description }}　条件：{{ detail.conditions }}</p>
        <p class="meta">正式 {{ detail.formal }}/{{ detail.capacity }}　预留 {{ detail.reserved }}　候补 {{ detail.waiting }}
          <span v-if="detail.my_status">　我的状态：{{ statusText(detail.my_status) }}
            <span v-if="detail.my_rank">，候补第 {{ detail.my_rank }} 名</span>
            <span v-if="detail.invitation_expires_at">，请在 {{ fmt(detail.invitation_expires_at) }} 前确认</span>
          </span>
        </p>
        <button v-if="user && (!detail.my_status || ['WITHDRAWN','EXPIRED','CANCELLED'].includes(detail.my_status))" class="primary" @click="signup(detail.activity_id || detail.id)">报名</button>
        <button v-if="user && detail.participation_id && ['FORMAL','WAITING'].includes(detail.my_status)" class="warn" @click="withdraw(detail.participation_id)">取消报名 / 放弃候补</button>
        <button v-if="user && detail.invitation_id" class="primary" @click="accept(detail.invitation_id)">接受递补</button>
        <button v-if="user && detail.invitation_id" class="warn" @click="reject(detail.invitation_id)">拒绝递补</button>
        <button v-if="user && detail.participation_id && detail.my_status==='INVITED'" class="warn" @click="withdraw(detail.participation_id)">放弃候补（等同拒绝）</button>
        <div v-if="detail.roster">
          <h4>组织者名单</h4>
          <p v-for="r in detail.roster" :key="r.id">{{ r.nickname }}：{{ statusText(r.status) }}</p>
          <p v-for="inv in detail.pending_invitations" :key="inv.id">待确认邀请：{{ inv.nickname }}，截止 {{ fmt(inv.expires_at) }}</p>
          <button class="warn" @click="cancelActivity(detail.id)">取消活动</button>
        </div>
      </div>
      <div v-for="a in hall" :key="a.id" class="card">
        <h3>{{ a.title }}</h3>
        <p>
          <span class="badge">{{ a.category }}</span><span class="badge">{{ a.location }}</span>
          <span class="badge">正式 {{ a.formal_count }}/{{ a.capacity }}</span>
          <span class="badge">预留 {{ a.reserved_count }}</span><span class="badge">候补 {{ a.waiting_count }} 人</span>
          <span class="badge badge-accent" v-if="a.my_status">我的状态：{{ statusText(a.my_status) }}</span>
        </p>
        <p class="meta">报名截止：{{ fmt(a.deadline) }}　开始：{{ fmt(a.start_time) }}　组织者：{{ a.organizer_name }}</p>
        <button @click="openDetail(a.id)">查看详情 / 报名</button>
      </div>
    </section>

    <section v-if="view==='my'">
      <div v-for="p in mine" :key="p.id" class="card">
        <h3>{{ p.title }}</h3>
        <p class="meta">状态：{{ statusText(p.status) }}　地点：{{ p.location }}　开始：{{ fmt(p.start_time) }}
          <span v-if="p.invitation_expires_at">　请在 {{ fmt(p.invitation_expires_at) }} 前确认</span>
        </p>
        <button v-if="['FORMAL','WAITING','INVITED'].includes(p.status)" class="warn" @click="withdraw(p.id); loadMy()">取消 / 放弃</button>
        <button v-if="['WITHDRAWN','EXPIRED','CANCELLED'].includes(p.status)" class="primary" @click="resignup(p.activity_id)">重新报名</button>
        <button v-if="p.invitation_id && p.status==='INVITED'" class="primary" @click="accept(p.invitation_id); loadMy()">接受递补</button>
        <button v-if="p.invitation_id && p.status==='INVITED'" class="warn" @click="reject(p.invitation_id); loadMy()">拒绝递补</button>
      </div>
      <p v-if="!mine.length">暂无参与记录。</p>
    </section>

    <section v-if="view==='notif'">
      <button @click="readAll">全部标为已读</button>
      <div v-for="n in notifs" :key="n.id" class="card">
        <p><b>{{ n.type }}</b>　{{ n.content }}</p>
        <p class="meta">{{ fmt(n.created_at) }}　<span v-if="!n.read_at" class="unread">未读</span><span v-else>已读</span></p>
      </div>
    </section>

    <section v-if="view==='publish'" class="card">
      <h3>发布活动</h3>
      <label>标题 <input v-model="form.title" /></label>
      <label>类别
        <select v-model="form.category"><option>羽毛球</option><option>桌游</option><option>学习组</option><option>其他</option></select>
      </label>
      <label>地点 <input v-model="form.location" /></label>
      <label>人数上限 <input v-model.number="form.capacity" type="number" min="1" max="100" /></label>
      <label>报名截止 <input v-model="form.deadline" type="datetime-local" /></label>
      <label>开始时间 <input v-model="form.start_time" type="datetime-local" /></label>
      <label>结束时间 <input v-model="form.end_time" type="datetime-local" /></label>
      <label>介绍 <input v-model="form.description" /></label>
      <label>参与条件 <input v-model="form.conditions" /></label>
      <button class="primary" @click="publish">发布</button>
    </section>
  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
const view = ref('hall')
const user = ref(null)
const token = ref(localStorage.getItem('token') || '')
const account = ref(''), password = ref('123456'), nickname = ref('')
const msg = ref('')
const hall = ref([]), detail = ref(null), mine = ref([]), notifs = ref([]), unread = ref(0)
const q = ref(''), category = ref('')
const form = ref({ title: '', category: '羽毛球', location: '', capacity: 4, deadline: '', start_time: '', end_time: '', description: '', conditions: '' })

const headers = () => token.value ? { 'Authorization': 'Bearer ' + token.value, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' }
// 手机原生 App 用：后端不在本机时，在此填电脑地址（如 http://192.168.1.5:8080），留空则用当前页面地址（网页/PWA 默认）
const apiBase = ref(localStorage.getItem('apiBase') || '')
function saveApiBase() { localStorage.setItem('apiBase', apiBase.value.trim()) }
async function api(path, method = 'GET', body) {
  const r = await fetch((apiBase.value.trim().replace(/\/$/, '') || '') + path, { method, headers: headers(), body: body ? JSON.stringify(body) : undefined })
  const j = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(j.error || ('请求失败 ' + r.status))
  return j
}
function statusText(s) {
  return { FORMAL: '正式成员', WAITING: '候补中', INVITED: '待确认', WITHDRAWN: '已退出', EXPIRED: '已过期', CANCELLED: '已取消' }[s] || s
}
// 仅展示用：把后端 ISO / 空格时间截成“YYYY-MM-DD HH:mm”，不改业务时间值
function fmt(s) {
  if (!s) return ''
  return String(s).replace('T', ' ').slice(0, 16)
}
async function login() {
  try {
    const j = await api('/api/login', 'POST', { account: account.value, password: password.value })
    token.value = j.token; localStorage.setItem('token', j.token); user.value = j.user; msg.value = '登录成功'; loadHall()
  } catch (e) { msg.value = e.message }
}
async function quick(a) { account.value = a; password.value = '123456'; await login() }
async function register() {
  try {
    await api('/api/register', 'POST', { account: account.value, password: password.value, nickname: nickname.value || account.value })
    msg.value = '注册成功，已自动登录'; await login()
  } catch (e) { msg.value = e.message }
}
async function resignup(aid) { try { await api('/api/activities/' + aid + '/signup', 'POST'); msg.value = '已重新报名'; loadMy() } catch (e) { msg.value = e.message } }
function logout() { token.value = ''; localStorage.removeItem('token'); user.value = null; detail.value = null }
async function loadHall() {
  try {
    const params = new URLSearchParams()
    if (q.value) params.set('q', q.value)
    if (category.value) params.set('category', category.value)
    hall.value = await api('/api/activities?' + params.toString())
  } catch (e) { msg.value = e.message }
}
async function openDetail(id) { try { detail.value = await api('/api/activities/' + id); msg.value = '' } catch (e) { msg.value = e.message } }
async function signup(id) { try { detail.value = await api('/api/activities/' + id + '/signup', 'POST'); msg.value = '报名结果：' + statusText(detail.value.my_status); loadHall() } catch (e) { msg.value = e.message } }
async function withdraw(pid) { try { await api('/api/participations/' + pid + '/withdraw', 'POST'); msg.value = '已退出'; if (detail.value) openDetail(detail.value.id || detail.value.activity_id) } catch (e) { msg.value = e.message } }
async function accept(id) { try { await api('/api/invitations/' + id + '/accept', 'POST'); msg.value = '已接受递补'; if (detail.value) openDetail(detail.value.id || detail.value.activity_id); loadMy() } catch (e) { msg.value = e.message } }
async function reject(id) { try { await api('/api/invitations/' + id + '/reject', 'POST'); msg.value = '已拒绝递补'; if (detail.value) openDetail(detail.value.id || detail.value.activity_id); loadMy() } catch (e) { msg.value = e.message } }
async function loadMy() { try { mine.value = await api('/api/my') } catch (e) { msg.value = e.message } }
async function loadNotif() { try { const j = await api('/api/notifications'); notifs.value = j.list; unread.value = j.unread } catch (e) { msg.value = e.message } }
async function readAll() { await api('/api/notifications/read-all', 'POST'); loadNotif() }
async function cancelActivity(id) { if (!confirm('确认取消该活动吗？')) return; try { await api('/api/activities/' + id + '/cancel', 'POST'); msg.value = '活动已取消'; loadHall() } catch (e) { msg.value = e.message } }
async function publish() {
  try {
    const j = await api('/api/activities', 'POST', form.value)
    msg.value = '发布成功，活动编号 ' + j.id; view.value = 'hall'; loadHall()
  } catch (e) { msg.value = e.message }
}
async function resetDemo() { if (!confirm('确认恢复演示数据吗？当前业务数据会被清空。')) return; await api('/api/demo/reset', 'POST'); msg.value = '演示数据已恢复'; loadHall() }
function go(v) { view.value = v; if (v === 'my') loadMy(); if (v === 'notif') loadNotif(); if (v === 'hall') loadHall() }
onMounted(async () => {
  loadHall()
  if (token.value) { try { const j = await api('/api/me'); user.value = j.user } catch { logout() } }
})
</script>
