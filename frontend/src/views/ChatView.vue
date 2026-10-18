<template>
  <div class="chat-page">
    <div class="card sidebar">
      <div class="page-title">会话</div>
      <el-select v-model="kbId" style="width: 100%; margin-bottom: 10px" placeholder="选择知识库">
        <el-option v-for="b in bases" :key="b.id" :label="b.name" :value="b.id" />
      </el-select>
      <el-button type="primary" plain style="width: 100%; margin-bottom: 10px" @click="newConversation">新建会话</el-button>
      <div class="conv-list">
        <div
          v-for="c in conversations"
          :key="c.id"
          class="conv-item"
          :class="{ active: c.id === conversationId }"
          @click="selectConversation(c.id)"
        >
          <div class="conv-title">{{ c.title || '未命名会话' }}</div>
          <div class="muted">{{ formatTime(c.updatedAt) }}</div>
        </div>
      </div>
    </div>

    <div class="card main-panel">
      <div class="messages" ref="msgBox">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="bubble">
            <div class="role-tag">{{ m.role === 'user' ? '我' : 'DocMind' }}</div>
            <div v-if="m.role === 'assistant'" class="answer-body" v-html="renderMarkdown(m.content)"></div>
            <div v-else class="answer-body">{{ m.content }}</div>
            <div v-if="m.citations && m.citations.length" class="citations">
              <div class="muted" style="margin-bottom: 4px">引用来源：</div>
              <el-tag v-for="c in m.citations" :key="c.index" size="small" style="margin-right: 6px; margin-bottom: 4px">
                [{{ c.index }}] {{ c.docName }}<span v-if="c.pageNo"> · P{{ c.pageNo }}</span>
              </el-tag>
            </div>
          </div>
        </div>
        <div v-if="streaming" class="msg assistant">
          <div class="bubble">
            <div class="role-tag">DocMind</div>
            <div class="answer-body" v-html="renderMarkdown(streamText)"></div>
            <span class="cursor">▌</span>
          </div>
        </div>
        <el-empty v-if="!messages.length && !streaming" description="选择知识库后开始提问" />
      </div>

      <div class="composer">
        <el-upload
          class="attach"
          :show-file-list="false"
          :auto-upload="false"
          :on-change="onFileChange"
          accept="image/*,audio/*"
        >
          <el-button :icon="Paperclip" title="上传图片或音频随问题一起提问" />
        </el-upload>
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="基于知识库提问，Enter 发送 / Shift+Enter 换行"
          @keydown.enter.exact.prevent="send"
        />
        <el-button type="primary" :loading="streaming" @click="send">发送</el-button>
      </div>
      <div v-if="attachment" class="muted" style="margin-top: 6px">
        已附加：{{ attachment.name }}
        <el-button link type="danger" @click="attachment = null">移除</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { Paperclip } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import http, { streamChat } from '../api/http'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const bases = ref([])
const conversations = ref([])
const messages = ref([])
const kbId = ref(null)
const conversationId = ref(null)
const input = ref('')
const streaming = ref(false)
const streamText = ref('')
const msgBox = ref(null)
const attachment = ref(null)

onMounted(async () => {
  bases.value = await http.get('/kb')
  if (bases.value.length) kbId.value = bases.value[0].id
  await loadConversations()
})

async function loadConversations() {
  conversations.value = await http.get('/chat/conversations')
}

function newConversation() {
  conversationId.value = null
  messages.value = []
}

async function selectConversation(id) {
  conversationId.value = id
  const rows = await http.get(`/chat/conversations/${id}/messages`, { params: { limit: 100 } })
  messages.value = rows
    .map((r) => ({
      role: r.role,
      content: r.content,
      citations: r.citations ? safeParse(r.citations) : []
    }))
    .reverse()
  scrollBottom()
}

function onFileChange(file) {
  attachment.value = file.raw
}

async function send() {
  if (!input.value.trim() && !attachment.value) return
  if (!kbId.value) {
    ElMessage.warning('请先选择知识库')
    return
  }
  const question = input.value.trim()
  input.value = ''
  messages.value.push({ role: 'user', content: question })
  scrollBottom()

  if (attachment.value) {
    await sendMultimodal(question)
    return
  }

  streaming.value = true
  streamText.value = ''
  try {
    await streamChat(
      kbId.value,
      { question, conversationId: conversationId.value, topK: 6 },
      {
        onMeta: (meta) => {
          if (meta && meta.conversationId) {
            conversationId.value = meta.conversationId
            loadConversations()
          }
        },
        onDelta: (token) => {
          streamText.value += token
          scrollBottom()
        },
        onDone: () => {
          if (streamText.value) {
            messages.value.push({ role: 'assistant', content: streamText.value, citations: [] })
          }
          streaming.value = false
          streamText.value = ''
        },
        onError: (msg) => {
          ElMessage.error('生成失败：' + msg)
          streaming.value = false
        }
      }
    )
  } catch (e) {
    streaming.value = false
  }
}

async function sendMultimodal(question) {
  const form = new FormData()
  form.append('question', question)
  form.append('file', attachment.value)
  const kb = kbId.value
  const name = attachment.value.name
  attachment.value = null
  try {
    const res = await http.post(`/chat/${kb}/multimodal`, form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    messages.value.push({
      role: 'assistant',
      content: `【附件理解】${res.attachmentUnderstanding}\n\n${res.answer}`,
      citations: res.citations || []
    })
  } catch (e) {
    ElMessage.error('附件处理失败，已忽略：' + name)
  }
  scrollBottom()
}

function renderMarkdown(text) {
  if (!text) return ''
  return md.render(text)
}

function safeParse(text) {
  try {
    return JSON.parse(text)
  } catch {
    return []
  }
}

function scrollBottom() {
  nextTick(() => {
    if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
  })
}

function formatTime(iso) {
  if (!iso) return ''
  return iso.replace('T', ' ').slice(5, 16)
}
</script>

<style scoped>
.chat-page { display: flex; gap: 16px; height: calc(100vh - 120px); }
.sidebar { width: 240px; display: flex; flex-direction: column; }
.conv-list { flex: 1; overflow-y: auto; }
.conv-item { padding: 8px 10px; border-radius: 6px; cursor: pointer; margin-bottom: 4px; }
.conv-item:hover { background: #f5f7fa; }
.conv-item.active { background: #ecf5ff; }
.conv-title { font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.main-panel { flex: 1; display: flex; flex-direction: column; }
.messages { flex: 1; overflow-y: auto; padding: 6px 4px; }
.msg { display: flex; margin-bottom: 14px; }
.msg.user { justify-content: flex-end; }
.bubble {
  max-width: 78%; background: #f5f7fa; border-radius: 10px; padding: 10px 12px;
}
.msg.user .bubble { background: #ecf5ff; }
.role-tag { font-size: 12px; color: #909399; margin-bottom: 4px; }
.citations { margin-top: 8px; border-top: 1px dashed #dcdfe6; padding-top: 6px; }
.composer { display: flex; gap: 8px; align-items: flex-end; margin-top: 10px; }
.attach { flex: none; }
.cursor { animation: blink 1s steps(2, start) infinite; }
@keyframes blink { 50% { opacity: 0; } }
</style>
