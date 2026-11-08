<template>
  <header>
    <h1>找个搭子</h1>
    <button class="menu-btn" @click="menuOpen = !menuOpen" :aria-expanded="menuOpen">☰ 菜单</button>
    <button class="ghost theme-btn" @click="toggleTheme" :aria-pressed="darkMode"><span class="theme-icon">{{ darkMode ? '☀️' : '🌙' }}</span><span class="theme-label">{{ darkMode ? ' 浅色模式' : ' 深色模式' }}</span></button>
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
    <div v-if="user" class="quick-nav" aria-label="常用入口">
      <button @click="go('my')">我的报名</button>
      <button @click="go('publish')">发活动</button>
      <button @click="go('notif')">消息<span v-if="unread">（{{ unread }} 未读）</span></button>
    </div>
    <div v-if="msg" class="msg">{{ msg }}<button class="msg-close" @click="msg=''" aria-label="关闭提示">×</button></div>
    <div v-if="withdrawNotice" class="msg withdraw-notice"><b>已退出{{ withdrawNotice.title ? '活动「' + withdrawNotice.title + '」' : '' }}。</b>手抖点错了？点这里报回来：<button class="primary" @click="resignupFromNotice">重新报名</button></div>

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
        <input v-model="q" placeholder="按活动名称、介绍、参与条件搜索" />
        <select v-model="timeSlot" aria-label="按时间段筛选">
          <option value="">全部时段</option><option value="凌晨">凌晨 0–6 点</option><option value="上午">上午 6–12 点</option><option value="下午">下午 12–18 点</option><option value="晚上">晚上 18–24 点</option>
        </select>
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
        <h3>详情：{{ detail.title }} <span class="activity-no">#{{ detail.activity_id || detail.id }}</span> <span v-if="detail.status && detail.status !== 'OPEN'" class="badge badge-full">{{ statusLabel(detail) }}</span></h3>
        <p>{{ detail.description }}　条件：{{ detail.conditions }}</p>
        <p class="info-line"><span class="info-label">时间</span><b><span class="nowrap">{{ fmtWeek(detail.start_time) }}</span> – <span class="nowrap">{{ fmtWeek(detail.end_time) }}</span></b></p>
        <p class="info-line"><span class="info-label">地点</span><b>{{ detail.location }}</b></p>
        <p class="info-line"><span class="info-label">组织者</span><b>{{ detail.organizer_name }}</b></p>
        <p class="info-line"><span class="info-label">报名截止</span><b><span class="nowrap">{{ fmtWeek(detail.deadline) }}</span></b></p>
        <p class="info-line"><span class="info-label">名额</span><b>正式 {{ detail.formal }}/{{ detail.capacity }}　预留 {{ detail.reserved }}　候补 {{ detail.waiting }}</b><span v-if="isFull(detail)">　已满员</span></p>
        <p v-if="detail.reserved > 0" class="hint">预留是指已发出递补邀请、待对方确认的名额；对方确认后转为正式，过期则顺延给下一位候补。</p>
        <p v-if="detail.my_status" class="info-line"><span class="info-label">我的状态</span><b>{{ statusText(detail.my_status) }}<span v-if="detail.my_rank">，候补第 {{ detail.my_rank }} 名</span><span v-if="detail.invitation_expires_at">，请在 <span class="nowrap">{{ fmtWeek(detail.invitation_expires_at) }}</span> 前确认</span></b></p>
        <template v-if="user && (!detail.my_status || ['WITHDRAWN','EXPIRED','CANCELLED'].includes(detail.my_status))">
          <button v-if="canSignup(detail)" class="primary" :disabled="signupBusy" @click="signup(detail.activity_id || detail.id)">{{ signupBusy ? '提交中…' : '报名' }}</button>
          <button v-else class="primary" disabled>{{ signupDisabledReason(detail) }}</button>
          <p class="hint">报名后组织者仅能看到你的昵称，不会索要或公开你的联系方式。</p>
        </template>
        <button v-if="user && detail.participation_id && ['FORMAL','WAITING'].includes(detail.my_status)" class="warn" @click="withdraw(detail.participation_id, detail.activity_id || detail.id, detail.title)">取消报名 / 放弃候补</button>
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
      <div v-if="!hallLoading && !loadError && !filteredHall.length" class="card empty-state">
        <p>{{ emptyHint }}</p>
      </div>
      <div v-for="a in filteredHall" :key="a.id" class="card">
        <h3>{{ a.title }} <span class="activity-no">#{{ a.id }}</span> <span v-if="isFull(a)" class="badge badge-full">已满员</span> <span v-if="a.status && a.status !== 'OPEN'" class="badge badge-full">{{ statusLabel(a) }}</span></h3>
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
          <h3>{{ o.title }} <span class="activity-no">#{{ o.id }}</span></h3>
          <p>
            <span class="badge">{{ o.category }}</span><span class="badge">{{ o.location }}</span>
            <span class="badge badge-big">正式 {{ o.formal_count }}/{{ o.capacity }}</span>
            <span class="badge">预留 {{ o.reserved_count }}</span><span class="badge">候补 {{ o.waiting_count }} 人</span>
          </p>
          <p class="meta card-meta"><span class="nowrap">报名截止：{{ fmtWeek(o.deadline) }}</span>　<span class="nowrap">开始：{{ fmtWeek(o.start_time) }}</span>　状态：{{ o.status }}</p>
          <button @click="go('hall'); openDetail(o.id)">查看详情</button>
          <button @click="openRoster(o.id)">查看名单</button>
        </div>
        <p v-if="!organized.length" class="meta">暂无组织的活动。</p>
      </div>
      <div v-else>
        <div v-for="p in myFiltered" :key="p.id" class="card">
          <h3>{{ p.title }}</h3>
          <p class="meta card-meta">状态：{{ statusText(p.status) }}　地点：{{ p.location }}　<span class="nowrap">开始：{{ fmtWeek(p.start_time) }}</span>
            <span v-if="p.invitation_expires_at">　请在 <span class="nowrap">{{ fmtWeek(p.invitation_expires_at) }}</span> 前确认</span>
          </p>
          <button v-if="['FORMAL','WAITING','INVITED'].includes(p.status)" class="warn" @click="withdraw(p.id, p.activity_id, p.title)">取消 / 放弃</button>
          <button v-if="['WITHDRAWN','EXPIRED','CANCELLED'].includes(p.status)" class="primary" @click="resignup(p.activity_id)">重新报名</button>
          <button v-if="p.invitation_id && p.status==='INVITED'" class="primary" @click="accept(p.invitation_id); loadMy()">接受递补</button>
          <button v-if="p.invitation_id && p.status==='INVITED'" class="warn" @click="reject(p.invitation_id); loadMy()">拒绝递补</button>
        </div>
        <p v-if="!myFiltered.length" class="meta">{{ myEmptyText }}</p>
      </div>
    </section>

    <section v-if="view==='roster'" class="card">
      <h3>名单：{{ rosterActivity ? rosterActivity.title : '' }}</h3>
      <button class="ghost" @click="go('my')">← 返回我的活动</button>
      <p v-if="rosterError" class="meta">{{ rosterError }}</p>
      <div v-if="rosterActivity && rosterActivity.roster">
        <h4>正式名单（{{ rosterFormal.length }} 人）</h4>
        <p v-for="r in rosterFormal" :key="'f-' + r.id">{{ r.nickname }}：{{ statusText(r.status) }}</p>
        <p v-if="!rosterFormal.length" class="meta">暂无正式成员。</p>
        <h4>待确认（{{ rosterInvited.length }} 人）</h4>
        <p v-for="r in rosterInvited" :key="'i-' + r.id">{{ r.nickname }}：待确认</p>
        <p v-for="inv in (rosterActivity.pending_invitations || [])" :key="'pi-' + inv.id">待确认邀请：{{ inv.nickname }}，截止 <span class="nowrap">{{ fmtWeek(inv.expires_at) }}</span></p>
        <p v-if="!rosterInvited.length && !(rosterActivity.pending_invitations || []).length" class="meta">暂无待确认。</p>
        <h4>候补队列（{{ rosterWaiting.length }} 人）</h4>
        <p v-for="(r, idx) in rosterWaiting" :key="'w-' + r.id">{{ r.nickname }}：候补中，第 {{ idx + 1 }} 名</p>
        <p v-if="!rosterWaiting.length" class="meta">暂无候补。</p>
        <p class="hint">名单只显示昵称与状态，不含联系方式；仅组织者可见。</p>
      </div>
    </section>

    <section v-if="view==='notif'">
      <button @click="readAll">全部标为已读</button>
      <div v-for="g in groupedNotifs" :key="g.key" class="card notif-row" :class="{'notif-unread': !g.latest.read_at}">
        <p><b>{{ notifTypeText(g.latest.type) }}</b>　{{ g.latest.content }}</p>
        <p v-if="g.count > 1" class="meta">同一活动 30 分钟内还有 {{ g.count - 1 }} 条同类通知，已折叠为最新一条。</p>
        <button v-if="g.count > 1" class="ghost" @click="toggleGroup(g.key)">{{ expandedGroups.has(g.key) ? '收起折叠的通知' : '展开被折叠的 ' + (g.count - 1) + ' 条' }}</button>
        <div v-if="expandedGroups.has(g.key)">
          <p v-for="n in g.items" :key="'fold-' + n.id" class="meta"><b>{{ notifTypeText(n.type) }}</b>　{{ n.content }}　<span class="nowrap">{{ fmtWeek(n.created_at) }}</span></p>
        </div>
        <p class="meta"><span class="nowrap">{{ fmtWeek(g.latest.created_at) }}</span>　<span v-if="!g.latest.read_at" class="unread">未读</span><span v-else>已读</span></p>
      </div>
      <p v-if="!groupedNotifs.length" class="meta">暂无通知。</p>
    </section>

    <section v-if="view==='publish'" class="card">
      <h3>发布活动</h3>
      <label>标题 <input v-model="form.title" placeholder="如：周六羽毛球友谊赛" /></label>
      <label>类别
        <select v-model="form.category"><option>羽毛球</option><option>桌游</option><option>学习组</option><option>其他</option></select>
      </label>
      <label>地点 <input v-model="form.location" placeholder="如：江安校区体育馆三楼" /></label>
      <label>人数上限 <input v-model.number="form.capacity" type="number" min="1" max="100" /></label>
      <label>报名截止 <input v-model="form.deadline" type="datetime-local" /><span class="field-hint">点日历图标选日期和时间；选好后下方会用中文显示你选的时间。</span><span v-if="form.deadline" class="field-hint">已选：{{ fmtWeek(form.deadline.replace('T', ' ')) }}</span></label>
      <label>开始时间 <input v-model="form.start_time" type="datetime-local" /><span class="field-hint">点日历图标选日期和时间；选好后下方会用中文显示你选的时间。</span><span v-if="form.start_time" class="field-hint">已选：{{ fmtWeek(form.start_time.replace('T', ' ')) }}</span></label>
      <label>结束时间 <input v-model="form.end_time" type="datetime-local" /><span class="field-hint">点日历图标选日期和时间；选好后下方会用中文显示你选的时间。</span><span v-if="form.end_time" class="field-hint">已选：{{ fmtWeek(form.end_time.replace('T', ' ')) }}</span></label>
      <label>介绍 <input v-model="form.description" /></label>
      <label>参与条件 <input v-model="form.conditions" placeholder="如：水平不限，AA 场地费 10 元" /></label>
      <button class="primary" :disabled="publishBusy" @click="publish">{{ publishBusy ? '提交中…' : '发布' }}</button>
    </section>
    <footer v-if="demoMode" class="page-foot">
      <button class="danger" @click="resetDemo">⚠ 恢复演示数据</button>
      <span class="hint">危险操作：会清空当前业务数据并恢复种子数据，仅演示时使用。</span>
      <button class="ghost" @click="exitDemoMode">退出演示模式</button>
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
const q = ref(''), category = ref(''), timeSlot = ref('')
// 任务 9：深色模式开关（localStorage 'theme' 持久化，默认浅色）。
const darkMode = ref(localStorage.getItem('theme') === 'dark')
// 任务 4：演示模式开关。普通用户默认看不到页脚恢复按钮；演示时在地址后加
// ?demo=1 一次即可开启（写入 localStorage 'demoMode'），页脚出现恢复入口与退出按钮。
const demoMode = ref(localStorage.getItem('demoMode') === '1')
// 任务 3：已展开的通知折叠组（key 含类型，见 groupedNotifs）。
const expandedGroups = ref(new Set())
// 任务 19：退出后的明显提示条（带重新报名入口），15 秒后自动消失。
const withdrawNotice = ref(null)
// 任务 7：组织者名单视图的数据与错误。
const rosterActivity = ref(null), rosterError = ref('')
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
// 任务 9：深色模式切换（只用 REA v4 深色 token，见 style.css body.dark）。
function applyTheme() {
  document.body.classList.toggle('dark', darkMode.value)
  localStorage.setItem('theme', darkMode.value ? 'dark' : 'light')
}
function toggleTheme() { darkMode.value = !darkMode.value; applyTheme() }
// 任务 4：退出演示模式（恢复按钮随之隐藏，功能本身保留，?demo=1 可再开）。
function exitDemoMode() { demoMode.value = false; localStorage.removeItem('demoMode') }
// 任务 2：报名是否可点——状态是前后端唯一口径（任务 1 定方案 (a) 后，
// READY 且截止未到会在一个 sweep 周期内自动翻回 OPEN；此处只按状态 + 截止展示）。
function deadlinePassed(a) {
  if (!a || !a.deadline) return false
  return new Date(String(a.deadline).replace(' ', 'T')) < new Date()
}
function canSignup(a) {
  if (!a) return false
  return a.status === 'OPEN' && !deadlinePassed(a)
}
function signupDisabledReason(a) {
  if (!a) return '报名未开始'
  if (a.status === 'CANCELLED') return '活动已取消'
  if (a.status === 'ENDED') return '活动已结束'
  if (a.status === 'RUNNING') return '活动进行中，报名已截止'
  if (deadlinePassed(a)) return '报名已截止'
  return '报名未开始'
}
// 任务 2：大厅卡片 / 详情标题的不可报标识文案（OPEN 无标识）。
function statusLabel(a) {
  if (!a) return ''
  if (a.status === 'CANCELLED') return '已取消'
  if (a.status === 'ENDED') return '已结束'
  if (a.status === 'RUNNING') return '进行中'
  if (deadlinePassed(a)) return '报名已截止'
  if (a.status === 'READY') return '报名未开始'
  return ''
}
// 任务 6：时段归属（按开始时间，边界：凌晨 0–6 / 上午 6–12 / 下午 12–18 / 晚上 18–24）。
// 注意与展示用的 periodWord 不同：展示保留「中午」词，筛选口径按用户定的四段，不设中午段。
function slotOf(hour) {
  if (hour < 6) return '凌晨'
  if (hour < 12) return '上午'
  if (hour < 18) return '下午'
  return '晚上'
}
function startHour(a) {
  const m = String(a.start_time || '').match(/[T ](\d{2}):/)
  return m ? +m[1] : null
}
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
// 任务 13：区分「大厅确实无活动」与「当前筛选下无活动」。
const seedHint = computed(() => {
  if (hallLoading.value) return '活动列表加载中…'
  if (!hall.value.length) return '当前大厅暂无活动。'
  if (!filteredHall.value.length) return '当前筛选下暂无活动。'
  const items = filteredHall.value.slice(0, 3).map(a => `${a.title}（正式 ${a.formal_count}/${a.capacity}）`)
  const scope = (q.value || timeSlot.value || category.value) ? '当前筛选' : '当前大厅'
  return `${scope} ${filteredHall.value.length} 个活动：${items.join('；')}。`
})
const emptyHint = computed(() => {
  if (q.value) return `没有找到含「${q.value}」的活动，换个关键词试试`
  if (timeSlot.value) return `「${timeSlot.value}」时段暂无活动，换个时段试试`
  if (category.value) return `「${category.value}」分类下暂无活动`
  return '暂无活动，快来发布第一个吧'
})
// 任务 6 + 任务 12：大厅前端筛选。搜索在前端做（后端 /api/activities 的 q
// 只按标题 LIKE，改后端不在本轮前端任务内）：匹配标题 + 介绍 + 参与条件；
// q 本身是「凌晨/上午/下午/晚上」时段词时，也按开始时间归属匹配；
// 再叠加时间段下拉筛选。类别仍走后端参数。
const filteredHall = computed(() => {
  let list = hall.value
  const needle = q.value.trim().toLowerCase()
  if (needle) {
    const periodWords = ['凌晨', '上午', '下午', '晚上']
    list = list.filter(a => {
      const hay = `${a.title || ''} ${a.description || ''} ${a.conditions || ''}`.toLowerCase()
      if (hay.includes(needle)) return true
      if (periodWords.includes(needle)) {
        const h = startHour(a)
        return h !== null && slotOf(h) === needle
      }
      return false
    })
  }
  if (timeSlot.value) {
    list = list.filter(a => {
      const h = startHour(a)
      return h !== null && slotOf(h) === timeSlot.value
    })
  }
  return list
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
const FOLDABLE_TYPES = ['报名', '候补', '变更', '退出']
// 任务 3 返工（2026-11-08）：折叠只在同类型之间发生。旧实现分组 key 只有
// 活动 + 操作人、且只校验两边类型都在可折叠集合里，报名与退出会被折成一行；
// 新 key 含类型，且入组条件要求 n.type === g.latest.type。邀请类不参与折叠。
// 后端已把退出通知 type 统一存「退出」（任务 3 后端部分，历史 11 行已迁移）；
// '变更' 保留在可折叠集合里作兼容（万一还有旧行）。
function notifIsNewer(a, b) {
  const ta = new Date(a.created_at), tb = new Date(b.created_at)
  return ta > tb || (ta.getTime() === tb.getTime() && (a.id ?? 0) > (b.id ?? 0))
}
const groupedNotifs = computed(() => {
  const groups = []
  for (const n of notifs.value) {
    const key = (n.activity_id ?? '') + '|' + notifActor(n) + '|' + n.type
    const g = groups.find(g => g.key === key
      && FOLDABLE_TYPES.includes(n.type) && n.type === g.latest.type
      && Math.abs(new Date(n.created_at) - new Date(g.latest.created_at)) <= 30 * 60 * 1000)
    if (g) { g.items.push(n); g.count++; if (notifIsNewer(n, g.latest)) g.latest = n } else { groups.push({ key, latest: n, items: [n], count: 1 }) }
  }
  return groups
})
// 任务 3：折叠行可展开 / 收起（点一下展开被折叠的各条，标题逐条可见）。
function toggleGroup(key) {
  const s = new Set(expandedGroups.value)
  if (s.has(key)) s.delete(key); else s.add(key)
  expandedGroups.value = s
}
// 任务 7：名单视图分组（正式 / 待确认 / 候补；待确认单列）。
const rosterFormal = computed(() => (rosterActivity.value?.roster || []).filter(r => r.status === 'FORMAL'))
const rosterInvited = computed(() => (rosterActivity.value?.roster || []).filter(r => r.status === 'INVITED'))
const rosterWaiting = computed(() => (rosterActivity.value?.roster || []).filter(r => r.status === 'WAITING'))
// 任务 16 返工（2026-11-07）：入组时按 created_at（再按 id）取最大者更新
// g.latest（见上文新版 groupedNotifs；旧实现已由任务 3 返工替换，
// 30 分钟窗口以组内最新一条（g.latest）为锚点，逻辑不变）。
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
    // 任务 18：登录成功横幅 5 秒后自动消失（另有 × 可随时关闭）；其他提示条不受影响。
    setTimeout(() => { if (msg.value === '登录成功') msg.value = '' }, 5000)
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
    // 任务 18：注册成功横幅同样 5 秒后自动消失。
    if (user.value) {
      msg.value = '注册成功，已自动登录'
      setTimeout(() => { if (msg.value === '注册成功，已自动登录') msg.value = '' }, 5000)
    }
  } catch (e) { msg.value = friendlyErr(e) } finally { registerBusy.value = false }
}
async function resignup(aid) {
  try {
    const j = await api('/api/activities/' + aid + '/signup', 'POST')
    // 任务 19 返工：重新报名后刷新当前详情（与 signup() 一致）——
    // 否则详情仍显示已退出（名额 0/6、「报名」按钮），与横幅/接口不一致。
    // openDetail 会清空 msg，须在它之后设置提示；只刷新当前正打开的同一活动。
    if (detail.value && (detail.value.id === aid || detail.value.activity_id === aid)) {
      try { await openDetail(aid) } catch (_) {}
    }
    // 任务 14：重新报名也用用户语言，按重新拉到的详情实际状态写（与 signup() 一致）。
    const st = detail.value?.my_status || j.my_status
    const rank = detail.value?.my_rank || j.my_rank
    msg.value = st === 'WAITING'
      ? (rank ? `你已进入候补队列，第 ${rank} 名` : '你已进入候补队列')
      : st === 'INVITED' ? '你收到递补邀请，请及时确认' : '你报上了，你是正式成员'
    withdrawNotice.value = null
    loadMy()
    loadHall()
  } catch (e) { msg.value = friendlySignupErr(e) }
}
// 任务 19：从退出提示条点「重新报名」。
async function resignupFromNotice() {
  if (withdrawNotice.value?.activityId) await resignup(withdrawNotice.value.activityId)
}
// 任务 3（换号串号）：按账号缓存的前端状态集中清理——通知列表、
// 未读数、我的活动（我报名的 + 我组织的）、详情页（含 my_status /
// 组织者名单，均属当前账号视角）。登录成功后、退出时、token 失效时都走这里。
function clearAccountState() { mine.value = []; organized.value = []; notifs.value = []; unread.value = 0; detail.value = null; withdrawNotice.value = null; rosterActivity.value = null; rosterError.value = '' }
function logout() { token.value = ''; localStorage.removeItem('token'); user.value = null; clearAccountState(); view.value = 'hall' }
async function loadHall() {
  hallLoading.value = true; loadError.value = ''
  try {
    const params = new URLSearchParams()
    // 任务 12：搜索在前端做（见 filteredHall），不再把 q 发给后端——
    // 后端 q 只按标题 LIKE，发过去会把介绍/条件命中的活动先筛掉。
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
    // 任务 14：报名成功提示改用户语言，按重新拉到的详情实际状态写。
    const st = detail.value?.my_status || j.my_status
    const rank = detail.value?.my_rank || j.my_rank
    msg.value = st === 'WAITING'
      ? (rank ? `你已进入候补队列，第 ${rank} 名` : '你已进入候补队列')
      : st === 'INVITED' ? '你收到递补邀请，请及时确认' : '你报上了，你是正式成员'
    loadHall()
  } catch (e) { msg.value = friendlySignupErr(e) } finally { signupBusy.value = false }
}
// 任务 2：报名失败文案与事实一致——后端仍可能返回「活动不在报名期」
// （如 READY 未翻回的窗口期），按截止时间翻译，不再原样抛给用户。
function friendlySignupErr(e) {
  const m = String((e && e.message) || e || '')
  if (/活动不在报名期/.test(m)) {
    if (detail.value && deadlinePassed(detail.value)) return '报名已截止'
    return '报名未开始，请稍后再试'
  }
  if (/已过报名截止时间/.test(m)) return '报名已截止'
  return friendlyErr(e)
}
async function withdraw(pid, aid, title) {
  try {
    await api('/api/participations/' + pid + '/withdraw', 'POST')
    // 任务 3-1：详情刷新会清空提示（openDetail 置空 msg），先等刷新完成再置提示；
    // 刷新失败也不吞掉「已退出」。
    if (detail.value) { try { await openDetail(detail.value.id || detail.value.activity_id) } catch (_) {} }
    // 任务 19：退出提示更明显、停留更久（15 秒）、带重新报名入口；保持一键退出，不加二次确认。
    withdrawNotice.value = { activityId: aid, title: title || '' }
    msg.value = ''
    const snap = withdrawNotice.value
    setTimeout(() => { if (withdrawNotice.value === snap) withdrawNotice.value = null }, 15000)
    if (view.value === 'my') { loadMy(); loadOrganized() } else { loadHall() }
  } catch (e) { msg.value = e.message }
}
// 任务 7：组织者名单视图。复用详情接口（组织者才返回 roster 字段，
// 非组织者无 roster 字段，后端行为不变；此处只做展示层分支）。
async function openRoster(id) {
  rosterError.value = ''
  try {
    const d = await api('/api/activities/' + id)
    if (!d.roster) { rosterActivity.value = null; rosterError.value = '仅组织者可查看名单。'; view.value = 'roster'; return }
    rosterActivity.value = d; view.value = 'roster'
  } catch (e) { rosterError.value = friendlyErr(e); rosterActivity.value = null; view.value = 'roster' }
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
  // 任务 9：刷新后保持深色偏好（localStorage 'theme'）。
  applyTheme()
  // 任务 4：地址带 ?demo=1 时开启演示模式（页脚恢复入口出现），写入 localStorage。
  if (new URLSearchParams(location.search).get('demo') === '1') {
    demoMode.value = true; localStorage.setItem('demoMode', '1')
  }
  loadHall()
  // 任务 3：刷新页面带 token 恢复登录时，未读数也要立即是当前账号的，
  // 不能等到手动点「通知」才刷。
  if (token.value) { try { const j = await api('/api/me'); user.value = j.user; loadNotif() } catch { logout() } }
})
</script>
