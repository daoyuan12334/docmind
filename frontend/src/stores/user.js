import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('docmind_token') || '',
    userId: Number(localStorage.getItem('docmind_userId') || 0),
    username: localStorage.getItem('docmind_username') || '',
    role: localStorage.getItem('docmind_role') || ''
  }),
  getters: {
    isLogin: (state) => !!state.token,
    isAdmin: (state) => state.role === 'ADMIN'
  },
  actions: {
    setAuth(data) {
      this.token = data.token || data
      localStorage.setItem('docmind_token', this.token)
    },
    setProfile(profile) {
      this.userId = profile.userId
      this.username = profile.username
      this.role = profile.role
      localStorage.setItem('docmind_userId', String(profile.userId))
      localStorage.setItem('docmind_username', profile.username)
      localStorage.setItem('docmind_role', profile.role)
    },
    logout() {
      this.token = ''
      this.userId = 0
      this.username = ''
      this.role = ''
      localStorage.removeItem('docmind_token')
      localStorage.removeItem('docmind_userId')
      localStorage.removeItem('docmind_username')
      localStorage.removeItem('docmind_role')
    }
  }
})
