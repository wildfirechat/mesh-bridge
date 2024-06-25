// The Vue build version to load with the `import` command
// (runtime-only or standalone) has been set in webpack.base.conf with an alias.
import {createApp} from 'vue'
import App from './App.vue'
import routers from './router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css' //默认主题
import {createI18n} from 'vue-i18n'
import {createRouter, createWebHashHistory} from "vue-router";


import zhCN from "@/assets/languages/zh-CN.json";

import zhTW from "@/assets/languages/zh-TW.json";

import en from "@/assets/languages/en.json";
import {createPinia} from "pinia";

const app = createApp(App)

// Vue.config.productionTip = false

app.use(ElementPlus, {size: 'small'})

const pinia = createPinia()
app.use(pinia)

const i18n = createI18n({
    // 使用localStorage存储语言状态是为了保证页面刷新之后还是保持原来选择的语言状态
    legacy: false,
    locale: localStorage.getItem('lang') ? localStorage.getItem('lang') : 'zh-CN', // 定义默认语言为中文
    messages: {
        'zh-CN': zhCN,
        'zh-TW': zhTW,
        en: en,
    },
})
app.use(i18n)

const _router = createRouter({
    // mode: 'hash',
    history: createWebHashHistory(),
    routes: routers,
})
app.use(_router)
app.config.globalProperties.$router = _router

app.mount('#app')