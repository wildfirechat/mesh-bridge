<template>
    <div
        class="bgg"
        :style="{
      backgroundImage: bg,
    }"
    >
        <div class="ms-title">野火IM开放平台</div>
        <div class="ms-login">
            <el-form
                @submit.native.prevent
                :model="loginForm"
                :rules="rules"
                ref="loginFormRef"
                label-width="0px"
                class="loginForm"
            >
                <el-form-item prop="username">
                    <span>用户名</span>
                    <input
                        v-model.trim="loginForm.username"
                        class="input_style user_bg"
                    />
                </el-form-item>
                <el-form-item prop="password" class="ps_style">
                    <span>密码</span>
                    <input
                        type="password"
                        v-model.trim="loginForm.password"
                        @keyup.enter="submitForm('loginForm')"
                        class="input_style pass_bg"
                    />
                </el-form-item>
                <div class="login-btn">
                    <el-button
                        :loading="loading"
                        type="primary"
                        @click="submitForm('loginForm')"
                        class="btn_style"
                    >
                        {{ $t('login.login') }}
                    </el-button>
                </div>
            </el-form>
        </div>
        <div class="copyright">wildfirechat.net&copy;rights reserved</div>
        <div class="blur-mask"></div>
    </div>
</template>

<script setup>
import bgImage from "@/assets/images/pic.png";
import {getCurrentInstance, onMounted, ref} from "vue";
import {useRouter} from "vue-router";
import {useMeshBridgeStore} from "../../store/store";
import {useI18n} from "vue-i18n";

const router = useRouter()
const store = useMeshBridgeStore()
const {proxy} = getCurrentInstance()

const {t} = useI18n()

const loading = ref(false)
const bg = bgImage
const loginForm = ref(
    {
        username: '',
        password: '',
    }
)
const rules = {
    username: [
        {
            required: true,
            message: t('login.input_user_name_tip'),
            trigger: 'blur',
        },
    ],
    password: [
        {
            required: true,
            message: t('login.input_password_tip'),
            trigger: 'blur',
        },
    ],
}

const loginFormRef = ref(null)

onMounted(() => {
    const htmlDom = document.getElementsByTagName('html')[0]
    htmlDom.removeAttribute('style')
    if (localStorage.getItem('authToken')) {
        router.replace({path: '/index'})
    }
})

const submitForm = () => {
    loading.value = true
    loginFormRef.value.validate((valid) => {
        if (valid) {
            store.login(loginForm.value.username, loginForm.value.password)
                .then(() => {
                    console.log('login success')
                    store.getAccount()
                    router.push({path: '/index'})
                })
                .catch((reason) => {
                    console.log('login failed', reason)
                    proxy.$message.error('登录失败' + reason)
                    loading.value = false
                })
        } else {
            loading.value = false
            return false
        }
    })
}
</script>

<style scoped>
html {
    font-size: 14px;
}

.bgg {
    position: relative;
    width: 100%;
    height: 100%;
    background-repeat: repeat;
    /*background-size: 100% 72%;*/
}

.bgg:before {
    content: '';
    width: 100%;
    height: 100%;
    background: inherit;
    position: absolute;
    left: -25px;
    right: 0;
    top: -25px;
    bottom: 0;
    box-shadow: inset 0 0 0 200px rgba(255, 255, 255, 0.3);
    filter: blur(10px);
}

.ms-title {
    position: absolute;
    top: 60%;
    width: 100%;
    margin-top: -230px;
    text-align: center;
    font-size: 30px;
    color: #232323;
}

.ms-login {
    position: absolute;
    left: 50%;
    top: 58%;
    width: 300px;
    margin: -150px 0 0 -190px;
    padding: 40px;
    border-radius: 8px;
    background: #fff;
    box-shadow: 0 1px 12px rgba(129, 145, 166, 0.5);
}

.login-btn {
    text-align: center;
}

.login-btn button {
    width: 100%;
    height: 40px;
    /* line-height: 40px; */
    background-color: #232323;
    color: #fff;
    border: none;
    border-radius: 20px;
    font-size: 16px;
    cursor: pointer;
}

.el-input__inner {
    border: 1px solid #d0d0d0;
    height: 30px;
    line-height: 30px;
    border-radius: 15px;
}

.input_style {
    width: 84%;
    height: 40px;
    line-height: 40px;
    border: 1px solid #d0d0d0;
    border-radius: 20px;
    outline: 0;
    padding-left: 20px;
    margin-bottom: 15px;
    padding-left: 47px;
    color: #5e5e5e;
}

.user_bg {
    /* background: url('../../assets/images/login_icon_username.png') 0 0 no-repeat; */
    background-size: 9%;
    background-position: 10px 6px;
}

.pass_bg {
    /* background: url('../../assets/images/login_icon_password.png') 0 0 no-repeat; */
    background-size: 9%;
    background-position: 10px 6px;
}

.copyright {
    color: #777c8a;
    font-size: 12px;
    position: fixed;
    bottom: 70px;
    width: 100%;
    text-align: center;
}

.ps_style {
    position: relative;
}

.keyImg {
    position: absolute;
    right: 10px;
    top: 38px;
    width: 30px;
}
</style>
