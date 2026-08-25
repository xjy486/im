const screens = [
  // T02 · 登录 / 会话 / 联系人 / 搜索 / 设备
  { id: 'A01', label: '登录 · 账号输入', domain: 'auth', status: 'IDLE', entry: '首次打开 App', next: '登录', back: 'A01', render: renderLogin },
  { id: 'A02', label: '登录 · 凭证错误', domain: 'auth', status: 'LOGIN_FAILED', entry: '登录失败', next: '重试登录', back: 'A01', render: renderLoginError },
  { id: 'A03', label: '登录 · 设备替换确认', domain: 'auth', status: 'DEVICE_REPLACE_REQUIRED', entry: '发现同类设备', next: '确认替换', back: 'A01', render: renderDeviceReplace },
  { id: 'A04', label: '登录 · 首次同步', domain: 'auth', status: 'SYNCING', entry: '登录成功', next: '进入会话列表', back: 'A01', render: renderSync },
  { id: 'A05', label: '会话列表 · 空状态', domain: 'conversations', status: 'EMPTY', entry: '首次同步完成', next: '添加联系人', back: 'A06', render: renderSessionEmpty },
  { id: 'A06', label: '会话列表 · 正常', domain: 'conversations', status: 'READY', entry: '进入 App', next: '打开会话 / 搜索 / 加联系人', back: 'A06', render: renderSessionList },
  { id: 'A07', label: '会话列表 · 离线', domain: 'conversations', status: 'OFFLINE', entry: '网络断开', next: '读取历史 / 排队发送', back: 'A06', render: renderOffline },
  { id: 'A08', label: 'C2C · 长按消息', domain: 'conversations', status: 'MESSAGE_SELECTED', entry: '长按消息', next: '撤回 / AI / 查看来源', back: 'A06', render: renderC2CLongPress },
  { id: 'A09', label: 'C2C · 只读历史', domain: 'conversations', status: 'READ_ONLY', entry: '删除联系人', next: '返回会话列表', back: 'A06', render: renderReadonly },
  { id: 'A10', label: '联系人 · 输入账号', domain: 'contacts', status: 'IDLE', entry: '会话列表 + 添加联系人', next: '精确搜索', back: 'A06', render: renderAddContact },
  { id: 'A11', label: '联系人 · 找到用户', domain: 'contacts', status: 'FOUND', entry: '精确搜索成功', next: '发送联系人申请', back: 'A10', render: renderContactFound },
  { id: 'A12b', label: '联系人 · 未找到', domain: 'contacts', status: 'NO_RESULT', entry: '精确搜索无结果', next: '修改账号或返回', back: 'A10', render: renderContactNotFound },
  { id: 'A12', label: '搜索 · 本地消息', domain: 'contacts', status: 'SEARCH_READY', entry: '会话列表搜索', next: '打开消息结果', back: 'A06', render: renderLocalSearch },
  { id: 'A13', label: '设备管理', domain: 'auth', status: 'DEVICES_READY', entry: '设置 / 设备管理', next: '远程撤销或退出', back: 'A06', render: renderDeviceManagement },
  { id: 'A14', label: '图片消息状态', domain: 'conversations', status: 'UPLOAD_FAILED', entry: '发送图片消息', next: '重试上传或删除', back: 'A06', render: renderImageStatus },

  // T03 · 群聊治理
  { id: 'G01', label: '群发现 · PUBLIC 搜索', domain: 'groups', status: 'SEARCH_READY', entry: '会话列表 + 搜索群', next: '查看群资料', back: 'A06', render: renderPublicGroupSearch },
  { id: 'G02', label: '群发现 · UNLISTED 精确加入', domain: 'groups', status: 'EXACT_MATCH', entry: '输入完整群号', next: '查看群资料', back: 'G01', render: renderUnlistedJoin },
  { id: 'G03', label: '群发现 · PRIVATE 无结果', domain: 'groups', status: 'NO_RESULT', entry: '搜索私密群', next: '使用邀请链接', back: 'G01', render: renderPrivateNoResult },
  { id: 'G04', label: '群发现 · PRIVATE 邀请进入', domain: 'groups', status: 'INVITE_RECEIVED', entry: '收到私密群邀请', next: '接受邀请', back: 'G01', render: renderPrivateInvite },
  { id: 'G05', label: '新建群', domain: 'groups', status: 'CREATE_FORM', entry: '群发现 / 新建', next: '创建群聊', back: 'G01', render: renderCreateGroup },
  { id: 'G06', label: '群资料页', domain: 'groups', status: 'PROFILE_READY', entry: '搜索结果 / 邀请 / 创建成功', next: '申请入群或管理群', back: 'G01', render: renderGroupProfile },
  { id: 'G07', label: '入群申请 · 验证信息', domain: 'groups', status: 'REQUEST_FORM', entry: '群资料页', next: '发送申请', back: 'G06', render: renderJoinRequest },
  { id: 'G08', label: '入群申请 · 待审批', domain: 'groups', status: 'PENDING', entry: '申请已发送', next: '等待审批', back: 'G06', render: renderJoinPending },
  { id: 'G09', label: '入群申请 · 已通过', domain: 'groups', status: 'APPROVED', entry: 'OWNER / ADMIN 同意', next: '进入群聊', back: 'G06', render: renderJoinApproved },
  { id: 'G10', label: '入群申请 · 拒绝 / 过期', domain: 'groups', status: 'REJECTED', entry: '审批结束', next: '重新申请', back: 'G06', render: renderJoinRejected },
  { id: 'G11', label: '群治理 · OWNER', domain: 'groups', status: 'OWNER', entry: '群资料 / 管理入口', next: '角色、黑名单、解散', back: 'G06', render: renderOwnerGovernance },
  { id: 'G12', label: '群治理 · ADMIN', domain: 'groups', status: 'ADMIN', entry: '群资料 / 管理入口', next: '审批、移除、消息治理', back: 'G06', render: renderAdminGovernance },
  { id: 'G13', label: '群治理 · MEMBER', domain: 'groups', status: 'MEMBER', entry: '进入群聊', next: '查看与退出', back: 'G06', render: renderMemberGovernance },
  { id: 'G14', label: '黑名单 · 列表', domain: 'groups', status: 'BLOCKLIST_READY', entry: 'OWNER 管理入口', next: '查看封禁用户', back: 'G11', render: renderBlacklist },
  { id: 'G15', label: '黑名单 · 再次进入失败', domain: 'groups', status: 'BLOCKED', entry: '搜索 / 邀请 / 链接进入', next: '返回群发现', back: 'G01', render: renderBlocked },
  { id: 'G16', label: '消息治理 · MODERATED', domain: 'groups', status: 'MODERATED', entry: 'OWNER / ADMIN 长按消息', next: '返回治理工作台', back: 'G11', render: renderModerated },
  { id: 'G17', label: '群解散 · 确认', domain: 'groups', status: 'DISSOLVE_CONFIRM', entry: 'OWNER 专属入口', next: '输入群名并解散', back: 'G11', render: renderDissolveConfirm },
  { id: 'G18', label: '群解散 · 已失效', domain: 'groups', status: 'DISSOLVED', entry: '解散成功', next: '返回会话列表', back: 'A06', render: renderDissolved },

  // T04 · 私人 AI
  { id: 'AI-A01', label: 'AI 助手入口', domain: 'ai', status: 'IDLE', entry: 'C2C 会话 AI 入口', next: '打开 AI 助手', back: 'A08', render: renderChat },
  { id: 'AI-A02', label: 'C2C AI 双方同意', domain: 'ai', status: 'CONSENT_REQUIRED', entry: 'AI 助手入口', next: '发起双方同意', back: 'AI-A01', render: renderConsent },
  { id: 'AI-A03', label: '选择消息范围', domain: 'ai', status: 'IDLE', entry: '双方同意完成', next: '开始总结', back: 'AI-A02', render: renderRange },
  { id: 'AI-A04', label: 'AI 任务排队', domain: 'ai', status: 'QUEUED', entry: '提交消息范围', next: '取消或等待运行', back: 'AI-A03', render: renderQueued },
  { id: 'AI-A05', label: 'AI 任务运行中', domain: 'ai', status: 'RUNNING', entry: '队列服务', next: '等待总结完成', back: 'AI-A04', render: renderRunning },
  { id: 'AI-A06', label: 'AI 总结成功', domain: 'ai', status: 'SUCCEEDED', entry: '任务完成', next: '编辑结果 / 查看来源', back: 'AI-A05', render: renderSuccess },
  { id: 'AI-A07', label: 'AI 总结失败', domain: 'ai', status: 'FAILED', entry: '模型或服务错误', next: '重试或关闭图片能力', back: 'AI-A05', render: renderFailed },
  { id: 'AI-A08', label: 'AI 任务取消', domain: 'ai', status: 'CANCELLED', entry: '用户取消任务', next: '返回 AI 助手入口', back: 'AI-A04', render: renderCancelled },
  { id: 'AI-A09', label: 'AI 结果过期', domain: 'ai', status: 'EXPIRED', entry: '上下文发生变化', next: '重新选择消息', back: 'AI-A03', render: renderExpired },
  { id: 'AI-A10', label: 'AI 预算超限', domain: 'ai', status: 'BUDGET_EXCEEDED', entry: '图片任务预算不足', next: '关闭图片能力并重试', back: 'AI-A07', render: renderBudget },
  { id: 'AI-A11', label: '智能回复抽屉', domain: 'ai', status: 'SUCCEEDED', entry: 'C2C 会话 AI 入口', next: '编辑或发送建议', back: 'AI-A01', render: renderDrawer },
  { id: 'AI-A12', label: '编辑回复草稿', domain: 'ai', status: 'DRAFT_EDITING', entry: '智能回复建议', next: '继续确认发送', back: 'AI-A11', render: renderDraft },
  { id: 'AI-A13', label: '确认发送回复', domain: 'ai', status: 'CONFIRM_REQUIRED', entry: '编辑完成', next: '确认并发送', back: 'AI-A12', render: renderConfirmSend },
  { id: 'AI-A14', label: '待办与关键信息', domain: 'ai', status: 'SUCCEEDED', entry: 'AI 总结结果', next: '添加到待办', back: 'AI-A06', render: renderTodo },
  { id: 'AI-A15', label: '来源消息', domain: 'ai', status: 'SUCCEEDED', entry: 'AI 总结结果', next: '返回会话', back: 'AI-A06', render: renderSources },
  { id: 'AI-A16', label: '多模态开关', domain: 'ai', status: 'MULTIMODAL_ON', entry: 'AI 设置', next: '关闭图片理解', back: 'AI-A06', render: renderMultimodal },
  { id: 'AI-A17', label: '图片能力不可用', domain: 'ai', status: 'MODEL_UNSUPPORTED', entry: '模型能力检查', next: '仅使用文本重试', back: 'AI-A07', render: renderUnsupported },
  { id: 'AI-A18', label: '多模态安全降级', domain: 'ai', status: 'TEXT_ONLY_FALLBACK', entry: '关闭多模态', next: '继续文本总结', back: 'AI-A16', render: renderFallback },
  { id: 'AI-A19', label: '待办已保存', domain: 'ai', status: 'TODO_SAVED', entry: 'AI 待办结果', next: '查看同步状态', back: 'AI-A14', render: renderTodoSaved },
  { id: 'AI-A20', label: '设备同步状态', domain: 'ai', status: 'SYNCING', entry: '私人 AI 结果保存', next: '完成同步', back: 'AI-A19', render: renderDeviceSync },
];

const domains = [
  { id: 'all', label: '全部' },
  { id: 'auth', label: '登录 / 设备' },
  { id: 'conversations', label: '会话 / C2C' },
  { id: 'contacts', label: '联系人 / 搜索' },
  { id: 'groups', label: '群聊治理' },
  { id: 'ai', label: '私人 AI' },
];

const state = {
  screenId: 'A01',
  domain: 'all',
  loginAccount: '138 0013 8000',
  draft: '好的，我明天上午准备好材料，辛苦林晚啦！',
  contactQuery: '138 0013 8001',
  groupQuery: '设计评审群',
  groupName: '设计评审群',
  role: 'OWNER',
  groupAi: true,
  multimodal: true,
  drawerOpen: false,
  todo: { dinner: false, proposal: false },
  toastTimer: null,
};

const phone = document.querySelector('#phone-screen');
const flowList = document.querySelector('#flow-list');
const domainTabs = document.querySelector('#domain-tabs');
const counter = document.querySelector('#screen-counter');
const statusBadge = document.querySelector('#status-badge');
const summary = document.querySelector('#state-summary');
const entry = document.querySelector('#screen-entry');
const next = document.querySelector('#screen-next');
const toast = document.querySelector('#toast');

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#039;', '"': '&quot;' }[char]));
}
function currentScreen() { return screens.find((screen) => screen.id === state.screenId) || screens[0]; }
function go(id) { if (screens.some((screen) => screen.id === id)) { state.screenId = id; state.drawerOpen = id === 'AI-A11'; render(); } }
function showToast(message) { toast.textContent = message; toast.classList.add('is-visible'); clearTimeout(state.toastTimer); state.toastTimer = setTimeout(() => toast.classList.remove('is-visible'), 2200); }
function primary(label, action, extra = '') { return `<button class="primary-button ${extra}" data-action="${action}">${label}</button>`; }
function ghost(label, action, extra = '') { return `<button class="ghost-button ${extra}" data-action="${action}">${label}</button>`; }
function phoneStatus() { return `<div class="mobile-status"><span>9:41</span><span>MOBILE</span></div>`; }
function mobileHeader(title, right = '•••') { return `<header class="mobile-header"><button class="icon-button" data-action="back" aria-label="返回">‹</button><h2>${title}</h2><button class="icon-button" data-action="noop" aria-label="更多">${right}</button></header>`; }
function stateCard(status, body, tone = '') { return `<div class="surface-card ${tone}"><p class="status-line">${status}</p><p class="mobile-copy">${body}</p></div>`; }
function standardScreen(title, copy, body, actions = '') { return `${phoneStatus()}${mobileHeader(title, '×')}<section class="mobile-body"><p class="mobile-copy">${copy}</p>${body}${actions}</section>`; }
function authCard(title, copy, body, actions = '') { return `<div class="auth-screen">${phoneStatus()}<section class="auth-card"><div class="auth-mark">即</div><p class="eyebrow">JI TONG IM</p><h2>${title}</h2><p class="mobile-copy">${copy}</p>${body}${actions}</section></div>`; }
function appTabBar(active = '会话') { return `<nav class="app-tabbar"><button class="app-tab ${active === '会话' ? 'is-active' : ''}" data-action="session-list">会话</button><button class="app-tab ${active === '联系人' ? 'is-active' : ''}" data-action="contacts">联系人</button><button class="app-tab ${active === '群组' ? 'is-active' : ''}" data-action="groups">群组</button><button class="app-tab ${active === '设置' ? 'is-active' : ''}" data-action="devices">设置</button></nav>`; }
function sessionRow(name, preview, meta, action = 'c2c') { return `<button class="session-row" data-action="${action}"><div class="session-avatar">${name.slice(0, 1)}</div><div class="session-main"><div class="session-title"><strong>${name}</strong><span>${meta}</span></div><p>${preview}</p></div><span class="session-arrow">›</span></button>`; }
function groupCard(title, visibility, detail, action) { return `<button class="group-card" data-action="${action}"><div class="group-card-head"><strong>${title}</strong><span class="visibility-chip">${visibility}</span></div><p>${detail}</p><span class="group-card-link">查看群资料 〉</span></button>`; }
function actionGrid(actions) { return `<div class="action-grid">${actions.map(([label, action, style = 'ghost']) => `<button class="${style === 'primary' ? 'primary-button' : 'ghost-button'}" data-action="${action}">${label}</button>`).join('')}</div>`; }

function renderLogin() { return authCard('登录即通', '使用 11 位账号登录。账号是你的唯一身份标识，登录后会同步会话与设备状态。', `<div class="form-field"><label>即通账号</label><div class="fake-input">${state.loginAccount}</div></div><div class="form-field"><label for="login-password">密码</label><input id="login-password" class="textarea" style="min-height:44px;resize:none" type="password" value="123456" /></div>`, primary('登录', 'login')); }
function renderLoginError() { return authCard('登录失败', '账号或密码不正确。请检查后重试，原有设备不会受到影响。', stateCard('LOGIN_FAILED', '连续失败会触发安全限流。', 'error'), `<div class="button-row">${primary('重试登录', 'retry-login')}${ghost('返回账号输入', 'login-form')}</div>`); }
function renderDeviceReplace() { return authCard('发现同类设备', '检测到已有一台 Android 设备在线。确认替换后，旧设备会被撤销，历史消息仍保留。', `<div class="surface-card pending"><p><strong>旧设备</strong>　Android · 昨天 21:44 在线</p><p class="mobile-copy">本机登录后，旧设备将无法继续发送消息。</p></div>`, `<div class="button-row">${primary('确认替换', 'replace-device')}${ghost('取消登录', 'login-form')}</div>`); }
function renderSync() { return authCard('正在同步会话', '登录成功。正在恢复本地历史与会话索引，完成后会进入会话列表。', `<div class="surface-card"><p class="status-line">SYNCING · 68%</p><div class="progress-track"><span style="width:68%"></span></div><p class="mobile-copy">正在同步联系人、群聊和未发送消息。</p></div>`, primary('进入会话列表', 'session-list')); }
function renderSessionEmpty() { return `${phoneStatus()}${mobileHeader('即通', '+')}<section class="mobile-body empty-body"><div class="empty-illustration">✦</div><h2>还没有会话</h2><p class="mobile-copy">添加联系人或发现一个群组后，即可开始第一段对话。</p>${actionGrid([['添加联系人', 'contacts', 'primary'], ['发现群组', 'groups']])}</section>${appTabBar('会话')}`; }
function renderSessionList() { return `<div class="app-screen">${phoneStatus()}${mobileHeader('即通', '+')}<section class="mobile-body session-page"><div class="session-toolbar"><h2>会话</h2><button class="mini-button" data-action="contacts">添加联系人</button></div><div class="search-pill" data-action="search">⌕　搜索联系人、消息或群</div><div class="offline-banner"><span>●</span> 2 条消息待发送，将在网络恢复后自动发送 <button data-action="offline">查看</button></div><div class="session-list">${sessionRow('林晚', '收到！我看一下，谢谢林晚', '21:01', 'c2c')}${sessionRow('设计评审群', '林晚：评审材料已整理并分享', '昨天', 'group-profile')}${sessionRow('产品讨论', '你：图片消息上传失败，点击重试', '周一', 'image-status')}</div></section>${appTabBar('会话')}</div>`; }
function renderOffline() { return `<div class="app-screen">${phoneStatus()}${mobileHeader('会话 · 离线', '⋯')}<section class="mobile-body session-page"><div class="offline-banner strong"><span>●</span> 当前离线：历史可读可搜，新的消息会排队</div><div class="session-list">${sessionRow('林晚', '离线草稿：明早见，消息将在恢复后发送', '排队中', 'c2c')}${sessionRow('设计评审群', '历史消息可查看，输入框暂时禁用', '只读', 'readonly')}</div><div class="surface-card offline"><p class="status-line">OFFLINE</p><p class="mobile-copy">不影响本地历史和搜索。网络恢复后，排队消息会按顺序发送。</p></div></section>${appTabBar('会话')}</div>`; }
function renderC2CLongPress() { return `<div class="chat-screen">${phoneStatus()}${mobileHeader('林晚  在线')}<section class="chat-body"><div class="chat-time">今天 21:00</div><div class="chat-row"><div class="avatar">林</div><div class="chat-content"><span class="chat-name">林晚</span><div class="chat-bubble">明天上午的评审材料我整理好了，发你一份～</div></div></div><div class="chat-mine selected-message">收到！我看一下，谢谢林晚</div><div class="message-actions"><button data-action="recall">撤回</button><button data-action="open-ai">AI 助手</button><button data-action="sources">查看来源</button></div></section><footer class="chat-toolbar"><button class="ai-launch" data-action="open-ai">✦</button><div class="chat-input">长按消息后显示操作</div><button class="send-button" data-action="noop">↑</button></footer></div>`; }
function renderReadonly() { return `<div class="chat-screen readonly-screen">${phoneStatus()}${mobileHeader('林晚  ·  只读')}<section class="chat-body"><div class="readonly-banner"><strong>会话只读</strong><span>联系人已删除，历史消息仍保留</span></div><div class="chat-row"><div class="avatar">林</div><div class="chat-content"><span class="chat-name">林晚</span><div class="chat-bubble">明天的评审材料我整理好了，发你一份～</div></div></div><div class="chat-mine">收到！我看一下，谢谢林晚</div></section><footer class="chat-toolbar disabled"><div class="chat-input">无法发送消息</div></footer></div>`; }
function renderAddContact() { return standardScreen('添加联系人', '使用完整 11 位账号进行精确搜索。无结果不代表网络错误。', `<div class="form-field"><label for="contact-query">账号</label><input id="contact-query" class="textarea" style="min-height:44px;resize:none" value="${escapeHtml(state.contactQuery)}" /></div><p class="mono">示例：138 0013 8001</p>`, primary('精确搜索', 'search-contact')); }
function renderContactFound() { return standardScreen('找到用户', '确认后可发送联系人申请。对方同意后才能开始 C2C 会话。', `<div class="contact-profile"><div class="avatar large">周</div><div><h3>周航</h3><p class="mobile-copy">账号 138 0013 8001</p><span class="status-dot">允许被搜索</span></div></div>`, `<div class="button-row">${primary('发送申请', 'send-contact')}${ghost('修改搜索', 'contacts')}</div>`); }
function renderContactNotFound() { return standardScreen('没有找到用户', '账号不存在或对方关闭了搜索。请确认账号是否完整。', stateCard('NO_RESULT', '无结果 ≠ 网络错误。', 'offline'), `<div class="button-row">${primary('重新输入', 'contacts')}${ghost('返回会话列表', 'session-list')}</div>`); }
function renderLocalSearch() { return standardScreen('搜索本地消息', '搜索只作用于本机已同步的有效消息；已撤回消息不可搜索。', `<div class="search-pill large">⌕　评审材料</div><div class="search-results"><button data-action="c2c"><strong>林晚</strong><span>明天的<strong>评审材料</strong>我整理好了</span><small>今天 21:00</small></button><button data-action="group-profile"><strong>设计评审群</strong><span>评审材料已经放到群相册</span><small>昨天 18:20</small></button></div>`, ghost('返回会话列表', 'session-list')); }
function renderDeviceManagement() { return standardScreen('设备管理', '管理当前账号的活动设备、远程撤销和正常退出。', `<div class="device-list"><div class="device-row"><div><strong>本机 Android</strong><p>当前设备 · 最近活动</p></div><span class="status-dot">在线</span></div><div class="device-row"><div><strong>macOS Desktop</strong><p>上次活动 · 2 分钟前</p></div><button class="mini-button" data-action="revoke-device">远程撤销</button></div><div class="device-row muted"><div><strong>旧 Android</strong><p>已撤销 · 历史保留</p></div><span>已失效</span></div></div>`, `<div class="button-row">${ghost('正常退出', 'logout')}${primary('管理登录设备', 'replace-device')}</div>`); }
function renderImageStatus() { return standardScreen('图片消息状态', '图片上传与消息发送是两组独立状态。失败时可以单独重试。', `<div class="image-status-card"><div class="image-placeholder">图片</div><div><p class="status-line">UPLOAD_FAILED</p><p class="mobile-copy">图片上传失败，消息尚未发送。</p></div></div>`, `<div class="button-row">${primary('重试上传', 'image-retry')}${ghost('删除图片', 'session-list')}</div>`); }

function renderPublicGroupSearch() { return standardScreen('发现群组', 'PUBLIC 群可以按名称、简介和群号搜索。UNLISTED 只能精确输入群号。', `<div class="search-pill large">⌕　${escapeHtml(state.groupQuery)}</div>${groupCard('设计评审群', 'PUBLIC', '群号 138 0013 8200 · 成员 19 / 100\n明天的项目评审与设计协作', 'group-profile')}${groupCard('技术分享会', 'PUBLIC', '群号 138 0022 1100 · 成员 72 / 100\n每周分享工程实践', 'group-profile')}`, actionGrid([['精确输入群号', 'unlisted'], ['新建群', 'create-group'], ['私密群无结果', 'private-no-result']])); }
function renderUnlistedJoin() { return standardScreen('精确加入群组', 'UNLISTED 群不会出现在搜索结果中，只有完整群号才能进入资料页。', `<div class="surface-card"><p class="status-line">EXACT_MATCH</p><p><strong>设计评审群</strong><br>群号 138 0013 8200 · 成员 19 / 100</p><p class="mobile-copy">需要群主审批后才能加入。</p></div>`, primary('查看群资料', 'group-profile')); }
function renderPrivateNoResult() { return standardScreen('没有找到私密群', 'PRIVATE 群不会出现在搜索结果。请使用群主发来的邀请链接。', stateCard('PRIVATE · NO_RESULT', '搜索无结果是设计约束，不代表群不存在。', 'offline'), `<div class="button-row">${primary('输入邀请链接', 'private-invite')}${ghost('返回发现', 'groups')}</div>`); }
function renderPrivateInvite() { return standardScreen('收到群邀请', '这是一个 PRIVATE 群，只有受邀成员才能进入。', `<div class="group-invite"><div class="group-avatar">设</div><div><h3>设计评审群</h3><p>许舟邀请你加入 · 成员 19 / 100</p><span class="visibility-chip">PRIVATE</span></div></div>`, `<div class="button-row">${primary('接受邀请并加入', 'group-profile')}${ghost('拒绝邀请', 'groups')}</div>`); }
function renderCreateGroup() { return standardScreen('新建群', '创建后可在群资料页管理可见性、成员上限和群角色。', `<div class="form-field"><label>群名称</label><div class="fake-input">设计评审群</div></div><div class="form-field"><label>群简介</label><div class="fake-input">明天的项目评审与设计协作</div></div><div class="surface-card"><p><strong>可见性</strong></p><p class="mobile-copy">PUBLIC · 可搜索<br>成员上限 · 100 人</p></div>`, primary('创建群聊', 'create-group-success')); }
function renderGroupProfile() { return standardScreen('设计评审群', '群号 138 0013 8200 · 成员 19 / 100', `<div class="group-profile"><div class="group-avatar large">设</div><h3>设计评审群</h3><span class="visibility-chip">PUBLIC</span><p>明天的项目评审与设计协作。入群申请需要 OWNER / ADMIN 审批。</p></div>`, actionGrid([['申请加入', 'join-request', 'primary'], ['群治理', 'owner-governance'], ['返回发现', 'groups']])); }
function renderJoinRequest() { return standardScreen('申请加入群组', '填写验证信息后提交申请。审批结果会同步到你的 Android / macOS 设备。', `<div class="form-field"><label>验证信息</label><div class="fake-input">我是周航，参加明天的项目评审</div></div><div class="surface-card"><p class="mono">申请状态：IDLE</p><p class="mobile-copy">普通成员不能直接进入，需要等待审批。</p></div>`, primary('发送入群申请', 'send-join-request')); }
function renderJoinPending() { return standardScreen('入群申请待审批', '申请已经发送给群 OWNER / ADMIN。你可以离开页面，状态会在审批后同步。', stateCard('PENDING', '申请验证信息：我是周航，参加明天的项目评审', 'pending'), `<div class="button-row">${ghost('模拟审批通过', 'approve-join')}${primary('返回群资料', 'group-profile')}</div>`); }
function renderJoinApproved() { return standardScreen('入群申请已通过', '你已加入设计评审群，现在可以查看历史消息并参与讨论。', stateCard('APPROVED', '群号 138 0013 8200 · 成员 20 / 100', 'success'), primary('进入群聊', 'group-member')); }
function renderJoinRejected() { return standardScreen('申请未通过', '本次申请已被拒绝或已过期。你可以修改验证信息后重新申请。', stateCard('REJECTED · EXPIRED', '群不会泄露拒绝原因以外的内部治理信息。', 'error'), primary('重新申请', 'join-request')); }
function renderOwnerGovernance() { return standardScreen('群治理 · OWNER', '群主拥有完整管理能力：角色、黑名单、消息治理与解散。', `<div class="role-grid"><div><strong>OWNER</strong><span>许舟 · 当前群主</span></div><div><strong>ADMIN</strong><span>林婉 · 管理员</span></div><div><strong>MEMBER</strong><span>周航 / 陈默 / 顾北</span></div></div>${state.groupAi ? stateCard('GROUP_AI_ON', '群 AI 已开启，成员会看到系统状态事件。', 'success') : stateCard('GROUP_AI_DISABLED', '群 AI 已关闭，未完成任务失效。', 'offline')}`, actionGrid([['成员与角色', 'admin-governance'], ['黑名单列表', 'blacklist'], ['消息治理', 'moderated'], ['解散群聊', 'dissolve'], [state.groupAi ? '关闭群 AI' : '开启群 AI', 'toggle-group-ai', state.groupAi ? 'ghost' : 'primary']])); }
function renderAdminGovernance() { return standardScreen('群治理 · ADMIN', 'ADMIN 可以审批入群、邀请成员、移除普通成员和管理移除消息，但不能管理 OWNER 或其他 ADMIN。', `<div class="surface-card"><p><strong>当前账号：林婉（ADMIN）</strong></p><p class="mobile-copy">可见：审批、邀请、移除普通成员、管理移除消息<br>不可见：转让群主、解散群聊、管理 OWNER</p></div>`, actionGrid([['消息治理', 'moderated', 'primary'], ['普通移除', 'member-governance'], ['返回群资料', 'group-profile']])); }
function renderMemberGovernance() { return standardScreen('群治理 · MEMBER', '普通成员可以查看群资料、发言和退出，不显示管理入口。', `<div class="surface-card"><p class="status-line">MEMBER</p><p class="mobile-copy">你可以查看群成员和历史消息，但不能审批、移除成员或管理消息。</p></div>`, actionGrid([['查看群资料', 'group-profile'], ['退出群聊', 'groups']])); }
function renderBlacklist() { return standardScreen('黑名单列表', '加入黑名单与普通移除不同。被拉黑用户无法再次申请、被邀请或通过链接进入。', `<div class="device-list"><div class="device-row"><div><strong>顾北</strong><p>因多次违规被加入黑名单</p></div><span class="status-dot danger">BLOCKED</span></div><div class="device-row"><div><strong>暂无更多成员</strong><p>黑名单事件会同步至所有设备</p></div></div></div>`, actionGrid([['模拟再次搜索失败', 'blocked', 'primary'], ['返回群治理', 'owner-governance']])); }
function renderBlocked() { return standardScreen('无法加入群组', '该用户已被加入黑名单。搜索、邀请和链接进入统一返回失败提示。', stateCard('BLOCKED', '你无法再次申请或通过邀请进入设计评审群。', 'error'), primary('返回群发现', 'groups')); }
function renderModerated() { return standardScreen('消息已被管理移除', 'OWNER / ADMIN 的治理结果会同步到所有客户端，普通 MEMBER 看不到管理移除入口。', `<div class="moderated-tombstone"><span>◌</span><strong>消息已被管理员移除</strong><code>MODERATED</code></div><p class="mobile-copy">治理事件已同步至所有成员设备。</p>`, actionGrid([['返回群治理', 'owner-governance'], ['切换 MEMBER 视图', 'member-governance']])); }
function renderDissolveConfirm() { return standardScreen('解散设计评审群？', '解散后所有成员无法继续发送消息，历史消息不可再访问，群号永久失效。', stateCard('DISSOLVE_CONFIRM', '请输入群名“设计评审群”确认解散。', 'error'), `<div class="form-field"><label>群名确认</label><input id="dissolve-name" class="textarea" style="min-height:44px;resize:none" value="设计评审群" /></div>${primary('确认解散群聊', 'dissolve-success')}`); }
function renderDissolved() { return standardScreen('群已解散', '设计评审群已于 14:32 解散。所有成员已退出，历史消息不可再访问。', stateCard('DISSOLVED · READ_ONLY', '会话失效，输入框已禁用，群号不会再次使用。', 'offline'), primary('返回会话列表', 'session-list')); }

function renderChat() {
  state.drawerOpen = currentScreen().id === 'AI-A11';
  const base = `<div class="chat-screen">${phoneStatus()}${mobileHeader('林晚  在线')}<section class="chat-body"><div class="chat-time">今天 21:00</div><div class="chat-row"><div class="avatar">林</div><div class="chat-content"><span class="chat-name">林晚</span><div class="chat-bubble">在吗？明天的评审材料我整理好了，发你一份～</div></div></div><div class="chat-mine">收到！我看一下，谢谢林晚</div></section><footer class="chat-toolbar"><button class="ai-launch" data-action="open-ai">✦</button><div class="chat-input">发送消息…</div><button class="send-button" data-action="noop">↑</button></footer>`;
  if (!state.drawerOpen) return `${base}</div>`;
  return `${base}<div class="chat-overlay"><div class="ai-drawer"><div class="drawer-handle"></div><div class="drawer-header"><span class="online-dot">●</span><h2>AI 助手</h2><button class="icon-button" data-action="close-drawer">×</button></div><div class="drawer-card"><h3>会话摘要</h3><p>1　林晚整理了明天评审材料并分享给你<br>2　项目评审时间改为明天上午<br>3　聚餐合照已放入群相册</p><button class="ghost-button" data-action="sources">查看原文 〉</button></div><h3 class="drawer-section-title">智能回复建议</h3><div class="suggestion"><p>${escapeHtml(state.draft)}</p><button class="mini-button" data-action="draft">编辑</button></div></div></div></div>`;
}
function renderConsent() { return standardScreen('AI 助手', '林晚也同意后，AI 才能读取双方选定的消息。AI 不会自动加入会话，也不会代替你发言。', stateCard('CONSENT_REQUIRED', '仅分析你和林晚共同选择的消息范围。RECALLED / MODERATED 不会进入上下文。', 'pending'), primary('发起双方同意', 'consent')); }
function renderRange() { return standardScreen('选择消息范围', '选择 AI 可以读取的范围，默认只包含最近 20 条有效消息。', `<div class="surface-card pending"><div class="stack-list"><label><input type="radio" name="range" checked> 最近 20 条有效消息</label><label><input type="radio" name="range"> 今天 00:00 之后</label><label><input type="radio" name="range"> 仅选择的 5 条消息</label></div><p class="mono">已过滤：1 条 RECALLED，1 条 MODERATED</p></div>`, primary('开始总结', 'start-summary')); }
function renderQueued() { return standardScreen('AI 任务排队中', '你的总结任务已创建，等待服务处理。', stateCard('QUEUED', '前面还有 2 个任务。任务属于你本人，可在其他设备继续查看。', 'pending'), `<div class="button-row">${ghost('取消任务', 'cancel')}${primary('等待运行', 'running')}</div>`); }
function renderRunning() { return standardScreen('AI 正在整理对话', '正在提取重点、时间和待办事项。你可以离开此页，任务会继续运行。', stateCard('RUNNING  ·  已处理 68%', '识别到 3 个主题，正在生成摘要…', 'pending'), primary('查看进度', 'success')); }
function renderSuccess() { return standardScreen('AI 总结完成', '以下内容来自你选择的有效消息，可继续编辑后保存。', `<div class="surface-card"><ol class="stack-list"><li>明天上午进行项目评审</li><li>林晚已整理并分享评审材料</li><li>聚餐合照已放入群相册</li></ol><button class="ghost-button" data-action="sources">查看来源消息 〉</button></div>`, `<div class="button-row">${primary('编辑结果', 'draft')}${ghost('查看待办', 'todo')}</div>`); }
function renderFailed() { return standardScreen('AI 总结失败', '当前服务暂时不可用，原消息不会受到影响。', stateCard('FAILED  ·  MODEL_UNSUPPORTED', '可关闭图片能力后重试，或仅使用文本消息。', 'error'), `<div class="button-row">${primary('重试', 'retry')}${ghost('关闭图片能力', 'multimodal-off')}</div>`); }
function renderCancelled() { return standardScreen('任务已取消', '本次 AI 任务已取消，不会继续消耗预算。', stateCard('CANCELLED', '不会继续消耗预算，原消息保持不变。', 'offline'), primary('返回 AI 助手', 'home-ai')); }
function renderExpired() { return standardScreen('结果已过期', '原消息范围发生变化，请重新选择有效消息后再生成。', stateCard('EXPIRED  ·  CONTEXT_CHANGED', 'AI 结果已失效，避免使用过期上下文。', 'pending'), primary('重新选择消息', 'range')); }
function renderBudget() { return standardScreen('本月 AI 预算不足', '本月剩余预算不足以完成这次图片分析。关闭多模态后可继续使用文本总结。', stateCard('BUDGET_EXCEEDED', '本月剩余 2% · 图片任务已暂停。', 'pending'), primary('关闭图片能力并重试', 'multimodal-off')); }
function renderDrawer() { return renderChat(); }
function renderDraft() { return standardScreen('编辑智能回复', 'AI 只提供草稿。发送前请确认内容、语气和收件人。', `<div class="form-field"><label for="draft-input">回复草稿</label><textarea id="draft-input" class="textarea">${escapeHtml(state.draft)}</textarea><span class="mono">已编辑 · ${state.draft.length} 字</span></div>`, primary('继续确认发送', 'confirm-send')); }
function renderConfirmSend() { return standardScreen('确认发送回复', '将向林晚发送以下内容。发送后会成为真实会话消息。', `<div class="surface-card"><p class="mono">收件人 · 林晚</p><p>${escapeHtml(state.draft)}</p></div>`, `<div class="button-row">${primary('确认并发送', 'send-reply')}${ghost('返回编辑', 'draft')}</div>`); }
function renderTodo() { return standardScreen('待办与关键信息', '从会话摘要中提取可执行事项，结果仅保存到你的私人空间。', `<div class="surface-card"><h3>待办</h3><ul class="todo-list"><li><input type="checkbox" data-todo="dinner" ${state.todo.dinner ? 'checked' : ''}> 明晚聚餐</li><li><input type="checkbox" data-todo="proposal" ${state.todo.proposal ? 'checked' : ''}> 查看林晚发来的方案</li></ul><hr><p class="mono">关键信息</p><p>明天上午进行项目评审</p></div>`, `<div class="button-row">${ghost('添加到待办', 'save-todo')}${primary('返回摘要', 'success')}</div>`); }
function renderSources() { return standardScreen('来源消息', '以下消息构成当前 AI 结果的上下文。已撤回和已管理移除消息不会显示。', `<div class="surface-card source-list"><div class="source-item"><p class="source-author">林晚 · 21:00</p><p class="source-text">明天的评审材料我整理好了，发你一份～</p></div><div class="source-item"><p class="source-author">你 · 21:01</p><p class="source-text">收到！我看一下，谢谢林晚</p></div><p class="mono">已过滤 · RECALLED / MODERATED</p></div>`, ghost('返回会话', 'home')); }
function renderMultimodal() { return standardScreen('多模态设置', '控制 AI 是否读取会话中的图片。关闭后，图片只显示占位提示。', `<div class="surface-card"><p>图片理解　·　${state.multimodal ? '已开启' : '已关闭'}<br>模型支持　·　文本 / 图片<br>范围限制　·　只读取已授权消息</p></div>`, state.multimodal ? ghost('关闭图片理解', 'multimodal-off') : primary('开启图片理解', 'multimodal-on')); }
function renderUnsupported() { return standardScreen('图片能力不可用', '当前模型不支持图片输入。原图片不会上传，AI 将安全降级为文本模式。', stateCard('MODEL_UNSUPPORTED', '图片消息显示为“图片内容未参与分析”。', 'error'), primary('仅使用文本重试', 'fallback')); }
function renderFallback() { return standardScreen('已安全降级为文本模式', '多模态已关闭，AI 只读取文本消息。图片仍保留在原会话中，不会进入 AI 上下文。', stateCard('TEXT_ONLY_FALLBACK  ·  SUCCEEDED', '[图片内容未参与分析]', 'success'), primary('继续文本总结', 'success')); }
function renderTodoSaved() { return standardScreen('待办已保存', '从 AI 结果添加的待办已保存到你的私人空间，并同步到已登录设备。', stateCard('TODO_SAVED  ·  SYNC_QUEUED', '待办会进入跨设备同步队列。', 'success'), primary('查看同步状态', 'device-sync')); }
function renderDeviceSync() { return standardScreen('正在同步到其他设备', 'AI 结果和待办属于你本人，会同步到已登录的 Android / macOS 设备。', stateCard('SYNCING  ·  Android ✓  ·  macOS …', '同步不会把私人 AI 结果写入会话成员可见范围。', 'pending'), primary('完成同步', 'sync-done')); }

function renderDomainTabs() {
  domainTabs.innerHTML = domains.map((domain) => `<button class="domain-tab ${state.domain === domain.id ? 'is-active' : ''}" data-domain="${domain.id}">${domain.label}</button>`).join('');
}
function filteredScreens() { return state.domain === 'all' ? screens : screens.filter((screen) => screen.domain === state.domain); }
function renderFlowList() {
  flowList.innerHTML = filteredScreens().map((screen) => `<button class="flow-item ${screen.id === state.screenId ? 'is-active' : ''}" data-screen="${screen.id}"><span class="flow-id">${screen.id}</span><span class="flow-label">${screen.label}</span></button>`).join('');
}
function renderInspector() {
  const screen = currentScreen();
  const domain = domains.find((item) => item.id === screen.domain);
  counter.textContent = `${screens.findIndex((item) => item.id === screen.id) + 1} / ${screens.length}`;
  statusBadge.textContent = screen.status;
  entry.textContent = screen.entry;
  next.textContent = screen.next;
  summary.innerHTML = `<div class="card-kicker">当前页面</div><p><strong>${screen.id}</strong> · ${screen.label}</p><div class="card-kicker" style="margin-top:14px">产品域</div><p>${domain?.label || '完整 App'}</p><div class="card-kicker" style="margin-top:14px">原型规则</div><p>${screen.domain === 'ai' ? 'AI 不自动发言；回复必须编辑或确认后发送。' : '页面状态由本地 Mock 驱动，不调用真实后端。'}</p>`;
}
function render() {
  phone.innerHTML = currentScreen().render();
  renderDomainTabs();
  renderFlowList();
  renderInspector();
}

function handleAction(action) {
  const actions = {
    previous: () => { const index = screens.findIndex((screen) => screen.id === state.screenId); go(screens[Math.max(0, index - 1)].id); },
    next: () => { const index = screens.findIndex((screen) => screen.id === state.screenId); go(screens[Math.min(screens.length - 1, index + 1)].id); },
    reset: () => { Object.assign(state, { screenId: 'A01', domain: 'all', draft: '好的，我明天上午准备好材料，辛苦林晚啦！', contactQuery: '138 0013 8001', groupAi: true, multimodal: true, todo: { dinner: false, proposal: false } }); showToast('完整 App 原型已重置'); render(); },
    back: () => go(currentScreen().back || 'A06'),
    noop: () => {},
    'login-form': () => go('A01'), login: () => go('A03'), 'retry-login': () => go('A01'), 'replace-device': () => go('A04'),
    'session-list': () => go('A06'), 'session-empty': () => go('A05'), empty: () => go('A05'), offline: () => go('A07'), c2c: () => go('A08'), readonly: () => go('A09'),
    contacts: () => go('A10'), search: () => go('A12'), devices: () => go('A13'), 'image-status': () => go('A14'),
    'search-contact': () => { const input = document.querySelector('#contact-query'); if (input) state.contactQuery = input.value.trim(); go(state.contactQuery ? 'A11' : 'A12b'); },
    'send-contact': () => { showToast('已模拟发送联系人申请'); go('A06'); }, 'revoke-device': () => showToast('已模拟远程撤销 macOS 设备'), logout: () => go('A01'), 'image-retry': () => showToast('已模拟重新上传图片'),
    groups: () => go('G01'), unlisted: () => go('G02'), 'private-no-result': () => go('G03'), 'private-invite': () => go('G04'), 'create-group': () => go('G05'), 'create-group-success': () => { showToast('群聊创建成功'); go('G06'); }, 'group-profile': () => go('G06'), 'join-request': () => go('G07'), 'send-join-request': () => go('G08'), 'approve-join': () => go('G09'), 'group-member': () => go('G13'), 'owner-governance': () => go('G11'), 'admin-governance': () => go('G12'), 'member-governance': () => go('G13'), blacklist: () => go('G14'), blocked: () => go('G15'), moderated: () => go('G16'), dissolve: () => go('G17'), 'dissolve-success': () => { showToast('群聊已解散'); go('G18'); }, 'toggle-group-ai': () => { state.groupAi = !state.groupAi; showToast(state.groupAi ? '群 AI 已开启' : '群 AI 已关闭'); go('G11'); },
    'open-ai': () => go('AI-A11'), 'close-drawer': () => go('AI-A01'), 'home-ai': () => go('AI-A01'), consent: () => { showToast('已模拟发起双方同意'); go('AI-A03'); }, 'start-summary': () => go('AI-A04'), running: () => go('AI-A05'), success: () => go('AI-A06'), cancel: () => go('AI-A08'), retry: () => go('AI-A05'), 'multimodal-off': () => { state.multimodal = false; showToast('已关闭图片理解'); go('AI-A18'); }, 'multimodal-on': () => { state.multimodal = true; go('AI-A16'); }, draft: () => go('AI-A12'), 'confirm-send': () => { const input = document.querySelector('#draft-input'); if (input) state.draft = input.value.trim() || state.draft; go('AI-A13'); }, 'send-reply': () => { showToast('已模拟发送回复'); go('A08'); }, todo: () => go('AI-A14'), 'save-todo': () => { showToast('待办已保存并进入同步队列'); go('AI-A19'); }, sources: () => go('AI-A15'), range: () => go('AI-A03'), fallback: () => { state.multimodal = false; go('AI-A18'); }, 'device-sync': () => go('AI-A20'), 'sync-done': () => { showToast('Android / macOS 已完成同步'); go('AI-A06'); },
  };
  if (actions[action]) actions[action]();
}

document.addEventListener('click', (event) => {
  const screenButton = event.target.closest('[data-screen]');
  if (screenButton) return go(screenButton.dataset.screen);
  const domainButton = event.target.closest('[data-domain]');
  if (domainButton) { state.domain = domainButton.dataset.domain; renderDomainTabs(); renderFlowList(); return; }
  const actionButton = event.target.closest('[data-action]');
  if (actionButton) handleAction(actionButton.dataset.action);
});
document.addEventListener('input', (event) => { if (event.target.id === 'draft-input') state.draft = event.target.value; });
document.addEventListener('change', (event) => { const todoKey = event.target.dataset.todo; if (todoKey) state.todo[todoKey] = event.target.checked; });

render();
