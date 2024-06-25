<template>
    <div id="app">
        <router-view v-slot="{ Component }">
            <component :is="Component"/>
        </router-view>
    </div>
</template>

<script setup>

import {getCurrentInstance, onMounted} from "vue";
import global from "./global";

const {proxy} = getCurrentInstance()
global.vueProxy = proxy

onMounted(() => {
    document.title = proxy.$t('login.title').toString()
})
</script>

<style>
@import './assets/css/main.css';
@import './assets/css/color-dark.css';
@import './assets/css/iconfont.css';
</style>
