<template>
  <header>
    <h1>找个搭子</h1>
    <button class="menu-btn" @click="menuOpen = !menuOpen" :aria-expanded="menuOpen">☰ 菜单</button>
    <nav :class="{open: menuOpen}">
      <button :class="{active: view==='hall'}" @click="go('hall')">活动大厅</button>
      <button :class="{active: view==='my'}" @click="go('my')">我的活动</button>
      <button :class="{active: view==='notif'}" @click="go('notif')">通知<span v-if="unread">（{{ unread }} 未读）</span></button>
      <button :class="{active: view==='publish'}" @click="go('publish')">发布活动</button>
      <span v-if="user" class="user-greet">你好，{{ user.nickname }}（{{ user.account }}）</span>
    </nav>
    <button v-if="user" class="ghost" @click="logout">退出</button>
  </header>
  <main>
    <div v-if="msg" class="msg">{{ msg }}</div>

    <section v-if="!user" class="card login">
      <h3>登录 / 注册新账号</h3>
      <label class="field-label" for="login-account">账号</label>
      <input id="login-account" v-model="account" placeholder="账号" autocomplete="username" />
      <label class="field-label" for="login-password">密码</label>
      <input id="login-password" v-model="password" type="password" placeholder="密码" autocomplete="current-password" />
      <label class="field-label" for="login-nickname">昵称（仅注册时用）</label>
      <input id="login-nickname" v-model="nickname" placeholder="昵称（仅注册时用）" />
      <button class="primary" :disabled="loginBusy" @click="login">{{ loginBusy ? '提交中…' : '登录' }}</button>
      <button class="ghost" :disabled="registerBusy" @click="register">{{ registerBusy ? '提交中…' : '注册新账号' }}</button>
      <button class="ghost" @click="quick('organizer')">组织者</button>
      <button class="ghost" @click="quick('demo01')">同学1</button>
      <button class="ghost" @click="quick('demo05')">同学5（桌游候补）</button>
      <label class="field-label" for="login-apibase">后端地址（仅手机 App 用）</label>
      <input id="login-apibase" v-model="apiBase" placeholder="后端地址" @change="saveApiBase" />
      <p class="hint">手机 App 才填后端地址，如 http://192.168.1.5:8080；网页 / PWA 留空，用当前地址。</p>
      <p class="hint">演示账号密码均为 123456，也可注册新账号。</p>
      <p class="hint">{{ seedHint }}</p>
    </section>

    <section v-if="view==='hall'">
      <div class="card">
        <input v-model="q" placeholder="按活动名称搜索" @input="loadHall" />
        <select v-model="category" @change="loadHall">
          <option value="">全部</option><option>羽毛球</option><option>桌游</option><option>学习组</option><option>其他</option>
        </select>
      </div>
      <div v-if="hallLoading" class="card skeleton" aria-busy="true">
        <p>活动加载中，请稍候…</p>
        <div class="skeleton-bar"></div><div class="skeleton-bar"></div><div class="skeleton-bar short"></div>
      </div>
      <div v-if="loadError" class="card load-error">
        <p>{{ loadError }}</p>
        <button class="primary" @click="retryHall">重新加载</button>
      </div>
      <div v-if="detail" class="card detail-card" ref="detailCard">
        <h3>详情：{{ detail.title }}</h3>
        <p>{{ detail.description }}　条件：{{ detail.conditions }}</p>
        <p class="info-line"><span class="info-label">时间</span><b><span class="nowrap">{{ fmtWeek(detail.start_time) }}</span> – <span class="nowrap">{{ fmtWeek(detail.end_time) }}</span></b></p>
        <p class="info-line"><span class="info-label">地点</span><b>{{ detail.location }}</b></p>
        <p class="info-line"><span class="info-label">组织者</span><b>{{ detail.organizer_name }}</b></p>
        <p class="info-line"><span class="info-label">报名截止</span><b><span class="nowrap">{{ fmtWeek(detail.deadline) }}</span></b></p>
        <p class="info-line"><span class="info-label">名额</span><b>正式 {{ detail.formal }}/{{ detail.capacity }}　预留 {{ detail.reserved }}　候补 {{ detail.waiting }}</b><span v-if="isFull(detail)">　已满员</span></p>
        <p class="hint">预留是指已发出递补邀请、待对方确认的名额；对方确认后转为正式，过期则顺延给下一位候补。</p>
        <p v-if="detail.my_status" class="info-line"><span class="info-label">我的状态</span><b>{{ statusText(detail.my_status) }}<span v-if="detail.my_rank">，候补第 {{ detail.my_rank }} 名</span><span v-if="detail.invitation_expires_at">，请在 <span class="nowrap">{{ fmtWeek(detail.invitation_expires_at) }}</span> 前确认</span></b></p>
        <button v-if="user && (!detail.my_status || ['WITHDRAWN','EXPIRED','CANCELLED'].includes(detail.my_status))" class="primary" :disabled="signupBusy" @click="signup(detail.activity_id || detail.id)">{{ signupBusy ? '提交中…' : '报名' }}</button>
        <button v-if="user && detail.participation_id && ['FORMAL','WAITING'].includes(detail.my_status)" class="warn" @click="withdraw(detail.participation_id)">取消报名 / 放弃候补</button>
        <button v-if="user && detail.invitation_id" class="primary" @click="accept(detail.invitation_id)">接受递补</button>
        <button v-if="user && detail.invitation_id" class="warn" @click="reject(detail.invitation_id)">拒绝递补</button>
        <button v-if="user && detail.participation_id && detail.my_status==='INVITED'" class="warn" @click="withdraw(detail.participation_id)">放弃候补（等同拒绝）</button>
        <div v-if="detail.roster">
          <h4>组织者名单</h4>
          <p v-for="r in detail.roster" :key="r.id">{{ r.nickname }}：{{ statusText(r.status) }}</p>
          <p v-for="inv in detail.pending_invitations" :key="inv.id">待确认邀请：{{ inv.nickname }}，截止 <span class="nowrap">{{ fmtWeek(inv.expires_at) }}</span></p>
          <button class="warn" @click="cancelActivity(detail.id)">取消活动</button>
        </div>
      </div>
      <div v-if="!hallLoading && !loadError && !hall.length" class="card empty-state">
        <p>{{ emptyHint }}</p>
      </div>
      <div v-for="a in hall" :key="a.id" class="card">
        <h3>{{ a.title }} <span v-if="isFull(a)" class="badge badge-full">已满员</span></h3>
        <p>
          <span class="badge">{{ a.category }}</span><span class="badge">{{ a.location }}</span>
          <span class="badge badge-big">正式 {{ a.formal_count }}/{{ a.capacity }}</span>
          <span class="badge">预留 {{ a.reserved_count }}</span><span class="badge">候补 {{ a.waiting_count }} 人</span>
          <span class="badge badge-accent" v-if="a.my_status">我的状态：{{ statusText(a.my_status) }}</span>
        </p>
        <p class="meta card-meta"><span class="nowrap">报名截止：{{ fmtWeek(a.deadline) }}</span>　<span class="nowrap">开始：{{ fmtWeek(a.start_time) }}</span>　组织者：{{ a.organizer_name }}</p>
        <button @click="openDetail(a.id)">{{ cardButtonText(a) }}</button>
      </div>
    </section>

    <section v-if="view==='my'">
      <div class="tabs" role="tablist">
        <button :class="{active: myTab==='signed'}" @click="myTab='signed'">已报名</button>
        <button :class="{active: myTab==='waiting'}" @click="myTab='waiting'">候补</button>
        <button :class="{active: myTab==='organized'}" @click="myTab='organized'">我发布</button>
        <button :class="{active: myTab==='history'}" @click="myTab='history'">历史</button>
      </div>
      <div v-if="myTab==='organized'">
        <div v-for="o in organized" :key="'org-' + o.id" class="card">
          <h3>{{ o.title }}</h3>
          <p>
            <span class="badge">{{ o.category }}</span><span class="badge">{{ o.location }}</span>
            <span class="badge badge-big">正式 {{ o.formal_count }}/{{ o.capacity }}</span>
            <span class="badge">预留 {{ o.reserved_count }}</span><span class="badge">候补 {{ o.waiting_count }} 人</span>
          </p>
          <p class="meta card-meta"><span class="nowrap">报名截止：{{ fmtWeek(o.deadline) }}</span>　<span class="nowrap">开始：{{ fmtWeek(o.start_time) }}</span>　状态：{{ o.status }}</p>
          <button @click="go('hall'); openDetail(o.id)">查看详情</button>
        </div>
        <p v-if="!organized.length" class="meta">暂无组织的活动。</p>
      </div>
      <div v-else>
        <div v-for="p in myFiltered" :key="p.id" class="card">
          <h3>{{ p.title }}</h3>
          <p class="meta card-meta">状态：{{ statusText(p.status) }}　地点：{{ p.location }}　<span class="nowrap">开始：{{ fmtWeek(p.start_time) }}</span>
            <span v-if="p.invitation_expires_at">　请在 <span class="nowrap">{{ fmtWeek(p.invitation_expires_at) }}</span> 前确认</span>
          </p>
          <button v-if="['FORMAL','WAITING','INVITED'].includes(p.status)" class="warn" @click="withdraw(p.id)">取消 / 放弃</button>
          <button v-if="['WITHDRAWN','EXPIRED','CANCELLED'].includes(p.status)" class="primary" @click="resignup(p.activity_id)">重新报名</button>
          <button v-if="p.invitation_id && p.status==='INVITED'" class="primary" @click="accept(p.invitation_id); loadMy()">接受递补</button>
          <button v-if="p.invitation_id && p.status==='INVITED'" class="warn" @click="reject(p.invitation_id); loadMy()">拒绝递补</button>
        </div>
        <p v-if="!myFiltered.length" class="meta">{{ myEmptyText }}</p>
      </div>
    </section>

    <section v-if="view==='notif'">
      <button @click="readAll">全部标为已读</button>
      <div v-for="g in groupedNotifs" :key="g.key" class="card notif-row" :class="{'notif-unread': !g.latest.read_at}">
        <p><b>{{ notifTypeText(g.latest.type) }}</b>　{{ g.latest.content }}</p>
        <p v-if="g.count > 1" class="meta">同一活动 30 分钟内还有 {{ g.count - 1 }} 条同类通知，已折叠为最新一条。</p>
        <p class="meta"><span class="nowrap">{{ fmtWeek(g.latest.created_at) }}</span>　<span v-if="!g.latest.read_at" class="unread">未读</span><span v-else>已读</span></p>
      </div>
      <p v-if="!groupedNotifs.length" class="meta">暂无通知。</p>
    </section>

    <section v-if="view==='publish'" class="card">
      <h3>发布活动</h3>
      <label>标题 <input v-model="form.title" /></label>
      <label>类别
        <select v-model="form.category"><option>羽毛球</option><option>桌游</option><option>学习组</option><option>其他</option></select>
      </label>
      <label>地点 <input v-model="form.location" placeholder="如：江安校区体育馆三楼" /></label>
      <label>人数上限 <input v-model.number="form.capacity" type="number" min="1" max="100" /></label>
      <label>报名截止 <input v-model="form.deadline" type="datetime-local" /><span class="field-hint">格式：年-月-日 时:分，如 2026-11-09 10:41</span></label>
      <label>开始时间 <input v-model="form.start_time" type="datetime-local" /><span class="field-hint">格式：年-月-日 时:分，如 2026-11-10 10:41</span></label>
      <label>结束时间 <input v-model="form.end_time" type="datetime-local" /><span class="field-hint">格式：年-月-日 时:分，如 2026-11-10 12:41</span></label>
      <label>介绍 <input v-model="form.description" /></label>
      <label>参与条件 <input v-model="form.conditions" placeholder="如：水平不限，AA 场地费 10 元" /></label>
      <button class="primary" :disabled="publishBusy" @click="publish">{{ publishBusy ? '提交中…' : '发布' }}</button>
    </section>
    <footer class="page-foot">
      <button class="danger" @click="resetDemo">⚠ 恢复演示数据</button>
      <span class="hint">危险操作：会清空当前业务数据并恢复种子数据，仅演示时使用。</span>
    </footer>
  </main>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
const view = ref('hall')
const menuOpen = ref(false)
const myTab = ref('signed')
const hallLoading = ref(false)
const loadError = ref('')
const loginBusy = ref(false)
const registerBusy = ref(false)
const signupBusy = ref(false)
const publishBusy = ref(false)
const detailCard = ref(null)
const user = ref(null)
const token = ref(localStorage.getItem('token') || '')
const account = ref(''), password = ref('123456'), nickname = ref('')
const msg = ref('')
const hall = ref([]), detail = ref(null), mine = ref([]), organized = ref([]), notifs = ref([]), unread = ref(0)
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
// 任务 4：时间加星期几与时段标注，如“2026-11-09 周日 上午 10:41”。
// 入参是东八区墙钟字符串，直接按本地时间取星期，不做时区换算。
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
function periodWord(h) {
  if (h < 6) return '凌晨'
  if (h < 12) return '上午'
  if (h < 14) return '中午'
  if (h < 18) return '下午'
  return '晚上'
}
function fmtWeek(s) {
  if (!s) return ''
  const m = String(s).replace('T', ' ').match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})/)
  if (!m) return fmt(s)
  const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5])
  return `${m[1]}-${m[2]}-${m[3]} ${WEEK[d.getDay()]} ${periodWord(+m[4])} ${m[4]}:${m[5]}`
}
// 任务 4：后端通知类型「变更」只用于退出（BuddyService.withdraw 两处，
// notification 表 CHECK 只有 报名/候补/邀请/变更四值，不改表），展示时映射为「退出」。
function notifTypeText(t) { return t === '变更' ? '退出' : t }
// 任务 8：满员 = 正式 + 预留 >= 上限（预留是已发邀请待确认的名额，同样占坑，见 SRS R1）。
function isFull(a) {
  if (!a) return false
  const formal = a.formal_count ?? a.formal ?? 0
  const reserved = a.reserved_count ?? a.reserved ?? 0
  return formal + reserved >= (a.capacity ?? 0)
}
// 任务 5：大厅卡片按钮按「我的状态」变文案。
function cardButtonText(a) {
  return ['FORMAL', 'WAITING', 'INVITED'].includes(a.my_status) ? '已报名 · 查看详情' : '查看详情 / 报名'
}
// 任务 5：登录页种子说明改实时数据（大厅已加载时取前 3 个活动现况）。
const seedHint = computed(() => {
  if (hallLoading.value) return '活动列表加载中…'
  if (!hall.value.length) return '当前大厅暂无活动。'
  const items = hall.value.slice(0, 3).map(a => `${a.title}（正式 ${a.formal_count}/${a.capacity}）`)
  return `当前大厅 ${hall.value.length} 个活动：${items.join('；')}。`
})
const emptyHint = computed(() => {
  if (q.value) return `没有找到含「${q.value}」的活动，换个关键词试试`
  if (category.value) return `「${category.value}」分类下暂无活动`
  return '暂无活动，快来发布第一个吧'
})
// 任务 15：「我的活动」四个视图（SRS §3.3.7 / SDD CEM-06 的文档要求视图）。
const myFiltered = computed(() => {
  if (myTab.value === 'signed') return mine.value.filter(p => p.status === 'FORMAL')
  if (myTab.value === 'waiting') return mine.value.filter(p => ['WAITING', 'INVITED'].includes(p.status))
  if (myTab.value === 'history') return mine.value.filter(p => ['WITHDRAWN', 'EXPIRED', 'CANCELLED'].includes(p.status))
  return mine.value
})
const myEmptyText = computed(() => ({
  signed: '暂无已报名的活动。', waiting: '暂无候补中的活动。', history: '暂无历史记录。'
}[myTab.value] || '暂无参与记录。'))
// 任务 16：通知折叠（前端展示层做，后端不改，见 changes.md 后端分工）。
// 折叠规则：同一活动 + 同一操作人、类型属于报名/候补/变更（退出）、
// 与组内最新一条间隔 ≤ 30 分钟的连续通知，只展示最新一条并注明折叠条数。
// 操作人从内容提取：组织者通知以「昵称」开头，本人通知以「你」开头记为「我」。
// 邀请类通知不折叠（每条邀请有独立的确认截止时间）。
function notifActor(n) {
  const m = String(n.content || '').match(/^「([^」]+)」/)
  return m ? m[1] : '我'
}
const FOLDABLE_TYPES = ['报名', '候补', '变更']
// 任务 16 返工（2026-11-07）：入组时按 created_at（再按 id）取最大者更新
// g.latest。原因：MySQL DATETIME 只到秒，同一轮进出的几条通知 created_at
// 相同或只差 1 秒，API 的 ORDER BY created_at DESC 在并列时不保证最新在前；
// 旧代码建组后从不更新 g.latest，折叠行实际显示组内最旧一条，与文案
// 「已折叠为最新一条」自相矛盾。30 分钟窗口以组内最新一条（g.latest）为锚点。
function notifIsNewer(a, b) {
  const ta = new Date(a.created_at), tb = new Date(b.created_at)
  return ta > tb || (ta.getTime() === tb.getTime() && (a.id ?? 0) > (b.id ?? 0))
}
const groupedNotifs = computed(() => {
  const groups = []
  for (const n of notifs.value) {
    const key = (n.activity_id ?? '') + '|' + notifActor(n)
    const g = groups.find(g => g.key === key
      && FOLDABLE_TYPES.includes(n.type) && FOLDABLE_TYPES.includes(g.latest.type)
      && Math.abs(new Date(n.created_at) - new Date(g.latest.created_at)) <= 30 * 60 * 1000)
    if (g) { g.items.push(n); g.count++; if (notifIsNewer(n, g.latest)) g.latest = n } else { groups.push({ key, latest: n, items: [n], count: 1 }) }
  }
  return groups
})
// 任务 13：fetch 失败等英文错误转中文提示。
function friendlyErr(e) {
  const m = String((e && e.message) || e || '')
  if (/Failed to fetch|NetworkError|Load failed/i.test(m)) return '网络好像断了，请检查网络后重试'
  return m
}
async function login() {
  if (loginBusy.value) return
  loginBusy.value = true
  try {
    const j = await api('/api/login', 'POST', { account: account.value, password: password.value })
    token.value = j.token; localStorage.setItem('token', j.token); user.value = j.user; msg.value = '登录成功'
    // 任务 3（换号串号）：先清掉上一个账号的缓存状态，再拉当前账号的；
    // 只拉大厅不够——顶栏未读数常驻显示，通知必须登录时就重拉，否则
    // 停在通知页换号会直接亮出上一个人的列表。
    clearAccountState(); loadHall(); loadNotif()
    if (view.value === 'my') { loadMy(); loadOrganized() }
  } catch (e) { msg.value = friendlyErr(e) } finally { loginBusy.value = false }
}
async function quick(a) { account.value = a; password.value = '123456'; await login() }
async function register() {
  if (registerBusy.value) return
  registerBusy.value = true
  try {
    await api('/api/register', 'POST', { account: account.value, password: password.value, nickname: nickname.value || account.value })
    await login()
    // 任务 5：login() 会把提示写成「登录成功」；注册路径须保留「注册成功」。
    if (user.value) msg.value = '注册成功，已自动登录'
  } catch (e) { msg.value = friendlyErr(e) } finally { registerBusy.value = false }
}
async function resignup(aid) { try { await api('/api/activities/' + aid + '/signup', 'POST'); msg.value = '已重新报名'; loadMy() } catch (e) { msg.value = e.message } }
// 任务 3（换号串号）：按账号缓存的前端状态集中清理——通知列表、
// 未读数、我的活动（我报名的 + 我组织的）、详情页（含 my_status /
// 组织者名单，均属当前账号视角）。登录成功后、退出时、token 失效时都走这里。
function clearAccountState() { mine.value = []; organized.value = []; notifs.value = []; unread.value = 0; detail.value = null }
function logout() { token.value = ''; localStorage.removeItem('token'); user.value = null; clearAccountState(); view.value = 'hall' }
async function loadHall() {
  hallLoading.value = true; loadError.value = ''
  try {
    const params = new URLSearchParams()
    if (q.value) params.set('q', q.value)
    if (category.value) params.set('category', category.value)
    hall.value = await api('/api/activities?' + params.toString())
  } catch (e) { loadError.value = friendlyErr(e); hall.value = [] } finally { hallLoading.value = false }
}
async function retryHall() { loadHall() }
async function openDetail(id) {
  try {
    detail.value = await api('/api/activities/' + id); msg.value = ''
    // 任务 6：详情卡片在列表上方、当前视口之外时，自动滚动到详情顶部。
    await nextTick()
    detailCard.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch (e) { msg.value = friendlyErr(e) }
}
async function signup(id) {
  if (signupBusy.value) return
  signupBusy.value = true
  try {
    const j = await api('/api/activities/' + id + '/signup', 'POST')
    // 任务 1 + P1-6：报名返回只含 detail() 的字段（无时间/地点/组织者），
    // 直接用它覆盖详情会让 P1-6 那行变空白；改走重新拉详情（全字段），
    // 再置提示（openDetail 会清空 msg，须在它之后设置）。
    await openDetail(id)
    msg.value = '报名结果：' + statusText(j.my_status)
    loadHall()
  } catch (e) { msg.value = friendlyErr(e) } finally { signupBusy.value = false }
}
async function withdraw(pid) {
  try {
    await api('/api/participations/' + pid + '/withdraw', 'POST')
    // 任务 3-1：详情刷新会清空提示（openDetail 置空 msg），先等刷新完成再置提示；
    // 刷新失败也不吞掉「已退出」。
    if (detail.value) { try { await openDetail(detail.value.id || detail.value.activity_id) } catch (_) {} }
    msg.value = '已退出'
    if (view.value === 'my') { loadMy(); loadOrganized() } else { loadHall() }
  } catch (e) { msg.value = e.message }
}
async function accept(id) { try { await api('/api/invitations/' + id + '/accept', 'POST'); msg.value = '已接受递补'; if (detail.value) openDetail(detail.value.id || detail.value.activity_id); loadMy() } catch (e) { msg.value = e.message } }
async function reject(id) { try { await api('/api/invitations/' + id + '/reject', 'POST'); msg.value = '已拒绝递补'; if (detail.value) openDetail(detail.value.id || detail.value.activity_id); loadMy() } catch (e) { msg.value = e.message } }
async function loadMy() { try { mine.value = await api('/api/my') } catch (e) { msg.value = e.message } }
async function loadOrganized() { try { organized.value = await api('/api/my/organized') } catch (e) { msg.value = e.message } }
async function loadNotif() { try { const j = await api('/api/notifications'); notifs.value = j.list; unread.value = j.unread } catch (e) { msg.value = e.message } }
async function readAll() { await api('/api/notifications/read-all', 'POST'); loadNotif() }
async function cancelActivity(id) { if (!confirm('确认取消该活动吗？')) return; try { await api('/api/activities/' + id + '/cancel', 'POST'); msg.value = '活动已取消'; loadHall() } catch (e) { msg.value = e.message } }
async function publish() {
  if (publishBusy.value) return
  publishBusy.value = true
  try {
    const j = await api('/api/activities', 'POST', form.value)
    msg.value = '发布成功，活动编号 ' + j.id; view.value = 'hall'; loadHall()
  } catch (e) { msg.value = friendlyErr(e) } finally { publishBusy.value = false }
}
async function resetDemo() { if (!confirm('确认恢复演示数据吗？当前业务数据会被清空。')) return; await api('/api/demo/reset', 'POST'); msg.value = '演示数据已恢复'; loadHall() }
function go(v) { menuOpen.value = false; view.value = v; if (v === 'my') { loadMy(); loadOrganized() } if (v === 'notif') loadNotif(); if (v === 'hall') loadHall() }
onMounted(async () => {
  loadHall()
  // 任务 3：刷新页面带 token 恢复登录时，未读数也要立即是当前账号的，
  // 不能等到手动点「通知」才刷。
  if (token.value) { try { const j = await api('/api/me'); user.value = j.user; loadNotif() } catch { logout() } }
})
</script>
