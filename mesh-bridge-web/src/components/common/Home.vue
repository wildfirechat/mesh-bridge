<template>
    <el-container style="height: 100%">
        <el-aside width="200px" style="background-color: rgb(238, 241, 246)">
            <div
                style="
          height: 60px;
          display: flex;
          justify-content: center;
          align-items: center;
        "
                @click="go2home"
            >
                <p>野火IM服务互通</p>
            </div>
            <el-menu router>
                <el-menu-item index="/dev/imdomain">IM域</el-menu-item>
                <!--                TODO more-->
                <el-menu-item>
                    <template #title>
                        <a
                            href="https://docs.wildfirechat.cn/open"
                            style="color: #303133; width: 100%"
                            target="_blank"
                        >开发文档</a>
                    </template>
                </el-menu-item>
            </el-menu>
        </el-aside>
        <el-container :class="{ 'content-collapse': collapse }">
            <el-header
                style="
          text-align: left;
          font-size: 14px;
          display: flex;
          padding-right: 40px;
        "
            >
                <el-button type="text">
                    <el-icon>
                        <el-icon-arrow-left/>
                    </el-icon>
                </el-button>
                <span style="flex: 1"> </span>
                <el-dropdown>
                    <el-icon style="margin-right: 15px">
                        <el-icon-setting/>
                    </el-icon>
                    <template #dropdown>
                        <el-dropdown-menu>
                            <el-dropdown-item @click.native="logout">退出</el-dropdown-item>
                            <el-dropdown-item @click.native="modifyPwdDialogVisible = true"
                            >修改密码
                            </el-dropdown-item>
                        </el-dropdown-menu>
                    </template>
                </el-dropdown>
                <span>{{ admin}}</span>
            </el-header>
            <el-main style="padding: 0">
                <router-view v-slot="{ Component, route }">
                    <transition name="fade">
                        <component :is="Component" :key="route.path"/>
                    </transition>
                </router-view>
            </el-main>

            <el-dialog title="修改密码"
                       width="500"
                       v-model="modifyPwdDialogVisible">
                <el-form :model="updatePwdRequest" ref="updatePwdFormRef" :rules="rules">
                    <el-form-item
                        label="旧密码"
                        :label-width="formLabelWidth"
                        prop="oldPwd"
                    >
                        <el-input
                            v-model="updatePwdRequest.oldPwd"
                            autocomplete="off"
                            placeholder="请输入旧密码"
                        ></el-input>
                    </el-form-item>
                    <el-form-item
                        label="新密码"
                        :label-width="formLabelWidth"
                        prop="newPwd"
                    >
                        <el-input
                            v-model="updatePwdRequest.newPwd"
                            autocomplete="off"
                            placeholder="请输入新密码"
                        ></el-input>
                    </el-form-item>
                    <el-form-item
                        label="确认新密码"
                        :label-width="formLabelWidth"
                        prop="confirmNewPwd"
                    >
                        <el-input
                            v-model="updatePwdRequest.confirmNewPwd"
                            autocomplete="off"
                            placeholder="请确认新密码"
                        ></el-input>
                    </el-form-item>
                </el-form>
                <div slot="footer" class="dialog-footer">
                    <el-button @click="modifyPwdDialogVisible = false">取 消</el-button>
                    <el-button type="primary" @click="updatePwd()"
                    >修 改
                    </el-button>
                </div>
            </el-dialog>
        </el-container>
    </el-container>
</template>

<script setup>
import {
    ArrowLeft as ElIconArrowLeft,
    Setting as ElIconSetting,
} from '@element-plus/icons'

import {useMeshBridgeStore} from '../../store/store'
import {getCurrentInstance, onMounted, ref} from "vue";
import {useRoute, useRouter} from 'vue-router'
import {storeToRefs} from "pinia";


const store = useMeshBridgeStore()
const {admin} = storeToRefs(store)

const collapse = ref(true)
const modifyPwdDialogVisible = ref(false)
const formLabelWidth = '120px'
const updatePwdRequest = ref({})
const rules = {
    oldPwd: [
        {required: true, message: '旧密码不能为空', trigger: 'blur'},
    ],
    newPwd: [
        {required: true, message: '新密码不能为空', trigger: 'blur'},
    ],
    confirmNewPwd: [
        {required: true, message: '新密码不能为空', trigger: 'blur'},
    ],
}

const router = useRouter()
const route = useRoute()

const updatePwdFormRef = ref(null)

const {proxy, ctx} = getCurrentInstance();

onMounted(() => {
    store.getAccount();
})

function logout() {
    localStorage.clear()
    router.replace('/login')
}

function go2home() {
    router.replace('/index')
}

function updatePwd() {
    updatePwdFormRef.value.validate((valid) => {
        if (valid) {
            if (
                updatePwdRequest.value.newPwd !== updatePwdRequest.value.confirmNewPwd
            ) {
                proxy.$message.error('两次输入的密码不一致')
            } else {
                store.updatePassword(updatePwdRequest.value.oldPwd, updatePwdRequest.value.newPwd)
                modifyPwdDialogVisible.value = false
            }
        }
    })
}

</script>

<style lang="css" scoped>
.el-header {
    color: #333;
    line-height: 60px;
    align-items: center;
    /*background-color: rgb(238, 241, 246);*/
}

.el-aside {
    color: #333;
}
</style>
