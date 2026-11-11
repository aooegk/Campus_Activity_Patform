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
      <label class="field-label" for="quick-account">演示：以以下身份进入</label>
      <select id="quick-account" v-model="quickAccount" aria-label="演示账号选择">
        <option value="organizer">组织者</option>
        <option value="demo01">同学1</option>
        <option value="demo05">同学5（桌游候补）</option>
      </select>
      <button class="ghost" @click="quick(quickAccount)">以该身份进入（演示）</button>
      <p class="hint">账号可自定（建议用学号），没有学号也可以注册。</p>
      <template v-if="isCapacitor">
      <label class="field-label" for="login-apibase">后端地址（仅手机 App 用）</label>
      <input id="login-apibase" v-model="apiBase" placeholder="后端地址" @change="saveApiBase" />
      <p class="hint">手机 App 才填后端地址，如 http://192.168.1.5:8080；网页 / PWA 留空，用当前地址。</p>
      </template>
      <p class="hint">演示账号密码均为 123456，也可注册新账号。</p>
      <p class="hint">{{ seedHint }}</p>
      </div>
    </section>

    <section v-if="user && view==='hall'">
      <div class="hero-banner"><img src="/img/hero.webp" alt="找个搭子：一起打球、玩桌游、学习" /></div>
      <div class="card">
        <input v-model="q" placeholder="按活动名称、介绍、条件、地点搜索" />
        <select v-model="campus" aria-label="按校区筛选">
          <option value="">全部校区</option><option>望江</option><option>华西</option><option>江安</option><option>其他</option>
        </select>
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
      <div v-if="!hallLoading && !loadError && !filteredHall.length" class="card empty-state">
        <div class="card-cover"><img src="/img/hero.webp" alt="" /></div>
        <p>{{ emptyHint }}</p>
        <p v-if="q">要不要自己发一个？</p>
        <button v-if="q" class="primary" @click="goPublishWithQuery">去发活动</button>
      </div>
      <div v-for="a in filteredHall" :key="a.id" class="card">
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
        <p v-if="detail.status === 'CANCELLED'" class="info-line"><b>该活动已取消</b></p>
        <div v-if="user && detail.status !== 'CANCELLED' && !detail.roster" class="message-box">
          <label class="field-label" for="message-draft">给组织者留言</label>
          <input id="message-draft" v-model="messageDraft" placeholder="如：请问场地有电梯吗？" maxlength="200" />
          <button class="ghost" @click="sendMessage(detail.activity_id || detail.id)">发送留言</button>
          <p class="hint">留言仅组织者可见，不公开联系方式。</p>
        </div>
        <div v-if="myMessages.length && !detail.roster" class="my-messages">
          <h4>我发过的留言</h4>
          <div v-for="m in myMessages" :key="'mm-' + m.id" class="message-item">
            <p><span class="nowrap">{{ fmtWeek(m.created_at) }}</span></p>
            <p>{{ m.content }}</p>
            <p v-if="m.reply" class="hint">组织者回复（{{ fmtWeek(m.reply.created_at) }}）：{{ m.reply.content }}</p>
            <p v-else class="hint">组织者尚未回复</p>
          </div>
        </div>
        <div v-if="detail.roster">
          <h4>组织者名单</h4>
          <p v-for="r in detail.roster" :key="r.id">{{ r.nickname }}：{{ statusText(r.status) }}</p>
          <p v-for="inv in detail.pending_invitations" :key="inv.id">待确认邀请：{{ inv.nickname }}，截止 <span class="nowrap">{{ fmtWeek(inv.expires_at) }}</span></p>
          <p v-if="!detail.roster.length" class="meta">暂无报名者。</p>
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
            <button v-if="parseShanghai(detail.start_time) > nowTick" class="warn" @click="cancelActivity(detail.id)">取消活动</button>
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
      </div>
      <div v-else>
        <div v-for="p in myFiltered" :key="p.id" class="card">
          <h3>{{ p.title }}</h3>
          <p class="meta card-meta">状态：{{ statusText(p.status) }}　地点：{{ p.location }}　<span class="nowrap">开始：{{ fmtWeek(p.start_time) }}</span>
            <span v-if="p.invitation_expires_at">　请在 <span class="nowrap">{{ fmtWeek(p.invitation_expires_at) }}</span> 前确认</span>
            <span v-if="p.status==='INVITED' && inviteSecondsLeft(p.invitation_expires_at) !== undefined">　<span v-if="inviteSecondsLeft(p.invitation_expires_at) !== null">剩余 {{ inviteSecondsLeft(p.invitation_expires_at) }} 秒</span><span v-else>邀请已过期</span></span>
          </p>
          <p v-if="p.status==='INVITED'" class="hint">拒绝后将退出候补队列，如需再排队请重新报名。</p>
          <button v-if="canWithdraw(p, p.status)" class="warn" @click="withdraw(p.id, p.activity_id, p.title)">{{ p.status === 'WAITING' ? '放弃候补' : '取消报名' }}</button>
          <button v-if="['WITHDRAWN','EXPIRED','CANCELLED'].includes(p.status)" class="primary" @click="resignup(p.activity_id)">重新报名</button>
          <button v-if="p.invitation_id && p.status==='INVITED' && inviteSecondsLeft(p.invitation_expires_at) !== null" class="primary" @click="accept(p.invitation_id); loadMy()">接受递补</button>
          <button v-if="p.invitation_id && p.status==='INVITED' && inviteSecondsLeft(p.invitation_expires_at) !== null" class="warn" @click="reject(p.invitation_id); loadMy()">拒绝递补</button>
        </div>
        <p v-if="!myFiltered.length" class="meta">{{ myEmptyText }}</p>
      </div>
    </section>

    <section v-if="user && view==='roster'" class="card">
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
          <p v-for="(n, i) in g.items" :key="'fold-' + n.id" class="meta"><b>{{ notifTypeText(n.type) }}</b>　第 {{ i + 1 }} 条　{{ n.content }}　<span class="nowrap">{{ fmtWeekSec(n.created_at) }}</span></p>
        </div>
        <p class="meta"><span class="nowrap">{{ fmtWeek(g.latest.created_at) }}</span>　<span v-if="!g.latest.read_at" class="unread">未读</span><span v-else>已读</span></p>
      </div>
      <p v-if="!groupedNotifs.length" class="meta">暂无通知。</p>
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
const account = ref(''), password = ref('123456'), nickname = ref('')
const msg = ref('')
const hall = ref([]), detail = ref(null), mine = ref([]), organized = ref([]), notifs = ref([]), unread = ref(0)
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
// 任务 7：组织者名单视图的数据与错误。
const rosterActivity = ref(null), rosterError = ref('')
const form = ref({ title: '', category: '羽毛球', location: '', capacity: 4, deadline: '', start_time: '', end_time: '', description: '', conditions: '' })
// 任务 11：演示快捷登录改为下拉自选，默认不选组织者号。
const quickAccount = ref('demo01')
// 任务 5：校区筛选（地点文本派生，不加字段）。
const campus = ref('')
// 任务 8/18：每秒刷新一次的时钟，驱动报名截止倒计时与递补邀请剩余秒数。
const nowTick = ref(Date.now())
// 任务 9：留言草稿（详情页）与回复草稿（按留言 id）。
const messageDraft = ref('')
// 任务 17（2026-11-10）：本人发过的留言及组织者回复（GET /api/activities/{id}/messages）。
const myMessages = ref([])
const replyDrafts = ref({})
// 任务 10：改密码表单。
const pwForm = ref({ oldPw: '', newPw: '' })
const pwBusy = ref(false)
// 组织者在报名截止前可编辑活动资料。
const rescheduleSource = ref(null)
const rescheduleOpen = ref(false)
const rescheduleForm = ref({ deadline: '', start_time: '', end_time: '' })
const rescheduleBusy = ref(false)
// 任务 12：自定义日期时间选择器状态（target: 'form' 发布表单 | 'reschedule' 改时间）。
const picker = ref(null)
const pickerYear = ref(2026), pickerMonth = ref(11), pickerDay = ref(9), pickerHour = ref(18), pickerMinute = ref(0)
// 任务 18：已触发过自动刷新的过期邀请（按活动 id 去重，避免每秒重复拉取）。
const expiredRefreshed = ref(new Set())
let tickTimer = null

const headers = () => token.value ? { 'Authorization': 'Bearer ' + token.value, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' }
// 手机原生 App 用：后端不在本机时，在此填电脑地址（如 http://192.168.1.5:8080），留空则用当前页面地址（网页/PWA 默认）
const apiBase = ref(localStorage.getItem('apiBase') || '')
// 后端地址只在 Capacitor 原生 App 里才需要（页面不在服务器上，相对 /api/ 走不通，
// 须指明后端地址）；网页 / PWA 与后端同源，默认就是当前地址，不用填也不显示（2026-11-10 用户问）。
const isCapacitor = ref(typeof window !== 'undefined' && !!window.Capacitor)
function saveApiBase() { localStorage.setItem('apiBase', apiBase.value.trim()) }
async function api(path, method = 'GET', body) {
  const url = apiBase.value.trim().replace(/\/$/, '') + path
  const options = { method, headers: headers(), body: body ? JSON.stringify(body) : undefined }
  const replayable = (method === 'PUT' && /^\/api\/activities\/\d+$/.test(path))
    || (method === 'POST' && /^\/api\/(?:activities(?:\/\d+\/(?:signup|cancel|reschedule))?|participations\/\d+\/withdraw|invitations\/\d+\/(?:accept|reject))$/.test(path))
  if (replayable) {
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    options.headers['Idempotency-Key'] = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  }
  let r
  try {
    r = await fetch(url, options)
  } catch (error) {
    if (!replayable) throw error
    r = await fetch(url, options)
  }
  const j = await r.json().catch(() => ({}))
  if (!r.ok) {
    const error = new Error(j.error || ('请求失败 ' + r.status))
    error.code = j.code
    throw error
  }
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
// 任务 8/18：服务端时间是东八区墙钟字符串（无时区后缀），浏览器
// new Date() 会按浏览器本地时区解析——本机 Chromium 是 UTC，会偏 8 小时
// （实测邀请剩余秒数多算 28916 秒）。统一按 +08:00 解析成绝对时间戳，
// 与浏览器时区无关。fmtWeek 只取星期（按墙钟分量算）不受影响，不动它。
function parseShanghai(s) {
  if (!s) return NaN
  const m = String(s).replace('T', ' ').match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})(?::(\d{2}))?/)
  if (!m) return NaN
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4] - 8, +m[5], +(m[6] || 0))
}
function fmtWeekSec(s) {
  if (!s) return ''
  const m = String(s).replace('T', ' ').match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})/)
  if (!m) return fmtWeek(s)
  return fmtWeek(s) + ':' + m[6]
}
// 任务 7：详情持续时长（由起止时间算出，不新增字段）。
function durationText(a) {
  if (!a || !a.start_time || !a.end_time) return ''
  const t0 = new Date(String(a.start_time).replace(' ', 'T'))
  const t1 = new Date(String(a.end_time).replace(' ', 'T'))
  const mins = Math.round((t1 - t0) / 60000)
  if (!(mins > 0)) return ''
  if (mins < 60) return `持续约 ${mins} 分钟`
  const h = Math.floor(mins / 60), r = mins % 60
  return r ? `持续约 ${h} 小时 ${r} 分钟` : `持续约 ${h} 小时`
}
// 任务 8：报名截止倒计时（只显示，不改变报名判定逻辑；每秒由 nowTick 刷新）。
function deadlineCountdown(a) {
  if (!a || !a.deadline) return ''
  const diff = parseShanghai(a.deadline) - nowTick.value
  if (diff <= 0) return '报名已截止'
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return '距截止还有不到 1 分钟'
  if (mins < 60) return `距截止还有 ${mins} 分钟`
  const h = Math.floor(mins / 60), r = mins % 60
  return r ? `距截止还有 ${h} 小时 ${r} 分钟` : `距截止还有 ${h} 小时`
}
// 任务 4（2026-11-10）：邀请通知倒计时。优先用后端通知行带的 expires_at
// （配合任务 4 后端改动，LEFT JOIN invitation 返回）；无该字段时解析通知文案
// 里的截止时间（后端 fmtFullMinute 固定格式「请在 2026-11-10 周二 上午 09:16 前确认」）；
// undefined = 非邀请通知或解析不到（不显示倒计时）；'已过期' = 已过截止。
function inviteCountdownText(n) {
  if (!n || n.type !== '邀请') return undefined
  let ts = NaN
  if (n.expires_at) ts = parseShanghai(n.expires_at)
  if (Number.isNaN(ts)) {
    const m = String(n.content || '').match(/请在\s*(\d{4}-\d{2}-\d{2})\s+\S+\s+\S+\s+(\d{2}):(\d{2})\s*前确认/)
    if (!m) return undefined
    ts = parseShanghai(m[1] + ' ' + m[2] + ':' + m[3])
  }
  const diff = ts - nowTick.value
  if (diff <= 0) return '已过期'
  const secs = Math.floor(diff / 1000)
  if (secs >= 60) return `还剩 ${Math.floor(secs / 60)} 分 ${secs % 60} 秒`
  return `还剩 ${secs} 秒`
}
// 任务 18：递补邀请剩余秒数。undefined = 无过期时间（不显示倒计时，
// 按钮照常可点）；null = 已过期；数字 = 剩余秒数。
function inviteSecondsLeft(exp) {
  if (!exp) return undefined
  const diff = parseShanghai(exp) - nowTick.value
  if (diff <= 0) return null
  return Math.floor(diff / 1000)
}
// 任务 2/6 返工（2026-11-09）：「变更」不再映射为「退出」。
// 退出通知自 2026-11-08 起 type 直接存「退出」（BuddyService.withdraw，
// 迁移 001 已把历史 11 行旧「变更」退出通知改写为「退出」）；
// 2026-11-09 核对：双库快照含 0 行「变更」，B 库现存 3 行「变更」
// 均为改时间通知（id 27/63/91），无旧退出行残留，兼容映射无保留必要。
// 「变更」现只用于改时间通知，展示为「时间变更」。
function notifTypeText(t) { return t === '变更' ? '时间变更' : t }
// 任务 9：深色模式切换（只用 REA v4 深色 token，见 style.css body.dark）。
function applyTheme() {
  document.body.classList.toggle('dark', darkMode.value)
  localStorage.setItem('theme', darkMode.value ? 'dark' : 'light')
}
function toggleTheme() { darkMode.value = !darkMode.value; applyTheme(); drawCapRings() }
// 任务 4：退出演示模式（恢复按钮随之隐藏，功能本身保留，?demo=1 可再开）。
function exitDemoMode() { demoMode.value = false; localStorage.removeItem('demoMode') }
// 任务 2：报名是否可点——状态是前后端唯一口径（任务 1 定方案 (a) 后，
// READY 且截止未到会在一个 sweep 周期内自动翻回 OPEN；此处只按状态 + 截止展示）。
function deadlinePassed(a) {
  if (!a || !a.deadline) return false
  return parseShanghai(a.deadline) < nowTick.value
}
function canWithdraw(a, status) {
  return a.status !== 'CANCELLED' && ['FORMAL', 'WAITING'].includes(status)
    && parseShanghai(a.start_time) > nowTick.value
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
// 任务 19：展示用的 periodWord 已统一到同一四段，不再有「中午」词。
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
// 任务 8（2026-11-10）：当日新发布判断。created_at 是东八区墙钟字符串，
// 前 10 位即东八区日期；今天的日期按 +08:00 算，与浏览器时区无关。
function shanghaiTodayStr() {
  const d = new Date(Date.now() + 8 * 3600 * 1000)
  return d.toISOString().slice(0, 10)
}
function isNewToday(a) {
  if (!a || !a.created_at) return false
  const m = String(a.created_at).match(/^(\d{4}-\d{2}-\d{2})/)
  return !!m && m[1] === shanghaiTodayStr()
}
// 任务 5：大厅卡片按钮按「我的状态」变文案。任务 21：已取消活动只写「查看详情」。
function cardButtonText(a) {
  if (a.status === 'CANCELLED') return '查看详情'
  return ['FORMAL', 'WAITING', 'INVITED'].includes(a.my_status) ? '已报名 · 查看详情' : '查看详情 / 报名'
}
// 任务 5：校区派生（地点文本含校区词即命中；三词皆无归「其他」；
// 地点含多个校区词时，筛任一命中词都列出）。
function campusOf(location) {
  const loc = location || ''
  if (loc.includes('望江')) return '望江'
  if (loc.includes('华西')) return '华西'
  if (loc.includes('江安')) return '江安'
  return '其他'
}
// 任务 5：登录页种子说明改实时数据（大厅已加载时取现况）。
// 任务 13：区分「大厅确实无活动」与「当前筛选下无活动」。
// 任务 15：数字与列表一致——列出筛选后的全部活动，不再只取前 3 个。
const seedHint = computed(() => {
  if (hallLoading.value) return '活动列表加载中…'
  if (!hall.value.length) return '当前大厅暂无活动。'
  if (!filteredHall.value.length) return '当前筛选下暂无活动。'
  const items = filteredHall.value.map(a => `${a.title}（正式 ${a.formal_count}/${a.capacity}）`)
  const scope = (q.value || timeSlot.value || category.value || campus.value) ? '当前筛选' : '当前大厅'
  return `${scope} ${filteredHall.value.length} 个活动：${items.join('；')}。`
})
const emptyHint = computed(() => {
  if (q.value) return `没有找到含「${q.value}」的活动，换个关键词试试`
  if (campus.value) return `「${campus.value}」校区暂无活动，换个校区试试`
  if (timeSlot.value) return `「${timeSlot.value}」时段暂无活动，换个时段试试`
  if (category.value) return `「${category.value}」分类下暂无活动`
  return '暂无活动，快来发布第一个吧'
})
// 任务 13（2026-11-10）：搜索空结果的「去发活动」入口，标题预填搜索词。
function goPublishWithQuery() {
  form.value.title = q.value.trim()
  view.value = 'publish'
}
// 任务 6 + 任务 12：大厅前端筛选。搜索在前端做（后端 /api/activities 的 q
// 只按标题 LIKE，改后端不在本轮前端任务内）：匹配标题 + 介绍 + 参与条件 +
// 地点（任务 4）；q 本身是「凌晨/上午/下午/晚上」时段词时，也按开始时间
// 归属匹配；再叠加时间段下拉、校区下拉筛选（任务 5，地点文本派生）。
// 类别仍走后端参数。
const filteredHall = computed(() => {
  // 任务 9（2026-11-10）：已取消活动默认不在大厅列出。
  let list = hall.value.filter(a => a.status !== 'CANCELLED')
  const needle = q.value.trim().toLowerCase()
  if (needle) {
    const periodWords = ['凌晨', '上午', '下午', '晚上']
    list = list.filter(a => {
      const hay = `${a.title || ''} ${a.description || ''} ${a.conditions || ''} ${a.location || ''}`.toLowerCase()
      if (hay.includes(needle)) return true
      if (periodWords.includes(needle)) {
        const h = startHour(a)
        return h !== null && slotOf(h) === needle
      }
      return false
    })
  }
  if (campus.value) {
    list = list.filter(a => campus.value === '其他'
      ? campusOf(a.location) === '其他'
      : (a.location || '').includes(campus.value))
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
// 折叠规则：同一活动 + 同一操作人、类型属于报名/候补/变更/退出，
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
// '变更' 在可折叠集合里：改时间通知同活动 30 分钟内可折叠，与退出不再混淆
//（2026-11-09 返工核对：双库快照 0 行「变更」，B 库现存 3 行均为改时间通知）。
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
// 任务 12：日期时间文本框解析。接受「2026-11-09 21:00」与
// 「2026-11-09T21:00」（移动端测试填 T 格式）两种写法，统一显示为空格格式
// （年-月-日 24 小时制，与下方「已选」行一致）；非法返回 null。
// 任务 1（2026-11-10）：同时接受后端 ISO 格式（如 2026-11-12T12:00:00.000+08:00，
// 带秒/毫秒/时区后缀），否则改时间预填全部落空。
// 后端 LocalDateTime.parse 只认 T 格式，提交时用 toApiDT 转回。
function parseDTInput(s) {
  if (!s) return ''
  const m = String(s).trim().match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?$/)
  if (!m) return null
  const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5])
  if (d.getFullYear() !== +m[1] || d.getMonth() !== +m[2] - 1 || d.getDate() !== +m[3]
    || d.getHours() !== +m[4] || d.getMinutes() !== +m[5]) return null
  const p = n => String(n).padStart(2, '0')
  return `${m[1]}-${p(+m[2])}-${p(+m[3])} ${p(+m[4])}:${p(+m[5])}`
}
function toApiDT(s) {
  const n = parseDTInput(s)
  return n ? n.replace(' ', 'T') : null
}
// 任务 12：失焦时把输入归一化为「年-月-日 时:分」显示格式；非法的不动，
// 提交时再报格式错误（不打断用户输入）。
function normalizeDT(target, key) {
  const obj = target === 'form' ? form.value : rescheduleForm.value
  if (!obj[key]) return
  const n = parseDTInput(obj[key])
  if (n) obj[key] = n
}
// 任务 22：标题含星期词但开始时间星期不符时的非阻塞提示（发布时再 confirm 一次）。
const WEEKDAY_WORDS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六',
  '星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
function weekdayOfTitle(title) {
  for (let i = 0; i < WEEKDAY_WORDS.length; i++) {
    if ((title || '').includes(WEEKDAY_WORDS[i])) return i % 7
  }
  return null
}
const titleWeekdayHint = computed(() => {
  const w = weekdayOfTitle(form.value.title)
  if (w === null) return ''
  const n = parseDTInput(form.value.start_time)
  if (!n) return ''
  const d = new Date(n.replace(' ', 'T'))
  if (d.getDay() === w) return ''
  return `标题写${WEEK[w]}，但开始时间是${WEEK[d.getDay()]}，确认无误吗？`
})
// 任务 12：自定义日期时间选择器（中文习惯，年-月-日 24 小时制）。
// 不用浏览器原生 datetime-local：其显示格式跟浏览器 locale 走，
// en-US 环境下是 mm/dd/yyyy + 12 小时制，与中文「已选」行永远对不上。
const pickerTitle = computed(() => {
  if (!picker.value) return ''
  return { deadline: '选择报名截止', start_time: '选择开始时间', end_time: '选择结束时间' }[picker.value.key] || '选择日期时间'
})
const pickerDays = computed(() => new Date(pickerYear.value, pickerMonth.value, 0).getDate())
const pickerBlanks = computed(() => new Date(pickerYear.value, pickerMonth.value - 1, 1).getDay())
const pickerDisplay = computed(() => {
  const p = n => String(n).padStart(2, '0')
  return `${pickerYear.value}-${p(pickerMonth.value)}-${p(pickerDay.value)} ${WEEK[new Date(pickerYear.value, pickerMonth.value - 1, pickerDay.value).getDay()]} ${periodWord(pickerHour.value)} ${p(pickerHour.value)}:${p(pickerMinute.value)}`
})
function openPicker(target, key) {
  const obj = target === 'form' ? form.value : rescheduleForm.value
  const cur = parseDTInput(obj[key])
  const base = cur ? new Date(cur.replace(' ', 'T')) : new Date()
  if (!cur) { base.setDate(base.getDate() + 1); base.setHours(18, 0, 0, 0) }
  pickerYear.value = base.getFullYear(); pickerMonth.value = base.getMonth() + 1
  pickerDay.value = base.getDate(); pickerHour.value = base.getHours(); pickerMinute.value = base.getMinutes()
  picker.value = { target, key }
}
function pickerPrevMonth() {
  if (pickerMonth.value === 1) { pickerMonth.value = 12; pickerYear.value-- }
  else pickerMonth.value--
  if (pickerDay.value > pickerDays.value) pickerDay.value = pickerDays.value
}
function pickerNextMonth() {
  if (pickerMonth.value === 12) { pickerMonth.value = 1; pickerYear.value++ }
  else pickerMonth.value++
  if (pickerDay.value > pickerDays.value) pickerDay.value = pickerDays.value
}
function confirmPicker() {
  if (!picker.value) return
  const p = n => String(n).padStart(2, '0')
  const v = `${pickerYear.value}-${p(pickerMonth.value)}-${p(pickerDay.value)} ${p(pickerHour.value)}:${p(pickerMinute.value)}`
  const obj = picker.value.target === 'form' ? form.value : rescheduleForm.value
  obj[picker.value.key] = v
  picker.value = null
}
async function login() {
  if (loginBusy.value) return
  loginBusy.value = true
  try {
    const j = await api('/api/login', 'POST', { account: account.value, password: password.value })
    token.value = j.token; localStorage.setItem('token', j.token); user.value = j.user; msg.value = '登录成功'
    // 登录页是单独一页：登录成功后进大厅（2026-11-10 游客不看内容）。
    view.value = 'hall'
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
    // 任务 14：报名提示 5 秒自动消失（与登录横幅一致）；切页时 go() 也会清除。
    const snapMsg = msg.value
    setTimeout(() => { if (msg.value === snapMsg) msg.value = '' }, 5000)
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
function clearAccountState() { mine.value = []; organized.value = []; notifs.value = []; unread.value = 0; detail.value = null; myMessages.value = []; withdrawNotice.value = null; rosterActivity.value = null; rosterError.value = '' }
function logout() { token.value = ''; localStorage.removeItem('token'); user.value = null; clearAccountState(); view.value = 'hall' }
// UI 配图（2026-11-10，用户定：用通用配图，不按分类配专用图——专用图使用场景有限）。
// 3 张通用封面按活动 id 轮换，与分类无关；横幅与空状态共用 hero。
function coverImg(id) {
  const n = ((Number(id) || 0) % 3) + 1
  return '/img/cover-' + n + '.webp'
}
// Canvas 名额环：正式/容量画成圆环，中心写数字；深色模式换配色。
// 画布属性 112（CSS 56，2x 清晰度），按 data-formal / data-cap 画。
function drawCapRings() {
  const dark = darkMode.value
  document.querySelectorAll('canvas.cap-ring').forEach(cv => {
    const formal = Number(cv.dataset.formal), cap = Number(cv.dataset.cap)
    if (!cap || Number.isNaN(formal)) return
    const ctx = cv.getContext('2d')
    const s = cv.width
    ctx.clearRect(0, 0, s, s)
    const cx = s / 2, cy = s / 2, r = s / 2 - 9
    ctx.lineWidth = 10
    ctx.strokeStyle = dark ? 'rgba(240,239,236,0.18)' : 'rgba(20,20,19,0.12)'
    ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.stroke()
    const frac = Math.min(1, formal / cap)
    if (frac > 0) {
      ctx.strokeStyle = '#D97757'
      ctx.lineCap = 'round'
      ctx.beginPath(); ctx.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * frac); ctx.stroke()
    }
    ctx.fillStyle = dark ? '#f0efec' : '#141413'
    ctx.font = '600 26px system-ui, sans-serif'
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
    ctx.fillText(formal + '/' + cap, cx, cy + 1)
  })
}
async function loadHall() {
  hallLoading.value = true; loadError.value = ''
  try {
    const params = new URLSearchParams()
    // 任务 12：搜索在前端做（见 filteredHall），不再把 q 发给后端——
    // 后端 q 只按标题 LIKE，发过去会把介绍/条件命中的活动先筛掉。
    if (category.value) params.set('category', category.value)
    hall.value = await api('/api/activities?' + params.toString())
  } catch (e) { loadError.value = friendlyErr(e); hall.value = [] } finally { hallLoading.value = false; await nextTick(); drawCapRings() }
}
async function retryHall() { loadHall() }
async function openDetail(id) {
  try {
    // 记录从哪来（大厅 / 我的活动），详情页返回时回到原地。
    if (view.value !== 'detail') detailFrom.value = view.value === 'my' ? 'my' : 'hall'
    const nextDetail = await api('/api/activities/' + id)
    if (rescheduleSource.value?.id !== nextDetail.id) rescheduleOpen.value = false
    detail.value = nextDetail
    msg.value = ''
    // 任务 17（2026-11-10）：非组织者视角拉取本人发过的留言及组织者回复；
    // 组织者走名单视图的全部留言，不拉此接口。
    if (user.value && !detail.value.roster) loadMyMessages(id); else myMessages.value = []
    // 详情是独立页（2026-11-10）：切过去并回到页顶，不再在列表里原地展开、
    // 不再跳到列表最上方——之前 scrollIntoView 就是用户说的“跳到第一个”。
    view.value = 'detail'
    await nextTick()
    window.scrollTo(0, 0)
    drawCapRings()
  } catch (e) { msg.value = friendlyErr(e) }
}
// 任务 17（2026-11-10）：GET /api/activities/{id}/messages，本人只返回自己的留言。
async function loadMyMessages(id) {
  if (!token.value) { myMessages.value = []; return }
  try { myMessages.value = await api('/api/activities/' + id + '/messages') } catch (_) { myMessages.value = [] }
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
    // 任务 14：报名提示 5 秒自动消失（与登录横幅一致）；切页时 go() 也会清除。
    const snapMsg = msg.value
    setTimeout(() => { if (msg.value === snapMsg) msg.value = '' }, 5000)
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
async function cancelActivity(id) {
  if (!confirm('确认取消该活动吗？')) return
  try {
    await api('/api/activities/' + id + '/cancel', 'POST')
    // 任务 20：取消后详情卡片立即刷新为已取消态（openDetail 会清空 msg，须在它之后设置提示）。
    if (detail.value && (detail.value.id === id || detail.value.activity_id === id)) {
      try { await openDetail(id) } catch (_) {}
    }
    msg.value = '活动已取消'
    const snapMsg = msg.value
    setTimeout(() => { if (msg.value === snapMsg) msg.value = '' }, 5000)
    loadHall()
    if (view.value === 'my') { loadMy(); loadOrganized() }
  } catch (e) { msg.value = e.message }
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
    if (form.value.title.trim() && hall.value.some(a => a.title === form.value.title.trim())) warns.push(`已有同名活动「${form.value.title.trim()}」，请确认不是重复发布`)
    if (warns.length && !confirm(warns.join('\n'))) return
    const j = await api('/api/activities', 'POST', { ...form.value, deadline, start_time: start, end_time: end })
    msg.value = '发布成功，活动编号 ' + j.id
    const snapMsg = msg.value
    setTimeout(() => { if (msg.value === snapMsg) msg.value = '' }, 5000)
    // 任务 23：发完清空表单（恢复空态，类别回默认值）。
    form.value = { title: '', category: '羽毛球', location: '', capacity: 4, deadline: '', start_time: '', end_time: '', description: '', conditions: '' }
    view.value = 'hall'; loadHall()
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
    token.value = ''; localStorage.removeItem('token'); user.value = null
    clearAccountState(); view.value = 'hall'
  } catch (e) { msg.value = friendlyErr(e) } finally { pwBusy.value = false }
}
async function resetDemo() {
  if (!confirm('确认恢复演示数据吗？当前业务数据会被清空。')) return
  try {
    await api('/api/demo/reset', 'POST')
    logout()
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
  tickTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
  // 任务 9：刷新后保持深色偏好（localStorage 'theme'）。
  applyTheme()
  // 任务 4：地址带 ?demo=1 时开启演示模式（页脚恢复入口出现），写入 localStorage。
  if (new URLSearchParams(location.search).get('demo') === '1') {
    demoMode.value = true; localStorage.setItem('demoMode', '1')
  }
  if (demoMode.value) {
    api('/api/health').then(health => { demoEnabled.value = health.demo_enabled === true }).catch(() => {})
  }
  // 未登录只见登录页，不拉大厅数据；带 token 才恢复登录并拉大厅（2026-11-10）。
  if (token.value) {
    loadHall()
    // 任务 3：刷新页面带 token 恢复登录时，未读数也要立即是当前账号的，
    // 不能等到手动点「通知」才刷。
    try { const j = await api('/api/me'); user.value = j.user; loadNotif() } catch { logout() }
  }
})
onUnmounted(() => { if (tickTimer) clearInterval(tickTimer) })
</script>
