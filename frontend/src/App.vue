<template>
  <header>
    <img class="logo" src="/img/logo.webp" alt="找个搭子" />
    <h1>找个搭子</h1>
    <button v-if="user" class="menu-btn" @click="menuOpen = !menuOpen" :aria-expanded="menuOpen">☰ 菜单</button>
    <button v-if="user" class="ghost theme-btn" @click="toggleTheme" :aria-pressed="darkMode"><span class="theme-icon">{{ darkMode ? '☀️' : '🌙' }}</span><span class="theme-label">{{ darkMode ? ' 浅色模式' : ' 深色模式' }}</span></button>
    <nav v-if="user" :class="{open: menuOpen}">
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
    <div v-if="withdrawNotice" class="msg withdraw-notice"><b>已退出{{ withdrawNotice.title ? '活动「' + withdrawNotice.title + '」' : '' }}。</b>手抖点错了？<button class="primary" @click="resignupFromNotice"><b>点这里重新报名</b></button></div>

    <section v-if="!user" class="login-page">
      <div class="hero-banner"><img src="/img/hero.webp" alt="找个搭子：一起打球、玩桌游、学习" /></div>
      <h2 class="login-welcome">欢迎来到找个搭子</h2>
      <p class="meta login-sub">登录或注册后，即可查看活动、报名参加，也可以自己发布活动。</p>
      <div class="card login">
      <h3>登录 / 注册新账号</h3>
      <label class="field-label" for="login-account">账号</label>
      <input id="login-account" v-model="account" placeholder="账号" autocomplete="username" />
      <label class="field-label" for="login-password">密码</label>
      <input id="login-password" v-model="password" type="password" placeholder="密码" autocomplete="current-password" />
      <label class="field-label" for="login-nickname">昵称（仅注册时用）</label>
      <input id="login-nickname" v-model="nickname" placeholder="昵称（仅注册时用）" />
      <button class="primary" :disabled="loginBusy" @click="login">{{ loginBusy ? '提交中…' : '登录' }}</button>
      <button class="ghost" :disabled="registerBusy" @click="register">{{ registerBusy ? '提交中…' : '注册新账号' }}</button>
      <label v-if="demoEnabled" class="field-label" for="quick-account">演示：以以下身份进入</label>
      <select v-if="demoEnabled" id="quick-account" v-model="quickAccount" aria-label="演示账号选择">
        <option value="organizer">组织者</option>
        <option value="demo01">同学1</option>
        <option value="demo05">同学5（桌游候补）</option>
      </select>
      <button v-if="demoEnabled" class="ghost" @click="quick(quickAccount)">以该身份进入（演示）</button>
      <p class="hint">账号可自定（建议用学号），没有学号也可以注册。</p>
      <template v-if="isCapacitor">
      <label class="field-label" for="login-apibase">后端地址（仅手机 App 用）</label>
      <input id="login-apibase" v-model="apiBase" placeholder="后端地址" @change="saveApiBase" />
      <p class="hint">手机 App 才填后端地址，如 http://192.168.1.5:8080；网页 / PWA 留空，用当前地址。</p>
      </template>
      <p v-if="demoEnabled" class="hint">演示账号密码均为 123456，也可注册新账号。</p>
      <p v-if="demoEnabled" class="hint">{{ seedHint }}</p>
      </div>
    </section>

    <section v-if="user && view==='hall'">
      <div class="hero-banner"><img src="/img/hero.webp" alt="找个搭子：一起打球、玩桌游、学习" /></div>
      <div class="card">
        <input v-model="q" maxlength="50" placeholder="按活动名称、介绍、条件、地点搜索" />
        <select v-model="campus" aria-label="按校区筛选">
          <option value="">全部校区</option><option>望江</option><option>华西</option><option>江安</option><option>其他</option>
        </select>
        <select v-model="timeSlot" aria-label="按时间段筛选">
          <option value="">全部时段</option><option value="凌晨">凌晨 0–6 点</option><option value="上午">上午 6–12 点</option><option value="下午">下午 12–18 点</option><option value="晚上">晚上 18–24 点</option>
        </select>
        <select v-model="category">
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
      <div v-if="!hallLoading && !loadError && !hall.length" class="card empty-state">
        <div class="card-cover"><img src="/img/hero.webp" alt="" /></div>
        <p>{{ emptyHint }}</p>
        <p v-if="q">要不要自己发一个？</p>
        <button v-if="q" class="primary" @click="goPublishWithQuery">去发活动</button>
      </div>
      <div v-for="a in hall" :key="a.id" class="card">
        <div class="card-cover"><img :src="coverImg(a.id)" alt="" loading="lazy" /></div>
        <div class="card-head">
          <h3 class="card-title" @click="openDetail(a.id)">{{ a.title }} <span class="activity-no">#{{ a.id }}</span> <span v-if="isNewToday(a)" class="badge badge-accent">新发布</span> <span v-if="isFull(a)" class="badge badge-full">已满员</span> <span v-if="a.status && a.status !== 'OPEN'" class="badge badge-full">{{ statusLabel(a) }}</span></h3>
          <canvas class="cap-ring" width="112" height="112" :data-formal="a.formal_count" :data-cap="a.capacity" role="img" :aria-label="'正式 ' + a.formal_count + '/' + a.capacity + ' 人'"></canvas>
        </div>
        <p>
          <span class="badge">{{ a.category }}</span><span class="badge">{{ a.location }}</span>
          <span v-if="isFull(a) && (a.reserved_count ?? 0) > 0" class="badge badge-big">已占满（正式 {{ a.formal_count }} + 预留 {{ a.reserved_count }}）</span>
          <span v-else class="badge badge-big">正式 {{ a.formal_count }}/{{ a.capacity }}</span>
        </p>
        <p class="meta card-meta"><span class="nowrap">开始：{{ fmtWeek(a.start_time) }}</span>　<span class="nowrap">组织者：{{ a.organizer_name }}</span></p>
        <button @click="openDetail(a.id)">{{ cardButtonText(a) }}</button>
      </div>
      <PageNav :page="hallPage" :size="activityPageSize" :total="hallTotal" :busy="hallLoading"
               label="活动大厅分页" @change="loadHall" />
    </section>

    <section v-if="user && view==='detail'">
      <button class="ghost" @click="go(detailFrom)">← 返回{{ detailFrom === 'my' ? '我的活动' : '活动大厅' }}</button>
      <div v-if="detail" class="card detail-card">
        <div class="card-cover detail-cover"><img :src="coverImg(detail.activity_id || detail.id)" alt="" /></div>
        <div class="card-head">
          <h3>详情：{{ detail.title }} <span class="activity-no">#{{ detail.activity_id || detail.id }}</span> <span v-if="detail.status && detail.status !== 'OPEN'" class="badge badge-full">{{ statusLabel(detail) }}</span></h3>
          <canvas v-if="detail.formal !== undefined && detail.formal !== null && detail.capacity" class="cap-ring" width="112" height="112" :data-formal="detail.formal" :data-cap="detail.capacity" role="img" :aria-label="'正式 ' + detail.formal + '/' + detail.capacity + ' 人'"></canvas>
        </div>
        <p>{{ detail.description }}　条件：{{ detail.conditions }}</p>
        <p class="info-line"><span class="info-label">时间</span><b><span class="nowrap">{{ fmtWeek(detail.start_time) }}</span> – <span class="nowrap">{{ fmtWeek(detail.end_time) }}</span></b><span v-if="durationText(detail)">　{{ durationText(detail) }}</span></p>
        <p class="info-line"><span class="info-label">形式</span><b>单次活动</b><span class="hint">　本次活动仅举办一次</span></p>
        <p class="info-line"><span class="info-label">地点</span><b>{{ detail.location }}</b></p>
        <p class="info-line"><span class="info-label">组织者</span><b>{{ detail.organizer_name }}</b></p>
        <p class="info-line"><span class="info-label">报名截止</span><b><span class="nowrap">{{ fmtWeek(detail.deadline) }}</span></b><span v-if="detail.status !== 'CANCELLED'">　{{ deadlineCountdown(detail) }}</span></p>
        <p v-if="detail.formal !== undefined && detail.formal !== null && detail.reserved !== undefined && detail.waiting !== undefined" class="info-line"><span class="info-label">名额</span><b>正式 {{ detail.formal }}/{{ detail.capacity }}　预留 {{ detail.reserved }}　候补 {{ detail.waiting }}</b><span v-if="isFull(detail)">　已满员</span></p>
        <p v-if="detail.my_status" class="info-line"><span class="info-label">我的状态</span><b>{{ statusText(detail.my_status) }}<span v-if="detail.my_rank">，候补第 {{ detail.my_rank }} 名</span><span v-if="detail.invitation_expires_at">，请在 <span class="nowrap">{{ fmtWeek(detail.invitation_expires_at) }}</span> 前确认</span></b></p>
        <template v-if="user && detail.status !== 'CANCELLED' && (!detail.my_status || ['WITHDRAWN','EXPIRED','CANCELLED'].includes(detail.my_status))">
          <button v-if="canSignup(detail)" class="primary" :disabled="signupBusy" @click="signup(detail.activity_id || detail.id)">{{ signupBusy ? '提交中…' : (isFull(detail) ? '报名候补' : '报名') }}</button>
          <button v-else class="primary" disabled>{{ signupDisabledReason(detail) }}</button>
          <p class="hint">报名后组织者仅能看到你的昵称，不会索要或公开你的联系方式。</p>
          <p v-if="isFull(detail)" class="hint">名额已满，报名将进入候补队列，有人退出时按顺序递补。</p>
        </template>
        <button v-if="user && detail.participation_id && canWithdraw(detail, detail.my_status)" class="warn" @click="withdraw(detail.participation_id, detail.activity_id || detail.id, detail.title)">{{ detail.my_status === 'WAITING' ? '放弃候补' : '取消报名' }}</button>
        <p v-if="user && detail.invitation_id" class="info-line"><span class="info-label">递补邀请</span><b v-if="inviteSecondsLeft(detail.invitation_expires_at) !== undefined && inviteSecondsLeft(detail.invitation_expires_at) !== null">剩余 {{ inviteSecondsLeft(detail.invitation_expires_at) }} 秒，请及时确认</b><b v-else-if="inviteSecondsLeft(detail.invitation_expires_at) === null">邀请已过期</b></p>
        <p v-if="user && detail.invitation_id" class="hint">拒绝后将退出候补队列，如需再排队请重新报名。</p>
        <button v-if="user && detail.invitation_id && inviteSecondsLeft(detail.invitation_expires_at) !== null" class="primary" @click="accept(detail.invitation_id)">接受递补</button>
        <button v-if="user && detail.invitation_id && inviteSecondsLeft(detail.invitation_expires_at) !== null" class="warn" @click="reject(detail.invitation_id)">拒绝递补</button>
        <p v-if="detail.reserved > 0" class="hint">预留是指已发出递补邀请、待对方确认的名额；对方确认后转为正式，过期则顺延给下一位候补。</p>
        <details class="term-help">
          <summary>术语解释：候补 / 递补 / 预留 / 正式成员</summary>
          <p class="hint">正式成员：已占一个名额，可以参加活动。</p>
          <p class="hint">候补：名额满了，先排队；有人退出时按顺序递补。</p>
          <p class="hint">递补：有人退出空出名额时，按候补顺序邀请下一位参加。</p>
          <p class="hint">预留：已发出递补邀请、等对方确认的名额；对方确认后转为正式，过期则顺延给下一位候补。</p>
        </details>
        <p v-if="detail.status === 'CANCELLED'" class="info-line"><b>该活动已取消</b><span v-if="detail.cancel_reason">　{{ detail.cancel_reason }}</span></p>
        <AttendancePanel v-if="user && detail.status !== 'CANCELLED' && (detail.is_organizer || detail.my_status === 'FORMAL')"
                         :key="detail.id" :activity-id="detail.id" :organizer="detail.is_organizer"
                         :clock="nowTick" :request="api" :format-time="fmtWeekSec" />
        <div v-if="user && detail.status !== 'CANCELLED' && !detail.is_organizer" class="message-box">
          <label class="field-label" for="message-draft">给组织者留言</label>
          <input id="message-draft" v-model="messageDraft" placeholder="如：请问场地有电梯吗？" maxlength="200" />
          <button class="ghost" @click="sendMessage(detail.activity_id || detail.id)">发送留言</button>
          <p class="hint">留言仅组织者可见，不公开联系方式。</p>
        </div>
        <div v-if="myMessages.length && !detail.is_organizer" class="my-messages">
          <h4>我发过的留言</h4>
          <div v-for="m in myMessages" :key="'mm-' + m.id" class="message-item">
            <p><span class="nowrap">{{ fmtWeek(m.created_at) }}</span></p>
            <p>{{ m.content }}</p>
            <p v-if="m.reply" class="hint">组织者回复（{{ fmtWeek(m.reply.created_at) }}）：{{ m.reply.content }}</p>
            <p v-else class="hint">组织者尚未回复</p>
          </div>
        </div>
        <div v-if="detail.is_organizer">
          <button class="ghost" @click="openRoster(detail.id)">查看报名名单</button>
          <div v-if="detail.messages">
            <h4>收到的留言</h4>
            <div v-for="m in detail.messages" :key="'msg-' + m.id" class="message-item">
              <p><b>{{ m.sender_nickname }}</b>　<span class="nowrap">{{ fmtWeek(m.created_at) }}</span></p>
              <p>{{ m.content }}</p>
              <p v-if="m.reply" class="hint">你的回复（{{ fmtWeek(m.reply.created_at) }}）：{{ m.reply.content }}</p>
              <template v-else>
                <input v-model="replyDrafts[m.id]" placeholder="回一条（仅留言人可见）" maxlength="200" />
                <button class="ghost" @click="replyMessage(detail.activity_id || detail.id, m.id)">回复</button>
              </template>
            </div>
            <p v-if="!detail.messages.length" class="meta">暂无留言。</p>
          </div>
          <template v-if="detail.status !== 'CANCELLED'">
            <button v-if="parseShanghai(detail.end_time) > nowTick" class="warn" @click="cancelActivity(detail.id)">取消活动</button>
            <button v-if="detail.status === 'OPEN' && !deadlinePassed(detail)" class="ghost" @click="openReschedule(detail)">修改活动</button>
            <button v-if="detail.status === 'OPEN' && deadlinePassed(detail)" class="ghost" disabled>报名已截止，无法修改</button>
          </template>
          <div v-if="rescheduleOpen" class="reschedule-box">
            <h4>修改活动</h4>
            <label>名称 <input v-model="rescheduleForm.title" maxlength="50" /></label>
            <label>类别
              <select v-model="rescheduleForm.category"><option>羽毛球</option><option>桌游</option><option>学习组</option><option>其他</option></select>
            </label>
            <label>地点 <input v-model="rescheduleForm.location" maxlength="100" /></label>
            <label>人数上限 <input v-model.number="rescheduleForm.capacity" type="number" min="1" max="100" /></label>
            <label>介绍 <textarea v-model="rescheduleForm.description" maxlength="2000"></textarea></label>
            <label>参与条件 <textarea v-model="rescheduleForm.conditions" maxlength="500"></textarea></label>
            <label class="field-label">报名截止</label>
            <div class="dt-row"><input v-model="rescheduleForm.deadline" placeholder="如：2026-11-09 21:00" @blur="normalizeDT('reschedule','deadline')" /><button class="ghost" type="button" @click="openPicker('reschedule','deadline')">📅 选择</button></div>
            <label class="field-label">开始时间</label>
            <div class="dt-row"><input v-model="rescheduleForm.start_time" placeholder="如：2026-11-10 10:00" @blur="normalizeDT('reschedule','start_time')" /><button class="ghost" type="button" @click="openPicker('reschedule','start_time')">📅 选择</button></div>
            <label class="field-label">结束时间</label>
            <div class="dt-row"><input v-model="rescheduleForm.end_time" placeholder="如：2026-11-10 12:00" @blur="normalizeDT('reschedule','end_time')" /><button class="ghost" type="button" @click="openPicker('reschedule','end_time')">📅 选择</button></div>
            <label>修改原因 <textarea v-model="rescheduleForm.reason" minlength="10" maxlength="500"></textarea></label>
            <p class="hint">填写 10–500 字的原因。更新后会通知正式成员和候补成员。</p>
            <button class="primary" :disabled="rescheduleBusy" @click="submitReschedule(detail.activity_id || detail.id)">{{ rescheduleBusy ? '提交中…' : '确认修改' }}</button>
            <button class="ghost" @click="rescheduleOpen = false">取消</button>
          </div>
        </div>
      </div>
    </section>

    <section v-if="user && view==='my'">
      <div class="tabs" role="tablist">
        <button :class="{active: myTab==='signed'}" @click="myTab='signed'">已报名</button>
        <button :class="{active: myTab==='waiting'}" @click="myTab='waiting'">候补</button>
        <button :class="{active: myTab==='organized'}" @click="myTab='organized'">我发布</button>
        <button :class="{active: myTab==='history'}" @click="myTab='history'">历史</button>
      </div>
      <div class="card">
        <h4>修改密码</h4>
        <label class="field-label" for="pw-old">原密码</label>
        <input id="pw-old" v-model="pwForm.oldPw" type="password" autocomplete="current-password" />
        <label class="field-label" for="pw-new">新密码</label>
        <input id="pw-new" v-model="pwForm.newPw" type="password" autocomplete="new-password" />
        <button class="primary" :disabled="pwBusy" @click="changePassword">{{ pwBusy ? '提交中…' : '修改密码' }}</button>
        <p class="hint">新密码须 8–64 位，且含字母与数字；修改后需要重新登录。</p>
      </div>
      <div v-if="myTab==='organized'">
        <div v-for="o in organized" :key="'org-' + o.id" class="card">
          <h3 class="card-title" @click="openDetail(o.id)">{{ o.title }} <span class="activity-no">#{{ o.id }}</span></h3>
          <p>
            <span class="badge">{{ o.category }}</span><span class="badge">{{ o.location }}</span>
            <span class="badge badge-big">正式 {{ o.formal_count }}/{{ o.capacity }}</span>
            <span class="badge">预留 {{ o.reserved_count }}</span><span class="badge">候补 {{ o.waiting_count }} 人</span>
          </p>
          <p class="meta card-meta"><span class="nowrap">报名截止：{{ fmtWeek(o.deadline) }}</span>　<span class="nowrap">开始：{{ fmtWeek(o.start_time) }}</span>　状态：{{ o.status }}</p>
          <button @click="openDetail(o.id)">查看详情</button>
          <button v-if="o.status !== 'CANCELLED'" @click="openRoster(o.id)">查看名单</button>
        </div>
        <p v-if="!organized.length" class="meta">暂无组织的活动。</p>
        <PageNav :page="organizedPage" :size="activityPageSize" :total="organizedTotal"
                 label="我发布的活动分页" @change="loadOrganized" />
      </div>
      <div v-else>
        <div v-for="p in myFiltered" :key="p.id" class="card">
          <h3>{{ p.title }}</h3>
          <p class="meta card-meta">状态：{{ myParticipationStatus(p) }}　地点：{{ p.location }}　<span class="nowrap">开始：{{ fmtWeek(p.start_time) }}</span>
            <span v-if="p.my_rank">　候补第 {{ p.my_rank }} 名</span>
            <span v-if="p.invitation_expires_at">　请在 <span class="nowrap">{{ fmtWeek(p.invitation_expires_at) }}</span> 前确认</span>
            <span v-if="p.status==='INVITED' && inviteSecondsLeft(p.invitation_expires_at) !== undefined">　<span v-if="inviteSecondsLeft(p.invitation_expires_at) !== null">剩余 {{ inviteSecondsLeft(p.invitation_expires_at) }} 秒</span><span v-else>邀请已过期</span></span>
          </p>
          <p v-if="p.my_view === 'waiting' && p.status==='INVITED'" class="hint">拒绝后将退出候补队列，如需再排队请重新报名。</p>
          <button v-if="p.my_view !== 'history' && canWithdraw(p, p.status)" class="warn" @click="withdraw(p.id, p.activity_id, p.title)">{{ p.status === 'WAITING' ? '放弃候补' : '取消报名' }}</button>
          <button v-if="p.activity_status === 'OPEN' && !deadlinePassed(p) && ['WITHDRAWN','EXPIRED'].includes(p.status)" class="primary" @click="resignup(p.activity_id)">重新报名</button>
          <button v-if="p.invitation_id && p.status==='INVITED' && inviteSecondsLeft(p.invitation_expires_at) !== null" class="primary" @click="accept(p.invitation_id); loadMy()">接受递补</button>
          <button v-if="p.invitation_id && p.status==='INVITED' && inviteSecondsLeft(p.invitation_expires_at) !== null" class="warn" @click="reject(p.invitation_id); loadMy()">拒绝递补</button>
          <button class="ghost" @click="openDetail(p.activity_id)">查看活动</button>
          <button class="ghost" @click="toggleParticipationHistory(p.id)">{{ historyId === p.id ? '收起参与过程' : '查看参与过程' }}</button>
          <div v-if="historyId === p.id">
            <p v-for="event in historyEvents" :key="event.id" class="meta">
              {{ fmtWeekSec(event.occurred_at) }}　{{ eventLabel(event.change_kind) }}
              <span v-if="event.from_status">　{{ statusText(event.from_status) }} →</span> {{ statusText(event.to_status) }}
            </p>
            <p v-if="!historyEvents.length" class="meta">暂无已记录的状态变更。</p>
          </div>
        </div>
        <p v-if="!myFiltered.length" class="meta">{{ myEmptyText }}</p>
      </div>
    </section>

    <section v-if="user && view==='roster'" class="card">
      <h3>名单：{{ rosterActivity ? rosterActivity.title : '' }}</h3>
      <button class="ghost" @click="go('my')">← 返回我的活动</button>
      <p v-if="rosterError" class="meta">{{ rosterError }}</p>
      <div v-if="rosterActivity">
        <section v-for="section in rosterSections" :key="section.status">
          <h4>{{ section.title }}（{{ rosterPages[section.status]?.total ?? 0 }} 人）</h4>
          <p v-for="(row, index) in rosterPages[section.status]?.items || []" :key="row.id">
            {{ row.nickname }}
            <span v-if="section.status === 'WAITING'">　候补第 {{ (rosterPages.WAITING.page - 1) * activityPageSize + index + 1 }} 名</span>
            <span v-if="section.status === 'INVITED'">　截止 <span class="nowrap">{{ fmtWeek(row.expires_at) }}</span></span>
          </p>
          <p v-if="rosterPages[section.status]?.total === 0" class="meta">{{ section.empty }}</p>
          <PageNav v-if="rosterPages[section.status]" :page="rosterPages[section.status].page"
                   :size="activityPageSize" :total="rosterPages[section.status].total"
                   :label="section.title + '分页'" @change="page => loadRosterPage(section.status, page)" />
        </section>
        <div v-if="rosterActivity.messages">
          <h4>收到的留言</h4>
          <div v-for="m in rosterActivity.messages" :key="'rm-' + m.id" class="message-item">
            <p><b>{{ m.sender_nickname }}</b>　<span class="nowrap">{{ fmtWeek(m.created_at) }}</span></p>
            <p>{{ m.content }}</p>
            <p v-if="m.reply" class="hint">你的回复（{{ fmtWeek(m.reply.created_at) }}）：{{ m.reply.content }}</p>
            <template v-else>
              <input v-model="replyDrafts[m.id]" placeholder="回一条（仅留言人可见）" maxlength="200" />
              <button class="ghost" @click="replyMessage(rosterActivity.activity_id || rosterActivity.id, m.id)">回复</button>
            </template>
          </div>
          <p v-if="!rosterActivity.messages.length" class="meta">暂无留言。</p>
        </div>
        <p class="hint">名单只显示昵称与状态，不含联系方式；仅组织者可见。</p>
      </div>
    </section>

    <section v-if="user && view==='notif'">
      <button @click="readAll">全部标为已读</button>
      <div v-for="g in groupedNotifs" :key="g.key" class="card notif-row" :class="{'notif-unread': !g.latest.read_at}">
        <p><b>{{ notifTypeText(g.latest.type) }}</b>　{{ g.latest.content }}</p>
        <p v-if="inviteCountdownText(g.latest) !== undefined" class="meta"><b>{{ inviteCountdownText(g.latest) }}</b></p>
        <p v-if="g.count > 1" class="meta">同一活动 30 分钟内还有 {{ g.count - 1 }} 条同类通知，已折叠为最新一条。</p>
        <button v-if="g.count > 1" class="ghost" @click="toggleGroup(g.key)">{{ expandedGroups.has(g.key) ? '收起折叠的通知' : '展开被折叠的 ' + (g.count - 1) + ' 条' }}</button>
        <div v-if="expandedGroups.has(g.key)">
          <div v-for="n in g.items.filter(item => item.id !== g.latest.id)" :key="'fold-' + n.id" class="meta">
            <p>{{ n.content }}　<span class="nowrap">{{ fmtWeekSec(n.created_at) }}</span></p>
            <button v-if="!n.read_at" class="ghost" @click="readNotification(n)">标为已读</button>
          </div>
        </div>
        <button v-if="g.latest.activity_id" class="ghost" @click="openNotification(g.latest)">查看活动</button>
        <button v-if="!g.latest.read_at" class="ghost" @click="readNotification(g.latest)">标为已读</button>
        <p class="meta"><span class="nowrap">{{ fmtWeek(g.latest.created_at) }}</span>　<span v-if="!g.latest.read_at" class="unread">未读</span><span v-else>已读</span></p>
      </div>
      <p v-if="!groupedNotifs.length" class="meta">暂无通知。</p>
      <PageNav :page="notifPage" :size="notifPageSize" :total="notifTotal"
               label="通知分页" @change="loadNotif" />
    </section>

    <section v-if="user && view==='publish'" class="card publish-form">
      <h3>发布活动</h3>
      <label>标题 <input v-model="form.title" placeholder="如：羽毛球友谊赛" /><span v-if="titleWeekdayHint" class="field-hint warn-hint">{{ titleWeekdayHint }}</span></label>
      <label>类别
        <select v-model="form.category"><option>羽毛球</option><option>桌游</option><option>学习组</option><option>其他</option></select>
      </label>
      <label>地点 <input v-model="form.location" placeholder="如：江安校区体育馆三楼" /><span class="field-hint">请先写校区（望江/华西/江安/其他），再写场馆与楼层；如有电梯/坡道请写明，如：江安校区体育馆三楼，有电梯</span></label>
      <label>人数上限 <input v-model.number="form.capacity" type="number" min="1" max="100" /></label>
      <label class="field-label">报名截止</label>
      <div class="dt-row"><input v-model="form.deadline" placeholder="如：2026-11-09 21:00" @blur="normalizeDT('form','deadline')" /><button class="ghost" type="button" @click="openPicker('form','deadline')">📅 选择</button></div>
      <span class="field-hint">点「📅 选择」打开日历，或直接按「年-月-日 时:分」输入（24 小时制）。</span>
      <span v-if="form.deadline" class="field-hint">已选：{{ fmtWeek(form.deadline.replace('T', ' ')) }}</span>
      <label class="field-label">开始时间</label>
      <div class="dt-row"><input v-model="form.start_time" placeholder="如：2026-11-10 10:00" @blur="normalizeDT('form','start_time')" /><button class="ghost" type="button" @click="openPicker('form','start_time')">📅 选择</button></div>
      <span class="field-hint">点「📅 选择」打开日历，或直接按「年-月-日 时:分」输入（24 小时制）。</span>
      <span v-if="form.start_time" class="field-hint">已选：{{ fmtWeek(form.start_time.replace('T', ' ')) }}</span>
      <label class="field-label">结束时间</label>
      <div class="dt-row"><input v-model="form.end_time" placeholder="如：2026-11-10 12:00" @blur="normalizeDT('form','end_time')" /><button class="ghost" type="button" @click="openPicker('form','end_time')">📅 选择</button></div>
      <span class="field-hint">点「📅 选择」打开日历，或直接按「年-月-日 时:分」输入（24 小时制）。</span>
      <span v-if="form.end_time" class="field-hint">已选：{{ fmtWeek(form.end_time.replace('T', ' ')) }}</span>
      <label>介绍 <input v-model="form.description" /></label>
      <label>参与条件 <input v-model="form.conditions" placeholder="如：水平不限，免费参加；新人友好；场地有电梯" /></label>
      <button class="primary" :disabled="publishBusy" @click="publish">{{ publishBusy ? '提交中…' : '发布' }}</button>
    </section>
    <footer v-if="user && demoMode" class="page-foot">
      <button v-if="demoEnabled" class="danger" @click="resetDemo">⚠ 恢复演示数据</button>
      <span v-if="demoEnabled" class="hint">危险操作：会清空当前业务数据并恢复种子数据，仅演示时使用。</span>
      <button class="ghost" @click="exitDemoMode">退出演示模式</button>
    </footer>
    <div v-if="picker" class="picker-pop" role="dialog" aria-label="选择日期时间">
      <h4>{{ pickerTitle }}</h4>
      <div class="picker-nav">
        <button class="ghost" @click="pickerPrevMonth">‹ 上月</button>
        <span class="picker-month">{{ pickerYear }} 年 {{ pickerMonth }} 月</span>
        <button class="ghost" @click="pickerNextMonth">下月 ›</button>
      </div>
      <div class="picker-grid">
        <span v-for="w in ['日','一','二','三','四','五','六']" :key="'w'+w" class="picker-week">{{ w }}</span>
        <span v-for="b in pickerBlanks" :key="'b'+b"></span>
        <button v-for="d in pickerDays" :key="'d'+d" :class="{active: d === pickerDay}" @click="pickerDay = d">{{ d }}</button>
      </div>
      <div class="picker-time">
        <label>时间 <select v-model.number="pickerHour" aria-label="小时"><option v-for="h in 24" :key="'h'+h" :value="h-1">{{ String(h-1).padStart(2,'0') }}</option></select> : <select v-model.number="pickerMinute" aria-label="分钟"><option v-for="m in 60" :key="'m'+m" :value="m-1">{{ String(m-1).padStart(2,'0') }}</option></select></label>
      </div>
      <p class="meta">已选：{{ pickerDisplay }}</p>
      <button class="primary" @click="confirmPicker">确定</button>
      <button class="ghost" @click="picker = null">取消</button>
    </div>
  </main>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import PageNav from './PageNav.vue'
import AttendancePanel from './AttendancePanel.vue'
const view = ref('hall')
// 详情页从哪来（大厅 / 我的活动），返回时回到原地（2026-11-10 详情独立页）。
const detailFrom = ref('hall')
const menuOpen = ref(false)
const myTab = ref('signed')
const hallLoading = ref(false)
const loadError = ref('')
const loginBusy = ref(false)
const registerBusy = ref(false)
const signupBusy = ref(false)
const publishBusy = ref(false)
const user = ref(null)
const token = ref(localStorage.getItem('token') || '')
const account = ref(''), password = ref(''), nickname = ref('')
const msg = ref('')
const hall = ref([]), detail = ref(null), mine = ref([]), organized = ref([]), notifs = ref([]), unread = ref(0)
const activityPageSize = 10
const hallPage = ref(1), hallTotal = ref(0)
const organizedPage = ref(1), organizedTotal = ref(0)
let hallRequest = 0
let hallSearchTimer = null
const historyId = ref(null), historyEvents = ref([])
const notifPage = ref(1), notifTotal = ref(0)
const notifPageSize = 20
const q = ref(''), category = ref(''), timeSlot = ref('')
// 任务 9：深色模式开关（localStorage 'theme' 持久化，默认浅色）。
const darkMode = ref(localStorage.getItem('theme') === 'dark')
// 任务 4：演示模式开关。普通用户默认看不到页脚恢复按钮；演示时在地址后加
// ?demo=1 一次即可开启（写入 localStorage 'demoMode'），页脚出现恢复入口与退出按钮。
const demoMode = ref(localStorage.getItem('demoMode') === '1')
const demoEnabled = ref(false)
// 任务 3：已展开的通知折叠组（key 含类型，见 groupedNotifs）。
const expandedGroups = ref(new Set())
// 任务 19：退出后的明显提示条（带重新报名入口），15 秒后自动消失。
const withdrawNotice = ref(null)
async function openRoster(id) {
  rosterError.value = ''
  rosterActivity.value = null
  rosterPages.value = {}
  view.value = 'roster'
  try {
    const activity = await api('/api/activities/' + id + '?include_roster=false')
    if (!activity.is_organizer) throw new Error('仅组织者可查看名单')
    rosterActivity.value = activity
    await Promise.all(rosterSections.map(section => loadRosterPage(section.status, 1)))
  } catch (e) { rosterError.value = friendlyErr(e) }
}
async function loadRosterPage(status, page) {
  const id = rosterActivity.value?.id
  if (!id) return
  try {
    const params = new URLSearchParams({ status, page, size: activityPageSize })
    const result = await api('/api/activities/' + id + '/roster?' + params)
    if (rosterActivity.value?.id === id) rosterPages.value[status] = result
  } catch (e) { rosterError.value = friendlyErr(e) }
}
async function accept(id) { try { await api('/api/invitations/' + id + '/accept', 'POST'); msg.value = '已接受递补'; if (detail.value) openDetail(detail.value.id || detail.value.activity_id); loadMy() } catch (e) { msg.value = e.message } }
async function reject(id) { try { await api('/api/invitations/' + id + '/reject', 'POST'); msg.value = '已拒绝递补'; if (detail.value) openDetail(detail.value.id || detail.value.activity_id); loadMy() } catch (e) { msg.value = e.message } }
async function loadMy() { try { mine.value = await api('/api/my') } catch (e) { msg.value = e.message } }
async function loadOrganized(page = organizedPage.value) {
  try {
    const result = await api('/api/my/organized?page=' + page + '&size=' + activityPageSize)
    organized.value = result.items
    organizedPage.value = result.page
    organizedTotal.value = result.total
  } catch (e) { msg.value = friendlyErr(e) }
}
async function loadNotif(page = notifPage.value) {
  try {
    const data = await api('/api/notifications?page=' + page + '&size=' + notifPageSize)
    notifs.value = data.list
    unread.value = data.unread
    notifTotal.value = data.total
    notifPage.value = data.page
  } catch (e) { msg.value = friendlyErr(e) }
}
async function readNotification(notification) {
  try {
    await api('/api/notifications/' + notification.id + '/read', 'POST')
    await loadNotif()
  } catch (e) { msg.value = friendlyErr(e) }
}
async function openNotification(notification) {
  if (!notification.read_at) await readNotification(notification)
  await openDetail(notification.activity_id)
}
async function readAll() {
  try { await api('/api/notifications/read-all', 'POST'); await loadNotif() }
  catch (e) { msg.value = friendlyErr(e) }
}
async function cancelActivity(id) {
  const activity = detail.value
  const reason = prompt('填写取消原因（10–500 字）')?.trim()
  if (reason === undefined) return
  const length = [...reason].length
  if (length < 10 || length > 500) { msg.value = '取消原因需为 10–500 字'; return }
  const affected = activity.formal + activity.waiting + activity.reserved
  if (!confirm('确认取消「' + activity.title + '」？系统将通知 ' + affected + ' 位参与者。')) return
  try {
    await api('/api/activities/' + id + '/cancel', 'POST', {
      confirmed: true,
      reason,
      version: activity.version,
      expected_affected: affected,
    })
    // 任务 20：取消后详情卡片立即刷新为已取消态（openDetail 会清空 msg，须在它之后设置提示）。
    if (detail.value && (detail.value.id === id || detail.value.activity_id === id)) {
      try { await openDetail(id) } catch (_) {}
    }
    msg.value = '活动已取消'
    const snapMsg = msg.value
    setTimeout(() => { if (msg.value === snapMsg) msg.value = '' }, 5000)
    loadHall()
    if (view.value === 'my') { loadMy(); loadOrganized() }
  } catch (e) {
    if (['ACTIVITY_VERSION_CONFLICT', 'ACTIVITY_IMPACT_CHANGED'].includes(e.code)) await openDetail(id)
    msg.value = friendlyErr(e)
  }
}
async function publish() {
  if (publishBusy.value) return
  publishBusy.value = true
  try {
    // 任务 12：提交前把三处时间归一化为后端要的 T 格式；非法格式直接报，不发请求。
    const deadline = toApiDT(form.value.deadline)
    const start = toApiDT(form.value.start_time)
    const end = toApiDT(form.value.end_time)
    if (!deadline || !start || !end) { msg.value = '时间格式不对，请用「2026-11-09 21:00」这样的格式填写'; return }
    // 任务 22：标题星期与开始星期不符，非阻塞确认（点取消可回去改）。
    // 任务 23：同名标题非阻塞提醒（不拦截入库，点取消可回去改）。
    const warns = []
    if (titleWeekdayHint.value) warns.push(titleWeekdayHint.value)
    const title = form.value.title.trim()
    if (title && [...title].length <= 50) {
      const matches = await api('/api/activities?q=' + encodeURIComponent(title))
      if (matches.some(a => a.title === title)) warns.push('已有同名活动「' + title + '」，请确认是否重复发布')
    }
    if (warns.length && !confirm(warns.join('\n'))) return
    const j = await api('/api/activities', 'POST', { ...form.value, deadline, start_time: start, end_time: end })
    msg.value = '发布成功，活动编号 ' + j.id
    const snapMsg = msg.value
    setTimeout(() => { if (msg.value === snapMsg) msg.value = '' }, 5000)
    // 任务 23：发完清空表单（恢复空态，类别回默认值）。
    form.value = { title: '', category: '羽毛球', location: '', capacity: 4, deadline: '', start_time: '', end_time: '', description: '', conditions: '' }
    view.value = 'hall'; loadHall(1)
  } catch (e) { msg.value = friendlyErr(e) } finally { publishBusy.value = false }
}
function openReschedule(d) {
  rescheduleSource.value = d
  rescheduleForm.value = {
    version: d.version,
    title: d.title,
    category: d.category,
    location: d.location,
    capacity: d.capacity,
    description: d.description || '',
    conditions: d.conditions || '',
    deadline: parseDTInput(d.deadline) || '',
    start_time: parseDTInput(d.start_time) || '',
    end_time: parseDTInput(d.end_time) || '',
    reason: '',
  }
  rescheduleOpen.value = true
}
async function submitReschedule(id) {
  if (rescheduleBusy.value) return
  rescheduleBusy.value = true
  try {
    const fields = { ...rescheduleForm.value }
    const labels = { deadline: '报名截止', start_time: '开始时间', end_time: '结束时间' }
    for (const [key, label] of Object.entries(labels)) {
      const formatted = toApiDT(fields[key])
      if (!formatted) { msg.value = label + '格式不正确'; return }
      const original = rescheduleSource.value[key]
      // 未修改分钟时，保留原值的秒数。
      const seconds = formatted === toApiDT(original) ? String(original).slice(17, 19) : '00'
      fields[key] = formatted + ':' + (seconds || '00')
    }
    const result = await api('/api/activities/' + id, 'PUT', fields)
    rescheduleOpen.value = false
    await openDetail(id)
    msg.value = Object.keys(result.changes).length ? '活动已修改，已通知 ' + result.affected + ' 人' : '活动资料没有变化'
    loadHall()
  } catch (e) {
    if (e.code === 'ACTIVITY_VERSION_CONFLICT') {
      rescheduleOpen.value = false
      await openDetail(id)
    }
    msg.value = friendlyErr(e)
  } finally {
    rescheduleBusy.value = false
  }
}
// 任务 9：站内留言——详情页给组织者留言（报名前后均可，仅组织者可见）。
async function sendMessage(id) {
  const content = messageDraft.value.trim()
  if (!content) { msg.value = '留言内容不能为空'; return }
  try {
    await api('/api/activities/' + id + '/messages', 'POST', { content })
    messageDraft.value = ''
    // 任务 17（2026-11-10）：发完刷新详情（新留言进「我发过的留言」区），
    // 再置提示（openDetail 会清空 msg，须在它之后设置）。
    try { await openDetail(id) } catch (_) {}
    msg.value = '留言已发送，组织者会看到'
    const snapMsg = msg.value
    setTimeout(() => { if (msg.value === snapMsg) msg.value = '' }, 5000)
  } catch (e) { msg.value = friendlyErr(e) }
}
// 任务 9：组织者在名单视图回一条留言（每条留言只能回一条，后端校验）。
async function replyMessage(activityId, mid) {
  const content = (replyDrafts.value[mid] || '').trim()
  if (!content) { msg.value = '回复内容不能为空'; return }
  try {
    await api(`/api/activities/${activityId}/messages/${mid}/reply`, 'POST', { content })
    msg.value = '回复已发送，留言人会收到通知'
    const snapMsg = msg.value
    setTimeout(() => { if (msg.value === snapMsg) msg.value = '' }, 5000)
    if (view.value === 'roster') await openRoster(activityId)
    if (detail.value) { try { await openDetail(activityId) } catch (_) {} }
  } catch (e) { msg.value = friendlyErr(e) }
}
// 任务 10：改密码（POST /api/me/password；成功后当前 token 即刻失效，须重新登录）。
async function changePassword() {
  if (pwBusy.value) return
  pwBusy.value = true
  try {
    await api('/api/me/password', 'POST', { old_password: pwForm.value.oldPw, new_password: pwForm.value.newPw })
    pwForm.value = { oldPw: '', newPw: '' }
    msg.value = '密码已修改，请重新登录'
    clearSessionState()
  } catch (e) { msg.value = friendlyErr(e) } finally { pwBusy.value = false }
}
async function resetDemo() {
  if (!confirm('确认恢复演示数据吗？当前业务数据会被清空。')) return
  try {
    await api('/api/demo/reset', 'POST')
    clearSessionState()
    msg.value = '演示数据已恢复，请重新登录'
  } catch (e) {
    msg.value = e.message
  }
}
function go(v) {
  menuOpen.value = false
  // 任务 14：切页清除提示条（报名提示 5 秒自动消失，退出提示 15 秒；换页即清，行为与登录横幅一致）。
  msg.value = ''; withdrawNotice.value = null
  view.value = v; if (v === 'my') { loadMy(); loadOrganized() } if (v === 'notif') loadNotif(); if (v === 'hall') loadHall()
}
// 任务 18：每秒检查一次递补邀请是否过期；过期后自动刷新为已过期/已顺延态，
// 不再显示可点的接受/拒绝按钮（每活动只触发一次，避免每秒重复拉取）。
watch(nowTick, () => {
  const expired = mine.value.filter(p => p.status === 'INVITED' && p.invitation_expires_at
    && parseShanghai(p.invitation_expires_at) <= nowTick.value
    && !expiredRefreshed.value.has(p.activity_id))
  if (expired.length) {
    expired.forEach(p => expiredRefreshed.value.add(p.activity_id))
    loadMy()
    // sweep 每 10 秒跑一次，12 秒后再拉一次，把已顺延的 EXPIRED 状态也刷回来。
    setTimeout(() => loadMy(), 12000)
  }
  // 详情页的邀请过期才刷新详情（2026-11-10：openDetail 会切到详情页，不在详情页时别把用户拽过去）。
  if (view.value === 'detail' && detail.value && detail.value.invitation_id && detail.value.invitation_expires_at
    && parseShanghai(detail.value.invitation_expires_at) <= nowTick.value
    && !expiredRefreshed.value.has('detail-' + (detail.value.activity_id || detail.value.id))) {
    expiredRefreshed.value.add('detail-' + (detail.value.activity_id || detail.value.id))
    openDetail(detail.value.activity_id || detail.value.id).catch(() => {})
  }
})
onMounted(async () => {
  // 任务 8/18：每秒刷新时钟，驱动倒计时显示。
  tickTimer = setInterval(() => { nowTick.value = Date.now() + serverClockOffset }, 1000)
  // 任务 9：刷新后保持深色偏好（localStorage 'theme'）。
  applyTheme()
  // 任务 4：地址带 ?demo=1 时开启演示模式（页脚恢复入口出现），写入 localStorage。
  if (new URLSearchParams(location.search).get('demo') === '1') {
    demoMode.value = true; localStorage.setItem('demoMode', '1')
  }
  loadDemoStatus()
  if (hasSession()) {
    loadHall()
    // 任务 3：刷新页面带 token 恢复登录时，未读数也要立即是当前账号的，
    // 不能等到手动点「通知」才刷。
    try { const j = await api('/api/me'); user.value = j.user; loadNotif() } catch { clearSessionState() }
  }
})
onUnmounted(() => { clearInterval(tickTimer); clearTimeout(hallSearchTimer) })
</script>
